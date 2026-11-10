/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.ha;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用 PostgreSQL 会话级咨询锁（{@code pg_try_advisory_lock}）实现的图执行权租约。
 * <p>
 * 为什么是咨询锁而不是"数据库里一行 flag"：
 * <ul>
 *   <li>会话级咨询锁随连接关闭自动释放——旧进程被 kill、崩溃、断电都不会留下需要人工清理的死锁；</li>
 *   <li>{@code pg_try_advisory_lock} 非阻塞，抢不到立刻返回 false，standby 可以每 500 ms 试一次。</li>
 * </ul>
 * 锁必须独占一条连接：锁属于会话，连接归还到池里就等于放锁。因此本类持有 {@code Connection}
 * 直到 {@link #release()}，连接池最大连接数要 ≥ 2（见 {@code application.yml} 的 hikari 配置）。
 */
public class AdvisoryLockLease implements GraphLease {

    private static final Logger log = LoggerFactory.getLogger(AdvisoryLockLease.class);

    /** 咨询锁的键：固定常量，跨进程、跨版本一致；改它等于换锁，两个版本互斥关系会失效。 */
    static final long LOCK_KEY = 20_261_110L;

    /** 心跳用的语句：锁会话活着就返回 1。 */
    private static final String HEARTBEAT_SQL = "SELECT 1";

    private static final String TRY_LOCK_SQL = "SELECT pg_try_advisory_lock(?)";

    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(?)";

    private final DataSource dataSource;

    private final long lockKey;

    /** 持有锁的那条连接；null 表示当前没锁。 */
    private Connection lockConnection;

    /**
     * 创建租约。
     *
     * @param dataSource 数据源（必须是 PostgreSQL）
     */
    public AdvisoryLockLease(final DataSource dataSource) {
        this(dataSource, LOCK_KEY);
    }

    /**
     * 创建租约（指定锁键，测试用）。
     *
     * @param dataSource 数据源
     * @param lockKey 咨询锁键
     */
    AdvisoryLockLease(final DataSource dataSource, final long lockKey) {
        this.dataSource = dataSource;
        this.lockKey = lockKey;
    }

    @Override
    public boolean supported() {
        return true;
    }

    @Override
    public synchronized boolean tryAcquire() {
        if (this.lockConnection != null) {
            return true;
        }
        Connection connection = null;
        try {
            connection = this.dataSource.getConnection();
            connection.setAutoCommit(true);
            try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK_SQL)) {
                statement.setLong(1, this.lockKey);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next() && resultSet.getBoolean(1)) {
                        this.lockConnection = connection;
                        log.info("Acquired wire-graph execution lease (advisory key {})", this.lockKey);
                        return true;
                    }
                }
            }
            closeQuietly(connection);
            return false;
        } catch (final SQLException e) {
            closeQuietly(connection);
            log.warn("Failed to try wire-graph lease: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public synchronized boolean stillHeld() {
        if (this.lockConnection == null) {
            return false;
        }
        try (PreparedStatement statement = this.lockConnection.prepareStatement(HEARTBEAT_SQL);
                ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next();
        } catch (final SQLException e) {
            // 连接已断：会话级锁在服务端早就释放了，继续跑图就可能与别人重复执行
            log.warn("Wire-graph lease connection lost: {}", e.getMessage());
            closeQuietly(this.lockConnection);
            this.lockConnection = null;
            return false;
        }
    }

    @Override
    public synchronized void release() {
        final Connection connection = this.lockConnection;
        if (connection == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(UNLOCK_SQL)) {
            statement.setLong(1, this.lockKey);
            statement.executeQuery().close();
            log.info("Released wire-graph execution lease (advisory key {})", this.lockKey);
        } catch (final SQLException e) {
            log.warn("Failed to unlock wire-graph lease: {}（连接关闭时 PostgreSQL 也会自动释放）", e.getMessage());
        } finally {
            closeQuietly(connection);
            this.lockConnection = null;
        }
    }

    private static void closeQuietly(final Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (final SQLException e) {
            log.debug("Failed to close lease connection: {}", e.getMessage());
        }
    }
}
