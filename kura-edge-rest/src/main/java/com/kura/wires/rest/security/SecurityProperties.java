/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 管理员账号配置（application.yml 只放占位符，口令由环境变量注入）。
 *
 * @param username 管理员用户名
 * @param password 管理员口令
 */
@ConfigurationProperties(prefix = "kura.security.admin")
public record SecurityProperties(String username, String password) {
}
