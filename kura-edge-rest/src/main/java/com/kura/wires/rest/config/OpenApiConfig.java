/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc OpenAPI 配置：文档元信息与 HTTP Basic 安全方案。
 */
@Configuration
public class OpenApiConfig {

    /**
     * OpenAPI 描述对象。
     *
     * @return OpenAPI
     */
    @Bean
    public OpenAPI kuraEdgeOpenApi() {
        final String scheme = "basicAuth";
        return new OpenAPI().info(new Info().title("kura-edge Wires REST API")
                .description("Eclipse Kura Wires 线图功能的独立后端（对照上游 /wire/v1 语义）").version("v1"))
                .components(new Components().addSecuritySchemes(scheme,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList(scheme));
    }
}
