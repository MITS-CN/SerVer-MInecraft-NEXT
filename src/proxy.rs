use std::collections::HashMap;
use std::io::Write;
use std::net::{Shutdown, TcpStream};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime};

use crate::logger::Logger;
use crate::mc::*;
use crate::util::*;

static GLOBAL_UPLOAD: AtomicU64 = AtomicU64::new(0);
static GLOBAL_DOWNLOAD: AtomicU64 = AtomicU64::new(0);
pub static TOTAL_CONNECTIONS: AtomicU64 = AtomicU64::new(0);
pub static ACTIVE_CONNECTIONS: AtomicU64 = AtomicU64::new(0);

pub fn global_up() -> u64 {
    GLOBAL_UPLOAD.load(Ordering::Relaxed)
}
pub fn global_down() -> u64 {
    GLOBAL_DOWNLOAD.load(Ordering::Relaxed)
}

const CONNECT_TIMEOUT_SECS: u64 = 10;
const CONNECT_RETRY: u32 = 1;
const HANDSHAKE_TIMEOUT_SECS: u64 = 5;

pub struct Player {
    pub state: ConnState,
    pub compression_threshold: i32,
    pub config_finish_sent: bool,
    pub protocol_version: i32,
    pub writer: Arc<Mutex<TcpStream>>,
}

pub type Players = Arc<Mutex<HashMap<u64, Player>>>;

// ============================================================
// say 注入
// ============================================================

pub fn broadcast_system_chat(players: &Players, msg: &str) -> usize {
    let targets: Vec<(Arc<Mutex<TcpStream>>, i32, i32)> = {
        let lock = players.lock().unwrap();
        lock.values()
            .filter(|p| p.state == ConnState::Play)
            .map(|p| {
                (
                    p.writer.clone(),
                    p.compression_threshold,
                    p.protocol_version,
                )
            })
            .collect()
    };

    let mut count = 0usize;
    for (writer, thresh, proto) in targets {
        let pkt = build_system_chat_packet(msg, proto);
        let wire = wrap_packet(&pkt, thresh);
        if let Ok(mut w) = writer.lock() {
            if w.write_all(&wire).is_ok() {
                count += 1;
            }
        }
    }
    count
}

// ============================================================
// 连接处理
// ============================================================

pub fn handle_client(
    mut client: TcpStream,
    t_host: String,
    t_port: u16,
    players: Players,
    logger: Arc<Logger>,
) {
    let total = TOTAL_CONNECTIONS.fetch_add(1, Ordering::Relaxed) + 1;
    let active = ACTIVE_CONNECTIONS.fetch_add(1, Ordering::Relaxed) + 1;
    let conn_id = format!("#{}", total);
    let start_ts = SystemTime::now();

    let peer = client
        .peer_addr()
        .map(|a| a.to_string())
        .unwrap_or_else(|_| "unknown".to_string());

    logger.info(&format!(
        "[{}] {} {} | 活跃 {} | 累计 {}",
        conn_id,
        pick_text(&logger.texts().new_conn_msgs),
        peer,
        active,
        total
    ));

    // ---- 读握手（带 5s 超时）----
    let _ = client.set_read_timeout(Some(Duration::from_secs(HANDSHAKE_TIMEOUT_SECS)));
    let hs_pkt = match read_packet_opt(&mut client) {
        Ok(Some(p)) if !p.is_empty() => p,
        Ok(Some(_)) => {
            logger.warn(&format!(
                "[{}] {}",
                conn_id,
                pick_text(&logger.texts().no_data_msgs)
            ));
            ACTIVE_CONNECTIONS.fetch_sub(1, Ordering::Relaxed);
            return;
        }
        Ok(None) => {
            logger.warn(&format!(
                "[{}] {}",
                conn_id,
                pick_text(&logger.texts().read_timeout_msgs)
            ));
            ACTIVE_CONNECTIONS.fetch_sub(1, Ordering::Relaxed);
            return;
        }
        Err(e) => {
            logger.warn(&format!(
                "[{}] {} {}",
                conn_id,
                pick_text(&logger.texts().read_error_msgs),
                e
            ));
            ACTIVE_CONNECTIONS.fetch_sub(1, Ordering::Relaxed);
            return;
        }
    };
    let _ = client.set_read_timeout(None);

    let handshake = parse_handshake(&hs_pkt);
    let protocol_version = handshake.as_ref().map(|h| h.protocol).unwrap_or(-1);

    match &handshake {
        Some(h) => {
            logger.info(&format!(
                "[{}] {} 协议={} 虚拟地址={}:{} 状态={}",
                conn_id,
                pick_text(&logger.texts().handshake_msgs),
                h.protocol,
                h.host,
                h.port,
                next_state_name(h.next_state)
            ));
            if h.next_state != 1 && h.next_state != 2 {
                logger.warn(&format!(
                    "[{}] {} 状态={} 拒绝转发",
                    conn_id,
                    pick_text(&logger.texts().illegal_msgs),
                    h.next_state
                ));
                ACTIVE_CONNECTIONS.fetch_sub(1, Ordering::Relaxed);
                return;
            }
        }
        None => {
            logger.info(&format!(
                "[{}] {}",
                conn_id,
                pick_text(&logger.texts().handshake_incomplete_msgs)
            ));
        }
    }

    // ---- 连后端 ----
    logger.info(&format!(
        "[{}] {} {}:{}",
        conn_id,
        pick_text(&logger.texts().connecting_msgs),
        t_host,
        t_port
    ));

    let backend = match connect_with_retry(&t_host, t_port, &conn_id, &logger) {
        Ok(s) => s,
        Err(e) => {
            logger.error(&format!(
                "[{}] {} {}",
                conn_id,
                pick_text(&logger.texts().target_fail_msgs),
                e
            ));
            ACTIVE_CONNECTIONS.fetch_sub(1, Ordering::Relaxed);
            return;
        }
    };

    logger.info(&format!(
        "[{}] {} 对端 {}",
        conn_id,
        pick_text(&logger.texts().target_ok_msgs),
        backend
            .peer_addr()
            .map(|a| a.to_string())
            .unwrap_or_else(|_| "?".into())
    ));

    // 转发握手
    {
        let mut bw = match backend.try_clone() {
            Ok(s) => s,
            Err(_) => {
                ACTIVE_CONNECTIONS.fetch_sub(1, Ordering::Relaxed);
                return;
            }
        };
        if write_packet(&mut bw, &hs_pkt).is_err() {
            ACTIVE_CONNECTIONS.fetch_sub(1, Ordering::Relaxed);
            return;
        }
    }

    // 注册 player
    let client_writer = match client.try_clone() {
        Ok(s) => Arc::new(Mutex::new(s)),
        Err(_) => {
            ACTIVE_CONNECTIONS.fetch_sub(1, Ordering::Relaxed);
            return;
        }
    };
    players.lock().unwrap().insert(
        total,
        Player {
            state: ConnState::Login,
            compression_threshold: -1,
            config_finish_sent: false,
            protocol_version,
            writer: client_writer.clone(),
        },
    );

    // ---- 上行线程 ----
    let up_bytes = Arc::new(AtomicU64::new(0));
    let up_bytes_c = up_bytes.clone();

    let mut c_reader = match client.try_clone() {
        Ok(s) => s,
        Err(_) => {
            ACTIVE_CONNECTIONS.fetch_sub(1, Ordering::Relaxed);
            return;
        }
    };
    let mut b_writer = match backend.try_clone() {
        Ok(s) => s,
        Err(_) => {
            ACTIVE_CONNECTIONS.fetch_sub(1, Ordering::Relaxed);
            return;
        }
    };
    let players_up = players.clone();
    let lg_up = logger.clone();
    let cid_up = conn_id.clone();
    let up_handle = std::thread::spawn(move || {
        loop {
            let pkt = match read_packet(&mut c_reader) {
                Ok(p) if !p.is_empty() => p,
                _ => break,
            };
            up_bytes_c.fetch_add(pkt.len() as u64, Ordering::Relaxed);
            GLOBAL_UPLOAD.fetch_add(pkt.len() as u64, Ordering::Relaxed);

            {
                let mut lock = players_up.lock().unwrap();
                if let Some(p) = lock.get_mut(&total) {
                    if p.state == ConnState::Config && p.config_finish_sent {
                        if let Some(plain) = unwrap_payload(&pkt, p.compression_threshold) {
                            let (pid, _) = read_varint_slice(&plain);
                            if pid == 0x03 {
                                p.state = ConnState::Play;
                                lg_up.info(&format!("[{}] -> Play", cid_up));
                            }
                        }
                    }
                }
            }

            if write_packet(&mut b_writer, &pkt).is_err() {
                break;
            }
        }
        let _ = b_writer.shutdown(Shutdown::Both);
    });

    // ---- 下行 ----
    let mut b_reader = backend;
    let mut down_bytes: u64 = 0;
    loop {
        let pkt = match read_packet(&mut b_reader) {
            Ok(p) if !p.is_empty() => p,
            _ => break,
        };
        down_bytes += pkt.len() as u64;
        GLOBAL_DOWNLOAD.fetch_add(pkt.len() as u64, Ordering::Relaxed);

        {
            let mut lock = players.lock().unwrap();
            if let Some(p) = lock.get_mut(&total) {
                let thresh = p.compression_threshold;
                if let Some(plain) = unwrap_payload(&pkt, thresh) {
                    let (pid, off) = read_varint_slice(&plain);
                    match (p.state, pid) {
                        (ConnState::Login, 0x03) => {
                            let (t, _) = read_varint_slice(&plain[off..]);
                            p.compression_threshold = t;
                            logger.info(&format!("[{}] Set Compression t={}", conn_id, t));
                        }
                        (ConnState::Login, 0x02) => {
                            if p.protocol_version >= 764 {
                                p.state = ConnState::Config;
                            } else {
                                p.state = ConnState::Play;
                            }
                            logger.info(&format!(
                                "[{}] Login Success -> {:?}",
                                conn_id, p.state
                            ));
                        }
                        (ConnState::Config, 0x03) => {
                            p.config_finish_sent = true;
                            logger.info(&format!("[{}] Finish Config 下发", conn_id));
                        }
                        _ => {}
                    }
                }
            }
        }

        if let Ok(mut w) = client_writer.lock() {
            if write_packet(&mut *w, &pkt).is_err() {
                break;
            }
        } else {
            break;
        }
    }

    let _ = client_writer.lock().map(|w| w.shutdown(Shutdown::Both));
    let _ = up_handle.join();
    players.lock().unwrap().remove(&total);
    ACTIVE_CONNECTIONS.fetch_sub(1, Ordering::Relaxed);

    let duration = SystemTime::now()
        .duration_since(start_ts)
        .unwrap_or_default();
    let up = up_bytes.load(Ordering::Relaxed);
    let total_b = up + down_bytes;
    logger.info(&format!(
        "[{}] {} {} 时长={:.2}s 上行={} 下行={} 合计={} 活跃={}",
        conn_id,
        pick_text(&logger.texts().abort_msgs),
        peer,
        duration.as_secs_f64(),
        format_bytes(up),
        format_bytes(down_bytes),
        format_bytes(total_b),
        ACTIVE_CONNECTIONS.load(Ordering::Relaxed)
    ));
}

fn connect_with_retry(
    host: &str,
    port: u16,
    _conn_id: &str,
    logger: &Logger,
) -> std::io::Result<TcpStream> {
    use std::net::ToSocketAddrs;
    let addr = format!("{}:{}", host, port);
    let mut last_err: Option<std::io::Error> = None;
    for attempt in 0..=CONNECT_RETRY {
        let resolved: Vec<std::net::SocketAddr> =
            match addr.to_socket_addrs().map(|it| it.collect()) {
                Ok(v) => v,
                Err(e) => {
                    last_err = Some(e);
                    continue;
                }
            };
        for sa in resolved {
            match TcpStream::connect_timeout(&sa, Duration::from_secs(CONNECT_TIMEOUT_SECS)) {
                Ok(s) => return Ok(s),
                Err(e) => last_err = Some(e),
            }
        }
        if attempt < CONNECT_RETRY {
            logger.debug(&format!(
                "目标连接重试 ({}/{})",
                attempt + 1,
                CONNECT_RETRY
            ));
            std::thread::sleep(Duration::from_millis(200));
        }
    }
    Err(last_err.unwrap_or_else(|| {
        std::io::Error::new(std::io::ErrorKind::Other, "未知连接错误")
    }))
}