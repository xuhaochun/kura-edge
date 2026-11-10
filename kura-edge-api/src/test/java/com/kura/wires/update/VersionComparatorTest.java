/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.update;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link VersionComparator} 的判定测试（Gherkin 风格）：数字段按数值比、段数补齐、非数字段按字典序、
 * 以及"本机版本未知不升级"这条安全阀。
 */
class VersionComparatorTest {

    @Test
    public void comparesNumericSegmentsByValue() {
        // 字符串比较会把 1.0.10 判成比 1.0.9 旧，这是最容易踩的坑
        assertThat(VersionComparator.compare("1.0.10", "1.0.9")).isPositive();
        assertThat(VersionComparator.compare("1.0.9", "1.0.10")).isNegative();
    }

    @Test
    public void padsMissingSegmentsWithZero() {
        assertThat(VersionComparator.compare("1.0", "1.0.0")).isZero();
        assertThat(VersionComparator.compare("1.0.1", "1.0")).isPositive();
    }

    @Test
    public void comparesNonNumericSegmentsLexically() {
        assertThat(VersionComparator.compare("1.0.4", "1.0.3-beta")).isPositive();
        assertThat(VersionComparator.compare("1.0.3-beta", "1.0.3")).isPositive();
    }

    @Test
    public void upgradesOnlyWhenTheFeedIsStrictlyNewer() {
        assertThat(VersionComparator.isNewer("1.0.4", "1.0.3")).isTrue();
        assertThat(VersionComparator.isNewer("1.0.3", "1.0.3")).isFalse();
        // 更旧不自动降级：要回退走页面上的手动升级
        assertThat(VersionComparator.isNewer("1.0.2", "1.0.3")).isFalse();
    }

    @Test
    public void neverUpgradesWhenTheLocalVersionIsUnknown() {
        assertThat(VersionComparator.isNewer("1.0.4", "dev")).isFalse();
        assertThat(VersionComparator.isNewer("1.0.4", "unknown")).isFalse();
        assertThat(VersionComparator.isNewer("1.0.4", "")).isFalse();
        assertThat(VersionComparator.isNewer("", "1.0.3")).isFalse();
    }
}
