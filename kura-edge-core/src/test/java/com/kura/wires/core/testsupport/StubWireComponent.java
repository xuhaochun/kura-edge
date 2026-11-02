package com.kura.wires.core.testsupport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.model.WireComponent;
import com.kura.wires.model.WireEnvelope;

/**
 * 测试用 Wire 组件桩：记录收到的信封，并持有引擎注入的 {@link WireEmitContext}。
 * <p>
 * {@code failing} 为 true 时接收信封抛异常，用于验证投递层的容错（Rules 08 §5）。
 * </p>
 */
public class StubWireComponent implements WireComponent {

    private final String pid;
    private final WireEmitContext emitContext;
    private final boolean failing;
    private final List<WireEnvelope> received = new CopyOnWriteArrayList<>();

    /**
     * 创建组件桩。
     *
     * @param pid 组件实例 PID
     * @param emitContext 引擎注入的发射上下文
     * @param failing 接收信封时是否抛异常
     */
    public StubWireComponent(final String pid, final WireEmitContext emitContext, final boolean failing) {
        this.pid = pid;
        this.emitContext = emitContext;
        this.failing = failing;
    }

    @Override
    public String getPid() {
        return this.pid;
    }

    @Override
    public void onWireReceive(final WireEnvelope envelope) {
        if (this.failing) {
            throw new IllegalStateException("receiver " + this.pid + " failed on purpose");
        }
        this.received.add(envelope);
    }

    /**
     * 返回已收到的信封列表。
     *
     * @return 收到的信封
     */
    public List<WireEnvelope> getReceived() {
        return this.received;
    }

    /**
     * 返回引擎注入的发射上下文。
     *
     * @return 发射上下文
     */
    public WireEmitContext getEmitContext() {
        return this.emitContext;
    }
}
