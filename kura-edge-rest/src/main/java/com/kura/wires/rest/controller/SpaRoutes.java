/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.controller;

/**
 * 前端 history 路由的单一真源（RFC 005）。
 *
 * <p>
 * 三处必须一致：Vue Router 的 {@code routes}、{@link SpaForwardController} 的映射、以及
 * {@code SecurityConfig} 的匿名放行表。新增前端路由时在这里加一个常量，然后在上述三处引用它——
 * 漏改 SecurityConfig 会让「刷新页面」变成 401，而漏改这里会让新路由直接 404。
 * </p>
 */
public final class SpaRoutes {

    /** 根路径，桌面外壳首次打开的入口。 */
    public static final String ROOT = "/";

    /** 图编辑器页面。 */
    public static final String WIRES = "/wires";

    /** 驱动与资产页面。 */
    public static final String DRIVERS_ASSETS = "/drivers-assets";

    /** 全部需要回退到 {@code index.html} 的路由，测试与安全放行表按此遍历。 */
    public static final String[] ALL = {ROOT, WIRES, DRIVERS_ASSETS};

    private SpaRoutes() {
        // 常量表，不实例化
    }
}
