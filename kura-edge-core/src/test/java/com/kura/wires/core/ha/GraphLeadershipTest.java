/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.ha;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link GraphLeadership} 的选主语义测试（Gherkin 风格）：启动抢到锁、standby 等锁、接管、
 * 掉线撤权，以及没有 PostgreSQL 时退化单机。
 */
class GraphLeadershipTest {

    private final RecordingPublisher publisher = new RecordingPublisher();

    private final FakeLease lease = new FakeLease();

    @Test
    public void grantedOnStartWhenLeaseIsFree() {
        final GraphLeadership leadership = givenLeadership(this.lease);

        whenStart(leadership);

        assertThat(leadership.isLeader()).isTrue();
        assertThat(leadership.isBlueGreen()).isTrue();
        assertThat(this.publisher.events).singleElement().isInstanceOf(GraphLeadershipGrantedEvent.class);
    }

    @Test
    public void standbyStaysSilentUntilLeaseIsHandedOver() {
        this.lease.acquirable = false;
        final GraphLeadership leadership = givenLeadership(this.lease);

        whenStart(leadership);

        assertThat(leadership.isLeader()).isFalse();
        assertThat(this.publisher.events).isEmpty();

        whenTick(leadership);
        assertThat(leadership.isLeader()).isFalse();
        assertThat(this.publisher.events).isEmpty();
    }

    @Test
    public void standbyTakesOverOnTheTickAfterTheLeaseIsFreed() {
        this.lease.acquirable = false;
        final GraphLeadership leadership = givenLeadership(this.lease);
        whenStart(leadership);

        this.lease.acquirable = true;
        whenTick(leadership);

        assertThat(leadership.isLeader()).isTrue();
        assertThat(this.publisher.events).singleElement().isInstanceOf(GraphLeadershipGrantedEvent.class);
    }

    @Test
    public void revokedWhenTheLeaseConnectionDrops() {
        final GraphLeadership leadership = givenLeadership(this.lease);
        whenStart(leadership);
        this.publisher.events.clear();

        this.lease.alive = false;
        whenTick(leadership);

        assertThat(leadership.isLeader()).isFalse();
        assertThat(this.publisher.events).singleElement().isInstanceOf(GraphLeadershipRevokedEvent.class);
    }

    @Test
    public void releaseStopsBeingLeaderSoTheNextInstanceCanTakeOver() {
        final GraphLeadership leadership = givenLeadership(this.lease);
        whenStart(leadership);

        whenRelease(leadership);

        assertThat(leadership.isLeader()).isFalse();
        assertThat(this.lease.releases).isEqualTo(1);
    }

    @Test
    public void retireNeverAcquiresAgainEvenIfTheLeaseIsFree() {
        final GraphLeadership leadership = givenLeadership(this.lease);
        whenStart(leadership);
        whenRelease(leadership);
        this.publisher.events.clear();

        // 实测回归：retire 放锁后心跳线程若不设防，会在几十毫秒内把锁抢回来并在将死进程里重建图
        this.lease.acquirable = true;
        whenTick(leadership);

        assertThat(leadership.isLeader()).isFalse();
        assertThat(leadership.isRetired()).isTrue();
        assertThat(this.publisher.events).isEmpty();
    }

    @Test
    public void singleInstanceModeWhenBlueGreenIsOff() {
        final GraphLeadership leadership = new GraphLeadership(null, this.publisher, "off");

        leadership.initialize();

        assertThat(leadership.isLeader()).isTrue();
        // 没有咨询锁能力：热更新只能就地重启，前端会据此提示"会有短暂中断"
        assertThat(leadership.isBlueGreen()).isFalse();
    }

    @Test
    public void singleInstanceLeaseAlsoStopsForGoodAfterRelease() {
        final GraphLeadership leadership = new GraphLeadership(null, this.publisher, "off");
        leadership.initialize();

        leadership.release();
        leadership.tick();

        // 移交是一次性的：单机模式也一样，放掉之后不再参与选主（进程马上要退出了）
        assertThat(leadership.isLeader()).isFalse();
        assertThat(leadership.isRetired()).isTrue();
    }

    @Test
    public void autoModeFallsBackToSingleInstanceWithoutPostgres() {
        final GraphLeadership leadership = new GraphLeadership(null, this.publisher, "auto");

        leadership.initialize();

        assertThat(leadership.isLeader()).isTrue();
        assertThat(leadership.isBlueGreen()).isFalse();
    }

    private GraphLeadership givenLeadership(final FakeLease fakeLease) {
        return new GraphLeadership(this.publisher, fakeLease);
    }

    private void whenStart(final GraphLeadership leadership) {
        leadership.initialize();
    }

    private void whenTick(final GraphLeadership leadership) {
        leadership.tick();
    }

    private void whenRelease(final GraphLeadership leadership) {
        leadership.release();
    }

    /** 可控租约：可切换"锁被别人占着"与"连接已断"。 */
    private static final class FakeLease implements GraphLease {

        private boolean acquirable = true;

        private boolean alive = true;

        private boolean held;

        private int releases;

        @Override
        public boolean supported() {
            return true;
        }

        @Override
        public boolean tryAcquire() {
            if (!this.acquirable) {
                return false;
            }
            this.held = true;
            return true;
        }

        @Override
        public boolean stillHeld() {
            if (!this.alive) {
                this.held = false;
            }
            return this.held;
        }

        @Override
        public void release() {
            this.releases++;
            this.held = false;
        }
    }

    /**
     * 记录发布出去的事件。
     * <p>
     * 必须覆写 {@code publishEvent(Object)}：接口的默认实现会把入参强转成 {@code ApplicationEvent}，
     * 而本项目的事件是 record（不是 ApplicationEvent 子类），走默认实现会 ClassCastException。
     */
    private static final class RecordingPublisher implements ApplicationEventPublisher {

        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(final Object event) {
            this.events.add(event);
        }

        @Override
        public void publishEvent(final ApplicationEvent event) {
            this.events.add(event);
        }
    }
}
