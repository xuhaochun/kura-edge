/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 直接访问回退（桌面版由后端托管前端构建产物，见 RFC 005）。
 * <p>
 * Vue Router 使用 history 模式，直接访问或刷新非根路径时后端必须交回 {@code index.html}，
 * 否则会 404。静态资源（{@code /assets/**} 等）不经过这里，由 Spring 静态资源处理器负责；
 * 路由常量集中在 {@link SpaRoutes}，{@code SecurityConfig} 的匿名放行表引用同一组常量。
 * <p>
 * 只在 {@code classpath:static/index.html} 存在（即 {@code -Pdesktop} 构建）时装配：普通
 * {@code mvn package} 的 jar 没有前端产物，若仍注册转发会把 {@code /} 变成 500。
 * </p>
 * </p>
 */
@Controller
@ConditionalOnResource(resources = "classpath:static/index.html")
public class SpaForwardController {

    /**
     * 前端路由入口，统一转发到 {@code index.html}。
     *
     * @return forward 视图名
     */
    @GetMapping({SpaRoutes.ROOT, SpaRoutes.WIRES, SpaRoutes.DRIVERS_ASSETS})
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
