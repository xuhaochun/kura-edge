/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.config;

import com.kura.wires.core.snapshot.GraphJsonCodec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean 装配：图 JSON 编解码器等跨模块的基础设施 Bean。
 * <p>
 * 内置组件工厂（Timer / Logger / RegexFilter / Fifo / DbStore / CloudPublisher /
 * CloudSubscriber）不在此逐个声明：各工厂类自带 {@code @Component}，由
 * {@code @SpringBootApplication(scanBasePackages = "com.kura.wires")} 自动发现，
 * 再构造器注入汇总到 {@code WireComponentRegistry}（对照上游 DS 自动注册 + 组件跟踪）。
 * 新增组件只需加 {@code @Component}，避免「工厂已实现却漏注册、图里选不到」的遗漏。
 * </p>
 */
@Configuration
public class AppConfig {

    /**
     * 图 JSON 编解码器。
     *
     * @return GraphJsonCodec
     */
    @Bean
    public GraphJsonCodec graphJsonCodec() {
        return new GraphJsonCodec();
    }
}
