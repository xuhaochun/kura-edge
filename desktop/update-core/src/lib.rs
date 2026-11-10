//! kura-edge 桌面外壳**热更新的纯逻辑**（RFC 006）。
//!
//! 为什么单独一个 crate：Tauri 本体在没有 `webkit2gtk` 开发包的机器上编不了，把"解析清单、
//! 校验 jar、原子写、更新决策"这些不碰窗口的逻辑搬出来，它们就能在 CI/开发机上用
//! `cargo test` 真跑（外壳里只剩"停进程/起进程/刷新窗口"这类没法单测的胶水）。
//!
//! 两侧共享的文件协议（详见 `change/rfc/006-backend-hot-update.md`）：
//!
//! - `staging/pending.json` —— 后端写，`PendingRequest`：请求外壳应用某版本
//! - `app/current.json`    —— 外壳写，`CurrentState`：当前该启动哪个 jar（唯一真相）
//! - `app/apply-result.json` —— 外壳写，`ApplyResult`：最近一次应用的结果
//!
//! 单一写者原则：`app/` 只有外壳写，`staging/` 只有后端写，两侧对对方目录只读。

pub mod atomic;
pub mod manifest;
pub mod plan;
pub mod time;
pub mod verify;

pub use manifest::{ApplyResult, ApplyStatus, CurrentState, JarRef, PendingRequest, PROTOCOL};
pub use plan::{choose_launch_jar, decide, install, looks_like_jar, stale_jars, Decision, LaunchChoice};
pub use verify::{check_version, jar_file_name, validate_jar, Reject, MAX_JAR_BYTES, SHA256_HEX_LEN};
