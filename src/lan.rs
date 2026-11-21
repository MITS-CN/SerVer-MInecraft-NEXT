use std::net::UdpSocket;
use std::sync::Arc;
use std::time::Duration;

use crate::logger::Logger;
use crate::util::pick_text;

const MCAST_GRP: &str = "224.0.2.60";
const MCAST_PORT: u16 = 4445;

pub fn start_udp_broadcast(
    logger: Arc<Logger>,
    motd: String,
    adv_port: u16,
    lan_ip: Option<String>,
) {
    std::thread::spawn(move || {
        let payload = format!("[MOTD]{}[/MOTD][AD]{}[/AD]", motd, adv_port);
        let payload_bytes = payload.into_bytes();

        let bind_addr = match &lan_ip {
            Some(ip) => format!("{}:0", ip),
            None => "0.0.0.0:0".to_string(),
        };

        let socket = match UdpSocket::bind(&bind_addr) {
            Ok(s) => {
                logger.info(&format!(
                    "[UDP] {} {}",
                    pick_text(&logger.texts().udp_bind_msgs),
                    lan_ip.as_deref().unwrap_or("0.0.0.0")
                ));
                s
            }
            Err(e) => {
                logger.warn(&format!(
                    "[UDP] {} {} {}",
                    pick_text(&logger.texts().udp_bind_fail_msgs),
                    lan_ip.as_deref().unwrap_or("0.0.0.0"),
                    e
                ));
                match UdpSocket::bind("0.0.0.0:0") {
                    Ok(s) => s,
                    Err(e2) => {
                        logger.error(&format!("[UDP] 回退绑定失败: {}", e2));
                        return;
                    }
                }
            }
        };

        let _ = socket.set_multicast_ttl_v4(1);

        logger.info(&format!(
            "[UDP] {} MOTD=\"{}\" 组播={}:{} 端口={} 周期=1.5s",
            pick_text(&logger.texts().udp_start_msgs),
            motd,
            MCAST_GRP,
            MCAST_PORT,
            adv_port
        ));

        let dst = format!("{}:{}", MCAST_GRP, MCAST_PORT);
        loop {
            if let Err(e) = socket.send_to(&payload_bytes, &dst) {
                logger.error(&format!(
                    "[UDP] {} {}",
                    pick_text(&logger.texts().udp_send_fail_msgs),
                    e
                ));
            }
            std::thread::sleep(Duration::from_millis(1500));
        }
    });
}