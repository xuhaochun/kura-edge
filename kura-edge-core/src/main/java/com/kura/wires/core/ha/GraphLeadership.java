/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.ha;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * 图执行权的选主协调者：决定"本进程现在该不该跑线图"，并在蓝绿热更新期间完成移交。
 * <p>
 * 规则只有一条：**谁持有租约谁跑图**。启动时抢到租约就发 {@link GraphLeadershipGrantedEvent}（引擎据此恢复图）；
 * 抢不到就以 standby 身份起 HTTP 服务（页面能打开、接口能应答），每 {@value #POLL_INTERVAL_MS} ms 重试一次，
 * 抢到后同样发事件接管线图。持有期间每次心跳掉线都会发 {@link GraphLeadershipRevokedEvent}，让引擎立刻停图，
 * 避免与别的实例重复执行。
 * <p>
 * 交接时序（旧进程 → 新进程）由桌面外壳驱动：新进程先以 standby 就绪并被导航过去（用户无感），
 * 外壳再让旧进程 {@code retire}（停图 + 交租约 + 退出），新进程在 0.5~1 s 内抢到租约接管线图。
 * 全程页面不中断，图执行中断从"15~25 s 冷启"降到"一次组件重建"。
 * <p>
 * 模式由 {@code kura.bluegreen.enabled} 控制：{@code auto}（默认）按数据源判断——PostgreSQL 才启用，
 * 文件 H2 无从共享状态，自动退回单机语义；{@code on}/{@code off} 强制开或关。
 */
@Component
public class GraphLeadership {

    private static final Logger log = LoggerFactory.getLogger(GraphLeadership.class);

    /** standby 重试与 leader 心跳的间隔：越小交接越快，代价是每 0.5 s 一次 SELECT 1。 */
    static final long POLL_INTERVAL_MS = 500;

    private static final String MODE_AUTO = "auto";

    private static final String MODE_ON = "on";

    private static final String MODE_OFF = "off";

    private final ApplicationEventPublisher publisher;

    private final DataSource dataSource;

    private final String mode;

    private GraphLease lease;

    private volatile boolean leader;

    /**
     * 已主动移交（retire）：此后**永不**再参与选主。
     * <p>
     * 没有这个标志会出一类致命竞争：`retire()` 放锁后心跳线程还在跑，会在几十毫秒内把锁**又抢回来**，
     * 于是引擎收到"已授权"事件、在一个马上要退出的进程里重新建组件、重新排 tick——与刚接手的新实例
     * 重复执行（现场表现就是定时器一秒钟发两条）。实测确认过这个窗口，必须用一次性标志封死。
     */
    private volatile boolean retired;

    private ScheduledExecutorService poller;

    /**
     * Spring 构造器。
     *
     * @param dataSource 数据源（{@code auto} 模式下用它判断是不是 PostgreSQL）
     * @param publisher 事件发布器
     * @param mode 蓝绿模式：{@code auto} / {@code on} / {@code off}
     */
    @Autowired
    public GraphLeadership(final DataSource dataSource, final ApplicationEventPublisher publisher,
            @Value("${kura.bluegreen.enabled:auto}") final String mode) {
        this.dataSource = dataSource;
        this.publisher = publisher;
        this.mode = mode == null ? MODE_AUTO : mode.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 测试用构造器：直接注入租约，不碰 Spring 与数据源。
     *
     * @param publisher 事件发布器
     * @param lease 租约
     */
    GraphLeadership(final ApplicationEventPublisher publisher, final GraphLease lease) {
        this.dataSource = null;
        this.publisher = publisher;
        this.mode = MODE_ON;
        this.lease = lease;
    }

    /**
     * 启动选主：抢到就成 leader，抢不到当 standby 并开始轮询。
     * <p>
     * 用 {@link Ordered#HIGHEST_PRECEDENCE} 排在 {@code ApplicationReadyEvent} 的所有监听器之前，
     * 保证引擎收到"已授权"事件之前不会有其它 ready 逻辑去碰图。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public synchronized void start() {
        initialize();
        startHeartbeat();
    }

    /**
     * 完成一次选主（不启动心跳线程，测试直接调用）。
     */
    synchronized void initialize() {
        if (this.retired) {
            return;
        }
        if (this.lease == null) {
            this.lease = createLease();
        }
        if (this.lease.tryAcquire()) {
            grant();
        } else {
            log.info("Wire-graph execution lease is held by another instance: 本进程以 standby 启动（{} 模式）",
                    this.lease.supported() ? "蓝绿" : "单机");
        }
    }

    /**
     * 当前是否是图执行者。
     *
     * @return true 表示本进程在跑图
     */
    public boolean isLeader() {
        return this.leader;
    }

    /**
     * 是否具备蓝绿能力（跨进程互斥可用）。H2 单机模式下恒为 false。
     *
     * @return true 表示热更新可以零中断
     */
    public boolean isBlueGreen() {
        return this.lease != null && this.lease.supported();
    }

    /**
     * 主动交出执行权：先由调用方停图，再由这里放锁，新实例随即接手。
     * <p>
     * 幂等：重复调用只是重复放锁（无锁时直接返回）。
     */
    public synchronized void release() {
        this.retired = true;
        this.leader = false;
        if (this.lease != null) {
            this.lease.release();
        }
    }

    /**
     * 是否已经主动移交（retire 过）。
     *
     * @return true 表示本进程不再参与选主，只等服务停止
     */
    public boolean isRetired() {
        return this.retired;
    }

    /**
     * 停止心跳线程并放锁（Spring 销毁回调）。
     */
    @PreDestroy
    public synchronized void stop() {
        this.retired = true;
        if (this.poller != null) {
            this.poller.shutdownNow();
            this.poller = null;
        }
        this.leader = false;
        if (this.lease != null) {
            this.lease.release();
        }
    }

    /**
     * 一次心跳/重试（包内可见，测试直接调用，不必等真实定时器）。
     */
    synchronized void tick() {
        if (this.lease == null || this.retired) {
            return;
        }
        if (this.leader) {
            if (!this.lease.stillHeld()) {
                this.leader = false;
                log.error("Lost wire-graph execution lease: 已停图，等待重新抢锁后再恢复");
                this.publisher.publishEvent(new GraphLeadershipRevokedEvent());
            }
            return;
        }
        if (this.lease.tryAcquire()) {
            grant();
        }
    }

    private void grant() {
        this.leader = true;
        log.info("Wire-graph execution lease granted: 本进程开始跑图（{} 模式）",
                this.lease.supported() ? "蓝绿" : "单机");
        this.publisher.publishEvent(new GraphLeadershipGrantedEvent(this.lease.supported()));
    }

    private void startHeartbeat() {
        if (this.poller != null) {
            return;
        }
        this.poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "kura-edge-graph-lease");
            thread.setDaemon(true);
            return thread;
        });
        this.poller.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (final RuntimeException e) {
                log.warn("Graph lease heartbeat failed: {}", e.getMessage());
            }
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private GraphLease createLease() {
        if (MODE_OFF.equals(this.mode)) {
            log.info("Blue-green disabled by configuration: 单机模式，热更新将就地重启");
            return new AlwaysLeaderLease();
        }
        final boolean postgres = MODE_ON.equals(this.mode) || isPostgres();
        if (!postgres) {
            log.info("Datasource is not PostgreSQL (or blue-green disabled): 单机模式，热更新将就地重启");
            return new AlwaysLeaderLease();
        }
        return new AdvisoryLockLease(this.dataSource);
    }

    private boolean isPostgres() {
        if (this.dataSource == null) {
            return false;
        }
        try (Connection connection = this.dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres");
        } catch (final SQLException e) {
            log.warn("Failed to probe database product for blue-green mode: {}", e.getMessage());
            return false;
        }
    }
}
