use std::sync::Arc;
use std::time::Duration;

use crate::logger::Logger;
use crate::proxy::{global_down, global_up, ACTIVE_CONNECTIONS, TOTAL_CONNECTIONS};
use crate::util::{format_bytes, pick_text};
use std::sync::atomic::Ordering;

pub fn start_reporter(logger: Arc<Logger>, interval_secs: u64) {
    std::thread::spawn(move || {
        let mut last_up = 0u64;
        let mut last_down = 0u64;
        loop {
            std::thread::sleep(Duration::from_secs(interval_secs));
            let cur_up = global_up();
            let cur_down = global_down();
            if cur_up == 0 && cur_down == 0 {
                continue;
            }
            let du = cur_up - last_up;
            let dd = cur_down - last_down;
            last_up = cur_up;
            last_down = cur_down;
            logger.info(&format!(
                "[流量] {} 周期={}s 新增=+{}/+{} 累计={}/{} 总计={} 活跃={} 累计连接={}",
                pick_text(&logger.texts().traffic_msgs),
                interval_secs,
                format_bytes(du),
                format_bytes(dd),
                format_bytes(cur_up),
                format_bytes(cur_down),
                format_bytes(cur_up + cur_down),
                ACTIVE_CONNECTIONS.load(Ordering::Relaxed),
                TOTAL_CONNECTIONS.load(Ordering::Relaxed)
            ));
        }
    });
}