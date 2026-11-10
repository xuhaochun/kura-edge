//! 文件协议的数据结构（两侧同名同义，字段全部 camelCase）。

use serde::{Deserialize, Serialize};

/// 协议版本。任何字段语义变化都必须加一，旧外壳遇到新协议直接拒（不猜）。
pub const PROTOCOL: u32 = 1;

fn default_protocol() -> u32 {
    PROTOCOL
}

/// 一段"版本 + jar + 摘要"的引用，`current.json.previous` 用它记录上一版。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JarRef {
    /// 版本号，如 `1.0.4`。
    pub version: String,
    /// jar 的**绝对路径**（安装目录可能在 C: 或 D:，相对路径不可靠）。
    pub jar: String,
    /// jar 的 sha256（64 位小写十六进制）。
    pub sha256: String,
}

/// `app/current.json`：外壳独占写，决定下次启动用哪个 jar。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CurrentState {
    /// 协议版本，缺省视为 1（兼容手写的初始文件）。
    #[serde(default = "default_protocol")]
    pub protocol: u32,
    /// 当前生效的版本号。
    pub version: String,
    /// 当前生效 jar 的绝对路径。
    pub jar: String,
    /// 当前生效 jar 的 sha256。
    pub sha256: String,
    /// 该版本生效的时间（RFC 3339 UTC）。
    #[serde(default)]
    pub applied_at: String,
    /// 上一版，回滚用；首个版本为 `None`。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub previous: Option<JarRef>,
}

impl CurrentState {
    /// 把当前记录降级为 `previous` 引用。
    pub fn as_jar_ref(&self) -> JarRef {
        JarRef {
            version: self.version.clone(),
            jar: self.jar.clone(),
            sha256: self.sha256.clone(),
        }
    }
}

/// `staging/pending.json`：后端写，外壳读到就尝试应用。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PendingRequest {
    /// 协议版本。
    #[serde(default = "default_protocol")]
    pub protocol: u32,
    /// 目标版本号（决定落地文件名）。
    pub version: String,
    /// `staging/` 下的**文件名**（不是路径；外壳用白名单正则校验）。
    pub jar: String,
    /// 期望的 sha256（64 位小写十六进制）。
    pub sha256: String,
    /// 期望的字节数；0 表示不校验（仅为兼容，后端总是填）。
    #[serde(default)]
    pub size: u64,
    /// 请求发起时间（RFC 3339 UTC），只用于日志与排障。
    #[serde(default)]
    pub requested_at: String,
}

/// `app/apply-result.json` 的状态枚举。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ApplyStatus {
    /// 新版本已安装并成功启动。
    Applied,
    /// 新版本启动失败，已回滚到上一版。
    RolledBack,
    /// 请求本身非法（校验未通过），未动任何文件。
    Rejected,
}

impl ApplyStatus {
    /// 稳定的字符串形式（写日志/落盘一致）。
    pub fn as_str(self) -> &'static str {
        match self {
            ApplyStatus::Applied => "applied",
            ApplyStatus::RolledBack => "rolled_back",
            ApplyStatus::Rejected => "rejected",
        }
    }
}

/// `app/apply-result.json`：外壳独占写，运维/后端排障用。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ApplyResult {
    /// 协议版本。
    #[serde(default = "default_protocol")]
    pub protocol: u32,
    /// 结果状态。
    pub status: ApplyStatus,
    /// 涉及的版本号。
    pub version: String,
    /// 失败原因；成功时省略。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    /// 结果写入时间（RFC 3339 UTC）。
    pub at: String,
}

impl ApplyResult {
    /// 构造一次结果记录。
    pub fn new(status: ApplyStatus, version: impl Into<String>, error: Option<String>) -> Self {
        ApplyResult {
            protocol: PROTOCOL,
            status,
            version: version.into(),
            error,
            at: crate::time::now_rfc3339(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn current_state_round_trips_through_json() {
        let state = CurrentState {
            protocol: PROTOCOL,
            version: "1.0.4".to_owned(),
            jar: r"C:\Users\x\AppData\Local\kura-edge\app\kura-edge-1.0.4.jar".to_owned(),
            sha256: "a".repeat(64),
            applied_at: "2026-11-10T03:06:00Z".to_owned(),
            previous: Some(JarRef {
                version: "1.0.3".to_owned(),
                jar: r"C:\Users\x\AppData\Local\kura-edge\app\kura-edge-1.0.3.jar".to_owned(),
                sha256: "b".repeat(64),
            }),
        };

        let json = serde_json::to_string(&state).expect("current.json 应能序列化");
        let back: CurrentState = serde_json::from_str(&json).expect("current.json 应能反序列化");

        assert_eq!(back, state, "往返必须无损（含 Windows 反斜杠路径）");
        assert!(json.contains("\"appliedAt\""), "字段名必须是 camelCase");
        assert!(json.contains("\"previous\""), "previous 必须落盘");
    }

    #[test]
    fn missing_protocol_defaults_to_one() {
        let json = r#"{"version":"1.0.0","jar":"/tmp/a.jar","sha256":"cc"}"#;

        let state: CurrentState = serde_json::from_str(json).expect("缺 protocol 也应能解析");

        assert_eq!(state.protocol, PROTOCOL);
        assert_eq!(state.previous, None, "previous 缺省为空");
    }

    #[test]
    fn unknown_fields_are_ignored_for_forward_compat() {
        let json = r#"{"protocol":1,"version":"1.0.0","jar":"/tmp/a.jar","sha256":"cc","future":42}"#;

        let state: CurrentState = serde_json::from_str(json).expect("未知字段应被忽略");

        assert_eq!(state.version, "1.0.0");
    }

    #[test]
    fn pending_request_rejects_missing_required_field() {
        let json = r#"{"version":"1.0.4","jar":"kura-edge-1.0.4.jar"}"#;

        let parsed = serde_json::from_str::<PendingRequest>(json);

        assert!(parsed.is_err(), "缺 sha256 必须解析失败，不能默认放行");
    }

    #[test]
    fn apply_result_serializes_status_as_snake_case() {
        let result = ApplyResult::new(ApplyStatus::RolledBack, "1.0.4", Some("启动超时".to_owned()));

        let json = serde_json::to_string(&result).expect("apply-result 应能序列化");

        assert!(json.contains("\"status\":\"rolled_back\""), "实际：{json}");
    }

    #[test]
    fn apply_result_omits_error_when_successful() {
        let result = ApplyResult::new(ApplyStatus::Applied, "1.0.4", None);

        let json = serde_json::to_string(&result).expect("apply-result 应能序列化");

        assert!(!json.contains("error"), "成功时不应写 error 字段：{json}");
    }
}
