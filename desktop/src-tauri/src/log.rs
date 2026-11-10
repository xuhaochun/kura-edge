//! 启动/更新日志：统一追加到 `%LOCALAPPDATA%\kura-edge\logs\backend.log`。
//!
//! 桌面版没有控制台（`windows_subsystem = "windows"`），用户报障时这份日志是唯一的第一手证据；
//! 后端 JVM 的 stdout/stderr 也重定向到同一个文件，于是启动、更新、后端运行三段日志天然接续。

use std::io::Write;
use std::path::Path;

use crate::paths;

/// 往 `logs/backend.log` 追加一行（写失败只能放弃：窗口已经起来了，不能因为日志崩掉）。
pub fn log_line(text: &str) {
    let path = paths::dirs().logs.join("backend.log");
    append(&path, text);
}

/// 往指定日志文件追加一行。
pub fn append(path: &Path, text: &str) {
    if let Ok(mut file) = std::fs::OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(file, "{text}");
    }
}

/// 给错误信息补上日志路径提示，让用户知道下一步去哪里看细节。
pub fn with_log_hint(message: &str, log_path: &Path) -> String {
    format!("{message}\n\n后端日志：{}", log_path.display())
}
