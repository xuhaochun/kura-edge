/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.controller;

import java.util.List;

import com.kura.wires.rest.dto.ApiResponse;
import com.kura.wires.rest.dto.ComponentConfigurationDto;
import com.kura.wires.rest.dto.ComponentConfigurationListDto;
import com.kura.wires.rest.dto.WireGraphTopologyDto;
import com.kura.wires.rest.mapper.GraphDtoMapper;
import com.kura.wires.rest.service.WireGraphSnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 线图端点：拓扑 / 快照 / 整图删除，端点语义对照上游
 * {@code WireRestService} 的 /wire/v1/graph*（Rules 07 映射表）。
 */
@RestController
@RequestMapping("/api/v1/wires/graph")
@Tag(name = "线图管理", description = "Wires 线图拓扑与快照的查询、保存与删除")
public class WireGraphController {

    private final WireGraphSnapshotService snapshotService;

    /**
     * 创建控制器。
     *
     * @param snapshotService 快照应用服务
     */
    public WireGraphController(final WireGraphSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    /**
     * 返回当前图拓扑 JSON（与上游字段一致）。
     *
     * @return 统一响应体包装的整图
     */
    @GetMapping("/topology")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "查询当前图拓扑", description = "返回当前整图的 components/wires JSON（字段名与上游 WireGraph 属性一致）")
    public ApiResponse<WireGraphTopologyDto> getTopology() {
        return ApiResponse.ok(GraphDtoMapper.toDto(this.snapshotService.getGraph()));
    }

    /**
     * 返回整图快照：图 + 全部组件配置（密码脱敏）。
     *
     * @return 统一响应体包装的配置列表
     */
    @GetMapping("/snapshot")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "查询整图快照", description = "返回 WireGraphService 配置与图内组件配置，密码字段脱敏为占位符")
    public ApiResponse<List<ComponentConfigurationDto>> getSnapshot() {
        return ApiResponse.ok(this.snapshotService.getSnapshot());
    }

    /**
     * 整图保存：校验 → 更新图 → 更新组件配置 → 快照落库。
     *
     * @param request 组件配置集合（含 WireGraphService 配置）
     * @return 统一响应体（无负载）
     */
    @PutMapping("/snapshot")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "保存整图", description = "按上游语义整图保存：更新图、更新组件配置并落库；失败聚合报错")
    public ApiResponse<Void> updateSnapshot(@Valid @RequestBody final ComponentConfigurationListDto request) {
        this.snapshotService.updateSnapshot(request);
        return ApiResponse.ok(null);
    }

    /**
     * 删除整图。
     *
     * @return 统一响应体（无负载）
     */
    @DeleteMapping
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "删除整图", description = "删除全部连线与组件配置，快照置空")
    public ApiResponse<Void> deleteGraph() {
        this.snapshotService.deleteGraph();
        return ApiResponse.ok(null);
    }
}
