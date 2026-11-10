/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可推进的测试时钟：让「按延迟排一次、到点再跑」的调度语义可以确定性验证，
 * 不必在测试里真的 sleep。
 */
public final class MutableClock extends Clock {

    private Instant instant;

    /**
     * 以给定时刻创建时钟。
     *
     * @param instant 初始时刻
     */
    public MutableClock(final Instant instant) {
        this.instant = instant;
    }

    /**
     * 把当前时刻向前推进。
     *
     * @param millis 推进的毫秒数
     */
    public void advanceMillis(final long millis) {
        this.instant = this.instant.plusMillis(millis);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return this.instant;
    }
}
