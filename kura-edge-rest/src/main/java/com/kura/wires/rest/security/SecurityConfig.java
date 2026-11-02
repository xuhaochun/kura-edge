/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.security;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 安全配置：/api/v1/wires/** 全部要求 {@code wires.admin} 角色（对照上游
 * {@code RolesAllowed(WIRES_ADMIN_ROLE)}），HTTP Basic、无状态会话、CSRF 关闭
 * （纯 API）；springdoc 端点放行。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    /**
     * 安全过滤链。
     *
     * @param http HttpSecurity
     * @return 过滤链
     * @throws Exception 配置失败时
     */
    @Bean
    public SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**",
                        "/swagger-ui.html").permitAll().anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(handling -> handling.authenticationEntryPoint((request, response, exception) -> writeError(
                        response, HttpStatus.UNAUTHORIZED, 401001, "未认证：请使用 HTTP Basic 提供 wires.admin 凭据"))
                        .accessDeniedHandler((request, response, exception) -> writeError(response,
                                HttpStatus.FORBIDDEN, 403001, "无权限：需要 wires.admin 角色")));
        return http.build();
    }

    /**
     * 管理员账号（来自配置；口令以 {noop} 前缀明文存储于内存，仅用于本地/开发）。
     *
     * @param properties 安全属性
     * @return UserDetailsService
     */
    @Bean
    public UserDetailsService userDetailsService(final SecurityProperties properties) {
        final var user = User.withUsername(properties.username()).password("{noop}" + properties.password())
                .roles("wires.admin").build();
        return new InMemoryUserDetailsManager(user);
    }

    private static void writeError(final jakarta.servlet.http.HttpServletResponse response, final HttpStatus status,
            final int code, final String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(new ObjectMapper().writeValueAsString(ApiResponseError.of(code, message)));
    }

    /**
     * 安全错误响应体（{code, message, data:null}，与统一响应体一致）。
     *
     * @param code 错误码
     * @param message 错误描述
     */
    record ApiResponseError(int code, String message, Object data) {

        static ApiResponseError of(final int code, final String message) {
            return new ApiResponseError(code, message, null);
        }
    }
}
