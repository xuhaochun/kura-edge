/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.controller;

import com.kura.wires.rest.dto.ApiResponse;
import com.kura.wires.rest.dto.FeedStatusDto;
import com.kura.wires.rest.dto.SystemVersionDto;
import com.kura.wires.rest.dto.UpdateAcceptedDto;
import com.kura.wires.rest.dto.UpdateRequestDto;
import com.kura.wires.rest.service.BackendUpdateService;
import com.kura.wires.rest.service.UpdateFeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 桌面外壳热更新端点（RFC 006 §2.4）。
 * <p>
 * 本组端点只在 Tauri 桌面外壳里真正可用：外壳通过环境变量 {@code KURA_EDGE_UPDATE_DIR} /
 * {@code KURA_EDGE_HOME} 指定"暂存目录"与"app 目录"，后端只写暂存目录、只读 app 目录。
 * 以 {@code java -jar} / {@code mvn spring-boot:run} 启动时两者缺失，{@code POST /update}
 * 直接返回 {@code 409004}——不做"看起来能更新但其实没人换 jar"的假动作。
 * </p>
 */
@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "系统与热更新", description = "查询当前后端版本、请求安装新的后端 jar、蓝绿移交、自动更新清单轮询（需 wires.admin）")
public class SystemUpdateController {

    private final BackendUpdateService updateService;

    private final UpdateFeedService feedService;

    /**
     * 创建控制器。
     *
     * @param updateService 热更新受理服务
     * @param feedService 自动更新（清单轮询）服务
     */
    public SystemUpdateController(final BackendUpdateService updateService, final UpdateFeedService feedService) {
        this.updateService = updateService;
        this.feedService = feedService;
    }

    /**
     * 查询当前运行的后端版本。
     *
     * @return 版本信息（桌面运行时读 {@code app/current.json}）
     */
    @GetMapping("/version")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "查询后端版本",
            description = "桌面运行时返回外壳记录的 current.json（source=desktop），否则回退到 jar 清单版本（source=maven）")
    public ApiResponse<SystemVersionDto> version() {
        return ApiResponse.ok(this.updateService.currentVersion());
    }

    /**
     * 受理一次热更新：下载并校验新 jar，然后由桌面外壳换 jar 并重启后端进程（exe 不重启）。
     * <p>
     * 请求可以只带 {@code version}（甚至什么都不带 = 升到发布服务器上的最新版）：地址与 sha256 由
     * {@code kura.update.release-server-url} 指向的发布服务器清单给出（RFC 010）。
     * </p>
     *
     * @param request 更新请求
     * @return 202 受理回执；蓝绿模式等 {@code /retire} 移交，单机模式约 1.5 s 后退出
     */
    @PostMapping("/update")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "请求热更新后端",
            description = "下载 jar、校验 sha256 后写入暂存目录并通知外壳。url 可留空——此时按 version（也可不给，"
                    + "等于最新版）到发布服务器清单里取地址与摘要；蓝绿模式等外壳 /retire 移交，单机模式约 1.5 秒后"
                    + "自行退出由外壳重启。回执 202 只代表已受理，结果见外壳的 apply-result.json")
    public ResponseEntity<ApiResponse<UpdateAcceptedDto>> update(@Valid @RequestBody final UpdateRequestDto request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(this.updateService.requestUpdate(request)));
    }

    /**
     * 查询自动更新（清单轮询）状态：是否启用、上次检查结果、已提交/失败的版本（RFC 008）。
     *
     * @return 状态
     */
    @GetMapping("/feed")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "查询自动更新状态",
            description = "设备定时拉取 feed.json 的当前状态：enabled/url/intervalMs + 最近一次检查结果与已提交版本")
    public ApiResponse<FeedStatusDto> feed() {
        return ApiResponse.ok(this.feedService.status());
    }

    /**
     * 立即检查一次更新清单（不必等下一轮轮询）。
     *
     * @return 检查后的状态
     */
    @PostMapping("/feed/check")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "立即检查一次更新清单",
            description = "同步拉取 feed.json；发现更新版本则按 /update 的同一套流程落包并交给外壳。"
                    + "未配置清单地址时返回 enabled=false")
    public ApiResponse<FeedStatusDto> checkFeed() {
        return ApiResponse.ok(this.feedService.checkNow());
    }

    /**
     * 蓝绿移交：停图、交出图执行权并优雅退出，让已经就绪的新实例接手（RFC 007）。
     * <p>
     * 由桌面外壳在"新实例已就绪且窗口已导航过去"之后调用。手工调用也没问题——它不会丢配置：
     * 图快照与组件配置都在数据库里，新实例起来会自己恢复。
     * </p>
     *
     * @return 202 空回执；本进程约 300 ms 后退出
     */
    @PostMapping("/retire")
    @PreAuthorize("hasRole('wires.admin')")
    @Operation(summary = "交出图执行权并退出（蓝绿移交）",
            description = "先停图再放锁，300 ms 后优雅退出；新实例随即抢到执行权并接管线图。"
                    + "单机（文件 H2）模式返回 409004")
    public ResponseEntity<ApiResponse<Void>> retire() {
        this.updateService.retire();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(null));
    }
}
