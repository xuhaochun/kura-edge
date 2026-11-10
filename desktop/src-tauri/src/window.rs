//! 窗口与页面：内置启动/错误页、导航、"正在更新"遮罩（RFC 005 §2.2 + RFC 006 §2.3 第 4 步）。

use serde_json::json;
use tauri::{Url, WebviewUrl, WebviewWindow, WebviewWindowBuilder};

use crate::log::log_line;

/// 构建主窗口：先加载内置启动/错误页，注入凭据与初始错误信息。
///
/// `initialization_script` 会在该 webview 的**每次**文档加载时执行，因此热更新后重新导航到
/// 后端的页面时，注入的 `__KURA_EDGE_AUTH__` 依然存在，前端不必重新登录（后端用 HTTP Basic）。
pub fn build_window(
    app: &tauri::App,
    password: &str,
    startup_error: Option<&str>,
) -> tauri::Result<WebviewWindow> {
    let error = startup_error.map_or(serde_json::Value::Null, |message| json!(message));
    let init_script = format!(
        "window.__KURA_EDGE_AUTH__ = {}; window.__KURA_EDGE_ERROR__ = {};",
        json!({ "username": "admin", "password": password }),
        error
    );
    WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
        .title("kura-edge")
        .inner_size(1440.0, 900.0)
        .min_inner_size(1024.0, 640.0)
        .center()
        .initialization_script(&init_script)
        .build()
}

/// 导航到后端的页面。热更新成功后带 `?v=<version>`，避免 webview 复用旧页面的缓存。
pub fn navigate(webview: &WebviewWindow, port: u16, version: Option<&str>) {
    let suffix = version.map(|value| format!("?v={value}")).unwrap_or_default();
    match Url::parse(&format!("http://127.0.0.1:{port}/{suffix}")) {
        Ok(url) => {
            let _ = webview.navigate(url);
        }
        Err(error) => log_line(&format!("[window] 本地地址解析失败：{error}")),
    }
}

/// 把错误信息推给内置错误页（页面未加载完时先存到全局变量，页面加载后自读）。
pub fn show_error(webview: &WebviewWindow, message: &str) {
    let payload = serde_json::to_string(message).unwrap_or_else(|_| "\"后端启动失败\"".to_owned());
    let script = format!(
        "window.__KURA_EDGE_ERROR__ = {payload}; if (typeof window.__kuraShowError === 'function') {{ window.__kuraShowError({payload}); }}"
    );
    let _ = webview.eval(&script);
}

/// 在后端页面盖一层"正在更新"遮罩：更新期间不要出现用户乱点造成的假象。
///
/// 注入失败（页面还没加载完、或已经是内置页）就忽略——遮罩只是体验，不参与状态机。
pub fn show_updating(webview: &WebviewWindow, version: &str) {
    let label = serde_json::to_string(version).unwrap_or_else(|_| "\"\"".to_owned());
    let script = format!(
        r#"(function(){{
  var VERSION = {label};
  if (document.getElementById('kura-edge-updating')) return;
  var box = document.createElement('div');
  box.id = 'kura-edge-updating';
  box.style.cssText = 'position:fixed;inset:0;z-index:2147483647;display:flex;align-items:center;' +
    'justify-content:center;flex-direction:column;gap:14px;background:rgba(17,24,39,.94);' +
    'color:#f9fafb;font:14px/1.7 system-ui,-apple-system,"Segoe UI",sans-serif';
  var title = document.createElement('div');
  title.style.cssText = 'font-size:17px;font-weight:600';
  title.textContent = '正在更新后端…';
  var hint = document.createElement('div');
  hint.style.cssText = 'opacity:.75;max-width:32rem;text-align:center';
  hint.textContent = '目标版本 ' + VERSION + '。窗口不会关闭，更新完成后自动刷新；新版本若起不来会自动回滚。';
  box.appendChild(title);
  box.appendChild(hint);
  document.body.appendChild(box);
}})();"#
    );
    let _ = webview.eval(&script);
}
