/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.controller;

import com.kura.wires.rest.service.ComponentLogStreamer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 组件运行日志端点（RFC 007）：以 SSE 把 Logger 组件收到的记录实时推给前端「日志详情」面板，
 * 效果等价 {@code tail -f}，只保留最近 {@code ComponentLogBuffer.CAPACITY} 行。
 * <p>
 * 本端点是 SSE 长连接，无法套用统一响应体 {@code {code,message,data}}（Rules 07 的统一响应约定
 * 针对一次性 JSON 响应）；鉴权失败仍由 {@code SecurityConfig} 的入口点返回统一错误体。
 * </p>
 */
@RestController
@RequestMapping("/api/v1/wires/graph/components")
@Tag(name = "组件运行日志", description = "按组件 PID 实时推送运行日志（SSE）")
public class WireComponentLogController {

    private final ComponentLogStreamer logStreamer;

    /**
     * 创建控制器。
     *
     * @param logStreamer 组件日志推送服务
     */
    public WireComponentLogController(final ComponentLogStreamer logStreamer) {
        this.logStreamer = logStreamer;
    }

    /**
     * 订阅某组件的实时日志流。
     *
     * @param pid 组件 PID
     * @param since 客户端已消费到的序号（默认 0，从缓冲现有内容开始）
     * @return SSE 发射器
     */
    @GetMapping(value = "/{pid}/log/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "订阅组件日志流",
            description = "SSE 推送该组件最近 100 行运行日志，事件名 log，data 为 {seq,timestamp,text} JSON")
    public SseEmitter streamLog(@PathVariable final String pid,
            @RequestParam(name = "since", defaultValue = "0") final long since) {
        return this.logStreamer.subscribe(pid, since);
    }
}
