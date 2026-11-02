/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.subscriber;

import static java.util.Objects.requireNonNull;

import java.util.Map;

import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.model.WireEmitter;
import com.kura.wires.model.WireEnvelope;

/**
 * 云订阅 Wire 组件：把外部数据源推送的信封作为自己的发射向下游发射，
 * 对照上游 {@code org.eclipse.kura.internal.wire.subscriber.CloudSubscriber} 的
 * {@code onMessageArrived} → {@code wireSupport.emit} 语义。
 * <p>
 * 外部数据经 {@link com.kura.wires.components.subscriber.SubscriberSource} 到达后调用
 * {@link #deliver(WireEnvelope)}；发射信封以本组件 PID 为发射者，携带的记录保持
 * 原样（上游从 KuraPayload 逐项重建记录，本项目信封已是 Wire 模型，无需转换）。
 * </p>
 */
public final class CloudSubscriber implements WireEmitter {

    /** 上游云订阅器 target filter 属性 key（保留上游 metatype 字段名，实际绑定改为 Spring 注入） */
    public static final String PROP_CLOUD_SUBSCRIBER_TARGET = "CloudSubscriber.target";

    private final String pid;
    private final WireEmitContext emitContext;

    /** 配置可经 updated() 热更新（对照上游 updated 回调），故为 volatile */
    private volatile Map<String, Object> properties;

    /**
     * 创建云订阅组件实例（由 {@link CloudSubscriberFactory} 调用）。
     *
     * @param pid 组件实例 PID
     * @param properties 组件配置属性（已合并默认值）
     * @param emitContext 引擎注入的发射上下文
     */
    CloudSubscriber(final String pid, final Map<String, Object> properties, final WireEmitContext emitContext) {
        this.pid = pid;
        this.properties = requireNonNull(properties, "Properties cannot be null");
        this.emitContext = emitContext;
    }

    /** {@inheritDoc} */
    @Override
    public String getPid() {
        return this.pid;
    }

    /**
     * 把外部数据源推送的信封作为自己的发射向下游发射（对照上游
     * CloudSubscriberListener#onMessageArrived）。
     *
     * @param envelope 外部数据源构造的信封
     */
    public void deliver(final WireEnvelope envelope) {
        requireNonNull(envelope, "Wire Envelope cannot be null");
        this.emitContext.emit(new WireEnvelope(this.pid, envelope.getRecords()));
    }

    /**
     * 热更新组件配置（对照上游 OSGi updated 回调）。
     *
     * @param properties 新的配置属性
     */
    public void updated(final Map<String, Object> properties) {
        this.properties = requireNonNull(properties, "Properties cannot be null");
    }
}
