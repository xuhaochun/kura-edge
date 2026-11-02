/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.kura.wires.model.MultiportWireConfiguration;

/**
 * {@link CycleDetector} 的行为测试：环检测正反路径（对照上游
 * {@code wires_composer.js#checkForCycleExistence} 的语义）。
 */
public class CycleDetectorTest {

    private List<MultiportWireConfiguration> wires = new ArrayList<>();

    @Test
    public void successfulDetectionOnDirectLoop() {
        givenWire("a", "b");
        givenWire("b", "a");

        thenCycleIsDetected();
    }

    @Test
    public void successfulDetectionOnSelfLoop() {
        givenWire("a", "a");

        thenCycleIsDetected();
    }

    @Test
    public void successfulDetectionOnLongerCycle() {
        givenWire("a", "b");
        givenWire("b", "c");
        givenWire("c", "a");

        thenCycleIsDetected();
    }

    @Test
    public void successfulDetectionOnCycleHiddenInDisconnectedSubgraph() {
        givenWire("a", "b");
        givenWire("x", "y");
        givenWire("y", "z");
        givenWire("z", "x");

        thenCycleIsDetected();
    }

    @Test
    public void successfulAcyclicChainHasNoCycle() {
        givenWire("a", "b");
        givenWire("b", "c");

        thenNoCycleIsDetected();
    }

    @Test
    public void successfulAcyclicDiamondHasNoCycle() {
        givenWire("a", "b");
        givenWire("a", "c");
        givenWire("b", "d");
        givenWire("c", "d");

        thenNoCycleIsDetected();
    }

    @Test
    public void successfulDuplicateEdgesDoNotFormCycle() {
        givenWire("a", "b");
        givenWire("a", "b");

        thenNoCycleIsDetected();
    }

    @Test
    public void successfulEmptyGraphHasNoCycle() {
        thenNoCycleIsDetected();
    }

    @Test
    public void successfulIncompleteWiresAreIgnored() {
        final MultiportWireConfiguration withoutEmitter = new MultiportWireConfiguration("a", "b", 0, 0);
        withoutEmitter.setEmitterPid(null);
        final MultiportWireConfiguration withoutReceiver = new MultiportWireConfiguration("a", "b", 0, 0);
        withoutReceiver.setReceiverPid(null);
        this.wires.add(null);
        this.wires.add(withoutEmitter);
        this.wires.add(withoutReceiver);

        thenNoCycleIsDetected();
    }

    @Test
    public void successfulAdjacencyExposureForDiagnostics() {
        givenWire("a", "b");
        givenWire("a", "b");
        givenWire("b", "c");

        final List<String> flattened = CycleDetector.flatten(CycleDetector.toAdjacency(this.wires));

        assertThat(flattened).containsExactly("a->[b]", "b->[c]", "c->[]");
    }

    private void givenWire(final String emitterPid, final String receiverPid) {
        this.wires.add(new MultiportWireConfiguration(emitterPid, receiverPid, 0, 0));
    }

    private void thenCycleIsDetected() {
        assertThat(CycleDetector.hasCycle(this.wires)).isTrue();
    }

    private void thenNoCycleIsDetected() {
        assertThat(CycleDetector.hasCycle(this.wires)).isFalse();
    }
}
