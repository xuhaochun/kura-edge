/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.kura.wires.components.timer.Timer;
import com.kura.wires.core.registry.WireComponentRegistry;

/**
 * 保存整图时定时器的交接（真实 Spring 上下文 + H2 + 图引擎 + 真实调度器）：
 * 改了间隔再点保存，必须停掉旧 Timer 实例、按新间隔重建，而不是让旧实例继续跑。
 * 对照上游 {@code WireGraphServiceImpl#update} 的差异更新语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "wires.admin")
class WireGraphSnapshotTimerRestartTest {

    private static final String GRAPH_SERVICE_PID = "org.eclipse.kura.wire.graph.WireGraphService";
    private static final String TIMER_FACTORY_PID = "org.eclipse.kura.wire.Timer";
    private static final String TIMER_PID = "timer1";
    private static final String GRAPH_JSON = "{\"components\":[{\"pid\":\"" + TIMER_PID + "\","
            + "\"inputPortCount\":0,\"outputPortCount\":1,\"renderingProperties\":"
            + "{\"position\":{\"x\":40.0,\"y\":60.0},\"inputPortNames\":{},\"outputPortNames\":{}}}],\"wires\":[]}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WireComponentRegistry registry;

    @AfterEach
    void tearDown() throws Exception {
        this.mockMvc.perform(delete("/api/v1/wires/graph"));
    }

    @Test
    void successfulSecondSaveReplacesRunningTimerWithNewInterval() throws Exception {
        givenSnapshotSavedWithTimerInterval(3600);
        final Timer runningTimer = runningTimer();

        whenSnapshotSavedWithTimerInterval(600);

        final Timer replacedTimer = runningTimer();
        assertThat(replacedTimer).isNotSameAs(runningTimer);
        assertThat(replacedTimer.getPeriodMillis()).isEqualTo(600_000L);
    }

    private Timer runningTimer() {
        return (Timer) this.registry.getInstance(TIMER_PID).orElseThrow();
    }

    private void givenSnapshotSavedWithTimerInterval(final int seconds) throws Exception {
        whenSnapshotSavedWithTimerInterval(seconds);
    }

    private void whenSnapshotSavedWithTimerInterval(final int seconds) throws Exception {
        final String body = "{\"configs\":["
                + "{\"pid\":\"" + GRAPH_SERVICE_PID + "\",\"properties\":{\"WireGraph\":{\"type\":\"STRING\",\"value\":\""
                + GRAPH_JSON.replace("\"", "\\\"") + "\"}}},"
                + "{\"pid\":\"" + TIMER_PID + "\",\"properties\":{\"service.factoryPid\":{\"type\":\"STRING\",\"value\":\""
                + TIMER_FACTORY_PID + "\"},\"type\":{\"type\":\"STRING\",\"value\":\"SIMPLE\"},"
                + "\"simple.interval\":{\"type\":\"INTEGER\",\"value\":" + seconds + "},"
                + "\"simple.time.unit\":{\"type\":\"STRING\",\"value\":\"SECONDS\"}}}]}";
        this.mockMvc.perform(put("/api/v1/wires/graph/snapshot").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
}
