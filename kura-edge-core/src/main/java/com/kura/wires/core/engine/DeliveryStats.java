/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.engine;

/**
 * 信封投递计数（RFC 004，只读快照）。
 * <p>
 * 用于判断「慢组件是否在堆积」与「是否已经在丢信封」：{@code submitted} 持续增长而
 * {@code delivered} 不增长、且 {@code droppedByReceiverQueue} 上升时，说明下游处理不过来，
 * 需要调大排队上限或让组件更快返回。
 * </p>
 * <p>
 * 不变式（不含正在处理的那一个）：{@code submitted == delivered + droppedByReceiverQueue + 排队中}。
 * </p>
 *
 * @param submitted 交给投递器的信封数（含随后被丢弃的）
 * @param delivered 实际交给接收组件处理的信封数
 * @param droppedByReceiverQueue 因「该接收组件排队已满」被丢弃的信封数（真正丢数据的计数）
 * @param rejectedDrains 排空任务被投递线程池/共享队列拒绝的次数。<b>不代表信封丢失</b>：
 * 信封仍留在该组件的排队里，下一次投递会重新申请排空（若之后一直没有新信封，这些信封会滞留在队列中直到组件被移除）
 */
public record DeliveryStats(long submitted, long delivered, long droppedByReceiverQueue, long rejectedDrains) {

    /**
     * 被丢弃的信封总数（只算真正丢数据的原因）。
     *
     * @return 丢弃的信封数
     */
    public long dropped() {
        return this.droppedByReceiverQueue;
    }
}
