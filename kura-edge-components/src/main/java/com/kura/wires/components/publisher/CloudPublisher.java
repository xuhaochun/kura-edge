/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.publisher;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.model.WireEmitter;
import com.kura.wires.model.WireEnvelope;
import com.kura.wires.model.WireReceiver;
import com.kura.wires.model.WireRecord;

/**
 * 云发布 Wire 组件：把接收到的记录经 {@link PublisherSink} 发布，并按
 * {@code publish.forward} 配置决定是否继续向下游发射，对照上游
 * {@code org.eclipse.kura.internal.wire.publisher.CloudPublisher}。
 * <p>
 * 适配说明：上游组件只接收不发射（云端是终点），但本项目的水槽可能是
 * 本地实现（如日志），保留向下游继续发射的能力更符合图编排需求，
 * 故默认端口数为 1 入 / 1 出；上游的 {@code publish.position}、
 * {@code set.body.from.property}等 KuraPayload 专属配置不再保留。发布失败时仅记录
 * 错误日志不中断图运行（保留上游 catch-all 行为），并仍按配置向下游转发。
 * </p>
 */
public final class CloudPublisher implements WireEmitter, WireReceiver {

    /** 是否在发布后继续向下游发射的属性 key */
    public static final String PROP_PUBLISH_FORWARD = "publish.forward";

    /** 上游云发布器 target filter 属性 key（保留上游 metatype 字段名，实际绑定改为 Spring 注入） */
    public static final String PROP_CLOUD_PUBLISHER_TARGET = "CloudPublisher.target";

    private static final Logger LOG = LoggerFactory.getLogger(CloudPublisher.class);

    private final String pid;
    private final PublisherSink sink;
    private final WireEmitContext emitContext;

    /** 配置可经 updated() 热更新（对照上游 updated 回调），故为 volatile */
    private volatile Map<String, Object> properties;

    /**
     * 创建云发布组件实例（由 {@link CloudPublisherFactory} 调用）。
     *
     * @param pid 组件实例 PID
     * @param sink 发布水槽（允许为 null，此时仅执行下游转发，保留上游
     *        {@code nonNull(cloudConnectionPublisher)} 的宽松语义）
     * @param properties 组件配置属性（已合并默认值）
     * @param emitContext 引擎注入的发射上下文
     */
    CloudPublisher(final String pid, final PublisherSink sink, final Map<String, Object> properties,
            final WireEmitContext emitContext) {
        this.pid = pid;
        this.sink = sink;
        this.properties = requireNonNull(properties, "Properties cannot be null");
        this.emitContext = emitContext;
    }

    /** {@inheritDoc} */
    @Override
    public String getPid() {
        return this.pid;
    }

    /**
     * 接收上游信封：交给水槽发布，视 {@code publish.forward} 配置向下游转发
     * （对照上游 onWireReceive → publish）。
     *
     * @param wireEnvelope 收到的信封
     */
    @Override
    public void onWireReceive(final WireEnvelope wireEnvelope) {
        requireNonNull(wireEnvelope, "Wire Envelope cannot be null");
        final List<WireRecord> records = wireEnvelope.getRecords();
        if (this.sink != null) {
            try {
                this.sink.publish(records);
            } catch (final Exception e) {
                LOG.error("Error in publishing wire records using publisher sink", e);
            }
        }
        if (isForward()) {
            this.emitContext.emit(new WireEnvelope(this.pid, records));
        }
    }

    /**
     * 热更新组件配置（对照上游 OSGi updated 回调）。
     *
     * @param properties 新的配置属性
     */
    public void updated(final Map<String, Object> properties) {
        this.properties = requireNonNull(properties, "Properties cannot be null");
    }

    /**
     * 是否在发布后向下游继续发射（供测试与调试）。
     *
     * @return forward 配置为 true 时返回 true（默认 false）
     */
    boolean isForward() {
        final Object forward = this.properties.get(PROP_PUBLISH_FORWARD);
        return forward instanceof Boolean ? (Boolean) forward : false;
    }
}
