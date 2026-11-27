mod console;
mod lan;
mod logger;
mod mc;
mod proxy;
mod traffic;
mod util;
mod viaproxy;

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicBool, Ordering};

use logger::Logger;
use proxy::Players;

const DEFAULT_LISTEN: &str = "0.0.0.0:25565";
const DEFAULT_TARGET: &str = "shiguangxu.minecraft.momeow.top:20226";
const DEFAULT_MOTD: &str = "\u{00A7}8[\u{00A7}r \u{00A7}l\u{00A7}cS\u{00A7}6h\u{00A7}ei\u{00A7}aG\u{00A7}bu\u{00A7}9a\u{00A7}dn\u{00A7}5g\u{00A7}cX\u{00A7}bu\u{00A7}r \u{00A7}8] \u{00A7}f时光墟 \u{00A7}8| \u{00A7}7shiguangxu.minecraft.momeow.top";

const BANNER: &str = r#"
███    ██ ███████ ██   ██  ██████          ███    ███ ██     ██ ███    ██ ██   ██
████   ██ ██      ██  ██  ██    ██         ████  ████ ██     ██ ████   ██ ██  ██
██ ██  ██ ██      █████   ██    ██ ██████  ██ ████ ██ ██     ██ ██ ██  ██ █████
██  ██ ██ █████   ██ ██   ██    ██ ██████  ██  ██  ██ ██     ██ ██  ██ ██ ██ ██
██   ████ ██      ██  ██  ██    ██         ██      ██ ██     ██ ██   ████ ██  ██
██    ███ ███████ ██   ██  ██████          ██      ██ ███████ ██ ██    ███ ██   ██


NekoProxy  ·  powered by Rust + ViaProxy
  时 光 墟  ·  M C  透 传  代 理
"#;

struct Args {
    listen: String,
    target: String,
    motd: String,
    lan_ip: Option<String>,
    no_broadcast: bool,
    log_level: String,
    viaproxy_port: u16,
}

fn parse_args() -> Args {
    let args: Vec<String> = std::env::args().collect();
    let mut a = Args {
        listen: DEFAULT_LISTEN.to_string(),
        target: DEFAULT_TARGET.to_string(),
        motd: DEFAULT_MOTD.to_string(),
        lan_ip: None,
        no_broadcast: false,
        log_level: "INFO".to_string(),
        viaproxy_port: 25568, // ViaProxy 默认监听端口
    };

    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--listen" if i + 1 < args.len() => { a.listen = args[i + 1].clone(); i += 2; }
            "--target" if i + 1 < args.len() => { a.target = args[i + 1].clone(); i += 2; }
            "--motd" if i + 1 < args.len() => { a.motd = args[i + 1].clone(); i += 2; }
            "--lan-ip" if i + 1 < args.len() => { a.lan_ip = Some(args[i + 1].clone()); i += 2; }
            "--no-broadcast" => { a.no_broadcast = true; i += 1; }
            "--log-level" if i + 1 < args.len() => { a.log_level = args[i + 1].clone(); i += 2; }
            "--viaproxy-port" if i + 1 < args.len() => { a.viaproxy_port = args[i + 1].parse().unwrap_or(25568); i += 2; }
            arg if !arg.starts_with("--") => { a.target = arg.to_string(); i += 1; }
            _ => i += 1,
        }
    }
    a
}

fn main() {
    let a = parse_args();

    let cwd = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    let base_dir = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|p| p.to_path_buf()))
        .unwrap_or_else(|| cwd.clone());

    // Cargo places the executable under target/<profile>, but runtime assets
    // live in the project working directory. Prefer the working directory
    // when it contains the data files, then fall back to the executable dir.
    let data_candidates = [cwd.join("data"), base_dir.join("data")];
    let data_dir = data_candidates
        .iter()
        .find(|path| path.join("faces.json").is_file() || path.join("texts.json").is_file())
        .cloned()
        .unwrap_or_else(|| cwd.join("data"));
    let log_file = cwd.join("Log").join("proxy.log");

    let logger = Arc::new(Logger::new(
        &data_dir,
        &log_file,
        util::level_from_str(&a.log_level),
    ));

    println!("{}", BANNER.trim_end_matches('\n'));
    println!("            {}", util::pick_text(&logger.texts().banner_tails));
    println!();

    let (l_host, l_port) = util::split_host_port(&a.listen, 25565);
    let (t_host, t_port) = util::split_host_port(&a.target, 25565);

    logger.info(&format!(
        "{} 时间={} Rust={} 日志级别={}",
        util::pick_text(&logger.texts().start_msgs),
        util::now_str(),
        env!("CARGO_PKG_VERSION"),
        util::level_name(util::level_from_str(&a.log_level))
    ));
    logger.info(&format!("日志文件 {}", log_file.display()));
    logger.info(&format!("数据目录 {}", data_dir.display()));
    logger.info(&format!("监听 {}:{} -> 目标 {}:{}", l_host, l_port, t_host, t_port));

    if !a.no_broadcast {
        logger.info(&format!(
            "UDP 广播开启 MOTD=\"{}\" 网卡={}",
            a.motd,
            a.lan_ip.clone().unwrap_or_else(|| "未指定".into())
        ));
        if a.lan_ip.is_none() {
            logger.warn(&util::pick_text(&logger.texts().no_lan_ip_warn));
        }
    } else {
        logger.info("UDP 广播关闭");
    }

    // ---------- 安装 ViaProxy 原生聊天注入插件 ----------
    let plugin_candidates = [
        cwd.join("viaproxy-plugin").join("build").join("libs").join("nekogate-viaproxy-plugin.jar"),
        base_dir.join("viaproxy-plugin").join("build").join("libs").join("nekogate-viaproxy-plugin.jar"),
    ];
    // Build the plugin on first launch so a fresh clone can start with one command.
    if !plugin_candidates.iter().any(|path| path.is_file()) {
        logger.info("[ViaProxy] 未找到聊天插件，正在自动构建 viaproxy-plugin...");
        // Inherit the console streams instead of buffering them. This keeps Gradle's
        // progress visible and, crucially, ensures startup failures are not hidden
        // when the asynchronous logger thread is stopped by an early return.
        #[cfg(windows)]
        let build_result = std::process::Command::new("powershell")
            .args(["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "scripts/build-viaproxy-plugin.ps1"])
            .current_dir(&cwd)
            .status();
        #[cfg(not(windows))]
        let build_result = std::process::Command::new("sh")
            .args(["scripts/build-viaproxy-plugin.sh"])
            .current_dir(&cwd)
            .status();

        match build_result {
            Ok(status) if status.success() => {
                println!("[ViaProxy] Gradle 插件构建成功。");
            }
            Ok(status) => {
                eprintln!(
                    "[ViaProxy] 插件构建失败，Gradle 退出状态: {}。请检查上方 Gradle 输出。",
                    status
                );
                return;
            }
            Err(e) => {
                eprintln!(
                    "[ViaProxy] 无法执行 Gradle 自动构建脚本: {}。请检查 PowerShell 或 sh 是否可用。",
                    e
                );
                return;
            }
        }
    }

    let plugin_jar = match plugin_candidates.iter().find(|path| path.is_file()) {
        Some(path) => path,
        None => {
            logger.error(&format!(
                "[ViaProxy] Gradle 构建完成但插件 JAR 仍不存在，检查位置: {}",
                plugin_candidates.iter().map(|p| p.display().to_string()).collect::<Vec<_>>().join(", ")
            ));
            return;
        }
    };
    let plugins_dir = cwd.join("plugins");
    if let Err(e) = std::fs::create_dir_all(&plugins_dir) {
        logger.error(&format!("无法创建 ViaProxy plugins 目录 {}: {}", plugins_dir.display(), e));
        return;
    }
    let installed_plugin = plugins_dir.join("nekogate-viaproxy-plugin.jar");
    if let Err(e) = std::fs::copy(plugin_jar, &installed_plugin) {
        logger.error(&format!("无法安装 ViaProxy 聊天插件: {}", e));
        return;
    }
    logger.info(&format!("[ViaProxy] 已安装聊天插件: {}", installed_plugin.display()));

    // ---------- 启动 ViaProxy 子进程 ----------
    // Cargo run places the executable under target/<profile>, while users
    // commonly keep ViaProxy.jar in the project working directory.
    let viaproxy_jar_candidates = [
        cwd.join("ViaProxy.jar"),
        cwd.join("ViaProxy-3.4.14.jar"),
        base_dir.join("ViaProxy.jar"),
        base_dir.join("ViaProxy-3.4.14.jar"),
    ];
    let viaproxy_jar_target = viaproxy_jar_candidates[0].clone();

    // Automatic ViaProxy updates are temporarily paused.
    // Keep the updater scripts in the repository for future manual use.
    logger.info("[ViaProxy] 启动时自动更新已暂停，使用本地现有版本");

    let viaproxy_jar = match viaproxy_jar_candidates.iter().find(|path| path.is_file()) {
        Some(path) => path,
        None => {
            logger.error(&format!(
                "ViaProxy JAR 未找到（支持 ViaProxy.jar 和 ViaProxy-3.4.14.jar），已检查: {}",
                viaproxy_jar_candidates
                    .iter()
                    .map(|path| path.display().to_string())
                    .collect::<Vec<_>>()
                    .join(", ")
            ));
            return;
        }
    };

    let viaproxy_host = "127.0.0.1".to_string();
    let viaproxy_addr = format!("{}:{}", viaproxy_host, a.viaproxy_port);
    let viaproxy_child = match viaproxy::start_viaproxy(
        viaproxy_jar.to_str().expect("ViaProxy.jar 路径不是有效的 UTF-8"),
        &viaproxy_addr,
        &t_host,
        t_port,
        logger.clone(),
    ) {
        Ok(child) => child,
        Err(e) => {
            logger.error(&format!("无法启动 ViaProxy: {}", e));
            return;
        }
    };

    let running = Arc::new(AtomicBool::new(true));
    let running_ctrlc = running.clone();
    let logger_ctrlc = logger.clone();
    ctrlc::set_handler(move || {
        logger_ctrlc.info(&util::pick_text(&logger_ctrlc.texts().stop_msgs));
        running_ctrlc.store(false, Ordering::SeqCst);
    }).expect("设置 Ctrl+C 处理器失败");

    // ---------- 控制台 & 其他服务 ----------
    let players: Players = Arc::new(Mutex::new(HashMap::new()));
    console::start_tcp_console(players.clone(), logger.clone(), running.clone());
    console::start_stdin_console(players.clone(), logger.clone(), running.clone());

    if !a.no_broadcast {
        lan::start_udp_broadcast(logger.clone(), a.motd.clone(), l_port, a.lan_ip.clone());
    }
    traffic::start_reporter(logger.clone(), 60);

    // ---------- TCP 代理主循环（转发到 ViaProxy） ----------
    let listener = match std::net::TcpListener::bind(format!("{}:{}", l_host, l_port)) {
        Ok(l) => l,
        Err(e) => {
            logger.error(&format!("绑定 {}:{} 失败: {}", l_host, l_port, e));
            return;
        }
    };
    logger.info(&util::pick_text(&logger.texts().ready_msgs));

    // 将监听器设为非阻塞，以便响应 Ctrl+C
    listener.set_nonblocking(true).unwrap();

    while running.load(Ordering::SeqCst) {
        match listener.accept() {
            Ok((client, _)) => {
                let viaproxy_host = viaproxy_host.clone();
                let viaproxy_port = a.viaproxy_port;
                let players = players.clone();
                let logger = logger.clone();
                std::thread::spawn(move || {
                    proxy::handle_client(client, viaproxy_host, viaproxy_port, players, logger);
                });
            }
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                std::thread::sleep(std::time::Duration::from_millis(100));
            }
            Err(e) => {
                logger.error(&format!("Accept 错误: {}", e));
            }
        }
    }

    // ---------- 优雅退出 ----------
    logger.info("正在关闭 ViaProxy...");
    viaproxy::stop_viaproxy(viaproxy_child, logger.clone());
    logger.info("代理已完全关闭");
}