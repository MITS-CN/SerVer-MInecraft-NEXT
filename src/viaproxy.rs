use std::io::{BufRead, BufReader};
use std::net::{TcpStream, ToSocketAddrs};
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use crate::logger::Logger;

const VIAPROXY_STARTUP_TIMEOUT: Duration = Duration::from_secs(30);
const VIAPROXY_CONNECT_TIMEOUT: Duration = Duration::from_millis(200);
const VIAPROXY_POLL_INTERVAL: Duration = Duration::from_millis(100);

/// 启动 ViaProxy 子进程
pub fn start_viaproxy(
    jar_path: &str,
    bind_addr: &str,
    target_host: &str,
    target_port: u16,
    logger: Arc<Logger>,
) -> std::io::Result<Child> {
    let target_addr = format!("{}:{}", target_host, target_port);
    let bind_addresses: Vec<_> = bind_addr.to_socket_addrs()?.collect();
    if bind_addresses.is_empty() {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidInput,
            format!("无法解析 ViaProxy 监听地址: {}", bind_addr),
        ));
    }

    // 避免把其他进程已经占用的端口误判为 ViaProxy 已就绪。
    if bind_addresses
        .iter()
        .any(|addr| TcpStream::connect_timeout(addr, VIAPROXY_CONNECT_TIMEOUT).is_ok())
    {
        return Err(std::io::Error::new(
            std::io::ErrorKind::AddrInUse,
            format!("ViaProxy 监听地址 {} 已有服务在监听", bind_addr),
        ));
    }

    logger.info(&format!(
        "[ViaProxy] 正在启动: java -jar {} cli --bind-address {} --target-address {}",
        jar_path, bind_addr, target_addr
    ));

    let mut child = Command::new("java")
        // Keep ViaProxy from inheriting the parent terminal: its interactive
        // command handler otherwise races the Rust stdin console for input.
        .stdin(Stdio::null())
        .arg("-jar")
        .arg(jar_path)
        .arg("cli")
        .arg("--bind-address")
        .arg(bind_addr)
        .arg("--target-address")
        .arg(&target_addr)
        .arg("--auth-method")
        .arg("NONE")
        .arg("--proxy-online-mode")
        .arg("false")
        .env("NEKOGATE_CONTROL_PORT", "25626")
        .arg("--ignore-protocol-translation-errors")
        .arg("true")
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()?;

    // 将 ViaProxy 的输出实时转发到主程序日志，避免丢失启动错误。
    let stdout = child.stdout.take().expect("ViaProxy stdout 未捕获");
    let stdout_logger = logger.clone();
    let stdout_thread = thread::spawn(move || {
        for line in BufReader::new(stdout).lines() {
            match line {
                Ok(line) if !line.is_empty() => {
                    stdout_logger.info(&format!("[ViaProxy] {}", line));
                }
                Ok(_) => {}
                Err(error) => {
                    stdout_logger.warn(&format!("[ViaProxy] 读取标准输出失败: {}", error));
                    break;
                }
            }
        }
    });

    let stderr = child.stderr.take().expect("ViaProxy stderr 未捕获");
    let stderr_logger = logger.clone();
    let stderr_thread = thread::spawn(move || {
        for line in BufReader::new(stderr).lines() {
            match line {
                Ok(line) if !line.is_empty() => {
                    stderr_logger.error(&format!("[ViaProxy stderr] {}", line));
                }
                Ok(_) => {}
                Err(error) => {
                    stderr_logger.warn(&format!("[ViaProxy] 读取错误输出失败: {}", error));
                    break;
                }
            }
        }
    });

    // 只有在目标监听端口确实可连接、且子进程仍存活时才报告就绪。
    let startup_deadline = Instant::now() + VIAPROXY_STARTUP_TIMEOUT;
    loop {
        let listener_ready = bind_addresses
            .iter()
            .any(|addr| TcpStream::connect_timeout(addr, VIAPROXY_CONNECT_TIMEOUT).is_ok());

        if listener_ready {
            if let Some(status) = child.try_wait()? {
                let _ = stdout_thread.join();
                let _ = stderr_thread.join();
                return Err(std::io::Error::new(
                    std::io::ErrorKind::Other,
                    format!("ViaProxy 在监听端口就绪前退出，状态: {:?}", status),
                ));
            }

            logger.info(&format!("[ViaProxy] 已开始监听 {}，启动就绪", bind_addr));
            return Ok(child);
        }

        if let Some(status) = child.try_wait()? {
            // 子进程已退出，等待输出读取线程排空管道，确保错误信息写入日志。
            let _ = stdout_thread.join();
            let _ = stderr_thread.join();
            return Err(std::io::Error::new(
                std::io::ErrorKind::Other,
                format!("ViaProxy 在监听端口就绪前退出，状态: {:?}", status),
            ));
        }

        if Instant::now() >= startup_deadline {
            logger.error(&format!(
                "[ViaProxy] 等待监听地址 {} 超时（{} 秒），正在终止子进程",
                bind_addr,
                VIAPROXY_STARTUP_TIMEOUT.as_secs()
            ));
            let _ = child.kill();
            let _ = child.wait();
            let _ = stdout_thread.join();
            let _ = stderr_thread.join();
            return Err(std::io::Error::new(
                std::io::ErrorKind::TimedOut,
                format!(
                    "ViaProxy 在 {} 秒内未能监听 {}",
                    VIAPROXY_STARTUP_TIMEOUT.as_secs(),
                    bind_addr
                ),
            ));
        }

        thread::sleep(VIAPROXY_POLL_INTERVAL);
    }
}

/// 优雅停止 ViaProxy
pub fn stop_viaproxy(mut child: Child, logger: Arc<Logger>) {
    logger.info("[ViaProxy] 正在关闭...");
    let _ = child.kill();
    let _ = child.wait();
    logger.info("[ViaProxy] 已关闭");
}
