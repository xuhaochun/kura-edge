/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.driver;

import java.util.List;

import com.kura.wires.model.channel.ChannelRecord;
import com.kura.wires.model.channel.listener.ChannelListener;

/**
 * 驱动 SPI：协议适配器，负责把通道记录真正读/写到设备。
 * 对照上游 {@code org.eclipse.kura.driver.Driver}。
 * <p>
 * 与上游的差异：<b>没有</b> {@code prepareRead()}（本轮不做 PreparedRead 复用优化），
 * 读操作直接调用 {@link #read(List)}。
 * </p>
 */
public interface Driver {

    /** 驱动实例 PID 的配置键（资产据此绑定驱动），对照上游同名常量 */
    String DRIVER_PID_PROPERTY_NAME = "driver.pid";

    /** 连接异常（对照上游 {@code Driver.ConnectionException}） */
    class ConnectionException extends Exception {

        private static final long serialVersionUID = 3050873377900124238L;

        /** 创建无消息异常。 */
        public ConnectionException() {
            super();
        }

        /**
         * 创建带消息的异常。
         *
         * @param message 消息
         */
        public ConnectionException(final String message) {
            super(message);
        }

        /**
         * 创建带消息与原因的异常。
         *
         * @param message 消息
         * @param cause 原因
         */
        public ConnectionException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 连接设备。
     *
     * @throws ConnectionException 连接失败时
     */
    void connect() throws ConnectionException;

    /**
     * 断开设备。
     *
     * @throws ConnectionException 断开失败时
     */
    void disconnect() throws ConnectionException;

    /**
     * 返回本驱动的通道描述符。
     *
     * @return 通道描述符
     */
    ChannelDescriptor getChannelDescriptor();

    /**
     * 读取给定记录对应的通道值（驱动就地填充 {@code value}/{@code status}）。
     *
     * @param records 读记录
     * @throws ConnectionException 连接/IO 失败时
     */
    void read(List<ChannelRecord> records) throws ConnectionException;

    /**
     * 写入通道值（驱动就地填充 {@code status}；失败记录不得中断其余记录）。
     *
     * @param records 写记录
     * @throws ConnectionException 连接/IO 失败时
     */
    void write(List<ChannelRecord> records) throws ConnectionException;

    /**
     * 注册通道监听器（本轮资产层不调用，接口保留以便后续支持 {@code +listen}）。
     *
     * @param channelConfig 通道配置（不含通道名前缀）
     * @param listener 监听器
     * @throws ConnectionException 注册失败时
     */
    void registerChannelListener(java.util.Map<String, Object> channelConfig, ChannelListener listener)
            throws ConnectionException;

    /**
     * 注销通道监听器。
     *
     * @param listener 监听器
     * @throws ConnectionException 注销失败时
     */
    void unregisterChannelListener(ChannelListener listener) throws ConnectionException;
}
