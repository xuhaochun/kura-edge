/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.controller;

import java.util.List;
import java.util.Map;

import com.kura.wires.model.definition.Ad;
import com.kura.wires.rest.dto.ApiResponse;
import com.kura.wires.rest.dto.CreateAssetRequestDto;
import com.kura.wires.rest.dto.CreateDriverRequestDto;
import com.kura.wires.rest.dto.PidAndFactoryPidSetDto;
import com.kura.wires.rest.service.DriversAssetsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drivers and Assets 管理端点：实例列表、创建、更新与删除。
 * <p>
 * 对照上游：列表来自 {@code WireRestService} 的 {@code /drivers/pids}、{@code /assets/pids}；
 * 创建来自 {@code /configuration/v2/factoryComponents}（GWT 界面也是走配置服务创建实例）；
 * 更新 Driver 走 {@code updateConfiguration}，更新 Asset 是「删掉再建」的语义。
 * </p>
 */
@RestController
@RequestMapping("/api/v1/wires")
@Tag(name = "驱动与资产", description = "Driver / Asset 实例的查询、创建、更新与删除")
public class WireDriversAssetsController {

    private final DriversAssetsService service;

    /**
     * 创建控制器。
     *
     * @param service 应用服务
     */
    public WireDriversAssetsController(final DriversAssetsService service) {
        this.service = service;
    }

    /**
     * 返回 Driver 实例列表。
     *
     * @return 实例列表
     */
    @GetMapping("/drivers")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "查询 Driver 实例", description = "返回 Driver 实例的 pid/factoryPid 列表（对照上游 GET /drivers/pids）")
    public ApiResponse<PidAndFactoryPidSetDto> getDrivers() {
        return ApiResponse.ok(new PidAndFactoryPidSetDto(this.service.listDrivers()));
    }

    /**
     * 返回 Asset 实例列表。
     *
     * @return 实例列表
     */
    @GetMapping("/assets")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "查询 Asset 实例", description = "返回 Asset 实例的 pid/factoryPid 列表（对照上游 GET /assets/pids）")
    public ApiResponse<PidAndFactoryPidSetDto> getAssets() {
        return ApiResponse.ok(new PidAndFactoryPidSetDto(this.service.listAssets()));
    }

    /**
     * 创建 Driver 实例。
     *
     * @param request 请求体
     * @return 统一响应体（无负载）
     */
    @PostMapping("/drivers")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "创建 Driver 实例", description = "按驱动工厂 PID 创建实例并立即实例化（对照上游配置服务建工厂配置）")
    public ApiResponse<Void> createDriver(@Valid @RequestBody final CreateDriverRequestDto request) {
        this.service.createDriver(request);
        return ApiResponse.ok(null);
    }

    /**
     * 创建 Asset 实例。
     *
     * @param request 请求体
     * @return 统一响应体（无负载）
     */
    @PostMapping("/assets")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "创建 Asset 实例", description = "创建 WireAsset 实例并绑定驱动；工厂固定为 org.eclipse.kura.wire.WireAsset")
    public ApiResponse<Void> createAsset(@Valid @RequestBody final CreateAssetRequestDto request) {
        this.service.createAsset(request);
        return ApiResponse.ok(null);
    }

    /**
     * 更新 Driver 配置。
     *
     * @param pid 实例 PID
     * @param properties 完整属性表
     * @return 统一响应体（无负载）
     */
    @PutMapping("/drivers/{pid}")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "更新 Driver 配置", description = "整体替换驱动配置并重建实例")
    public ApiResponse<Void> updateDriver(@PathVariable final String pid,
            @RequestBody final Map<String, Object> properties) {
        this.service.updateDriver(pid, properties);
        return ApiResponse.ok(null);
    }

    /**
     * 更新 Asset 配置（通道表）。
     *
     * @param pid 实例 PID
     * @param properties 完整属性表
     * @return 统一响应体（无负载）
     */
    @PutMapping("/assets/{pid}")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "更新 Asset 配置", description = "整体替换资产配置并重建实例（对照上游「删除再创建工厂配置」的语义）")
    public ApiResponse<Void> updateAsset(@PathVariable final String pid,
            @RequestBody final Map<String, Object> properties) {
        this.service.updateAsset(pid, properties);
        return ApiResponse.ok(null);
    }

    /**
     * 删除 Driver（仍有 Asset 引用时返回 409003）。
     *
     * @param pid 实例 PID
     * @return 统一响应体（无负载）
     */
    @DeleteMapping("/drivers/{pid}")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "删除 Driver 实例", description = "删除驱动配置与实例；仍有 Asset 引用时返回 409003")
    public ApiResponse<Void> deleteDriver(@PathVariable final String pid) {
        this.service.deleteDriver(pid);
        return ApiResponse.ok(null);
    }

    /**
     * 删除 Asset（仍被线图引用时返回 409003）。
     *
     * @param pid 实例 PID
     * @return 统一响应体（无负载）
     */
    @DeleteMapping("/assets/{pid}")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "删除 Asset 实例", description = "删除资产配置与实例；仍被线图引用时返回 409003")
    public ApiResponse<Void> deleteAsset(@PathVariable final String pid) {
        this.service.deleteAsset(pid);
        return ApiResponse.ok(null);
    }

    /**
     * 返回 Driver 实例的原始配置属性。
     *
     * @param pid 实例 PID
     * @return 属性表
     */
    @GetMapping("/drivers/{pid}/configuration")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "查询 Driver 配置", description = "返回驱动实例的原始配置属性（前端配置表单数据源）")
    public ApiResponse<Map<String, Object>> getDriverConfiguration(@PathVariable final String pid) {
        return ApiResponse.ok(this.service.getDriverProperties(pid));
    }

    /**
     * 返回 Asset 实例的原始配置属性。
     *
     * @param pid 实例 PID
     * @return 属性表（含 {@code driver.pid} 与各通道参数）
     */
    @GetMapping("/assets/{pid}/configuration")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "查询 Asset 配置", description = "返回资产实例的原始配置属性（含 driver.pid 与 <channel>#<param> 键）")
    public ApiResponse<Map<String, Object>> getAssetConfiguration(@PathVariable final String pid) {
        return ApiResponse.ok(this.service.getAssetProperties(pid));
    }

    /**
     * 返回资产当前生效的通道描述符（基座 + 绑定驱动）。
     *
     * @param pid 资产实例 PID
     * @return 通道 AD 列表
     */
    @GetMapping("/assets/{pid}/channel-descriptor")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "查询资产通道描述符", description = "返回基座参数 + 该资产绑定驱动的通道参数（顺序：基座在前）")
    public ApiResponse<List<Ad>> getAssetChannelDescriptor(
            @PathVariable final String pid) {
        return ApiResponse.ok(this.service.getAssetChannelDescriptor(pid));
    }
}
