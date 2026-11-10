//! 目录解析：安装目录、用户数据目录与空闲端口（RFC 005 §2.1 / RFC 006 §2.1）。
//!
//! 三层目录互相独立：
//! - **安装目录**（NSIS `currentUser` 下即 `%LOCALAPPDATA%\kura-edge`）：`resources/app/kura-edge.jar` 是基线 jar，
//!   `resources/jre` 是内嵌 JRE；卸载会清这里，所以热更产物不写这里。
//! - **用户数据目录**：`%LOCALAPPDATA%\kura-edge\{app,staging,logs}`，热更新用的 `app/`（外壳独占写）与
//!   `staging/`（后端独占写）都在这里，卸载不牵连。

use std::net::TcpListener;
use std::path::{Path, PathBuf};

use tauri::Manager;

/// 外壳用到的三个用户目录。
pub struct Dirs {
    /// `app/`：外壳独占写，放 `current.json`、版本化 jar、`apply-result.json`。
    pub home: PathBuf,
    /// `staging/`：后端独占写，放待安装 jar 与 `pending.json`。
    pub staging: PathBuf,
    /// `logs/`：后端 stdout/stderr 与外壳启动日志。
    pub logs: PathBuf,
}

/// 解析并创建用户目录：`%LOCALAPPDATA%\kura-edge\{app,staging,logs}`（非 Windows 回退到临时目录）。
pub fn dirs() -> Dirs {
    let base = std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(std::env::temp_dir)
        .join("kura-edge");
    let dirs = Dirs {
        home: base.join("app"),
        staging: base.join("staging"),
        logs: base.join("logs"),
    };
    for dir in [&dirs.home, &dirs.staging, &dirs.logs] {
        // 建不出来时后续步骤会给出更具体的错误，这里不吞掉语义、只忽略返回值
        let _ = std::fs::create_dir_all(dir);
    }
    dirs
}

/// 安装根目录：优先 Tauri 的 resource_dir（NSIS `currentUser` 下即安装目录），
/// 回退到 exe 所在目录；两者都可能因为打包方式差异而不同，所以用「jar 是否存在」来判定。
pub fn install_root(app: &tauri::App) -> PathBuf {
    let probe = |root: &Path| root.join("resources").join("app").join("kura-edge.jar").is_file();
    let exe_root = std::env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(Path::to_path_buf))
        .unwrap_or_else(|| PathBuf::from("."));
    if let Ok(resource_root) = app.path().resource_dir() {
        if probe(&resource_root) {
            return plain_path(&resource_root);
        }
    }
    plain_path(&exe_root)
}

/// 去掉 Windows 的 verbatim 前缀：`\\?\D:\kura-edge` → `D:\kura-edge`，
/// `\\?\UNC\server\share` → `\\server\share`。
///
/// Tauri 的 `resource_dir()` 会 `canonicalize`，在 Windows 上返回带 `\\?\` 的路径；
/// 这个前缀对 Rust 的 `File::open` 无害，但 **Java 启动器用 CRT 打开 jar**，遇到 verbatim
/// 前缀就报「尝试打开文件 \\?\… 时出现意外错误」——OpenJDK 的 `jli` 不走 Win32 长路径 API。
pub fn plain_path(path: &Path) -> PathBuf {
    let text = path.to_string_lossy();
    let plain = if let Some(rest) = text.strip_prefix(r"\\?\UNC\") {
        format!(r"\\{rest}")
    } else if let Some(rest) = text.strip_prefix(r"\\?\") {
        rest.to_string()
    } else {
        return path.to_path_buf();
    };
    // 真正超过传统 MAX_PATH(260) 的长路径不能去前缀，否则连 Win32 API 都打不开
    if plain.len() < 240 {
        PathBuf::from(plain)
    } else {
        path.to_path_buf()
    }
}

/// 让 OS 分配一个空闲端口，避免与用户机上已占用的 8080 冲突。
pub fn free_port() -> u16 {
    TcpListener::bind(("127.0.0.1", 0))
        .and_then(|listener| listener.local_addr())
        .map(|address| address.port())
        .unwrap_or(18080)
}
