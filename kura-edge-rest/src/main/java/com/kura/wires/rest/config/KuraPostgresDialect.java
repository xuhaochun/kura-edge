/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.config;

import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.type.SqlTypes;

/**
 * PostgreSQL 方言微调：把 CLOB 映射成 {@code text} 而不是 PostgreSQL 的 {@code oid} 大对象。
 * <p>
 * 实体里的 JSON 字段用的是 {@code @Lob String}（H2 上对应 CLOB，历史库已经是这个类型，不能动）。
 * Hibernate 原生的 PG 方言会把 CLOB 落成 {@code oid}，那需要 large object 读写、{@code pg_dump}
 * 也得单独照顾，做配置存储没必要。把映射改成 {@code text} 之后：
 * <ul>
 *   <li>PG：迁移脚本里的 {@code TEXT} 列直接对上，{@code ddl-auto: validate} 通过；</li>
 *   <li>H2：不受影响，仍然是 {@code CLOB}（{@code h2} profile 显式指定 H2Dialect）。</li>
 * </ul>
 * 只有 PostgreSQL 的默认 profile 用这个类，见 {@code application.yml} 的
 * {@code spring.jpa.database-platform}。
 */
public class KuraPostgresDialect extends PostgreSQLDialect {

    @Override
    public String columnType(final int sqlTypeCode) {
        if (sqlTypeCode == SqlTypes.CLOB) {
            return "text";
        }
        return super.columnType(sqlTypeCode);
    }
}
