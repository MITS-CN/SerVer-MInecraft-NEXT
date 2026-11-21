use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::path::Path;
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::mpsc::{self, Sender};
use std::sync::Mutex;
use std::thread;

use serde_json::Value;

use crate::util::{level_name, now_str};

#[derive(Default, Clone)]
pub struct TextsData {
    pub banner_tails: Vec<String>,
    pub start_msgs: Vec<String>,
    pub stop_msgs: Vec<String>,
    pub ready_msgs: Vec<String>,
    pub no_lan_ip_warn: Vec<String>,
    pub new_conn_msgs: Vec<String>,
    pub handshake_msgs: Vec<String>,
    pub handshake_incomplete_msgs: Vec<String>,
    pub illegal_msgs: Vec<String>,
    pub read_timeout_msgs: Vec<String>,
    pub read_error_msgs: Vec<String>,
    pub no_data_msgs: Vec<String>,
    pub connecting_msgs: Vec<String>,
    pub target_ok_msgs: Vec<String>,
    pub target_fail_msgs: Vec<String>,
    pub abort_msgs: Vec<String>,
    pub udp_bind_msgs: Vec<String>,
    pub udp_bind_fail_msgs: Vec<String>,
    pub udp_start_msgs: Vec<String>,
    pub udp_send_fail_msgs: Vec<String>,
    pub traffic_msgs: Vec<String>,
}

fn get_string_array(v: &Value, key: &str) -> Vec<String> {
    v.get(key)
        .and_then(|x| x.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|x| x.as_str().map(String::from))
                .collect()
        })
        .unwrap_or_default()
}

fn load_texts(path: &Path) -> TextsData {
    let v: Value = match std::fs::read_to_string(path)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
    {
        Some(v) => v,
        None => {
            eprintln!("[警告] 读取 {} 失败，使用空文本池", path.display());
            return TextsData::default();
        }
    };
    TextsData {
        banner_tails: get_string_array(&v, "banner_tails"),
        start_msgs: get_string_array(&v, "start_msgs"),
        stop_msgs: get_string_array(&v, "stop_msgs"),
        ready_msgs: get_string_array(&v, "ready_msgs"),
        no_lan_ip_warn: get_string_array(&v, "no_lan_ip_warn"),
        new_conn_msgs: get_string_array(&v, "new_conn_msgs"),
        handshake_msgs: get_string_array(&v, "handshake_msgs"),
        handshake_incomplete_msgs: get_string_array(&v, "handshake_incomplete_msgs"),
        illegal_msgs: get_string_array(&v, "illegal_msgs"),
        read_timeout_msgs: get_string_array(&v, "read_timeout_msgs"),
        read_error_msgs: get_string_array(&v, "read_error_msgs"),
        no_data_msgs: get_string_array(&v, "no_data_msgs"),
        connecting_msgs: get_string_array(&v, "connecting_msgs"),
        target_ok_msgs: get_string_array(&v, "target_ok_msgs"),
        target_fail_msgs: get_string_array(&v, "target_fail_msgs"),
        abort_msgs: get_string_array(&v, "abort_msgs"),
        udp_bind_msgs: get_string_array(&v, "udp_bind_msgs"),
        udp_bind_fail_msgs: get_string_array(&v, "udp_bind_fail_msgs"),
        udp_start_msgs: get_string_array(&v, "udp_start_msgs"),
        udp_send_fail_msgs: get_string_array(&v, "udp_send_fail_msgs"),
        traffic_msgs: get_string_array(&v, "traffic_msgs"),
    }
}

fn load_faces(
    path: &Path,
) -> (
    HashMap<String, Vec<String>>,
    Vec<(Vec<String>, Vec<String>)>,
) {
    let v: Value = match std::fs::read_to_string(path)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
    {
        Some(v) => v,
        None => {
            eprintln!("[警告] 读取 {} 失败，使用空颜文字池", path.display());
            return (HashMap::new(), Vec::new());
        }
    };

    let mut levels: HashMap<String, Vec<String>> = HashMap::new();
    if let Some(obj) = v.get("levels").and_then(|x| x.as_object()) {
        for (k, arr) in obj {
            if let Some(list) = arr.as_array() {
                let faces: Vec<String> = list
                    .iter()
                    .filter_map(|x| x.as_str().map(String::from))
                    .collect();
                levels.insert(k.clone(), faces);
            }
        }
    }

    let mut keywords: Vec<(Vec<String>, Vec<String>)> = Vec::new();
    if let Some(arr) = v.get("keywords").and_then(|x| x.as_array()) {
        for item in arr {
            let kws = get_string_array(item, "keywords");
            let fcs = get_string_array(item, "faces");
            if !kws.is_empty() && !fcs.is_empty() {
                keywords.push((kws, fcs));
            }
        }
    }

    (levels, keywords)
}

struct LogRecord { line: String }

pub struct Logger {
    level_names: HashMap<String, Vec<String>>,
    keyword_faces: Vec<(Vec<String>, Vec<String>)>,
    texts: TextsData,
    pool_idx: Mutex<HashMap<String, usize>>,
    log_tx: Sender<LogRecord>,
    level: AtomicU8,
}

impl Logger {
    pub fn new(data_dir: &Path, log_file: &Path, min_level: u8) -> Self {
        let (level_names, keyword_faces) = load_faces(&data_dir.join("faces.json"));
        let texts = load_texts(&data_dir.join("texts.json"));

        let file = if let Some(parent) = log_file.parent() {
            let _ = std::fs::create_dir_all(parent);
            if let Ok(meta) = std::fs::metadata(log_file) {
                if meta.len() > 5 * 1024 * 1024 {
                    let _ = std::fs::rename(log_file, log_file.with_extension("log.1"));
                }
            }
            OpenOptions::new()
                .create(true)
                .append(true)
                .open(log_file)
                .ok()
        } else {
            None
        };

        let (log_tx, log_rx) = mpsc::channel::<LogRecord>();
        thread::Builder::new().name("nekoproxy-logger".to_string()).spawn(move || {
            let mut file = file;
            while let Ok(record) = log_rx.recv() {
                use std::io::Write;
                let _ = std::io::stdout().write_all(record.line.as_bytes());
                let _ = std::io::stdout().flush();
                if let Some(output) = file.as_mut() {
                    let _ = output.write_all(record.line.as_bytes());
                    let _ = output.flush();
                }
            }
        }).expect("无法启动异步日志线程");

        Logger {
            level_names,
            keyword_faces,
            texts,
            pool_idx: Mutex::new(HashMap::new()),
            log_tx,
            level: AtomicU8::new(min_level),
        }
    }

    pub fn texts(&self) -> &TextsData {
        &self.texts
    }


    fn log(&self, level: u8, msg: &str) {
        if level < self.level.load(Ordering::Relaxed) {
            return;
        }
        let face = self.pick_face(level, msg);
        let line = format!(
            "[{}] [{:<7}] {}  {}\n",
            now_str(),
            level_name(level),
            face,
            msg
        );
        let _ = self.log_tx.send(LogRecord { line });
    }

        pub fn debug(&self, m: &str) {
        self.log(crate::util::LVL_DEBUG, m);
    }
    pub fn info(&self, m: &str) {
        self.log(crate::util::LVL_INFO, m);
    }
    pub fn warn(&self, m: &str) {
        self.log(crate::util::LVL_WARNING, m);
    }
    pub fn error(&self, m: &str) {
        self.log(crate::util::LVL_ERROR, m);
    }

    fn pick_face(&self, level: u8, msg: &str) -> String {
        for (i, (kws, faces)) in self.keyword_faces.iter().enumerate() {
            if kws.iter().any(|k| msg.contains(k.as_str())) {
                let key = format!("kw:{}", i);
                return self.next_from_pool(&key, faces);
            }
        }
        let name = level_name(level);
        if let Some(faces) = self.level_names.get(name) {
            if !faces.is_empty() {
                let key = format!("lvl:{}", name);
                return self.next_from_pool(&key, faces);
            }
        }
        "(・_・;)".to_string()
    }

    fn next_from_pool(&self, key: &str, pool: &[String]) -> String {
        if pool.is_empty() {
            return String::new();
        }
        let mut idx = self.pool_idx.lock().unwrap();
        let i = idx.entry(key.to_string()).or_insert(0);
        let v = pool[*i % pool.len()].clone();
        *i += 1;
        v
    }
}