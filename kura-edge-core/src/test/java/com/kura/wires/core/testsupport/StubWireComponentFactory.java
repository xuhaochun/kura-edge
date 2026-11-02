package com.kura.wires.core.testsupport;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.model.WireComponent;
import com.kura.wires.model.definition.Ocd;

/**
 * 测试用组件工厂桩：创建 {@link StubWireComponent}，可配置默认端口数与接收故障注入。
 * <p>
 * 对照上游内置组件工厂（如 TimerFactory）在注册表中扮演的角色。
 * </p>
 */
public class StubWireComponentFactory implements WireComponentFactory {

    private final String factoryPid;
    private final int defaultInputPortCount;
    private final int defaultOutputPortCount;
    private final Map<String, StubWireComponent> created = new ConcurrentHashMap<>();
    private final Set<String> failingPids = ConcurrentHashMap.newKeySet();

    /**
     * 创建工厂桩。
     *
     * @param factoryPid 工厂 PID
     * @param defaultInputPortCount 默认输入端口数
     * @param defaultOutputPortCount 默认输出端口数
     */
    public StubWireComponentFactory(final String factoryPid, final int defaultInputPortCount,
            final int defaultOutputPortCount) {
        this.factoryPid = factoryPid;
        this.defaultInputPortCount = defaultInputPortCount;
        this.defaultOutputPortCount = defaultOutputPortCount;
    }

    @Override
    public String factoryPid() {
        return this.factoryPid;
    }

    @Override
    public int defaultInputPortCount() {
        return this.defaultInputPortCount;
    }

    @Override
    public int defaultOutputPortCount() {
        return this.defaultOutputPortCount;
    }

    @Override
    public Ocd getDefinition() {
        return new Ocd();
    }

    @Override
    public WireComponent create(final String pid, final Map<String, Object> properties,
            final WireEmitContext emitContext) {
        final StubWireComponent component = new StubWireComponent(pid, emitContext, this.failingPids.contains(pid));
        this.created.put(pid, component);
        return component;
    }

    /**
     * 查找本工厂已创建的组件实例。
     *
     * @param pid 组件实例 PID
     * @return 组件桩
     */
    public Optional<StubWireComponent> created(final String pid) {
        return Optional.ofNullable(this.created.get(pid));
    }

    /**
     * 注入接收故障：指定 PID 的实例接收信封时抛异常。
     *
     * @param pid 组件实例 PID
     */
    public void failOnReceive(final String pid) {
        this.failingPids.add(pid);
    }
}
