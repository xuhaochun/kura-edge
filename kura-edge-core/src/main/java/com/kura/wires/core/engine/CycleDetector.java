/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kura.wires.model.MultiportWireConfiguration;

/**
 * 有向图环检测：对整图连线做三色 DFS，检测从任一组件出发能否回到自身。
 * <p>
 * 上游只在 GWT 前端 {@code wires_composer.js#checkForCycleExistence} 拦截环，
 * REST 可绕过前端，因此引擎必须独立兜底（Rules 08 §4），算法语义与前端一致。
 * </p>
 */
public final class CycleDetector {

    /** DFS 访问状态：未访问 / 访问中（在递归栈上） / 已完成 */
    private static final int WHITE = 0;
    private static final int GREY = 1;
    private static final int BLACK = 2;

    private CycleDetector() {
        // 工具类禁止实例化
    }

    /**
     * 判断连线集合构成的组件有向图是否含有环（含自环；同一对端口的重复连线只算一条边）。
     *
     * @param wires 整图连线
     * @return 存在环时为 true
     */
    public static boolean hasCycle(final Collection<MultiportWireConfiguration> wires) {
        final Map<String, Set<String>> adjacency = buildAdjacency(wires);
        final Map<String, Integer> states = new HashMap<>();
        for (final String pid : adjacency.keySet()) {
            states.put(pid, WHITE);
        }
        for (final String pid : adjacency.keySet()) {
            if (states.get(pid) == WHITE && reachesCycleFrom(pid, adjacency, states)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Set<String>> buildAdjacency(final Collection<MultiportWireConfiguration> wires) {
        final Map<String, Set<String>> adjacency = new HashMap<>();
        for (final MultiportWireConfiguration wire : wires) {
            if (wire == null || wire.getEmitterPid() == null || wire.getReceiverPid() == null) {
                continue;
            }
            adjacency.computeIfAbsent(wire.getEmitterPid(), key -> new HashSet<>()).add(wire.getReceiverPid());
            adjacency.computeIfAbsent(wire.getReceiverPid(), key -> new HashSet<>());
        }
        return adjacency;
    }

    private static boolean reachesCycleFrom(final String start, final Map<String, Set<String>> adjacency,
            final Map<String, Integer> states) {
        // 迭代式三色 DFS：压栈时染 GREY，弹栈时染 BLACK
        final Deque<String> stack = new LinkedList<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            final String current = stack.peek();
            if (states.get(current) == GREY) {
                states.put(current, BLACK);
                stack.pop();
                continue;
            }
            states.put(current, GREY);
            for (final String next : adjacency.getOrDefault(current, Set.of())) {
                final int state = states.getOrDefault(next, WHITE);
                if (state == GREY) {
                    return true;
                }
                if (state == WHITE) {
                    stack.push(next);
                }
            }
        }
        return false;
    }

    /**
     * 返回构成环检测输入的邻接表（供测试与诊断）。
     *
     * @param wires 整图连线
     * @return 组件 PID → 下游 PID 集合
     */
    static Map<String, Set<String>> toAdjacency(final Collection<MultiportWireConfiguration> wires) {
        return new HashMap<>(buildAdjacency(wires));
    }

    /**
     * 仅供测试断言使用：把邻接表按键排序后展开（避免依赖 HashMap 顺序）。
     *
     * @param adjacency 邻接表
     * @return 排序后的邻接表副本
     */
    static List<String> flatten(final Map<String, Set<String>> adjacency) {
        final List<String> result = new ArrayList<>();
        adjacency.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.add(entry.getKey() + "->" + new HashSet<>(entry.getValue())));
        return result;
    }
}
