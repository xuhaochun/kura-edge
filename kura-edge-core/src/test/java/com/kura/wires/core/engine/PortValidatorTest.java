/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.engine;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.kura.wires.core.registry.WireComponentRegistry;
import com.kura.wires.engine.WireComponentFactory;
import com.kura.wires.engine.WireEmitContext;
import com.kura.wires.exception.ErrorCode;
import com.kura.wires.exception.WiresException;
import com.kura.wires.model.ComponentConfiguration;
import com.kura.wires.model.MultiportWireConfiguration;
import com.kura.wires.model.WireComponent;
import com.kura.wires.model.WireComponentConfiguration;
import com.kura.wires.model.WireGraphConfiguration;
import com.kura.wires.model.WireGraphConstants;
import com.kura.wires.model.definition.Ocd;

/**
 * {@link PortValidator} 的行为测试：连线端点存在性与端口越界校验的正反路径
 * （端口语义与上游一致：从 0 开始，左闭右开）。
 */
public class PortValidatorTest {

    private static final String TIMER_FACTORY_PID = "org.eclipse.kura.wire.Timer";
    private static final String LOGGER_FACTORY_PID = "org.eclipse.kura.wire.Logger";

    private final List<WireComponentConfiguration> components = new ArrayList<>();
    private final List<MultiportWireConfiguration> wires = new ArrayList<>();
    private WireComponentRegistry registry;
    private PortValidator validator;
    private WireGraphConfiguration graph;

    @BeforeEach
    public void setUp() {
        this.registry = new WireComponentRegistry(List.of());
        this.validator = new PortValidator(this.registry);
    }

    @Test
    public void successfulValidationOnConsistentPorts() {
        givenFactory(TIMER_FACTORY_PID, 1, 2);
        givenFactory(LOGGER_FACTORY_PID, 2, 0);
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 2));
        givenComponent("logger", LOGGER_FACTORY_PID, Map.of(WireGraphConstants.INPUT_PORT_COUNT, 3));
        givenWire("timer", "logger", 1, 2);

        thenValidationSucceeds();
    }

    @Test
    public void successfulValidationFallsBackToFactoryDefaultsWhenPortCountsMissing() {
        givenFactory(TIMER_FACTORY_PID, 1, 2);
        givenFactory(LOGGER_FACTORY_PID, 3, 0);
        givenComponent("timer", TIMER_FACTORY_PID, Map.of());
        givenComponent("logger", LOGGER_FACTORY_PID, Map.of());
        givenWire("timer", "logger", 1, 2);

        thenValidationSucceeds();
    }

    @Test
    public void successfulValidationOnGraphWithoutWires() {
        givenComponent("timer", TIMER_FACTORY_PID, Map.of());

        thenValidationSucceeds();
    }

    @Test
    public void rejectWireWhenEmitterIsNotPartOfGraph() {
        givenComponent("logger", LOGGER_FACTORY_PID, Map.of(WireGraphConstants.INPUT_PORT_COUNT, 1));
        givenWire("timer", "logger", 0, 0);

        thenValidationFailsWithCode(ErrorCode.PORT_MISMATCH);
    }

    @Test
    public void rejectWireWhenReceiverIsNotPartOfGraph() {
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1));
        givenWire("timer", "logger", 0, 0);

        thenValidationFailsWithCode(ErrorCode.PORT_MISMATCH);
    }

    @Test
    public void rejectWireWhenEmitterPortExceedsOutputPortCount() {
        givenFactory(TIMER_FACTORY_PID, 1, 2);
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1));
        givenComponent("logger", LOGGER_FACTORY_PID, Map.of(WireGraphConstants.INPUT_PORT_COUNT, 1));
        givenWire("timer", "logger", 1, 0);

        thenValidationFailsWithCode(ErrorCode.PORT_MISMATCH);
    }

    @Test
    public void rejectWireWhenEmitterPortIsNegative() {
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1));
        givenComponent("logger", LOGGER_FACTORY_PID, Map.of(WireGraphConstants.INPUT_PORT_COUNT, 1));
        givenWire("timer", "logger", -1, 0);

        thenValidationFailsWithCode(ErrorCode.PORT_MISMATCH);
    }

    @Test
    public void rejectWireWhenReceiverPortExceedsInputPortCount() {
        givenFactory(TIMER_FACTORY_PID, 1, 2);
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1));
        givenComponent("logger", LOGGER_FACTORY_PID, Map.of(WireGraphConstants.INPUT_PORT_COUNT, 2));
        givenWire("timer", "logger", 0, 2);

        thenValidationFailsWithCode(ErrorCode.PORT_MISMATCH);
    }

    @Test
    public void rejectWireWhenReceiverPortIsNegative() {
        givenComponent("timer", TIMER_FACTORY_PID, Map.of(WireGraphConstants.OUTPUT_PORT_COUNT, 1));
        givenComponent("logger", LOGGER_FACTORY_PID, Map.of(WireGraphConstants.INPUT_PORT_COUNT, 2));
        givenWire("timer", "logger", 0, -1);

        thenValidationFailsWithCode(ErrorCode.PORT_MISMATCH);
    }

    @Test
    public void rejectWireWhenPortCountsCannotBeDetermined() {
        givenComponent("timer", TIMER_FACTORY_PID, Map.of());
        givenComponent("logger", LOGGER_FACTORY_PID, Map.of());
        givenWire("timer", "logger", 0, 0);

        thenValidationFailsWithCode(ErrorCode.PORT_MISMATCH);
    }

    @Test
    public void rejectWireWhenFactoryIsUnknownToRegistry() {
        givenComponent("timer", "unknown.factory", Map.of());
        givenComponent("logger", LOGGER_FACTORY_PID, Map.of(WireGraphConstants.INPUT_PORT_COUNT, 1));
        givenWire("timer", "logger", 0, 0);

        thenValidationFailsWithCode(ErrorCode.PORT_MISMATCH);
    }

    private void givenFactory(final String factoryPid, final int defaultInputPorts, final int defaultOutputPorts) {
        this.registry.registerFactory(new StubComponentFactory(factoryPid, defaultInputPorts, defaultOutputPorts));
    }

    private void givenComponent(final String pid, final String factoryPid, final Map<String, Object> graphProperties) {
        final ComponentConfiguration configuration = new ComponentConfiguration(pid, null,
                Map.of(WireGraphConstants.SERVICE_FACTORY_PID, factoryPid));
        this.components.add(new WireComponentConfiguration(configuration, graphProperties));
    }

    private void givenWire(final String emitterPid, final String receiverPid, final int emitterPort,
            final int receiverPort) {
        this.wires.add(new MultiportWireConfiguration(emitterPid, receiverPid, emitterPort, receiverPort));
    }

    private void thenValidationSucceeds() {
        this.graph = new WireGraphConfiguration(this.components, this.wires);
        assertThatCode(() -> this.validator.validate(this.graph)).doesNotThrowAnyException();
    }

    private void thenValidationFailsWithCode(final ErrorCode errorCode) {
        this.graph = new WireGraphConfiguration(this.components, this.wires);
        assertThatThrownBy(() -> this.validator.validate(this.graph)).isInstanceOf(WiresException.class)
                .extracting(ex -> ((WiresException) ex).getErrorCode()).isEqualTo(errorCode);
    }

    /** 测试用组件工厂桩。 */
    private static final class StubComponentFactory implements WireComponentFactory {

        private final String factoryPid;
        private final int defaultInputPortCount;
        private final int defaultOutputPortCount;

        private StubComponentFactory(final String factoryPid, final int defaultInputPortCount,
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
            return new Ocd(this.factoryPid, this.factoryPid, "stub", null);
        }

        @Override
        public WireComponent create(final String pid, final Map<String, Object> properties,
                final WireEmitContext emitContext) {
            return new StubComponent(pid);
        }
    }

    /** 测试用组件桩。 */
    private static final class StubComponent implements WireComponent {

        private final String pid;

        private StubComponent(final String pid) {
            this.pid = pid;
        }

        @Override
        public String getPid() {
            return this.pid;
        }
    }
}
