use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::time::Duration;

use crate::logger::Logger;

/// 启动 ViaProxy 子进程
pub fn start_viaproxy(
    jar_path: &str,
    bind_addr: &str,
    target_host: &str,
    target_port: u16,
    logger: Arc<Logger>,
) -> std::io::Result<Child> {
    let target_addr = format!("{}:{}", target_host, target_port);
    logger.info(&format!(
        "[ViaProxy] 正在启动: java -jar {} cli --bind-address {} --target-address {}",
        jar_path, bind_addr, target_addr
    ));

    let mut child = Command::new("java")
        .arg("-jar")
        .arg(jar_path)
        .arg("cli")
        .arg("--bind-address")
        .arg(bind_addr)
        .arg("--target-address")
        .arg(&target_addr)
        .arg("--auth-method")
        .arg("none")
        .arg("--proxy-online-mode")
        .arg("false")
        .arg("--ignore-protocol-translation-errors")
        .arg("true")
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()?;

    // 将 ViaProxy 的输出实时转发到主程序日志，避免丢失启动错误。
    let stdout = child.stdout.take().expect("ViaProxy stdout 未捕获");
    let stdout_logger = logger.clone();
    let stdout_thread = std::thread::spawn(move || {
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
    let stderr_thread = std::thread::spawn(move || {
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

    // 等待 ViaProxy 启动完成（简单轮询检测进程是否立即退出）。
    std::thread::sleep(Duration::from_secs(2));
    let status = child.try_wait()?;
    if let Some(status) = status {
        // 进程已退出，等待输出读取线程排空管道，确保错误信息写入日志。
        let _ = stdout_thread.join();
        let _ = stderr_thread.join();
        return Err(std::io::Error::new(
            std::io::ErrorKind::Other,
            format!("ViaProxy 启动后立即退出，状态: {:?}", status),
        ));
    }

    // 进程仍在运行；读取线程会在后台持续转发输出。
    logger.info("[ViaProxy] 启动成功");
    Ok(child)
}

/// 优雅停止 ViaProxy
pub fn stop_viaproxy(mut child: Child, logger: Arc<Logger>) {
    logger.info("[ViaProxy] 正在关闭...");
    let _ = child.kill();
    let _ = child.wait();
    logger.info("[ViaProxy] 已关闭");
}
