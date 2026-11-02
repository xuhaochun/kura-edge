/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.controller;

import java.util.List;

import com.kura.wires.rest.dto.ApiResponse;
import com.kura.wires.rest.dto.ComponentConfigurationDto;
import com.kura.wires.rest.dto.ComponentConfigurationListDto;
import com.kura.wires.rest.dto.PidSetDto;
import com.kura.wires.rest.service.WireConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配置端点：Driver / Asset 实例列表与组件配置批量读写，对照上游
 * {@code WireRestService} 的 /wire/v1/drivers|assets|configs*（Rules 07 映射表）。
 */
@RestController
@RequestMapping("/api/v1/wires")
@Tag(name = "组件配置", description = "Driver / Asset 实例与组件配置的批量查询、更新与删除")
public class WireConfigController {

    private final WireConfigService configService;

    /**
     * 创建控制器。
     *
     * @param configService 配置应用服务
     */
    public WireConfigController(final WireConfigService configService) {
        this.configService = configService;
    }

    /**
     * 批量按 pid 取配置（密码脱敏，不存在的 pid 跳过）。
     *
     * @param request PID 集合
     * @return 统一响应体包装的配置列表
     */
    @PostMapping("/configs/by-pids")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "批量查询配置", description = "按 pid 批量取组件配置，密码字段脱敏为占位符")
    public ApiResponse<List<ComponentConfigurationDto>> getConfigsByPids(@Valid @RequestBody final PidSetDto request) {
        return ApiResponse.ok(this.configService.getConfigsByPids(request.getPids()));
    }

    /**
     * 批量删除配置；被图引用的 pid 拒绝（409003）。
     *
     * @param request PID 集合
     * @return 统一响应体（无负载）
     */
    @DeleteMapping("/configs/by-pids")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "批量删除配置", description = "按 pid 批量删除组件配置；被图引用的 pid 返回 409003")
    public ApiResponse<Void> deleteConfigsByPids(@Valid @RequestBody final PidSetDto request) {
        this.configService.deleteConfigsByPids(request.getPids());
        return ApiResponse.ok(null);
    }

    /**
     * 批量更新组件配置；Wire Component 必须已被图引用。
     *
     * @param request 配置集合
     * @return 统一响应体（无负载）
     */
    @PutMapping("/configs")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "批量更新配置", description = "批量更新组件配置；未入图的 Wire Component 返回 400003，失败聚合报错")
    public ApiResponse<Void> updateConfigs(@Valid @RequestBody final ComponentConfigurationListDto request) {
        this.configService.updateConfigs(request);
        return ApiResponse.ok(null);
    }
}
