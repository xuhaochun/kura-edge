//! 决策层：给定 `pending.json` 与 `current.json`，判断"该装 / 该跳过 / 该拒"，以及启动时选哪个 jar。
//!
//! 这一层是纯逻辑 + 只读文件系统，不碰进程、不碰窗口，因此可以完整单测——外壳里最容易出错的
//! 恰恰就是这些判定顺序（先校验再装、同版本不覆盖、坏文件自动降级）。

use std::path::{Path, PathBuf};

use crate::atomic;
use crate::manifest::{CurrentState, PendingRequest, PROTOCOL};
use crate::verify::{self, check_version, is_sha256_hex, jar_file_name, validate_jar, Reject};

/// 一次应用的判定结果。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Decision {
    /// 校验通过，可以安装。
    Apply {
        /// `staging/` 里的源文件。
        source: PathBuf,
        /// 落地到 `app/` 里的目标文件。
        target: PathBuf,
        /// 目标版本号。
        version: String,
        /// 目标 jar 的 sha256。
        sha256: String,
    },
    /// 无需动作（已是目标版本）。
    Skip {
        /// 跳过原因，写日志用。
        reason: String,
    },
}

/// 启动时选中的 jar 及其来源。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LaunchChoice {
    /// 实际要交给 `javaw -jar` 的文件。
    pub jar: PathBuf,
    /// 版本号（基线 jar 用 exe 版本号）。
    pub version: String,
    /// 来源：`current` / `previous` / `bundled`。
    pub origin: &'static str,
}

/// 判定 `pending.json` 是否可以应用。
///
/// 判定顺序（先便宜后昂贵，且**绝不**在校验通过前动任何文件）：
/// 协议 → 版本号 → 文件名 → sha256 形式 → 同版本冲突 → 已就绪跳过 → 文件校验（存在/魔数/上限/大小/摘要）。
pub fn decide(
    pending: &PendingRequest,
    staging: &Path,
    app_dir: &Path,
    current: Option<&CurrentState>,
) -> Result<Decision, Reject> {
    if pending.protocol != PROTOCOL {
        return Err(Reject::Protocol {
            expected: PROTOCOL,
            actual: pending.protocol,
        });
    }
    check_version(&pending.version)?;
    let expected_name = jar_file_name(&pending.version)?;
    if pending.jar != expected_name {
        return Err(Reject::InvalidJarName {
            expected: expected_name.clone(),
            actual: pending.jar.clone(),
        });
    }
    if !is_sha256_hex(&pending.sha256) {
        return Err(Reject::InvalidSha256(pending.sha256.clone()));
    }
    if let Some(state) = current {
        let same_version = state.version == pending.version;
        let same_digest = state.sha256.eq_ignore_ascii_case(&pending.sha256);
        if same_version && same_digest {
            return Ok(Decision::Skip {
                reason: format!("已是 {}，无需更新", pending.version),
            });
        }
        if same_version && !same_digest {
            return Err(Reject::VersionConflict {
                version: pending.version.clone(),
            });
        }
    }
    let source = staging.join(&expected_name);
    let bytes = validate_jar(&source, Some(&pending.sha256))?;
    if pending.size != 0 && pending.size != bytes {
        return Err(Reject::SizeMismatch {
            declared: pending.size,
            actual: bytes,
        });
    }
    Ok(Decision::Apply {
        source,
        target: app_dir.join(expected_name),
        version: pending.version.clone(),
        sha256: pending.sha256.clone(),
    })
}

/// 执行安装并产出新的 `current.json` 内容（调用方负责把它原子写盘）。
///
/// 只做"移动文件 + 组装状态"，不动进程——进程编排在外壳里。
pub fn install(
    decision: &Decision,
    current: Option<&CurrentState>,
    app_dir: &Path,
) -> std::io::Result<CurrentState> {
    match decision {
        Decision::Skip { reason } => Err(std::io::Error::new(
            std::io::ErrorKind::InvalidInput,
            format!("Skip 决策不能用于安装：{reason}"),
        )),
        Decision::Apply {
            source,
            target,
            version,
            sha256,
        } => {
            if !target.starts_with(app_dir) {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidInput,
                    format!(
                        "拒绝把 jar 装到 app/ 之外：{}（app 目录 {}）",
                        target.display(),
                        app_dir.display()
                    ),
                ));
            }
            atomic::install_file(source, target)?;
            Ok(CurrentState {
                protocol: PROTOCOL,
                version: version.clone(),
                jar: target.display().to_string(),
                sha256: sha256.clone(),
                applied_at: crate::time::now_rfc3339(),
                previous: current.map(CurrentState::as_jar_ref),
            })
        }
    }
}

/// 选启动 jar：`current.json` 记录 → 它的 `previous` → 安装目录基线，第一个通过校验的即用。
///
/// 任何一环坏掉（文件被删、sha 不符、不是 jar）都静默降级到下一个候选——设备必须能启动，
/// 全部失败才返回错误（外壳据此显示错误页并列出每个候选的失败原因）。
pub fn choose_launch_jar(
    current: Option<&CurrentState>,
    bundled: &Path,
    bundled_version: &str,
) -> Result<LaunchChoice, String> {
    let mut failures: Vec<String> = Vec::new();
    if let Some(state) = current {
        let candidate = Path::new(&state.jar);
        match validate_jar(candidate, Some(&state.sha256)) {
            Ok(_) => {
                return Ok(LaunchChoice {
                    jar: candidate.to_path_buf(),
                    version: state.version.clone(),
                    origin: "current",
                })
            }
            Err(reject) => failures.push(format!("current（{}）：{}", state.version, reject.message())),
        }
        if let Some(previous) = &state.previous {
            let candidate = Path::new(&previous.jar);
            match validate_jar(candidate, Some(&previous.sha256)) {
                Ok(_) => {
                    return Ok(LaunchChoice {
                        jar: candidate.to_path_buf(),
                        version: previous.version.clone(),
                        origin: "previous",
                    })
                }
                Err(reject) => {
                    failures.push(format!("previous（{}）：{}", previous.version, reject.message()))
                }
            }
        }
    }
    match validate_jar(bundled, None) {
        Ok(_) => Ok(LaunchChoice {
            jar: bundled.to_path_buf(),
            version: bundled_version.to_owned(),
            origin: "bundled",
        }),
        Err(reject) => {
            failures.push(format!("bundled：{}", reject.message()));
            Err(failures.join("；"))
        }
    }
}

/// 列出 `app/` 里可以清理的旧 jar（不是 current、也不是 previous）。
///
/// 只返回候选，删除由调用方做且必须忽略失败——清不掉旧文件不影响功能。
pub fn stale_jars(app_dir: &Path, keep: &[&Path]) -> Vec<PathBuf> {
    let mut stale = Vec::new();
    let Ok(entries) = std::fs::read_dir(app_dir) else {
        return stale;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let name = entry.file_name().to_string_lossy().into_owned();
        let is_jar = name.starts_with("kura-edge-") && name.ends_with(".jar");
        if !is_jar || name.contains(".part") || name.contains(".tmp-") || name.contains(".tmp.") {
            continue;
        }
        if keep.iter().any(|kept| paths_equal(kept, &path)) {
            continue;
        }
        stale.push(path);
    }
    stale
}

fn paths_equal(left: &Path, right: &Path) -> bool {
    match (left.canonicalize(), right.canonicalize()) {
        (Ok(left), Ok(right)) => left == right,
        _ => left == right,
    }
}

/// 便捷包装：`staging/<jar>` 是否存在且像 jar（用于日志里快速区分"没下完"与"下坏了"）。
pub fn looks_like_jar(path: &Path) -> bool {
    verify::validate_jar(path, None).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::manifest::JarRef;
    use crate::verify::sha256_file;
    use std::fs;

    struct Sandbox {
        root: PathBuf,
    }

    impl Sandbox {
        fn new(tag: &str) -> Self {
            let root = std::env::temp_dir().join(format!(
                "kura-edge-plan-{tag}-{}-{}",
                std::process::id(),
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_nanos())
                    .unwrap_or_default()
            ));
            fs::create_dir_all(root.join("staging")).expect("建 staging");
            fs::create_dir_all(root.join("app")).expect("建 app");
            Sandbox { root }
        }

        fn staging(&self) -> PathBuf {
            self.root.join("staging")
        }

        fn app(&self) -> PathBuf {
            self.root.join("app")
        }

        fn write_jar(&self, dir: &Path, name: &str, body: &[u8]) -> PathBuf {
            let path = dir.join(name);
            let mut content = vec![0x50, 0x4b, 0x03, 0x04];
            content.extend_from_slice(body);
            fs::write(&path, content).expect("写 jar");
            path
        }
    }

    impl Drop for Sandbox {
        fn drop(&mut self) {
            fs::remove_dir_all(&self.root).ok();
        }
    }

    fn pending_for(version: &str, path: &Path) -> (PendingRequest, u64, String) {
        let bytes = fs::metadata(path).expect("元数据").len();
        let sha = sha256_file(path).expect("算摘要");
        (
            PendingRequest {
                protocol: PROTOCOL,
                version: version.to_owned(),
                jar: format!("kura-edge-{version}.jar"),
                sha256: sha.clone(),
                size: bytes,
                requested_at: "2026-11-10T03:06:00Z".to_owned(),
            },
            bytes,
            sha,
        )
    }

    #[test]
    fn decide_applies_valid_request() {
        let sandbox = Sandbox::new("apply");
        let jar = sandbox.write_jar(&sandbox.staging(), "kura-edge-1.0.4.jar", b"new");
        let (pending, _, sha) = pending_for("1.0.4", &jar);

        let decision = decide(&pending, &sandbox.staging(), &sandbox.app(), None).expect("应通过");

        match decision {
            Decision::Apply {
                source,
                target,
                version,
                sha256,
            } => {
                assert_eq!(source, sandbox.staging().join("kura-edge-1.0.4.jar"));
                assert_eq!(target, sandbox.app().join("kura-edge-1.0.4.jar"));
                assert_eq!(version, "1.0.4");
                assert_eq!(sha256, sha);
            }
            other => panic!("期望 Apply，实际 {other:?}"),
        }
    }

    #[test]
    fn decide_skips_when_same_version_and_digest() {
        let sandbox = Sandbox::new("skip");
        let jar = sandbox.write_jar(&sandbox.staging(), "kura-edge-1.0.4.jar", b"x");
        let (pending, _, sha) = pending_for("1.0.4", &jar);
        let current = CurrentState {
            protocol: PROTOCOL,
            version: "1.0.4".to_owned(),
            jar: jar.display().to_string(),
            sha256: sha,
            applied_at: "2026-11-10T03:00:00Z".to_owned(),
            previous: None,
        };

        let decision = decide(&pending, &sandbox.staging(), &sandbox.app(), Some(&current))
            .expect("同版本同摘要应跳过而非报错");

        assert!(matches!(decision, Decision::Skip { .. }), "实际 {decision:?}");
    }

    #[test]
    fn decide_rejects_same_version_with_different_digest() {
        let sandbox = Sandbox::new("conflict");
        let jar = sandbox.write_jar(&sandbox.staging(), "kura-edge-1.0.4.jar", b"x");
        let (pending, _, _) = pending_for("1.0.4", &jar);
        let current = CurrentState {
            protocol: PROTOCOL,
            version: "1.0.4".to_owned(),
            jar: "C:/app/kura-edge-1.0.4.jar".to_owned(),
            sha256: "c".repeat(64),
            applied_at: String::new(),
            previous: None,
        };

        let rejected = decide(&pending, &sandbox.staging(), &sandbox.app(), Some(&current))
            .expect_err("同版本换内容必须拒");

        assert_eq!(rejected.code(), "version_conflict");
    }

    #[test]
    fn decide_rejects_unknown_protocol() {
        let sandbox = Sandbox::new("protocol");
        let mut pending = PendingRequest {
            protocol: 99,
            version: "1.0.4".to_owned(),
            jar: "kura-edge-1.0.4.jar".to_owned(),
            sha256: "a".repeat(64),
            size: 0,
            requested_at: String::new(),
        };

        let rejected = decide(&pending, &sandbox.staging(), &sandbox.app(), None).unwrap_err();

        assert_eq!(rejected.code(), "protocol_mismatch");
        pending.protocol = PROTOCOL;
        assert!(decide(&pending, &sandbox.staging(), &sandbox.app(), None).is_err());
    }

    #[test]
    fn decide_rejects_jar_name_with_path_traversal() {
        let sandbox = Sandbox::new("traversal");
        let jar = sandbox.write_jar(&sandbox.staging(), "kura-edge-1.0.4.jar", b"x");
        let (mut pending, _, _) = pending_for("1.0.4", &jar);
        pending.jar = "../../../evil.exe".to_owned();

        let rejected = decide(&pending, &sandbox.staging(), &sandbox.app(), None).unwrap_err();

        assert_eq!(rejected.code(), "invalid_jar_name");
    }

    #[test]
    fn decide_rejects_when_staged_file_missing() {
        let sandbox = Sandbox::new("missing");
        let jar = sandbox.write_jar(&sandbox.staging(), "kura-edge-1.0.4.jar", b"x");
        let (pending, _, _) = pending_for("1.0.4", &jar);
        fs::remove_file(&jar).expect("删掉刚写的 jar");

        let rejected = decide(&pending, &sandbox.staging(), &sandbox.app(), None).unwrap_err();

        assert_eq!(rejected.code(), "missing");
    }

    #[test]
    fn decide_rejects_size_mismatch() {
        let sandbox = Sandbox::new("size");
        let jar = sandbox.write_jar(&sandbox.staging(), "kura-edge-1.0.4.jar", b"x");
        let (mut pending, _, _) = pending_for("1.0.4", &jar);
        pending.size += 1;

        let rejected = decide(&pending, &sandbox.staging(), &sandbox.app(), None).unwrap_err();

        assert_eq!(rejected.code(), "size_mismatch");
    }

    #[test]
    fn decide_rejects_digest_mismatch() {
        let sandbox = Sandbox::new("digest");
        let jar = sandbox.write_jar(&sandbox.staging(), "kura-edge-1.0.4.jar", b"x");
        let (mut pending, _, _) = pending_for("1.0.4", &jar);
        pending.sha256 = "e".repeat(64);

        let rejected = decide(&pending, &sandbox.staging(), &sandbox.app(), None).unwrap_err();

        assert_eq!(rejected.code(), "sha256_mismatch");
    }

    #[test]
    fn install_moves_file_and_records_previous() {
        let sandbox = Sandbox::new("install");
        let jar = sandbox.write_jar(&sandbox.staging(), "kura-edge-1.0.4.jar", b"new");
        let (pending, _, sha) = pending_for("1.0.4", &jar);
        let current = CurrentState {
            protocol: PROTOCOL,
            version: "1.0.3".to_owned(),
            jar: "C:/app/kura-edge-1.0.3.jar".to_owned(),
            sha256: "f".repeat(64),
            applied_at: "2026-11-09T00:00:00Z".to_owned(),
            previous: None,
        };
        let decision = decide(&pending, &sandbox.staging(), &sandbox.app(), Some(&current)).expect("应通过");

        let next = install(&decision, Some(&current), &sandbox.app()).expect("安装应成功");

        assert_eq!(next.version, "1.0.4");
        assert_eq!(next.sha256, sha);
        assert_eq!(next.jar, sandbox.app().join("kura-edge-1.0.4.jar").display().to_string());
        assert!(Path::new(&next.jar).is_file(), "新 jar 应已落地");
        assert!(!jar.exists(), "staging 里的源文件应被移走");
        assert_eq!(next.previous.as_ref().map(|p| p.version.as_str()), Some("1.0.3"));
        assert_eq!(next.protocol, PROTOCOL);
        assert!(!next.applied_at.is_empty());
    }

    #[test]
    fn launch_prefers_current_record() {
        let sandbox = Sandbox::new("launch-current");
        let jar = sandbox.write_jar(&sandbox.app(), "kura-edge-1.0.4.jar", b"x");
        let sha = sha256_file(&jar).expect("摘要");
        let current = CurrentState {
            protocol: PROTOCOL,
            version: "1.0.4".to_owned(),
            jar: jar.display().to_string(),
            sha256: sha,
            applied_at: String::new(),
            previous: None,
        };
        let bundled = sandbox.write_jar(&sandbox.root, "bundled.jar", b"b");

        let choice = choose_launch_jar(Some(&current), &bundled, "1.0.3").expect("应选 current");

        assert_eq!(choice.origin, "current");
        assert_eq!(choice.version, "1.0.4");
        assert_eq!(choice.jar, jar);
    }

    #[test]
    fn launch_falls_back_to_previous_when_current_jar_corrupted() {
        let sandbox = Sandbox::new("launch-previous");
        let good = sandbox.write_jar(&sandbox.app(), "kura-edge-1.0.3.jar", b"old");
        let good_sha = sha256_file(&good).expect("摘要");
        let bad = sandbox.write_jar(&sandbox.app(), "kura-edge-1.0.4.jar", b"new");
        let current = CurrentState {
            protocol: PROTOCOL,
            version: "1.0.4".to_owned(),
            jar: bad.display().to_string(),
            sha256: "a".repeat(64),
            applied_at: String::new(),
            previous: Some(JarRef {
                version: "1.0.3".to_owned(),
                jar: good.display().to_string(),
                sha256: good_sha,
            }),
        };
        let bundled = sandbox.write_jar(&sandbox.root, "bundled.jar", b"b");

        let choice = choose_launch_jar(Some(&current), &bundled, "1.0.0").expect("应回退到 previous");

        assert_eq!(choice.origin, "previous");
        assert_eq!(choice.version, "1.0.3");
    }

    #[test]
    fn launch_falls_back_to_bundled_when_state_points_nowhere() {
        let sandbox = Sandbox::new("launch-bundled");
        let current = CurrentState {
            protocol: PROTOCOL,
            version: "1.0.4".to_owned(),
            jar: sandbox.root.join("gone.jar").display().to_string(),
            sha256: "a".repeat(64),
            applied_at: String::new(),
            previous: None,
        };
        let bundled = sandbox.write_jar(&sandbox.root, "bundled.jar", b"b");

        let choice = choose_launch_jar(Some(&current), &bundled, "1.0.3").expect("应回退到基线");

        assert_eq!(choice.origin, "bundled");
        assert_eq!(choice.version, "1.0.3");
    }

    #[test]
    fn launch_reports_every_failure_when_all_candidates_invalid() {
        let sandbox = Sandbox::new("launch-fail");
        let mut broken_bundled = sandbox.root.join("bundled.jar");
        fs::write(&broken_bundled, b"not a jar").expect("写坏文件");

        let error = choose_launch_jar(None, &broken_bundled, "1.0.3").expect_err("全失败必须报错");

        assert!(error.contains("bundled"), "错误里要点明是哪个候选：{error}");
        broken_bundled.set_extension("jar.bak");
    }

    #[test]
    fn stale_jars_excludes_kept_files_and_temp_artifacts() {
        let sandbox = Sandbox::new("stale");
        let keep = sandbox.write_jar(&sandbox.app(), "kura-edge-1.0.4.jar", b"x");
        let old = sandbox.write_jar(&sandbox.app(), "kura-edge-1.0.2.jar", b"x");
        fs::write(sandbox.app().join("kura-edge-1.0.5.jar.part"), b"partial").expect("写 part");
        fs::write(sandbox.app().join("current.json"), b"{}").expect("写 state");

        let stale = stale_jars(&sandbox.app(), &[keep.as_path()]);

        assert_eq!(stale.len(), 1, "实际：{stale:?}");
        assert_eq!(stale[0], old);
    }

    #[test]
    fn looks_like_jar_distinguishes_truncated_download() {
        let sandbox = Sandbox::new("looks");
        let good = sandbox.write_jar(&sandbox.staging(), "good.jar", b"x");
        let partial = sandbox.staging().join("bad.jar.part");
        fs::write(&partial, b"PK").expect("写半截文件");

        assert!(looks_like_jar(&good));
        assert!(!looks_like_jar(&partial));
    }
}
