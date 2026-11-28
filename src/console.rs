use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use crate::logger::Logger;
use crate::proxy::Players;

const CONTROL_ADDR: &str = "127.0.0.1:25626";

fn send_control(request: serde_json::Value) -> Result<serde_json::Value, String> {
    let addr = CONTROL_ADDR.parse().map_err(|e| format!("invalid control address: {e}"))?;
    let mut stream = TcpStream::connect_timeout(&addr, Duration::from_secs(3))
        .map_err(|e| format!("cannot connect to ViaProxy control channel {CONTROL_ADDR}: {e}"))?;
    stream.set_read_timeout(Some(Duration::from_secs(5))).ok();
    stream.set_write_timeout(Some(Duration::from_secs(5))).ok();
    serde_json::to_writer(&mut stream, &request).map_err(|e| format!("encode request failed: {e}"))?;
    stream.write_all(b"\n").map_err(|e| format!("send request failed: {e}"))?;

    let mut response = String::new();
    BufReader::new(stream)
        .read_line(&mut response)
        .map_err(|e| format!("read ViaProxy response failed: {e}"))?;
    if response.trim().is_empty() {
        return Err("ViaProxy returned an empty response".to_string());
    }
    serde_json::from_str(&response).map_err(|e| format!("invalid ViaProxy response: {e}"))
}

fn execute(action: &str, fields: serde_json::Value, logger: &Logger) -> String {
    let mut request = serde_json::Map::new();
    request.insert("action".to_string(), serde_json::Value::String(action.to_string()));
    if let Some(values) = fields.as_object() {
        request.extend(values.clone());
    }
    match send_control(serde_json::Value::Object(request)) {
        Ok(response) => {
            let ok = response.get("ok").and_then(|v| v.as_bool()).unwrap_or(false);
            let message = response.get("message").and_then(|v| v.as_str()).unwrap_or("operation completed");
            if ok {
                logger.info(&format!("[Console] {action}: {message}"));
                if action == "list" {
                    if let Some(players) = response.get("players").and_then(|v| v.as_array()) {
                        let names = players.iter().filter_map(|v| v.as_str()).collect::<Vec<_>>();
                        return if names.is_empty() { "[Players] 当前没有在线玩家".to_string() }
                            else { format!("[Players] {}: {}", names.len(), names.join(", ")) };
                    }
                }
                if action == "banlist" {
                    if let Some(bans) = response.get("bans").and_then(|v| v.as_array()) {
                        if bans.is_empty() { return "[Ban] 封禁名单为空".to_string(); }
                        let lines = bans.iter().map(|entry| {
                            let name = entry.get("player").and_then(|v| v.as_str()).unwrap_or("unknown");
                            let reason = entry.get("reason").and_then(|v| v.as_str()).unwrap_or("");
                            format!("{name} — {reason}")
                        }).collect::<Vec<_>>();
                        return format!("[Ban] {} 条记录:\n{}", lines.len(), lines.join("\n"));
                    }
                }
                format!("[OK] {message}")
            } else {
                format!("[ERR] {message}")
            }
        }
        Err(error) => {
            logger.error(&format!("[Console] {action}: {error}"));
            format!("[ERR] {error}")
        }
    }
}

/// 处理一条控制台命令，返回 (响应, 是否应该退出)
fn handle_cmd(cmd: &str, _players: &Players, logger: &Logger) -> (String, bool) {
    let cmd = cmd.trim();
    if cmd.is_empty() {
        return (String::new(), false);
    }

    let (name, rest) = cmd.split_once(' ').unwrap_or((cmd, ""));
    let rest = rest.trim();
    let response = match name.to_ascii_lowercase().as_str() {
        "say" if !rest.is_empty() => execute("say", serde_json::json!({ "message": rest }), logger),
        "tell" | "msg" | "w" => {
            let mut parts = rest.splitn(2, ' ');
            match (parts.next(), parts.next()) {
                (Some(player), Some(message)) if !player.is_empty() && !message.trim().is_empty() =>
                    execute("tell", serde_json::json!({ "player": player, "message": message.trim() }), logger),
                _ => "[ERR] 用法: tell <player> <message>".to_string(),
            }
        }
        "kick" | "ban" => {
            let mut parts = rest.splitn(2, ' ');
            match parts.next() {
                Some(player) if !player.is_empty() => {
                    let reason = parts.next().unwrap_or("由管理员操作").trim();
                    execute(&name.to_ascii_lowercase(), serde_json::json!({ "player": player, "reason": reason }), logger)
                }
                _ => format!("[ERR] 用法: {name} <player> [reason]"),
            }
        }
        "op" | "deop" => {
            if rest.is_empty() {
                format!("[ERR] 用法: {name} <player|uuid>")
            } else {
                execute(&name.to_ascii_lowercase(), serde_json::json!({ "player": rest }), logger)
            }
        }
        "unban" => {
            if rest.is_empty() { "[ERR] 用法: unban <player|uuid>".to_string() }
            else { execute("unban", serde_json::json!({ "player": rest }), logger) }
        }
        "banlist" => execute("banlist", serde_json::json!({}), logger),
        "list" => execute("list", serde_json::json!({}), logger),
        "help" => "[Commands] say <msg> | tell <player> <msg> | kick <player> [reason] | ban <player> [reason] | op <player|uuid> | deop <player|uuid> | unban <player|uuid> | banlist | list | quit".to_string(),
        "quit" | "exit" => {
            let stop = crate::util::pick_text(&logger.texts().stop_msgs);
            logger.info(&stop);
            return (stop, true);
        }
        _ => "[ERR] 输入 help 查看命令列表".to_string(),
    };
    (response, false)
}

/// TCP 控制台仅绑定回环地址，避免未认证的远程主机获得管理权限。
pub fn start_tcp_console(players: Players, logger: Arc<Logger>, running: Arc<AtomicBool>) {
    let logger_tcp = logger.clone();
    std::thread::spawn(move || {
        let listener = match TcpListener::bind("127.0.0.1:25625") {
            Ok(l) => l,
            Err(e) => {
                logger_tcp.error(&format!("[Console] 绑定 127.0.0.1:25625 失败: {e}"));
                return;
            }
        };
        logger_tcp.info("[Console] 仅监听本机 127.0.0.1:25625（无远程访问）");
        for stream in listener.incoming() {
            let mut stream = match stream {
                Ok(s) => s,
                Err(_) => continue,
            };
            let players = players.clone();
            let log = logger.clone();
            let running = running.clone();
            std::thread::spawn(move || {
                let _ = stream.write_all(b"NekoGate Rust Console (type help)\n> ");
                let _ = stream.flush();
                let reader_stream = match stream.try_clone() {
                    Ok(s) => s,
                    Err(_) => return,
                };
                let mut reader = BufReader::new(reader_stream);
                let mut line = String::new();
                loop {
                    line.clear();
                    match reader.read_line(&mut line) {
                        Ok(0) | Err(_) => break,
                        Ok(_) => {}
                    }
                    let (response, quit) = handle_cmd(&line, &players, &log);
                    if !response.is_empty() {
                        let _ = writeln!(stream, "{response}");
                    }
                    if quit {
                        let _ = stream.write_all(b"bye\n");
                        let _ = stream.flush();
                        running.store(false, Ordering::SeqCst);
                        break;
                    }
                    let _ = stream.write_all(b"> ");
                    let _ = stream.flush();
                }
            });
        }
    });
}

/// stdin 交互式控制台（跑程序的终端里直接敲）
pub fn start_stdin_console(players: Players, logger: Arc<Logger>, running: Arc<AtomicBool>) {
    std::thread::spawn(move || {
        let stdin = std::io::stdin();
        let mut input = stdin.lock();
        let mut line = String::new();
        while running.load(Ordering::SeqCst) {
            line.clear();
            match input.read_line(&mut line) {
                Ok(0) | Err(_) => break,
                Ok(_) => {
                    let (response, quit) = handle_cmd(&line, &players, &logger);
                    if !response.is_empty() {
                        println!("{response}");
                    }
                    if quit {
                        running.store(false, Ordering::SeqCst);
                        break;
                    }
                }
            }
        }
    });
}
