/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest;

import com.kura.wires.components.db.DbStoreFactory;
import com.kura.wires.components.fifo.FifoFactory;
import com.kura.wires.components.logger.LoggerFactory;
import com.kura.wires.components.publisher.CloudPublisherFactory;
import com.kura.wires.components.regexfilter.RegexFilterFactory;
import com.kura.wires.components.subscriber.CloudSubscriberFactory;
import com.kura.wires.components.asset.WireAssetFactory;
import com.kura.wires.components.timer.TimerFactory;
import com.kura.wires.core.registry.WireComponentRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全量上下文启动测试：验证 Flyway 建表脚本与 core / components 的 JPA 实体映射一致、
 * 内置组件工厂全部注册、图引擎与安全装配可用（真实 Bean，不替换任何实现）。
 */
@SpringBootTest
class KuraEdgeApplicationContextTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WireComponentRegistry registry;

    @Test
    void contextStarts() {
        // 上下文启动成功即通过（含 Flyway 迁移、JPA validate、安全装配、图引擎装配）
    }

    @Test
    void flywayCreatedAllTables() {
        final Integer tables = this.jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name IN "
                        + "('COMPONENT_CONFIGURATION', 'WIRE_GRAPH_SNAPSHOT', 'WIRE_RECORDS')",
                Integer.class);

        assertThat(tables).isEqualTo(3);
    }

    @Test
    void builtInComponentFactoriesAreRegistered() {
        assertThat(this.registry.getFactoryPids()).containsExactlyInAnyOrder(TimerFactory.FACTORY_PID,
                LoggerFactory.FACTORY_PID, RegexFilterFactory.FACTORY_PID, FifoFactory.FACTORY_PID,
                DbStoreFactory.FACTORY_PID, CloudPublisherFactory.FACTORY_PID, CloudSubscriberFactory.FACTORY_PID,
                WireAssetFactory.FACTORY_PID);
    }
}
