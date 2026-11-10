/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.rest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.kura.wires.components.logger.ComponentLogBuffer;
import com.kura.wires.rest.dto.ComponentLogLineDto;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link ComponentLogStreamer} 单元测试（Gherkin 风格，正反路径）：订阅后推送增量、
 * 序号对齐、失效连接移除、关闭时收尾。
 */
class ComponentLogStreamerTest {

    private static final String PID = "logger-1";

    private final ComponentLogBuffer buffer = new ComponentLogBuffer();

    private final ComponentLogStreamer streamer = new ComponentLogStreamer(this.buffer, null, false);

    private RecordingSseEmitter emitter;

    @Test
    public void successfulSubscriptionReceivesAppendedLines() {
        givenLinesAppended(2);
        givenSubscription(0L);

        whenPushPending();

        thenPayloadHasSeqs(1L, 2L);
    }

    @Test
    public void successfulIncrementalPushSendsOnlyNewLines() {
        givenLinesAppended(1);
        givenSubscription(0L);
        whenPushPending();

        givenLinesAppended(1);
        whenPushPending();

        thenPayloadHasSeqs(1L, 2L);
    }

    @Test
    public void successfulPushRewindsWhenSinceIsAheadOfBuffer() {
        givenSubscription(99L);
        givenLinesAppended(1);

        whenPushPending();

        thenPayloadHasSeqs(1L);
    }

    @Test
    public void successfulPushKeepsOnlyNewestBufferLinesWhenBacklogExceedsCapacity() {
        givenLinesAppended(ComponentLogBuffer.CAPACITY + 20);
        givenSubscription(0L);

        whenPushPending();

        assertThat(this.emitter.payloads()).hasSize(ComponentLogBuffer.CAPACITY);
        assertThat(this.emitter.payloads().get(0).getSeq()).isEqualTo(21L);
    }

    @Test
    public void successfulFailedEmitterIsRemovedAndCompleted() {
        givenLinesAppended(1);
        givenFailingSubscription();

        whenPushPending();
        whenPushPending();

        assertThat(this.emitter.completed()).isTrue();
        assertThat(this.emitter.payloads()).isEmpty();
    }

    @Test
    public void successfulCompleteFailureIsSwallowed() {
        givenLinesAppended(1);
        this.emitter = new RecordingSseEmitter(true, true);
        this.streamer.subscribe(PID, 0L, this.emitter);

        whenPushPending();
    }

    @Test
    public void successfulNoKeepAliveOnFreshSubscription() {
        givenLinesAppended(1);
        givenSubscription(0L);

        whenPushPending();

        thenCommentsAreEmpty();
    }

    @Test
    public void successfulShutdownCompletesEverySubscription() {
        givenLinesAppended(1);
        givenSubscription(0L);

        this.streamer.shutdown();

        assertThat(this.emitter.completed()).isTrue();
    }

    private void givenLinesAppended(final int count) {
        for (int index = 1; index <= count; index++) {
            this.buffer.append(PID, "line-" + index);
        }
    }

    private void givenSubscription(final long sinceSeq) {
        this.emitter = new RecordingSseEmitter(false, false);
        this.streamer.subscribe(PID, sinceSeq, this.emitter);
    }

    private void givenFailingSubscription() {
        this.emitter = new RecordingSseEmitter(true, false);
        this.streamer.subscribe(PID, 0L, this.emitter);
    }

    private void whenPushPending() {
        this.streamer.pushPending();
    }

    private void thenPayloadHasSeqs(final Long... expected) {
        final List<Long> actual = this.emitter.payloads().stream().map(ComponentLogLineDto::getSeq).toList();
        assertThat(actual).containsExactly(expected);
    }

    private void thenCommentsAreEmpty() {
        assertThat(this.emitter.comments()).isEmpty();
    }

    /** 绕过编译器检查抛出受检异常，模拟 Spring 的 sneaky throw。 */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(final Throwable throwable) throws T {
        throw (T) throwable;
    }

    /** 记录推送内容的 SSE 发射器替身：不触达真实 HTTP 响应。 */
    private static final class RecordingSseEmitter extends SseEmitter {

        private final List<ComponentLogLineDto> payloads = new CopyOnWriteArrayList<>();

        private final List<String> comments = new CopyOnWriteArrayList<>();

        private final boolean failing;

        private final boolean completeFails;

        private boolean completed;

        RecordingSseEmitter(final boolean failing, final boolean completeFails) {
            super(60_000L);
            this.failing = failing;
            this.completeFails = completeFails;
        }

        @Override
        public synchronized void send(final SseEventBuilder builder) throws IOException {
            if (this.failing) {
                throw new IOException("broken pipe");
            }
            for (final DataWithMediaType item : builder.build()) {
                if (item.getData() instanceof ComponentLogLineDto dto) {
                    this.payloads.add(dto);
                } else if (item.getData() instanceof String text && text.startsWith(":")) {
                    this.comments.add(text);
                }
            }
        }

        @Override
        public synchronized void complete() {
            if (this.completeFails) {
                // 客户端断开时 Spring 在无 throws 声明的方法里抛出的受检异常（继承 IOException）
                sneakyThrow(new AsyncRequestNotUsableException("connection gone"));
            }
            this.completed = true;
        }

        List<ComponentLogLineDto> payloads() {
            return this.payloads;
        }

        List<String> comments() {
            return this.comments;
        }

        boolean completed() {
            return this.completed;
        }
    }
}
