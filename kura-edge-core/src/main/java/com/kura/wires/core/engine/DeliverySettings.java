/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.core.engine;

/**
 * 信封投递的并发参数（RFC 004）。
 * <p>
 * 默认值面向边缘设备：线程数 {@code max(4, CPU×2)}、共享队列 512、每个接收组件排队上限 256。
 * 三个值都可用系统属性覆盖，便于现场调参：
 * </p>
 * <ul>
 * <li>{@code wires.engine.delivery.threads}</li>
 * <li>{@code wires.engine.delivery.queue-capacity}</li>
 * <li>{@code wires.engine.delivery.receiver-queue-capacity}</li>
 * </ul>
 *
 * @param threads 投递线程池的固定线程数（必须 &gt; 0）
 * @param poolQueueCapacity 共享队列容量（必须 &gt; 0）
 * @param receiverQueueCapacity 每个接收组件的排队上限（必须 &gt; 0）
 */
public record DeliverySettings(int threads, int poolQueueCapacity, int receiverQueueCapacity) {

    /** 投递线程数系统属性 */
    public static final String THREADS_PROPERTY = "wires.engine.delivery.threads";

    /** 共享队列容量系统属性 */
    public static final String QUEUE_CAPACITY_PROPERTY = "wires.engine.delivery.queue-capacity";

    /** 每接收组件排队上限系统属性 */
    public static final String RECEIVER_QUEUE_CAPACITY_PROPERTY = "wires.engine.delivery.receiver-queue-capacity";

    private static final int DEFAULT_POOL_QUEUE_CAPACITY = 512;

    private static final int DEFAULT_RECEIVER_QUEUE_CAPACITY = 256;

    /**
     * 校验参数。
     *
     * @throws IllegalArgumentException 任一参数不为正
     */
    public DeliverySettings {
        if (threads <= 0 || poolQueueCapacity <= 0 || receiverQueueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "Delivery settings must be positive: threads=" + threads + ", poolQueueCapacity="
                            + poolQueueCapacity + ", receiverQueueCapacity=" + receiverQueueCapacity);
        }
    }

    /**
     * 生产默认参数（允许系统属性覆盖）。
     *
     * @return 投递参数
     */
    public static DeliverySettings fromSystemProperties() {
        return new DeliverySettings(
                positiveProperty(THREADS_PROPERTY, defaultThreads()),
                positiveProperty(QUEUE_CAPACITY_PROPERTY, DEFAULT_POOL_QUEUE_CAPACITY),
                positiveProperty(RECEIVER_QUEUE_CAPACITY_PROPERTY, DEFAULT_RECEIVER_QUEUE_CAPACITY));
    }

    /**
     * 测试/注入执行器时使用的默认参数（线程数与队列由外部执行器决定，仅排队上限生效）。
     *
     * @return 投递参数
     */
    public static DeliverySettings defaults() {
        return new DeliverySettings(defaultThreads(), DEFAULT_POOL_QUEUE_CAPACITY, DEFAULT_RECEIVER_QUEUE_CAPACITY);
    }

    private static int defaultThreads() {
        return Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
    }

    private static int positiveProperty(final String key, final int fallback) {
        final int value = Integer.getInteger(key, fallback);
        return value > 0 ? value : fallback;
    }
}
