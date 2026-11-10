//! kura-edge 桌面外壳入口（RFC 005 打包 + RFC 006 后端热更新）。
//!
//! 三层职责：
//! 1. **启动**：选 jar（`app/current.json` 记录的版本 → 安装目录基线）→ 拉起内嵌 JRE 里的 Spring Boot
//!    （只监听 `127.0.0.1:<空闲端口>`）→ 探测到 HTTP 200 后打开窗口；
//! 2. **热更新**：后台线程盯 `staging/pending.json`。PostgreSQL 模式走**蓝绿**——先把新实例以 standby
//!    拉起、就绪后把窗口导航过去，再让旧实例 `retire`（停图 → 放锁 → 退出），新实例随即接管图执行权；
//!    文件 H2 模式退回"等旧进程退出 → 同端口重启新 jar"。两条路径都会在失败时自动回滚，**exe 全程不重启**
//!    （见 `update.rs`）；
//! 3. **收尾**：窗口关闭时结束后端子进程，避免留下孤儿 `javaw.exe`。
//!
//! 凭据每次启动随机生成，只经环境变量与 WebView 初始化脚本传递，不落盘、不进前端 bundle（RFC 005 D7）。
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod backend;
mod log;
mod paths;
mod update;
mod window;

use std::path::PathBuf;
use std::process::Child;
use std::sync::Mutex;

use rand::RngCore;
use tauri::{Manager, RunEvent};

use crate::log::{log_line, with_log_hint};
use crate::window::build_window;

/// 后端运行态：热更新时会整体换掉（子进程、jar、端口）。
pub struct Runtime {
    /// 当前后端子进程；`None` 表示还没起来或已被回收。
    pub child: Option<Child>,
    /// 当前实际启动的 jar。
    pub jar: PathBuf,
    /// 当前监听端口（热更新后若端口冲突会变，窗口随之导航）。
    pub port: u16,
}

/// 外壳共享状态：Tauri 管理，`update.rs` 的后台线程据此换 jar 与重启。
pub struct Shell {
    /// 内嵌 JRE 的 `javaw.exe`。
    pub javaw: PathBuf,
    /// 安装目录里的基线 jar（`resources/app/kura-edge.jar`）。
    pub bundled_jar: PathBuf,
    /// 基线版本号，取 exe 的版本（同一次流水线产出，见 `build-desktop.sh`）。
    pub bundled_version: String,
    /// `%LOCALAPPDATA%\kura-edge\app`，外壳独占写。
    pub home_dir: PathBuf,
    /// `%LOCALAPPDATA%\kura-edge\staging`，后端独占写。
    pub staging_dir: PathBuf,
    /// 后端日志文件。
    pub log_path: PathBuf,
    /// 本轮随机管理员口令（热更新重启后沿用，前端不必重登）。
    pub password: String,
    /// 运行态。
    pub runtime: Mutex<Runtime>,
}

fn main() {
    tauri::Builder::default()
        .setup(|app| {
            let root = paths::install_root(app);
            let dirs = paths::dirs();
            let bundled_jar = root.join("resources").join("app").join("kura-edge.jar");
            let javaw = root.join("resources").join("jre").join("bin").join("javaw.exe");
            let log_path = dirs.logs.join("backend.log");
            let bundled_version = app.package_info().version.to_string();
            let password = random_password();

            log_line(&format!(
                "[launcher] 安装目录 {}；app 目录 {}；staging 目录 {}",
                root.display(),
                dirs.home.display(),
                dirs.staging.display()
            ));

            let port = paths::free_port();
            let mut startup_error: Option<String> = None;
            let mut child: Option<Child> = None;
            let mut jar = bundled_jar.clone();

            match update::prepare_launch(&bundled_jar, &bundled_version, &dirs.home) {
                Ok(choice) => {
                    log_line(&format!(
                        "[launcher] 选用 v{}（{}）：{}",
                        choice.version,
                        choice.origin,
                        choice.jar.display()
                    ));
                    jar = choice.jar;
                    match backend::start(
                        &javaw,
                        &jar,
                        port,
                        &password,
                        &log_path,
                        &dirs.home,
                        &dirs.staging,
                    ) {
                        Ok(spawned) => child = Some(spawned),
                        Err(message) => {
                            log_line(&format!("[launcher] 启动后端失败：{message}"));
                            startup_error = Some(with_log_hint(&message, &log_path));
                        }
                    }
                }
                Err(message) => {
                    log_line(&format!("[launcher] 没有可用的后端 jar：{message}"));
                    startup_error = Some(with_log_hint(&message, &log_path));
                }
            }

            let shell = Shell {
                javaw,
                bundled_jar,
                bundled_version,
                home_dir: dirs.home,
                staging_dir: dirs.staging,
                log_path: log_path.clone(),
                password: password.clone(),
                runtime: Mutex::new(Runtime { child, jar, port }),
            };
            app.manage(shell);

            let webview = build_window(app, &password, startup_error.as_deref())?;
            if startup_error.is_none() {
                // 后端就绪或失败后再更新窗口：启动期间先显示“正在启动”页，避免白屏。
                backend::spawn_readiness_watch(app.handle().clone(), webview, port, log_path);
                update::spawn_watcher(app.handle().clone());
            }
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("kura-edge 桌面外壳初始化失败")
        .run(|handle, event| {
            if matches!(event, RunEvent::Exit | RunEvent::ExitRequested { .. }) {
                backend::stop(handle);
            }
        });
}

/// 每次启动生成一次性管理员口令（48 位十六进制），只用于本机回环访问。
///
/// 热更新重启后端时沿用同一个口令：前端是 HTTP Basic 每请求带凭据（`http.ts`），
/// 不是 session cookie，换 JVM 后照旧有效，用户无感（RFC 006 D8）。
fn random_password() -> String {
    let mut bytes = [0u8; 24];
    rand::rng().fill_bytes(&mut bytes);
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}
