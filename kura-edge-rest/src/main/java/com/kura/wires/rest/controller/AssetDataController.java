/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.controller;

import java.util.List;
import java.util.Map;

import com.kura.wires.rest.dto.ApiResponse;
import com.kura.wires.rest.dto.AssetChannelDto;
import com.kura.wires.rest.dto.ChannelRecordDto;
import com.kura.wires.rest.dto.ReadChannelsRequestDto;
import com.kura.wires.rest.dto.WriteChannelsRequestDto;
import com.kura.wires.rest.service.AssetDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资产运行期端点：通道列表、读值、写值。
 * <p>
 * 路径与上游 {@code AssetRestService}（{@code /services/assets/**}）同构，保留 {@code _read}/
 * {@code _write} 后缀，便于与 Kura 的脚本/工具对照。响应体统一包装为 {@code {code,message,data}}
 * （本项目约定，上游是裸 JSON，属于有意偏离，见 RFC 002 §5）。
 * </p>
 */
@RestController
@RequestMapping("/api/v1/assets")
@Tag(name = "资产数据", description = "资产通道列表与读写（对照上游 /services/assets）")
public class AssetDataController {

    private final AssetDataService service;

    /**
     * 创建控制器。
     *
     * @param service 资产数据服务
     */
    public AssetDataController(final AssetDataService service) {
        this.service = service;
    }

    /**
     * 返回资产的通道列表。
     *
     * @param pid 资产实例 PID
     * @return 通道列表
     */
    @GetMapping("/{pid}")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "查询资产通道", description = "返回资产配置里的通道（名称/方向/取值类型/scale-offset/单位/启用）")
    public ApiResponse<List<AssetChannelDto>> getChannels(@PathVariable final String pid) {
        return ApiResponse.ok(this.service.getChannels(pid));
    }

    /**
     * 读全部可读通道（上游形态：通道名 → 原始值）。
     *
     * @param pid 资产实例 PID
     * @return 通道取值映射
     */
    @GetMapping("/{pid}/_read")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "读取全部通道", description = "读全部可读通道，返回 {通道名: 原始值}（字节数组为 base64，无类型信息）")
    public ApiResponse<Map<String, Object>> readAll(@PathVariable final String pid) {
        return ApiResponse.ok(this.service.readAll(pid));
    }

    /**
     * 读指定通道（本项目扩展：带每条状态与失败原因）。
     *
     * @param pid 资产实例 PID
     * @param request 通道名集合
     * @return 记录列表
     */
    @PostMapping("/{pid}/_read")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "读取指定通道", description = "按通道名读值，返回带 status/exceptionMessage 的记录（结果条数与请求一致）")
    public ApiResponse<List<ChannelRecordDto>> read(@PathVariable final String pid,
            @Valid @RequestBody final ReadChannelsRequestDto request) {
        return ApiResponse.ok(this.service.read(pid, request.getChannels()));
    }

    /**
     * 写通道值。
     *
     * @param pid 资产实例 PID
     * @param request 写请求
     * @return 写后记录（带状态与失败原因）
     */
    @PostMapping("/{pid}/_write")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "写入通道值", description = "写入 {name,type,value} 记录，返回写后每条记录的状态与失败原因")
    public ApiResponse<List<ChannelRecordDto>> write(@PathVariable final String pid,
            @Valid @RequestBody final WriteChannelsRequestDto request) {
        return ApiResponse.ok(this.service.write(pid, request.getChannels()));
    }
}
