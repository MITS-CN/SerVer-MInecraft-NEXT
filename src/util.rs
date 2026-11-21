use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

pub const LVL_DEBUG: u8 = 0;
pub const LVL_INFO: u8 = 1;
pub const LVL_WARNING: u8 = 2;
pub const LVL_ERROR: u8 = 3;
pub const LVL_CRITICAL: u8 = 4;

static RAND_SEED: AtomicU64 = AtomicU64::new(0x9E3779B97F4A7C15);

pub fn rand_usize() -> usize {
    let x = RAND_SEED.fetch_add(0x9E3779B97F4A7C15, Ordering::Relaxed);
    let mut z = x.wrapping_add(0x9E3779B97F4A7C15);
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D049BB133111EB);
    (z ^ (z >> 31)) as usize
}

pub fn pick_text(pool: &[String]) -> String {
    if pool.is_empty() {
        return String::new();
    }
    pool[rand_usize() % pool.len()].clone()
}

pub fn now_str() -> String {
    let dur = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    let secs = dur.as_secs() as i64 + 8 * 3600;
    let millis = dur.subsec_millis();
    let days = secs / 86400;
    let rem = secs % 86400;
    let (y, mo, d) = days_to_ymd(days);
    format!(
        "{:04}-{:02}-{:02} {:02}:{:02}:{:02}.{:03}",
        y,
        mo,
        d,
        rem / 3600,
        (rem % 3600) / 60,
        rem % 60,
        millis
    )
}

fn days_to_ymd(mut days: i64) -> (i32, u32, u32) {
    let mut y = 1970i32;
    loop {
        let dy = if is_leap(y) { 366 } else { 365 };
        if days < dy {
            break;
        }
        days -= dy;
        y += 1;
    }
    let months = [31u32, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
    let mut mo = 1u32;
    for (i, &md) in months.iter().enumerate() {
        let md = if i == 1 && is_leap(y) { 29 } else { md };
        if days < md as i64 {
            break;
        }
        days -= md as i64;
        mo += 1;
    }
    (y, mo, days as u32 + 1)
}

fn is_leap(y: i32) -> bool {
    (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
}

pub fn format_bytes(mut size: u64) -> String {
    let units = ["B", "KB", "MB", "GB", "TB", "PB"];
    let mut i = 0;
    let mut f = size as f64;
    while f >= 1024.0 && i < units.len() - 1 {
        f /= 1024.0;
        i += 1;
    }
    let _ = &mut size;
    format!("{:.2} {}", f, units[i])
}

pub fn level_name(l: u8) -> &'static str {
    match l {
        LVL_DEBUG => "DEBUG",
        LVL_INFO => "INFO",
        LVL_WARNING => "WARNING",
        LVL_ERROR => "ERROR",
        _ => "CRITICAL",
    }
}

pub fn level_from_str(s: &str) -> u8 {
    match s.to_uppercase().as_str() {
        "DEBUG" => LVL_DEBUG,
        "INFO" => LVL_INFO,
        "WARNING" | "WARN" => LVL_WARNING,
        "ERROR" => LVL_ERROR,
        "CRITICAL" => LVL_CRITICAL,
        _ => LVL_INFO,
    }
}

pub fn split_host_port(s: &str, def_port: u16) -> (String, u16) {
    let parts: Vec<&str> = s.rsplitn(2, ':').collect();
    if parts.len() == 2 {
        (
            parts[1].to_string(),
            parts[0].parse::<u16>().unwrap_or(def_port),
        )
    } else {
        (s.to_string(), def_port)
    }
}