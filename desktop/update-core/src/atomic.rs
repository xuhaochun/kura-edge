//! 原子写与文件安装。
//!
//! Windows 上两件事必须在这层处理掉，否则上层会莫名其妙失败：
//! 1. `fs::rename` **目标已存在时会失败**（与 Unix 语义不同）——必须先删目标；
//! 2. 跨卷（`staging/` 与 `app/` 被重定向到不同盘）时 `rename` 直接报 `ERROR_NOT_SAME_DEVICE`
//!    ——退化为 copy → fsync → rename。

use std::fs::{self, File};
use std::io::{ErrorKind, Result as IoResult, Write};
use std::path::Path;

/// 原子写：先写同目录 `<name>.tmp-<pid>`，`sync_all` 后 rename 覆盖。
///
/// 断电/崩溃时最多留下一个 `.tmp-*` 垃圾文件，绝不会留下半截 JSON 被下次启动读到。
pub fn write_atomic(path: &Path, bytes: &[u8]) -> IoResult<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let tmp = temp_sibling(path);
    {
        let mut file = File::create(&tmp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
    }
    replace_file(&tmp, path)
}

/// 把 `source` 安装到 `target`：
/// 同一卷走 `rename`（原子、零拷贝）；跨卷退化为 copy → fsync → 原子替换 → 删源。
///
/// 调用方必须保证 `source` 已经过 [`crate::verify::validate_jar`]，这里不重复校验。
pub fn install_file(source: &Path, target: &Path) -> IoResult<()> {
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent)?;
    }
    match fs::rename(source, target) {
        Ok(()) => Ok(()),
        Err(error) if is_cross_device(&error) => copy_then_replace(source, target),
        Err(error) => Err(error),
    }
}

fn copy_then_replace(source: &Path, target: &Path) -> IoResult<()> {
    let staging = temp_sibling(target);
    {
        let mut reader = File::open(source)?;
        let mut writer = File::create(&staging)?;
        std::io::copy(&mut reader, &mut writer)?;
        writer.sync_all()?;
    }
    replace_file(&staging, target)?;
    fs::remove_file(source).ok();
    Ok(())
}

/// 用 `replacement` 覆盖 `target`（目标已存在时先删；Windows 上 `rename` 不覆盖）。
fn replace_file(replacement: &Path, target: &Path) -> IoResult<()> {
    if target.exists() {
        fs::remove_file(target)?;
    }
    fs::rename(replacement, target)
}

fn is_cross_device(error: &std::io::Error) -> bool {
    // Unix: EXDEV(18)；Windows: ERROR_NOT_SAME_DEVICE(17)。用 raw_os_error 避免引入 libc/windows 依赖。
    match error.raw_os_error() {
        Some(code) => code == 18 || code == 17,
        None => error.kind() == ErrorKind::Other && error.to_string().contains("cross-device"),
    }
}

fn temp_sibling(path: &Path) -> std::path::PathBuf {
    let name = path
        .file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_else(|| "state".to_owned());
    let sibling = format!("{name}.tmp-{}", std::process::id());
    match path.parent() {
        Some(parent) => parent.join(sibling),
        None => std::path::PathBuf::from(sibling),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn temp_dir(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "kura-edge-atomic-{tag}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or_default()
        ));
        fs::create_dir_all(&dir).expect("建临时目录");
        dir
    }

    #[test]
    fn write_atomic_creates_and_overwrites() {
        let dir = temp_dir("write");
        let path = dir.join("app").join("current.json");

        write_atomic(&path, b"{\"version\":\"1.0.3\"}").expect("首次写");
        write_atomic(&path, b"{\"version\":\"1.0.4\"}").expect("覆盖写");

        let text = fs::read_to_string(&path).expect("读回");
        assert_eq!(text, "{\"version\":\"1.0.4\"}");
        let leftovers = fs::read_dir(path.parent().expect("父目录"))
            .expect("列目录")
            .filter_map(Result::ok)
            .filter(|entry| entry.file_name().to_string_lossy().contains(".tmp-"))
            .count();
        assert_eq!(leftovers, 0, "不应残留临时文件");
        fs::remove_dir_all(dir).ok();
    }

    #[test]
    fn install_file_moves_into_place_and_overwrites_existing() {
        let dir = temp_dir("install");
        let source = dir.join("staging").join("kura-edge-1.0.4.jar");
        let target = dir.join("app").join("kura-edge-1.0.4.jar");
        fs::create_dir_all(source.parent().expect("staging")).expect("建目录");
        fs::create_dir_all(target.parent().expect("app")).expect("建目录");
        fs::write(&source, b"PK\x03\x04new").expect("写源");
        fs::write(&target, b"PK\x03\x04old").expect("写旧目标");

        install_file(&source, &target).expect("安装应成功（覆盖已存在的目标）");

        assert_eq!(fs::read(&target).expect("读目标"), b"PK\x03\x04new");
        assert!(!source.exists(), "源文件应被移走");
        fs::remove_dir_all(dir).ok();
    }
}
