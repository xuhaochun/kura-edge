//! RFC 3339（UTC）时间戳。
//!
//! 不引 `chrono`：外壳只需要"给人看的落盘时间"，`std::time` + 一处民用历换算足够，
//! 少一个依赖就少一份交叉编译时要下载的东西。

use std::time::{SystemTime, UNIX_EPOCH};

/// 当前时间的 RFC 3339 形式，如 `2026-11-10T03:06:00Z`。
pub fn now_rfc3339() -> String {
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|elapsed| elapsed.as_secs())
        .unwrap_or_default();
    format_rfc3339(seconds)
}

/// 把 Unix 秒格式化成 RFC 3339（UTC，秒精度）。
pub fn format_rfc3339(unix_seconds: u64) -> String {
    let days = (unix_seconds / 86_400) as i64;
    let seconds_of_day = unix_seconds % 86_400;
    let (year, month, day) = civil_from_days(days);
    let hour = seconds_of_day / 3_600;
    let minute = (seconds_of_day % 3_600) / 60;
    let second = seconds_of_day % 60;
    format!("{year:04}-{month:02}-{day:02}T{hour:02}:{minute:02}:{second:02}Z")
}

/// Howard Hinnant 的 `civil_from_days`：把"1970-01-01 起的天数"换成 (年, 月, 日)。
fn civil_from_days(days_since_epoch: i64) -> (i64, u32, u32) {
    let z = days_since_epoch + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let day_of_era = z - era * 146_097;
    let year_of_era =
        (day_of_era - day_of_era / 1_460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let year = year_of_era + era * 400;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let month_pivot = (5 * day_of_year + 2) / 153;
    let day = day_of_year - (153 * month_pivot + 2) / 5 + 1;
    let month = if month_pivot < 10 {
        month_pivot + 3
    } else {
        month_pivot - 9
    };
    let year = if month <= 2 { year + 1 } else { year };
    (year, month as u32, day as u32)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn epoch_formats_as_1970() {
        assert_eq!(format_rfc3339(0), "1970-01-01T00:00:00Z");
    }

    #[test]
    fn known_timestamp_formats_correctly() {
        // 2001-09-09T01:46:40Z 是广为人知的 1e9 秒点
        assert_eq!(format_rfc3339(1_000_000_000), "2001-09-09T01:46:40Z");
    }

    #[test]
    fn leap_day_is_handled() {
        // 2024-02-29T12:34:56Z：闰日与"月内偏移"一起验证，写错 civil_from_days 会在这里露馅
        assert_eq!(format_rfc3339(1_709_210_096), "2024-02-29T12:34:56Z");
    }

    #[test]
    fn now_has_expected_shape() {
        let now = now_rfc3339();

        assert_eq!(now.len(), 20, "格式应为 YYYY-MM-DDTHH:MM:SSZ：{now}");
        assert!(now.ends_with('Z'));
        assert!(now.starts_with("20"), "当前年份应为 20xx：{now}");
    }
}
