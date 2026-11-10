/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.update;

/**
 * 版本号比较（RFC 008/RFC 009）：决定"服务器上的版本是不是比本机的更新"。
 * <p>
 * 放在 api 模块是因为这是**更新协议的一部分**，两端都要用同一套规则：设备侧（{@code kura-edge-rest} 的
 * 清单轮询）判断"要不要装"，发布侧（{@code kura-edge-release-server}）判断"目录里哪个 jar 是最新"。
 * 两处各写一份迟早会对不上（比如一边按字符串、一边按数值），所以只留一份。api 模块只依赖 JDK，
 * 放进这里不会给发布服务器带进 Spring 之类的重量级依赖。
 * </p>
 * <p>
 * 规则按段比较，段分隔符是 {@code .} 与 {@code -}：能解析成数字的段按数值比（所以 {@code 1.0.10 > 1.0.9}，
 * 字符串比较会错），解析不了的按字典序比；段数不同则短的补 0（{@code 1.0 == 1.0.0}）。
 * 比较结果 &gt; 0 才算"更新"——**等于不升级、更旧不升级**（要回退请用页面上的手动升级）。
 */
public final class VersionComparator {

    private VersionComparator() {
        // 工具类
    }

    /**
     * 比较两个版本号。
     *
     * @param left 左值
     * @param right 右值
     * @return 负数表示 left 更旧，0 表示相同，正数表示 left 更新
     */
    public static int compare(final String left, final String right) {
        final String[] leftParts = split(left);
        final String[] rightParts = split(right);
        final int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            final String leftPart = index < leftParts.length ? leftParts[index] : "0";
            final String rightPart = index < rightParts.length ? rightParts[index] : "0";
            final int result = comparePart(leftPart, rightPart);
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    /**
     * 清单里的版本是否比本机的新。
     *
     * @param candidate 清单里的版本（服务器上的）
     * @param current 本机正在跑的版本
     * @return true 表示应当升级
     */
    public static boolean isNewer(final String candidate, final String current) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (current == null || current.isBlank() || "dev".equals(current) || "unknown".equals(current)) {
            // 本机版本未知（mvn 直接跑 / 清单读不出来）：不自动升级，避免把开发环境刷成发布版
            return false;
        }
        return compare(candidate, current) > 0;
    }

    private static int comparePart(final String left, final String right) {
        final Long leftNumber = asNumber(left);
        final Long rightNumber = asNumber(right);
        if (leftNumber != null && rightNumber != null) {
            return Long.compare(leftNumber, rightNumber);
        }
        return left.compareTo(right);
    }

    private static Long asNumber(final String part) {
        if (part.isEmpty() || part.length() > 18) {
            return null;
        }
        for (int index = 0; index < part.length(); index++) {
            if (!Character.isDigit(part.charAt(index))) {
                return null;
            }
        }
        return Long.valueOf(part);
    }

    private static String[] split(final String version) {
        return version == null ? new String[0] : version.trim().split("[.-]");
    }
}
