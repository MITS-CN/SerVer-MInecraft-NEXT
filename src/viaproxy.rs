use std::process::{Child, Command, Stdio};
use std::sync::Arc;
use std::time::Duration;

use crate::logger::Logger;
use crate::util::pick_text;

/// 启动 ViaProxy 子进程
pub fn start_viaproxy(
    jar_path: &str,
    bind_addr: &str,
    target_host: &str,
    target_port: u16,
    logger: Arc<Logger>,
) -> std::io::Result<Child> {
    logger.info(&format!("[ViaProxy] 正在启动: java -jar {} --bind_address {} --target_ip {}:{}",
        jar_path, bind_addr, target_host, target_port));

    let mut child = Command::new("java")
        .arg("-jar")
        .arg(jar_path)
        .arg("--bind_address").arg(bind_addr)
        .arg("--target_ip").arg(format!("{}:{}", target_host, target_port))
        .arg("--auth_method").arg("none")
        .arg("--proxy_online_mode").arg("false")
        .arg("--ignore_protocol_translation_errors").arg("true")
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()?;

    // 等待 ViaProxy 启动完成（简单轮询检测端口是否可连）
    std::thread::sleep(Duration::from_secs(2));
    let status = child.try_wait()?;
    if let Some(status) = status {
        return Err(std::io::Error::new(
            std::io::ErrorKind::Other,
            format!("ViaProxy 启动后立即退出，状态: {:?}", status),
        ));
    }

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

/// 一个 Drop guard，用于在 main 函数意外退出时自动杀死子进程
pub struct ViaProxyGuard(pub Child);

impl Drop for ViaProxyGuard {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}