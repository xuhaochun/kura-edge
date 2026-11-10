/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.ha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

/**
 * {@link AdvisoryLockLease} 的 SQL 交互测试（Gherkin 风格）：抢锁成功/失败、心跳断连、主动放锁。
 * <p>
 * 用 Mockito 桩掉 JDBC 三件套而不是连真库：单测不该依赖现场那台 PostgreSQL，而这里要验的恰恰是
 * "哪条 SQL、什么返回值算抢到锁、异常怎么兜"，桩比真库更能把这些边界逼出来。
 */
class AdvisoryLockLeaseTest {

    private static final long KEY = 42L;

    @Test
    public void acquiredWhenPostgresReportsTrue() throws SQLException {
        final DataSource dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final PreparedStatement statement = mock(PreparedStatement.class);
        final ResultSet resultSet = mock(ResultSet.class);
        givenConnectionReturns(dataSource, connection, statement, resultSet, true);
        final AdvisoryLockLease lease = new AdvisoryLockLease(dataSource, KEY);

        assertThat(lease.tryAcquire()).isTrue();

        verify(connection).setAutoCommit(true);
        verify(statement).setLong(1, KEY);
        // 抢到锁后连接必须留着：会话级锁跟着连接走，归还到池里等于放锁
        verify(connection, never()).close();
        assertThat(lease.stillHeld()).isTrue();
    }

    @Test
    public void notAcquiredWhenPostgresReportsFalse() throws SQLException {
        final DataSource dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final PreparedStatement statement = mock(PreparedStatement.class);
        final ResultSet resultSet = mock(ResultSet.class);
        givenConnectionReturns(dataSource, connection, statement, resultSet, false);
        final AdvisoryLockLease lease = new AdvisoryLockLease(dataSource, KEY);

        assertThat(lease.tryAcquire()).isFalse();

        // 没抢到就必须把连接还回去，否则每 500 ms 的重试会把连接池耗干
        verify(connection).close();
        assertThat(lease.stillHeld()).isFalse();
    }

    @Test
    public void notAcquiredWhenTheDatabaseIsUnreachable() throws SQLException {
        final DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));
        final AdvisoryLockLease lease = new AdvisoryLockLease(dataSource, KEY);

        assertThat(lease.tryAcquire()).isFalse();
        assertThat(lease.stillHeld()).isFalse();
    }

    @Test
    public void stillHeldIsFalseWhenTheLeaseConnectionDied() throws SQLException {
        final DataSource dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final PreparedStatement lockStatement = mock(PreparedStatement.class);
        final ResultSet lockResult = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(lockStatement);
        when(lockStatement.executeQuery()).thenReturn(lockResult);
        when(lockResult.next()).thenReturn(true);
        when(lockResult.getBoolean(1)).thenReturn(true);
        final AdvisoryLockLease lease = new AdvisoryLockLease(dataSource, KEY);
        assertThat(lease.tryAcquire()).isTrue();

        // 第二次 prepareStatement（心跳）直接抛错：模拟数据库断连后会话级锁已被服务端释放
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("connection closed"));

        assertThat(lease.stillHeld()).isFalse();
        verify(connection).close();
    }

    @Test
    public void releaseUnlocksAndReturnsTheConnection() throws SQLException {
        final DataSource dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final PreparedStatement statement = mock(PreparedStatement.class);
        final ResultSet resultSet = mock(ResultSet.class);
        givenConnectionReturns(dataSource, connection, statement, resultSet, true);
        final AdvisoryLockLease lease = new AdvisoryLockLease(dataSource, KEY);
        assertThat(lease.tryAcquire()).isTrue();

        lease.release();

        // 两次 setLong：一次 pg_try_advisory_lock，一次 pg_advisory_unlock，键必须一致
        verify(statement, times(2)).setLong(1, KEY);
        assertThat(lease.stillHeld()).isFalse();
        // 幂等：没有锁时再放一次也不该炸
        lease.release();
    }

    private void givenConnectionReturns(final DataSource dataSource, final Connection connection,
            final PreparedStatement statement, final ResultSet resultSet, final boolean acquired) throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(acquired);
    }
}
