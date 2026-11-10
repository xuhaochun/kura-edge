//! 热更新状态机（RFC 006 §2.3）。
//!
//! 分工一句话：**后端只把 jar 放进 `staging/` 并写下 `pending.json`；外壳负责换文件、改指针、
//! 重启 JVM、刷新页面，必要时回滚。** exe 全程不重启。
//!
//! 两种落地方式，由后端 `/api/v1/system/version` 的 `blueGreen` 决定：
//! - **蓝绿（PostgreSQL，RFC 007，默认）**：旧实例不退出，外壳先把新实例以 standby 拉起、等它就绪、
//!   把窗口导航过去，再让旧实例 `retire`（停图 → 放锁 → 退出），新实例随即接管图执行权。
//!   全程页面不中断，图执行只中断一次组件重建的时间。
//! - **就地重启（文件 H2）**：旧实例收到 `pending.json` 后自己退出，外壳用同一端口重启新 jar；
//!   期间页面等 15~25 s，起不来就回滚。
//!
//! 为什么后端退出而不是外壳 kill：Windows 上 `Child::kill` 是 `TerminateProcess`，会掐掉 H2 刷盘，
//! 也绕过 Spring 的 graceful shutdown；让后端自己收到停机信号退出才安全（RFC 006 D2）。
//!
//! 判定逻辑（协议校验、摘要比对、版本冲突、启动候选回退）都在 `kura-edge-update-core` 里，
//! 那个 crate 能在没有 webkit 的机器上用 `cargo test` 真跑；本文件只做无法单测的胶水。

use std::fs;
use std::path::{Path, PathBuf};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use kura_edge_update_core::atomic;
use kura_edge_update_core::manifest::{ApplyResult, ApplyStatus, CurrentState, PendingRequest, PROTOCOL};
use kura_edge_update_core::time::now_rfc3339;
use kura_edge_update_core::verify::{jar_file_name, sha256_file, validate_jar};
use kura_edge_update_core::{choose_launch_jar, decide, install, stale_jars, Decision, LaunchChoice};
use tauri::{AppHandle, Manager, WebviewWindow};

use crate::backend;
use crate::log::log_line;
use crate::paths::free_port;
use crate::window;
use crate::Shell;

/// 轮询 `pending.json` 的间隔。
const WATCH_INTERVAL: Duration = Duration::from_secs(2);
/// 等后端自己优雅退出的上限；超时则强杀（兜底，正常路径是它 1.5 s 后自己退）。
const BACKEND_EXIT_TIMEOUT: Duration = Duration::from_secs(15);
/// 端口占用时的重试次数（复用原端口失败就换新端口）。
const PORT_RETRIES: usize = 3;
/// jar 打开的重试次数与间隔：杀毒软件对新解压/新下载的 60 MB jar 做实时扫描，几百毫秒内会占用文件。
const JAR_OPEN_RETRIES: usize = 12;
const JAR_OPEN_RETRY_INTERVAL: Duration = Duration::from_millis(400);
/// 蓝绿移交后等新实例抢到图执行权的上限：正常是 0.5~2 s（租约轮询 500 ms + 图重建）。
const LEADER_TIMEOUT: Duration = Duration::from_secs(20);

const PENDING_FILE: &str = "pending.json";
const CURRENT_FILE: &str = "current.json";
const RESULT_FILE: &str = "apply-result.json";
const MARKER_FILE: &str = "bundled.txt";

/// 启动准备：决定这一轮用哪个 jar（`app/current.json` → 它的 previous → 安装目录基线）。
///
/// 首次启动（或状态丢失）时把安装目录的基线 jar 复制进 `app/` 并写下 `current.json`，
/// 之后再更新就是"新增一个版本化文件名 + 改指针"，旧文件不动（运行中的 JVM 持有句柄，删不掉）。
pub fn prepare_launch(bundled_jar: &Path, bundled_version: &str, home: &Path) -> Result<LaunchChoice, String> {
    let current = read_state(home);
    let marker = read_marker(home);
    let identity = file_identity(bundled_jar, bundled_version);
    let stale_pointer = current
        .as_ref()
        .map(|state| !Path::new(&state.jar).is_file())
        .unwrap_or(true);
    // 安装器换过基线 jar（exe 升级 / 修复安装）→ 以新基线为准，否则升级 exe 后永远跑旧 jar
    let installer_replaced_baseline = match (&marker, &identity) {
        (Some(seen), Some(now)) => seen != now,
        _ => false,
    };
    let mut current = current;
    if current.is_none() || stale_pointer || installer_replaced_baseline {
        match materialize_baseline(bundled_jar, bundled_version, home, current.as_ref(), identity.as_deref()) {
            Ok(state) => {
                log_line(&format!("[launcher] 基线已落地：{} v{}", state.jar, state.version));
                current = Some(state);
            }
            Err(error) => log_line(&format!(
                "[launcher] 基线落地失败（{error}），本轮直接使用安装目录 jar"
            )),
        }
    }

    let mut last = String::new();
    for attempt in 0..JAR_OPEN_RETRIES {
        match choose_launch_jar(current.as_ref(), bundled_jar, bundled_version) {
            Ok(choice) => return Ok(choice),
            Err(error) => {
                last = error;
                if attempt + 1 < JAR_OPEN_RETRIES {
                    thread::sleep(JAR_OPEN_RETRY_INTERVAL);
                }
            }
        }
    }
    Err(format!("没有可用的后端 jar：{last}"))
}

/// 后台线程：每 2 s 看一次 `staging/pending.json`。
pub fn spawn_watcher(app: AppHandle) {
    thread::spawn(move || {
        // 用 (mtime, size) 去重：同一个文件不重复处理，也不因为"处理过但没删"而空转
        let mut seen: Option<(u64, u64)> = None;
        loop {
            thread::sleep(WATCH_INTERVAL);
            if let Err(error) = poll_once(&app, &mut seen) {
                log_line(&format!("[update] {error}"));
            }
        }
    });
}

fn poll_once(app: &AppHandle, seen: &mut Option<(u64, u64)>) -> Result<(), String> {
    let shell = app.state::<Shell>();
    let path = shell.staging_dir.join(PENDING_FILE);
    let Some(fingerprint) = fingerprint(&path) else {
        return Ok(());
    };
    if *seen == Some(fingerprint) {
        return Ok(());
    }
    *seen = Some(fingerprint);
    drop(shell);

    log_line("[update] 发现 pending.json，开始处理");
    let text = fs::read_to_string(&path).map_err(|error| format!("读取 pending.json 失败：{error}"))?;
    match serde_json::from_str::<PendingRequest>(&text) {
        Ok(pending) => apply(app, &pending),
        Err(error) => {
            let message = format!("pending.json 无法解析：{error}");
            log_line(&format!("[update] {message}"));
            let shell = app.state::<Shell>();
            write_result(&shell.home_dir, ApplyStatus::Rejected, "unknown", Some(&message));
            archive_pending(app, "rejected");
            drop(shell);
            recover_backend(app);
            Ok(())
        }
    }
}

/// 按状态机处理一次更新请求。
fn apply(app: &AppHandle, pending: &PendingRequest) -> Result<(), String> {
    let shell = app.state::<Shell>();
    let previous = read_state(&shell.home_dir);
    let decision = match decide(pending, &shell.staging_dir, &shell.home_dir, previous.as_ref()) {
        Ok(decision) => decision,
        Err(reject) => {
            let message = reject.message();
            log_line(&format!("[update] 拒绝 v{}：{message}", pending.version));
            write_result(&shell.home_dir, ApplyStatus::Rejected, &pending.version, Some(&message));
            archive_pending(app, "rejected");
            drop(shell);
            // 后端写下 pending.json 后就会退出；拒绝也必须把它拉回来，不能留一台死设备
            recover_backend(app);
            return Ok(());
        }
    };

    let version = match &decision {
        Decision::Apply { version, .. } => version.clone(),
        Decision::Skip { reason } => {
            log_line(&format!("[update] 跳过 v{}：{reason}", pending.version));
            write_result(&shell.home_dir, ApplyStatus::Applied, &pending.version, None);
            archive_pending(app, "applied");
            drop(shell);
            // 幂等跳过时后端同样已经/即将退出，必须按当前指针把它拉起来
            recover_backend(app);
            return Ok(());
        }
    };
    let home_dir = shell.home_dir.clone();
    drop(shell);

    let webview = app.get_webview_window("main");
    if let Some(webview) = &webview {
        window::show_updating(webview, &version);
    }

    // 蓝绿：旧实例不会自己退出（它要一直服务到新实例就绪），走"先起新的、再退役旧的"
    if backend::is_blue_green(app) {
        return apply_blue_green(app, &decision, &version, previous.as_ref(), &home_dir, &webview);
    }

    // 1. 等后端自己退出（它写下 pending.json 后约 1.5 s 开始优雅关闭）
    if !backend::wait_for_exit(app, BACKEND_EXIT_TIMEOUT) {
        log_line("[update] 后端未在 15s 内退出，强制结束进程");
        backend::stop(app);
    }

    // 2. 装 jar + 改指针（先写 current.json 再启动，崩在中间下次仍按指针走）
    let installed = install(&decision, previous.as_ref(), &home_dir)
        .map_err(|error| format!("安装 v{version} 失败：{error}"))
        .and_then(|next| {
            write_state(&home_dir, &next).map(|()| next).map_err(|error| error.to_string())
        });
    let next = match installed {
        Ok(next) => next,
        Err(message) => {
            log_line(&format!("[update] {message}"));
            write_result(&home_dir, ApplyStatus::RolledBack, &version, Some(&message));
            archive_pending(app, "failed");
            return restore(app, previous.as_ref(), &webview);
        }
    };
    log_line(&format!("[update] 已安装 v{}：{}", next.version, next.jar));

    // 3. 用新 jar 重启（同端口同口令），探测就绪
    match start_version(app, Path::new(&next.jar), &next.version, &webview) {
        Ok(port) => {
            write_result(&home_dir, ApplyStatus::Applied, &next.version, None);
            archive_pending(app, "applied");
            cleanup_old_jars(&home_dir, &next);
            log_line(&format!("[update] v{} 启动成功，端口 {port}", next.version));
            Ok(())
        }
        Err(message) => {
            log_line(&format!("[update] 新版本启动失败：{message}"));
            write_result(&home_dir, ApplyStatus::RolledBack, &next.version, Some(&message));
            archive_pending(app, "rolled_back");
            restore(app, previous.as_ref(), &webview)
        }
    }
}

/// 蓝绿落地（RFC 007）：新实例先就绪并接管页面，旧实例随后移交，全程不出现"后端没起来"的窗口。
///
/// 顺序是零中断的关键，不能调换：
/// 1. 装 jar（**先不改** `current.json`：指针还是旧版，任何一步失败都能按旧版原地重启）；
/// 2. 用新 jar 起 standby（新端口、同口令、同库）：此时旧实例仍在服务，新实例因抢不到执行权不跑图；
/// 3. 等 standby HTTP 200 → 把窗口导航过去（用户此刻已经在新版本上，页面不等重启）；
/// 4. 让旧实例 `retire`：停图 → 放锁 → 退出；
/// 5. 等新实例 `role=leader`（旧实例一放锁，新实例 0.5 s 内接手）；
/// 6. 改指针、归档 pending、清理旧 jar。
///
/// 失败面只有两处会真正中断服务：standby 起不来（此时旧实例**完全没被动过**，直接返回即可），
/// 以及移交给新实例后它没接管（极不可能，此时按旧指针重启旧 jar，属于兜底回滚）。
fn apply_blue_green(
    app: &AppHandle,
    decision: &Decision,
    version: &str,
    previous: Option<&CurrentState>,
    home_dir: &Path,
    webview: &Option<WebviewWindow>,
) -> Result<(), String> {
    // 1. 装 jar，但先不动指针
    let next = match install(decision, previous, home_dir) {
        Ok(next) => next,
        Err(error) => {
            let message = format!("安装 v{version} 失败：{error}");
            log_line(&format!("[update] {message}"));
            write_result(home_dir, ApplyStatus::RolledBack, version, Some(&message));
            archive_pending(app, "failed");
            // 旧实例一直在服务，这里什么都不用修
            return Ok(());
        }
    };

    let old_port = backend::current_port(app);
    let shell = app.state::<Shell>();
    let javaw = shell.javaw.clone();
    let password = shell.password.clone();
    let log_path = shell.log_path.clone();
    let staging_dir = shell.staging_dir.clone();
    drop(shell);

    // 2. 起 standby：新端口，旧实例继续在那个端口服务
    let port = free_port();
    let mut standby = match backend::start(
        &javaw,
        Path::new(&next.jar),
        port,
        &password,
        &log_path,
        home_dir,
        &staging_dir,
    ) {
        Ok(child) => child,
        Err(message) => {
            log_line(&format!("[update] 蓝绿：standby 拉起失败，旧实例继续服务：{message}"));
            write_result(home_dir, ApplyStatus::RolledBack, version, Some(&message));
            archive_pending(app, "failed");
            return Ok(());
        }
    };
    log_line(&format!(
        "[update] 蓝绿：standby v{} 已拉起（端口 {port}），旧实例仍在 {old_port} 服务",
        next.version
    ));

    // 3. 等 standby 就绪；失败只杀 standby，旧实例毫发无损
    if let Err(message) = backend::wait_ready_child(&mut standby, port) {
        let _ = standby.kill();
        let _ = standby.wait();
        log_line(&format!("[update] 蓝绿：standby 未就绪，已放弃本次更新：{message}"));
        write_result(home_dir, ApplyStatus::RolledBack, version, Some(&message));
        archive_pending(app, "failed");
        return Ok(());
    }

    // 4. 导航到新实例：从这里开始用户在用新版本
    if let Some(webview) = webview {
        window::navigate(webview, port, Some(&next.version));
    }
    log_line(&format!("[update] 蓝绿：窗口已切到 v{}（端口 {port}），开始移交执行权", next.version));

    // 5. 旧实例停图、放锁、退出
    if !backend::request_retire(old_port, &password) {
        log_line("[update] 蓝绿：旧实例未受理 retire，改为强制结束进程（它的图会随之停下）");
    }
    if !backend::wait_for_exit(app, BACKEND_EXIT_TIMEOUT) {
        log_line("[update] 蓝绿：旧实例未在 15s 内退出，强制结束");
        backend::stop(app);
    }
    backend::reap_child(app);

    // 6. 等新实例接管图执行权
    if !backend::wait_graph_leader(port, &password, LEADER_TIMEOUT) {
        let _ = standby.kill();
        let _ = standby.wait();
        let message = format!("v{version} 已就绪但未接管线图（执行权未移交）");
        log_line(&format!("[update] 蓝绿：{message}，回滚"));
        write_result(home_dir, ApplyStatus::RolledBack, version, Some(&message));
        archive_pending(app, "rolled_back");
        return restore(app, previous, webview);
    }

    // 7. 登记新子进程 + 改指针
    backend::store_child(app, standby, PathBuf::from(&next.jar), port);
    if let Err(error) = write_state(home_dir, &next) {
        // 指针写不进去意味着下次冷启动会退回旧版：如实标失败，但服务本身是好的
        let message = format!("v{} 已接管，但写 current.json 失败：{error}", next.version);
        log_line(&format!("[update] {message}"));
        write_result(home_dir, ApplyStatus::Applied, &next.version, Some(&message));
        archive_pending(app, "applied");
        return Ok(());
    }
    write_result(home_dir, ApplyStatus::Applied, &next.version, None);
    archive_pending(app, "applied");
    cleanup_old_jars(home_dir, &next);
    log_line(&format!(
        "[update] 蓝绿完成：v{} 已在端口 {port} 接管，旧实例已退出",
        next.version
    ));
    Ok(())
}

/// 回滚：把指针写回上一版（或删掉指针回到安装目录基线）并把它拉起来。
fn restore(
    app: &AppHandle,
    previous: Option<&CurrentState>,
    webview: &Option<WebviewWindow>,
) -> Result<(), String> {
    backend::stop(app);
    let shell = app.state::<Shell>();
    let home_dir = shell.home_dir.clone();
    let bundled_jar = shell.bundled_jar.clone();
    let bundled_version = shell.bundled_version.clone();
    let log_path = shell.log_path.clone();
    drop(shell);

    let (jar, version) = match previous {
        Some(state) => {
            write_state(&home_dir, state).map_err(|error| format!("恢复 current.json 失败：{error}"))?;
            (PathBuf::from(&state.jar), state.version.clone())
        }
        None => {
            let _ = fs::remove_file(home_dir.join(CURRENT_FILE));
            (bundled_jar, bundled_version)
        }
    };
    match start_version(app, &jar, &version, webview) {
        Ok(port) => {
            log_line(&format!("[update] 已回滚到 v{version}，端口 {port}"));
            Ok(())
        }
        Err(message) => {
            log_line(&format!("[update] 回滚也失败：{message}"));
            let message = crate::log::with_log_hint(
                &format!("后端更新失败且回滚失败，需要人工处理。\n\n{message}"),
                &log_path,
            );
            if let Some(webview) = webview {
                window::show_error(webview, &message);
            }
            Err(message)
        }
    }
}

/// 启动某个版本的 jar 并等到 HTTP 200：复用原端口，bind 失败就换端口再试。
fn start_version(
    app: &AppHandle,
    jar: &Path,
    version: &str,
    webview: &Option<WebviewWindow>,
) -> Result<u16, String> {
    let shell = app.state::<Shell>();
    let javaw = shell.javaw.clone();
    let password = shell.password.clone();
    let log_path = shell.log_path.clone();
    let home_dir = shell.home_dir.clone();
    let staging_dir = shell.staging_dir.clone();
    let preferred = backend::current_port(app);
    drop(shell);

    let mut candidates = Vec::with_capacity(PORT_RETRIES + 1);
    if preferred != 0 {
        candidates.push(preferred);
    }
    for _ in 0..PORT_RETRIES {
        candidates.push(free_port());
    }

    let mut last = String::from("没有可尝试的端口");
    for port in candidates {
        let began = Instant::now();
        match backend::start(&javaw, jar, port, &password, &log_path, &home_dir, &staging_dir) {
            Ok(child) => {
                backend::store_child(app, child, jar.to_path_buf(), port);
                match backend::wait_until_ready(app, port) {
                    Ok(()) => {
                        if let Some(webview) = webview {
                            window::navigate(webview, port, Some(version));
                        }
                        return Ok(port);
                    }
                    Err(message) => {
                        log_line(&format!("[update] 端口 {port} 上启动 v{version} 失败：{message}"));
                        backend::stop(app);
                        let hung = began.elapsed() >= backend::STARTUP_TIMEOUT;
                        last = message;
                        if hung {
                            // 等到超时才失败说明 JVM 卡住了（配置/依赖问题），换端口不会好，别再耗一个 120s
                            break;
                        }
                    }
                }
            }
            Err(message) => last = message,
        }
    }
    Err(format!("无法启动 v{version}：{last}"))
}

/// 保证后端在跑：`pending.json` 一旦存在，后端就已（或即将）优雅退出，这里等它退完再按当前指针拉起。
///
/// 拒绝与幂等跳过两条路径都走它——"请求被拒"绝不能以一台死设备收场。
fn recover_backend(app: &AppHandle) {
    if backend::has_child(app) && !backend::wait_for_exit(app, BACKEND_EXIT_TIMEOUT) {
        // 后端没按预期退出（比如 pending.json 是人工放进来的），保持现状即可
        log_line("[update] 后端未在预期时间内退出，按现状继续运行");
        return;
    }
    if backend::is_alive(app) {
        return;
    }
    let shell = app.state::<Shell>();
    let home_dir = shell.home_dir.clone();
    let webview = app.get_webview_window("main");
    let state = read_state(&home_dir);
    let (jar, version) = match state {
        Some(state) => (PathBuf::from(&state.jar), state.version),
        None => (shell.bundled_jar.clone(), shell.bundled_version.clone()),
    };
    drop(shell);
    if let Err(error) = start_version(app, &jar, &version, &webview) {
        log_line(&format!("[update] 重启后端失败：{error}"));
    } else {
        log_line(&format!("[update] 已按当前指针重启 v{version}"));
    }
}

/// 删掉既不是当前、也不是上一版的旧 jar（运行中的那份持有句柄，删不掉很正常，忽略失败）。
fn cleanup_old_jars(home_dir: &Path, current: &CurrentState) {
    let mut keep: Vec<PathBuf> = vec![PathBuf::from(&current.jar)];
    if let Some(previous) = &current.previous {
        keep.push(PathBuf::from(&previous.jar));
    }
    let keep_refs: Vec<&Path> = keep.iter().map(PathBuf::as_path).collect();
    for path in stale_jars(home_dir, &keep_refs) {
        match fs::remove_file(&path) {
            Ok(()) => log_line(&format!("[update] 清理旧 jar：{}", path.display())),
            Err(error) => log_line(&format!("[update] 旧 jar 暂不可删（可忽略）：{}（{error}）", path.display())),
        }
    }
}

fn read_state(home_dir: &Path) -> Option<CurrentState> {
    let path = home_dir.join(CURRENT_FILE);
    let bytes = fs::read(&path).ok()?;
    match serde_json::from_slice::<CurrentState>(&bytes) {
        Ok(state) => Some(state),
        Err(error) => {
            log_line(&format!(
                "[launcher] {} 无法解析（{error}），按安装目录基线启动",
                path.display()
            ));
            None
        }
    }
}

fn write_state(home_dir: &Path, state: &CurrentState) -> std::io::Result<()> {
    let bytes = serde_json::to_vec_pretty(state)?;
    atomic::write_atomic(&home_dir.join(CURRENT_FILE), &bytes)
}

fn write_result(home_dir: &Path, status: ApplyStatus, version: &str, error: Option<&str>) {
    let result = ApplyResult::new(status, version, error.map(str::to_owned));
    match serde_json::to_vec_pretty(&result) {
        Ok(bytes) => {
            if let Err(write_error) = atomic::write_atomic(&home_dir.join(RESULT_FILE), &bytes) {
                log_line(&format!("[update] 写 apply-result.json 失败：{write_error}"));
            }
        }
        Err(serialize_error) => log_line(&format!("[update] 序列化 apply-result 失败：{serialize_error}")),
    }
}

/// 把 `pending.json` 改名成 `pending.<tag>-<epoch>.json`：留证据，同时让下一次请求能被识别为新文件。
fn archive_pending(app: &AppHandle, tag: &str) {
    let shell = app.state::<Shell>();
    let staging_dir = shell.staging_dir.clone();
    drop(shell);
    let source = staging_dir.join(PENDING_FILE);
    let target = staging_dir.join(format!("pending.{tag}-{}.json", epoch_seconds()));
    if let Err(error) = fs::rename(&source, &target) {
        log_line(&format!("[update] 归档 pending.json 失败：{error}"));
    }
}

/// 安装目录基线 jar 的身份标记：`<exe 版本>\t<字节数>\t<mtime 秒>`。
///
/// 用它识别"安装器换过基线 jar"（exe 升级、修复安装）：换了就必须重新落地，否则新装的 exe
/// 会一直跑 `current.json` 里记着的旧 jar。
fn file_identity(path: &Path, version: &str) -> Option<String> {
    let metadata = fs::metadata(path).ok()?;
    let modified = metadata.modified().ok()?.duration_since(UNIX_EPOCH).ok()?.as_secs();
    Some(format!("{version}\t{}\t{modified}", metadata.len()))
}

fn read_marker(home_dir: &Path) -> Option<String> {
    fs::read_to_string(home_dir.join(MARKER_FILE))
        .ok()
        .map(|text| text.trim().to_owned())
}

fn write_marker(home_dir: &Path, identity: Option<&str>) -> std::io::Result<()> {
    let Some(identity) = identity else {
        return Ok(());
    };
    atomic::write_atomic(&home_dir.join(MARKER_FILE), identity.as_bytes())
}

/// 把安装目录的基线 jar 复制进 `app/` 并写下 `current.json`（首个版本，`previous` 为空）。
fn materialize_baseline(
    bundled_jar: &Path,
    bundled_version: &str,
    home_dir: &Path,
    previous: Option<&CurrentState>,
    identity: Option<&str>,
) -> Result<CurrentState, String> {
    let name = jar_file_name(bundled_version).map_err(|reject| reject.message())?;
    validate_jar(bundled_jar, None).map_err(|reject| reject.message())?;
    let target = home_dir.join(&name);
    let temp = home_dir.join(format!("{name}.tmp-{}", std::process::id()));
    fs::copy(bundled_jar, &temp).map_err(|error| format!("复制基线 jar 失败：{error}"))?;
    atomic::install_file(&temp, &target).map_err(|error| format!("就位基线 jar 失败：{error}"))?;
    let sha256 = sha256_file(&target).map_err(|error| format!("计算基线 sha256 失败：{error}"))?;
    let state = CurrentState {
        protocol: PROTOCOL,
        version: bundled_version.to_owned(),
        jar: target.display().to_string(),
        sha256,
        applied_at: now_rfc3339(),
        // 同一路径（版本号没变的重装/重新落地）时 previous 不能指向刚被覆盖的文件，否则"回滚"回的是同一份字节
        previous: previous
            .filter(|state| state.jar != target.display().to_string())
            .map(CurrentState::as_jar_ref),
    };
    write_state(home_dir, &state).map_err(|error| format!("写 current.json 失败：{error}"))?;
    if let Err(error) = write_marker(home_dir, identity) {
        log_line(&format!("[launcher] 写基线标记失败（下次会重新落地）：{error}"));
    }
    Ok(state)
}

fn fingerprint(path: &Path) -> Option<(u64, u64)> {
    let metadata = fs::metadata(path).ok()?;
    let modified = metadata.modified().ok()?.duration_since(UNIX_EPOCH).ok()?.as_millis() as u64;
    Some((modified, metadata.len()))
}

fn epoch_seconds() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or_default()
}
