use std::io::{BufRead, BufReader, Write};
use std::net::TcpListener;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use crate::logger::Logger;
use crate::proxy::{broadcast_system_chat, Players};

/// 处理一条控制台命令，返回 (响应, 是否应该退出)
fn handle_cmd(cmd: &str, players: &Players, logger: &Logger) -> (String, bool) {
    let cmd = cmd.trim();
    if cmd.is_empty() {
        return (String::new(), false);
    }
    if let Some(msg) = cmd.strip_prefix("say ") {
        let c = broadcast_system_chat(players, msg, logger);
        logger.info(&format!("[Console] say \"{}\" -> {} 名玩家", msg, c));
        return (format!("[OK] 已发送给 {} 名玩家", c), false);
    }
    match cmd {
        "list" => {
            let n = players.lock().unwrap().len();
            (format!("[Players] {} 连接", n), false)
        }
        "quit" | "exit" => {
            let stop = crate::util::pick_text(&logger.texts().stop_msgs);
            logger.info(&stop);
            (stop, true)
        }
        _ => ("[ERR] 用法: say <msg> | list | quit".to_string(), false),
    }
}

/// TCP 控制台仅绑定回环地址，避免未认证的远程主机获得管理权限。
pub fn start_tcp_console(players: Players, logger: Arc<Logger>, running: Arc<AtomicBool>) {
    let logger_tcp = logger.clone();
    std::thread::spawn(move || {
        let listener = match TcpListener::bind("127.0.0.1:25625") {
            Ok(l) => l,
            Err(e) => {
                logger_tcp.error(&format!("[Console] 绑定 127.0.0.1:25625 失败: {}", e));
                return;
            }
        };
        logger_tcp.info("[Console] 仅监听本机 127.0.0.1:25625（无远程访问）");
        for stream in listener.incoming() {
            let mut s = match stream {
                Ok(s) => s,
                Err(_) => continue,
            };
            let players = players.clone();
            let lg = logger.clone();
            let running = running.clone();
            std::thread::spawn(move || {
                let _ = s.write_all(b"NekoGate Rust Console (say/list/quit)\n> ");
                let _ = s.flush();
                let reader_stream = match s.try_clone() {
                    Ok(stream) => stream,
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
                    let (resp, quit) = handle_cmd(&line, &players, &lg);
                    if !resp.is_empty() {
                        let _ = s.write_all(resp.as_bytes());
                        let _ = s.write_all(b"\n");
                    }
                    if quit {
                        let _ = s.write_all(b"bye\n");
                        let _ = s.flush();
                        running.store(false, Ordering::SeqCst);
                        break;
                    }
                    let _ = s.write_all(b"> ");
                    let _ = s.flush();
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
                    let (resp, quit) = handle_cmd(&line, &players, &logger);
                    if !resp.is_empty() {
                        println!("{}", resp);
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
