/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.controller;

import java.util.LinkedHashSet;
import java.util.List;

import com.kura.wires.model.definition.Ad;
import com.kura.wires.rest.dto.ApiResponse;
import com.kura.wires.rest.dto.DriverDescriptorDto;
import com.kura.wires.rest.dto.PidSetDto;
import com.kura.wires.rest.dto.WireGraphMetadataDto;
import com.kura.wires.rest.service.DriversAssetsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 元数据端点：组件定义、驱动工厂、驱动通道描述符与资产基座通道描述符（前端面板数据源），
 * 对照上游 {@code WireRestService} 的 /wire/v1/metadata*。
 */
@RestController
@RequestMapping("/api/v1/wires/metadata")
@Tag(name = "线图元数据", description = "Wire 组件定义、驱动工厂与通道描述符、资产基座通道参数查询")
public class WireMetadataController {

    private final DriversAssetsService service;

    /**
     * 创建控制器。
     *
     * @param service 驱动与资产应用服务（元数据汇总）
     */
    public WireMetadataController(final DriversAssetsService service) {
        this.service = service;
    }

    /**
     * 返回全部元数据（组件定义 + 驱动 OCD + 驱动通道描述符 + 资产基座通道描述符）。
     *
     * @return 统一响应体包装的元数据
     */
    @GetMapping
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "查询元数据",
            description = "一次返回组件定义、驱动 OCD、驱动通道描述符与资产基座通道描述符（对照上游 GET /metadata）")
    public ApiResponse<WireGraphMetadataDto> getMetadata() {
        return ApiResponse.ok(this.service.getMetadata());
    }

    /**
     * 返回全部 Wire 组件工厂 PID（含 WireAsset），对照上游 {@code /metadata/wireComponents/factoryPids}。
     *
     * @return 统一响应体包装的 PID 集合
     */
    @GetMapping("/components/factory-pids")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "查询组件工厂 PID",
            description = "返回全部可用 Wire 组件工厂 PID（对照上游 /metadata/wireComponents/factoryPids）")
    public ApiResponse<PidSetDto> getComponentFactoryPids() {
        return ApiResponse.ok(new PidSetDto(new LinkedHashSet<>(this.service.getComponentFactoryPids())));
    }

    /**
     * 返回驱动工厂 PID（New Driver 下拉的数据源）。
     *
     * @return 统一响应体包装的 PID 集合
     */
    @GetMapping("/drivers/factory-pids")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "查询驱动工厂 PID",
            description = "返回全部可创建实例的驱动工厂 PID（对照上游 /metadata/drivers/factoryPids）")
    public ApiResponse<PidSetDto> getDriverFactoryPids() {
        return ApiResponse.ok(new PidSetDto(new LinkedHashSet<>(this.service.getDriverFactoryPids())));
    }

    /**
     * 返回驱动实例的通道描述符（含工厂级定义）。
     *
     * @return 统一响应体包装的描述符列表
     */
    @GetMapping("/drivers/channel-descriptors")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "查询驱动通道描述符",
            description = "返回每个驱动实例的通道参数定义（对照上游 /metadata/drivers/channelDescriptors）")
    public ApiResponse<List<DriverDescriptorDto>> getDriverChannelDescriptors() {
        final List<DriverDescriptorDto> descriptors = this.service.listDrivers().stream()
                .map(driver -> new DriverDescriptorDto(driver.getPid(), driver.getFactoryPid(),
                        this.service.getDriverChannelDescriptor(driver.getPid())))
                .toList();
        return ApiResponse.ok(descriptors);
    }

    /**
     * 返回资产基座通道描述符（与驱动无关的 {@code +} 参数）。
     *
     * @return 统一响应体包装的 AD 列表
     */
    @GetMapping("/assets/channel-descriptor")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "查询资产基座通道描述符",
            description = "返回 +enabled/+name/+type/+value.type/+scaleoffset.type/+scale/+offset/+unit/+listen"
                    + "（对照上游 /metadata/assets/channelDescriptor）")
    public ApiResponse<List<Ad>> getAssetChannelDescriptor() {
        return ApiResponse.ok(this.service.getBaseChannelDescriptor());
    }
}
