/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.publisher;

import java.util.List;

import com.kura.wires.model.WireRecord;

/**
 * 发布水槽 SPI：CloudPublisher 收到信封后把记录交给本接口实现发布，对照上游
 * {@code org.eclipse.kura.cloudconnection.publisher.CloudPublisher} 服务的角色。
 * <p>
 * 上游通过 OSGi target filter（{@code CloudPublisher.target}）绑定云连接器；本项目
 * 不接真实云平台，改为 Spring 依赖注入：工厂把容器中的
 * {@link PublisherSink} Bean 传给组件实例。
 * </p>
 */
@FunctionalInterface
public interface PublisherSink {

    /**
     * 发布一批记录（对照上游逐条构建 KuraMessage 后 publish 的语义，这里整体交给水槽）。
     *
     * @param records 待发布的记录
     */
    void publish(List<WireRecord> records);
}
