/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.engine;

import com.kura.wires.exception.WiresException;
import com.kura.wires.model.WireGraphConfiguration;

/**
 * 整图服务 SPI，对照上游 {@code org.eclipse.kura.wire.graph.WireGraphService}。
 * <p>
 * 实现在 {@code kura-edge-core} 的 {@code WireGraphEngine}；update 必须先做
 * 环检测与端口校验，再按「删除 → 重建连线 → 新增 → 刷新」的顺序原子生效
 * （对照 {@code WireGraphServiceImpl.update}）。
 * </p>
 */
public interface WireGraphService {

    /**
     * 返回当前整图配置。
     *
     * @return 当前图配置
     * @throws WiresException 引擎内部错误时
     */
    WireGraphConfiguration get() throws WiresException;

    /**
     * 创建 / 更新整图（校验 → diff → 重建 → 快照落库）。
     *
     * @param graphConfiguration 新图配置
     * @throws WiresException 拓扑/端口校验失败（409）或内部错误（500）时
     */
    void update(WireGraphConfiguration graphConfiguration) throws WiresException;

    /**
     * 删除整图（删除全部连线与组件配置，快照置空）。
     *
     * @throws WiresException 引擎内部错误时
     */
    void delete() throws WiresException;
}
