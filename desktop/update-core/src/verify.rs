//! jar 校验：文件名白名单、zip 头、大小上限、sha256。
//!
//! 这一层是**信任边界**：外壳只装从这里通过的 jar。后端在写 `pending.json` 前已经算过一遍
//! sha256（保证"下载完整"），外壳再算一遍（保证"读到的就是要装的"）。

use std::fs::File;
use std::io::{Read, Result as IoResult};
use std::path::Path;

use sha2::{Digest, Sha256};

/// 允许的最大 jar 字节数（1 GiB）。当前 fat jar 约 60 MB，留足余量但挡住"下载了个 ISO"。
pub const MAX_JAR_BYTES: u64 = 1 << 30;

/// sha256 十六进制字符串长度。
pub const SHA256_HEX_LEN: usize = 64;

/// 版本号允许的最大长度。
const MAX_VERSION_LEN: usize = 32;

/// ZIP 本地文件头魔数 `PK\x03\x04`（与 Java 侧 `checkJar` 判据一致）。
const JAR_MAGIC: [u8; 4] = [0x50, 0x4b, 0x03, 0x04];

/// 校验失败的具体原因。带 `code()` 是为了让日志/`apply-result.json` 有稳定可 grep 的标识。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Reject {
    /// 协议版本不认识（旧外壳遇到新协议）。
    Protocol {
        /// 支持的协议版本。
        expected: u32,
        /// 文件里写的协议版本。
        actual: u32,
    },
    /// 版本号含非法字符/过长。
    InvalidVersion(String),
    /// sha256 字段不是 64 位小写十六进制。
    InvalidSha256(String),
    /// jar 文件名不符合 `kura-edge-<version>.jar` 白名单（防路径穿越）。
    InvalidJarName {
        /// 期望的文件名。
        expected: String,
        /// 实际的文件名。
        actual: String,
    },
    /// 目标文件不存在。
    Missing(String),
    /// 文件头不是 `PK\x03\x04`（不是 jar，或下载被截断）。
    NotAJar(String),
    /// 文件超过 [`MAX_JAR_BYTES`]。
    TooLarge {
        /// 文件路径。
        path: String,
        /// 实际字节数。
        bytes: u64,
    },
    /// 声明大小与实际大小不符。
    SizeMismatch {
        /// `pending.json` 里写的大小。
        declared: u64,
        /// 实际大小。
        actual: u64,
    },
    /// sha256 不符。
    Sha256Mismatch {
        /// 期望值。
        expected: String,
        /// 实际值。
        actual: String,
    },
    /// 同一版本号却给了不同内容：运维忘了改版本号，拒绝（否则会覆盖正在运行的 jar）。
    VersionConflict {
        /// 冲突的版本号。
        version: String,
    },
}

impl Reject {
    /// 稳定的机器可读标识（写日志用）。
    pub fn code(&self) -> &'static str {
        match self {
            Reject::Protocol { .. } => "protocol_mismatch",
            Reject::InvalidVersion(_) => "invalid_version",
            Reject::InvalidSha256(_) => "invalid_sha256",
            Reject::InvalidJarName { .. } => "invalid_jar_name",
            Reject::Missing(_) => "missing",
            Reject::NotAJar(_) => "not_a_jar",
            Reject::TooLarge { .. } => "too_large",
            Reject::SizeMismatch { .. } => "size_mismatch",
            Reject::Sha256Mismatch { .. } => "sha256_mismatch",
            Reject::VersionConflict { .. } => "version_conflict",
        }
    }

    /// 中文可读描述（进 `apply-result.json` 与日志）。
    pub fn message(&self) -> String {
        match self {
            Reject::Protocol { expected, actual } => {
                format!("协议版本不支持：外壳支持 {expected}，文件写的是 {actual}")
            }
            Reject::InvalidVersion(version) => format!("版本号非法：{version:?}"),
            Reject::InvalidSha256(sha256) => {
                format!("sha256 不是 64 位小写十六进制：{sha256:?}")
            }
            Reject::InvalidJarName { expected, actual } => {
                format!("jar 文件名非法：期望 {expected}，实际 {actual}")
            }
            Reject::Missing(path) => format!("文件不存在：{path}"),
            Reject::NotAJar(path) => format!("不是有效的 jar（缺 PK\\x03\\x04 头）：{path}"),
            Reject::TooLarge { path, bytes } => {
                format!("文件过大：{path}（{bytes} 字节，上限 {MAX_JAR_BYTES}）")
            }
            Reject::SizeMismatch { declared, actual } => {
                format!("大小不符：清单写 {declared} 字节，实际 {actual} 字节")
            }
            Reject::Sha256Mismatch { expected, actual } => {
                format!("sha256 不符：期望 {expected}，实际 {actual}")
            }
            Reject::VersionConflict { version } => format!(
                "版本 {version} 已安装但内容不同：请先升版本号再发布（同版本换内容会覆盖运行中的 jar）"
            ),
        }
    }
}

/// 版本号白名单：只允许 `[0-9A-Za-z._-]`，长度 1..=32，且不含 `..`。
///
/// 之所以卡这么死：版本号直接拼成落盘文件名，放行 `/`、`\`、`..` 就等于放行路径穿越。
pub fn check_version(version: &str) -> Result<(), Reject> {
    let valid_len = !version.is_empty() && version.len() <= MAX_VERSION_LEN;
    let valid_chars = version
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-');
    if !valid_len || !valid_chars || version.contains("..") {
        return Err(Reject::InvalidVersion(version.to_owned()));
    }
    Ok(())
}

/// 由版本号推出落盘文件名：`kura-edge-<version>.jar`。
pub fn jar_file_name(version: &str) -> Result<String, Reject> {
    check_version(version)?;
    Ok(format!("kura-edge-{version}.jar"))
}

/// 判断字符串是否是 64 位小写十六进制（sha256 的规范形式）。
pub fn is_sha256_hex(value: &str) -> bool {
    value.len() == SHA256_HEX_LEN
        && value
            .chars()
            .all(|c| c.is_ascii_digit() || ('a'..='f').contains(&c))
}

/// 计算文件 sha256，返回 64 位小写十六进制。
pub fn sha256_file(path: &Path) -> IoResult<String> {
    let mut file = File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0u8; 64 * 1024];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(to_hex(&hasher.finalize()))
}

fn to_hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

/// 校验一个 jar 是否可以安装/启动：存在 → 头魔数 → 大小上限 →（可选）sha256。
///
/// 返回文件字节数，供调用方写日志与 `pending.size` 比对。
pub fn validate_jar(path: &Path, expected_sha256: Option<&str>) -> Result<u64, Reject> {
    let display = path.display().to_string();
    let mut file = File::open(path).map_err(|_| Reject::Missing(display.clone()))?;
    let mut magic = [0u8; 4];
    file.read_exact(&mut magic)
        .map_err(|_| Reject::NotAJar(display.clone()))?;
    if magic != JAR_MAGIC {
        return Err(Reject::NotAJar(display));
    }
    let bytes = file
        .metadata()
        .map_err(|_| Reject::Missing(display.clone()))?
        .len();
    if bytes > MAX_JAR_BYTES {
        return Err(Reject::TooLarge {
            path: display,
            bytes,
        });
    }
    if let Some(expected) = expected_sha256 {
        let actual = sha256_file(path).map_err(|_| Reject::Missing(display.clone()))?;
        if !actual.eq_ignore_ascii_case(expected) {
            return Err(Reject::Sha256Mismatch {
                expected: expected.to_owned(),
                actual,
            });
        }
    }
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;

    fn temp_dir(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "kura-edge-verify-{tag}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or_default()
        ));
        fs::create_dir_all(&dir).expect("建临时目录");
        dir
    }

    fn write_jar(dir: &Path, name: &str, body: &[u8]) -> PathBuf {
        let path = dir.join(name);
        let mut content = vec![0x50, 0x4b, 0x03, 0x04];
        content.extend_from_slice(body);
        fs::write(&path, content).expect("写测试 jar");
        path
    }

    #[test]
    fn accepts_plain_semver_version() {
        assert_eq!(check_version("1.0.4"), Ok(()));
        assert_eq!(jar_file_name("1.0.4").as_deref(), Ok("kura-edge-1.0.4.jar"));
    }

    #[test]
    fn rejects_version_with_path_separator() {
        let rejected = check_version("../../evil");

        assert!(rejected.is_err(), "路径穿越必须被拒");
        assert_eq!(rejected.unwrap_err().code(), "invalid_version");
    }

    #[test]
    fn rejects_empty_and_overlong_version() {
        assert_eq!(check_version("").unwrap_err().code(), "invalid_version");
        assert_eq!(
            check_version(&"9".repeat(33)).unwrap_err().code(),
            "invalid_version"
        );
    }

    #[test]
    fn rejects_version_with_space() {
        assert_eq!(
            check_version("1.0 4").unwrap_err().code(),
            "invalid_version"
        );
    }

    #[test]
    fn sha256_hex_recognises_canonical_form() {
        assert!(is_sha256_hex(&"a".repeat(64)));
        assert!(!is_sha256_hex(&"A".repeat(64)), "大写不是规范形式");
        assert!(!is_sha256_hex(&"g".repeat(64)));
        assert!(!is_sha256_hex(&"a".repeat(63)));
    }

    #[test]
    fn validate_jar_reports_missing_file() {
        let dir = temp_dir("missing");
        let path = dir.join("nope.jar");

        let rejected = validate_jar(&path, None).unwrap_err();

        assert_eq!(rejected.code(), "missing");
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn validate_jar_rejects_non_jar_payload() {
        let dir = temp_dir("notajar");
        let path = dir.join("bad.jar");
        fs::write(&path, b"<html>404</html>").expect("写文件");

        let rejected = validate_jar(&path, None).unwrap_err();

        assert_eq!(rejected.code(), "not_a_jar");
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn validate_jar_accepts_matching_sha256() {
        let dir = temp_dir("shaok");
        let path = write_jar(&dir, "a.jar", b"payload");
        let expected = sha256_file(&path).expect("算摘要");

        let bytes = validate_jar(&path, Some(&expected)).expect("摘要一致应通过");

        assert_eq!(bytes, 11, "PK 头 4 字节 + 7 字节负载");
        assert_eq!(expected.len(), SHA256_HEX_LEN);
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn validate_jar_rejects_mismatched_sha256() {
        let dir = temp_dir("shabad");
        let path = write_jar(&dir, "a.jar", b"payload");

        let rejected = validate_jar(&path, Some(&"0".repeat(64))).unwrap_err();

        assert_eq!(rejected.code(), "sha256_mismatch");
        assert!(rejected.message().contains("sha256 不符"));
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn reject_messages_are_chinese_and_mention_paths() {
        let rejected = Reject::Missing("C:/x/a.jar".to_owned());

        assert!(rejected.message().contains("C:/x/a.jar"));
        assert_eq!(rejected.code(), "missing");
    }
}
