/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.publisher;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.kura.wires.model.WireRecord;
import com.kura.wires.model.type.TypedValue;

/**
 * {@link PublisherSink} 的日志演示实现：把待发布记录写入日志，代替上游接入
 * 真实云平台的 CloudConnection 发布器。
 * <p>
 * 以 Spring {@code @Component} 注册，供 {@link CloudPublisherFactory} 构造注入；
 * 后续接入真实云平台时提供新的 {@code PublisherSink} 实现即可替换。
 * </p>
 */
@Component
public class LogPublisherSink implements PublisherSink {

    private static final Logger LOG = LoggerFactory.getLogger(LogPublisherSink.class);

    /** {@inheritDoc} */
    @Override
    public void publish(final List<WireRecord> records) {
        LOG.info("Publishing {} wire record(s) to log sink", records.size());
        for (final WireRecord record : records) {
            for (final Map.Entry<String, TypedValue<?>> entry : record.getProperties().entrySet()) {
                LOG.info("  {} : {}", entry.getKey(), entry.getValue().getValue());
            }
        }
    }
}
