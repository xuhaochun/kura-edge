/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.logger;

import static java.util.Objects.requireNonNull;

import java.util.Map;

import com.kura.wires.model.WireReceiver;
import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.type.TypedValue;

/**
 * 日志 Wire 组件：把接收到的 {@link WireEnvelope} 内容写入日志，对照上游
 * {@code org.eclipse.kura.internal.wire.logger.Logger}。
 * <p>
 * 配置 {@code log.verbosity} 为 {@code QUIET}（默认）时只记录信封来源；
 * {@code VERBOSE} 时逐条打印记录的 key 与 value。属性 key 与上游 metatype
 * （{@code org.eclipse.kura.wire.Logger.xml}）一致。
 * </p>
 */
public final class Logger implements WireReceiver {

    /** 日志详细程度属性 key（与上游一致） */
    public static final String PROP_LOG_LEVEL = "log.verbosity";

    private static final String DEFAULT_LOG_LEVEL = LoggingVerbosity.QUIET.name();

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Logger.class);

    private final String pid;

    /** 配置可经 updated() 热更新（对照上游 updated 回调），故为 volatile */
    private volatile Map<String, Object> properties;

    /**
     * 创建日志组件实例（由 {@link LoggerFactory} 调用）。
     *
     * @param pid 组件实例 PID
     * @param properties 组件配置属性（已合并默认值）
     */
    Logger(final String pid, final Map<String, Object> properties) {
        this.pid = pid;
        this.properties = requireNonNull(properties, "Properties cannot be null");
    }

    /**
     * 返回组件实例 PID。
     *
     * @return 组件 PID
     */
    public String getPid() {
        return this.pid;
    }

    /**
     * 热更新组件配置（对照上游 OSGi updated 回调）。
     *
     * @param properties 新的配置属性
     */
    public void updated(final Map<String, Object> properties) {
        this.properties = requireNonNull(properties, "Properties cannot be null");
    }

    /** {@inheritDoc} */
    @Override
    public void onWireReceive(final WireEnvelope wireEnvelope) {
        requireNonNull(wireEnvelope, "Wire Envelope cannot be null");
        LOG.info("Received WireEnvelope from {}", wireEnvelope.getEmitterPid());

        if (LoggingVerbosity.VERBOSE.name().equals(getLoggingLevel())) {
            LOG.info("Record List content: ");
            for (final var record : wireEnvelope.getRecords()) {
                LOG.info("  Record content: ");
                for (final Map.Entry<String, TypedValue<?>> entry : record.getProperties().entrySet()) {
                    LOG.info("    {} : {}", entry.getKey(), entry.getValue().getValue());
                }
            }
        }
    }

    private String getLoggingLevel() {
        String logLevel = DEFAULT_LOG_LEVEL;
        final Object configuredLogLevel = this.properties.get(PROP_LOG_LEVEL);
        if (configuredLogLevel instanceof String) {
            logLevel = String.valueOf(configuredLogLevel);
        }
        return logLevel;
    }
}
