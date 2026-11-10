//! 后端子进程：拉起、健康探测、退出检测与回收（RFC 005 §2.2 + RFC 006 §2.3 + RFC 007 蓝绿）。

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use tauri::{AppHandle, Manager, WebviewWindow};

use crate::log;
use crate::window;
use crate::Shell;

/// 后端启动等待上限：解压后的 JRE 在冷机器上首次启动较慢（本机实测约 17s），给足余量。
pub const STARTUP_TIMEOUT: Duration = Duration::from_secs(120);
/// 端口轮询间隔。
pub const POLL_INTERVAL: Duration = Duration::from_millis(250);
/// 单次连接探测超时。
const CONNECT_TIMEOUT: Duration = Duration::from_millis(300);
/// 内置管理员用户名：口令是外壳每次启动随机生成并注入后端的，用户名固定（见 SecurityProperties）。
const ADMIN_USER: &str = "admin";

/// 拉起内嵌 JRE 中的 Spring Boot；stdout/stderr 重定向到日志文件，便于排障。
///
/// `KURA_EDGE_UPDATE_DIR` / `KURA_EDGE_HOME` 就是外壳与后端约定的两个目录（RFC 006 §2.1）：
/// 后端据此知道"能热更新"，也据此找到 `app/current.json` 来回答 `GET /api/v1/system/version`。
pub fn start(
    javaw: &Path,
    jar: &Path,
    port: u16,
    password: &str,
    log_path: &Path,
    home_dir: &Path,
    staging_dir: &Path,
) -> Result<Child, String> {
    if !javaw.is_file() {
        return Err(format!("找不到内嵌 JRE：{}", javaw.display()));
    }
    if !jar.is_file() {
        return Err(format!("找不到后端 jar：{}", jar.display()));
    }
    if let Some(parent) = log_path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let stdout = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(log_path)
        .map_err(|error| format!("无法创建日志文件 {}：{error}", log_path.display()))?;
    let stderr = stdout.try_clone().map_err(|error| format!("无法复制日志句柄：{error}"))?;

    Command::new(javaw)
        .arg("-Dfile.encoding=UTF-8")
        .arg("-jar")
        .arg(jar)
        .arg(format!("--server.port={port}"))
        .arg("--server.address=127.0.0.1")
        .arg("--spring.main.banner-mode=off")
        .env("KURA_EDGE_ADMIN_PASSWORD", password)
        .env("KURA_EDGE_HOME", home_dir)
        .env("KURA_EDGE_UPDATE_DIR", staging_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::from(stdout))
        .stderr(Stdio::from(stderr))
        .spawn()
        .map_err(|error| format!("无法启动后端进程 {}：{error}", javaw.display()))
}

/// 后台等待后端监听端口：成功则导航到本地页面，失败则把原因写进内置错误页。
pub fn spawn_readiness_watch(app: AppHandle, webview: WebviewWindow, port: u16, log_path: PathBuf) {
    thread::spawn(move || match wait_until_ready(&app, port) {
        Ok(()) => window::navigate(&webview, port, None),
        Err(message) => window::show_error(&webview, &log::with_log_hint(&message, &log_path)),
    });
}

/// 轮询直到后端返回 HTTP 200；期间若后端进程提前退出，立即返回失败原因。
///
/// 不能只看 TCP 连通：`free_port()` 释放端口到 Spring 真正 bind 之间有十几秒窗口，
/// 端口若在这期间被别人占走，TCP 探测会误判就绪而把窗口导航到陌生进程的页面。
pub fn wait_until_ready(app: &AppHandle, port: u16) -> Result<(), String> {
    if !has_child(app) {
        return Err("后端进程不存在，无法探测就绪".to_owned());
    }
    let deadline = Instant::now() + STARTUP_TIMEOUT;
    let address = SocketAddr::from(([127, 0, 0, 1], port));
    while Instant::now() < deadline {
        if let Some(exit) = child_exit_status(app) {
            return Err(format!("后端进程提前退出：{exit}（端口 {port}）"));
        }
        if http_ready(&address) {
            return Ok(());
        }
        thread::sleep(POLL_INTERVAL);
    }
    Err(format!(
        "后端在 {} 秒内未监听 127.0.0.1:{port}",
        STARTUP_TIMEOUT.as_secs()
    ))
}

/// 发一个最小 HTTP 请求探测真实应用层就绪（要求 200，5xx/4xx 都算没就绪）。
fn http_ready(address: &SocketAddr) -> bool {
    use std::io::{Read, Write};

    let Ok(mut stream) = TcpStream::connect_timeout(address, CONNECT_TIMEOUT) else {
        return false;
    };
    let _ = stream.set_read_timeout(Some(Duration::from_millis(800)));
    let _ = stream.set_write_timeout(Some(Duration::from_millis(800)));
    if stream
        .write_all(b"GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
        .is_err()
    {
        return false;
    }
    let mut buf = [0u8; 32];
    match stream.read(&mut buf) {
        Ok(read) if read >= 12 => buf.starts_with(b"HTTP/1.1 200") || buf.starts_with(b"HTTP/1.0 200"),
        _ => false,
    }
}

/// 后端进程是否已退出；返回退出状态字符串（未退出或没有子进程返回 `None`）。
pub fn child_exit_status(app: &AppHandle) -> Option<String> {
    let shell = app.try_state::<Shell>()?;
    let mut runtime = shell.runtime.lock().ok()?;
    let child = runtime.child.as_mut()?;
    match child.try_wait() {
        Ok(Some(status)) => Some(status.to_string()),
        _ => None,
    }
}

/// 后端是否还活着（有子进程且未退出）。
pub fn is_alive(app: &AppHandle) -> bool {
    has_child(app) && child_exit_status(app).is_none()
}

/// 是否有活着的（或至少注册过的）后端子进程。
pub fn has_child(app: &AppHandle) -> bool {
    app.try_state::<Shell>()
        .and_then(|shell| shell.runtime.lock().ok().map(|runtime| runtime.child.is_some()))
        .unwrap_or(false)
}

/// 等后端自己退出（热更新时它收到信号后会优雅关闭），超时返回 false。
pub fn wait_for_exit(app: &AppHandle, timeout: Duration) -> bool {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if child_exit_status(app).is_some() {
            return true;
        }
        thread::sleep(POLL_INTERVAL);
    }
    false
}

/// 记录新拉起的子进程。
pub fn store_child(app: &AppHandle, child: Child, jar: PathBuf, port: u16) {
    if let Some(shell) = app.try_state::<Shell>() {
        if let Ok(mut runtime) = shell.runtime.lock() {
            runtime.child = Some(child);
            runtime.jar = jar;
            runtime.port = port;
        }
    }
}

/// 当前端口（没有运行态时返回 0）。
pub fn current_port(app: &AppHandle) -> u16 {
    app.try_state::<Shell>()
        .and_then(|shell| shell.runtime.lock().ok().map(|runtime| runtime.port))
        .unwrap_or(0)
}

/// 等**指定的**子进程监听端口（蓝绿时同时挂着新旧两个后端，不能用全局状态判断就绪）。
pub fn wait_ready_child(child: &mut Child, port: u16) -> Result<(), String> {
    let deadline = Instant::now() + STARTUP_TIMEOUT;
    let address = SocketAddr::from(([127, 0, 0, 1], port));
    while Instant::now() < deadline {
        if let Ok(Some(status)) = child.try_wait() {
            return Err(format!("新后端进程提前退出：{status}（端口 {port}）"));
        }
        if http_ready(&address) {
            return Ok(());
        }
        thread::sleep(POLL_INTERVAL);
    }
    Err(format!(
        "新后端在 {} 秒内未监听 127.0.0.1:{port}",
        STARTUP_TIMEOUT.as_secs()
    ))
}

/// 查后端的图执行角色：返回 (蓝绿能力, 是否 leader)。
///
/// 读 `GET /api/v1/system/version` 里的 `blueGreen` / `role` 两个字段。用最朴素的字符串匹配而不是
/// JSON 解析：这段响应是本项目自己产的固定结构，为它引一个 JSON 依赖不值当（`serde_json` 已经在了，
/// 但解析整个响应体反而更脆——字段顺序一变就要改代码）。
pub fn graph_role(port: u16, password: &str) -> Option<(bool, bool)> {
    let request = format!(
        "GET /api/v1/system/version HTTP/1.1\r\nHost: 127.0.0.1\r\n{}Connection: close\r\n\r\n",
        basic_header(password)
    );
    let raw = http_exchange(port, &request)?;
    if !is_success(&raw, "200") {
        return None;
    }
    Some((
        raw.contains("\"blueGreen\":true"),
        raw.contains("\"role\":\"leader\""),
    ))
}

/// 旧实例是否具备蓝绿能力（决定走"先起新实例"还是"等它自己退出"）。
pub fn is_blue_green(app: &AppHandle) -> bool {
    let port = current_port(app);
    if port == 0 {
        return false;
    }
    let password = match app.try_state::<Shell>() {
        Some(shell) => shell.password.clone(),
        None => return false,
    };
    matches!(graph_role(port, &password), Some((true, _)))
}

/// 让旧实例交出图执行权并退出（`POST /api/v1/system/retire`，RFC 007）。
pub fn request_retire(port: u16, password: &str) -> bool {
    let request = format!(
        "POST /api/v1/system/retire HTTP/1.1\r\nHost: 127.0.0.1\r\n{}Content-Length: 0\r\nConnection: close\r\n\r\n",
        basic_header(password)
    );
    matches!(http_exchange(port, &request), Some(raw) if is_success(&raw, "202"))
}

/// 等新实例抢到图执行权（`role=leader`）：旧实例退出到新实例接管之间只有几百毫秒。
pub fn wait_graph_leader(port: u16, password: &str, timeout: Duration) -> bool {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if matches!(graph_role(port, password), Some((_, true))) {
            return true;
        }
        thread::sleep(POLL_INTERVAL);
    }
    false
}

/// 回收已退出的子进程（`Child::wait`），释放进程句柄后再登记新的那个。
pub fn reap_child(app: &AppHandle) {
    if let Some(shell) = app.try_state::<Shell>() {
        if let Ok(mut runtime) = shell.runtime.lock() {
            if let Some(mut child) = runtime.child.take() {
                let _ = child.wait();
            }
        }
    }
}

fn basic_header(password: &str) -> String {
    format!(
        "Authorization: Basic {}\r\n",
        base64(format!("{ADMIN_USER}:{password}").as_bytes())
    )
}

fn is_success(raw: &str, status: &str) -> bool {
    raw.starts_with(&format!("HTTP/1.1 {status}")) || raw.starts_with(&format!("HTTP/1.0 {status}"))
}

/// 发一个 HTTP/1.1 请求并读回整个响应（服务端带 `Connection: close`，读到 EOF 即完整）。
fn http_exchange(port: u16, request: &str) -> Option<String> {
    let address = SocketAddr::from(([127, 0, 0, 1], port));
    let mut stream = TcpStream::connect_timeout(&address, CONNECT_TIMEOUT).ok()?;
    let _ = stream.set_read_timeout(Some(Duration::from_millis(2000)));
    let _ = stream.set_write_timeout(Some(Duration::from_millis(2000)));
    stream.write_all(request.as_bytes()).ok()?;
    let mut raw = Vec::new();
    stream.read_to_end(&mut raw).ok()?;
    Some(String::from_utf8_lossy(&raw).into_owned())
}

/// 标准 base64（Basic 认证头用）。只为这一处引一个依赖不值当，20 行就够。
fn base64(input: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(input.len().div_ceil(3) * 4);
    for chunk in input.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = *chunk.get(1).unwrap_or(&0) as u32;
        let b2 = *chunk.get(2).unwrap_or(&0) as u32;
        let triple = (b0 << 16) | (b1 << 8) | b2;
        out.push(TABLE[(triple >> 18) as usize & 63] as char);
        out.push(TABLE[(triple >> 12) as usize & 63] as char);
        out.push(if chunk.len() > 1 {
            TABLE[(triple >> 6) as usize & 63] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            TABLE[triple as usize & 63] as char
        } else {
            '='
        });
    }
    out
}

/// 结束后端子进程并回收，避免留下孤儿 `javaw.exe`。
pub fn stop(app: &AppHandle) {
    if let Some(shell) = app.try_state::<Shell>() {
        if let Ok(mut runtime) = shell.runtime.lock() {
            if let Some(mut child) = runtime.child.take() {
                let _ = child.kill();
                let _ = child.wait();
            }
        }
    }
}
