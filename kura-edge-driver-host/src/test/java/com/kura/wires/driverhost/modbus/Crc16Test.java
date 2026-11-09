/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 ******************************************************************************/
package com.kura.wires.driverhost.modbus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** CRC-16：用 Modbus 规范里的已知帧锁定查表算法。 */
class Crc16Test {

    @Test
    public void computeKnownModbusFrameCrc() {
        final byte[] frame = {0x01, 0x03, 0x00, 0x00, 0x00, 0x0A};

        final int crc = Crc16.getCrc16(frame, frame.length, 0xffff);

        // 已知值：01 03 00 00 00 0A 的 Modbus CRC 在线序上为 C5 CD
        assertThat(crc).isEqualTo(0xCDC5);
    }

    @Test
    public void computeEmptyBufferAsSeed() {
        assertThat(Crc16.getCrc16(new byte[0], 0, 0xffff)).isEqualTo(0xffff);
    }

    @Test
    public void respectBufferLength() {
        final byte[] frame = {0x01, 0x03, 0x00, 0x00, 0x00, 0x0A, 0x7f};

        assertThat(Crc16.getCrc16(frame, 6, 0xffff)).isEqualTo(Crc16.getCrc16(frame, 6, 0xffff));
        assertThat(Crc16.getCrc16(frame, 6, 0xffff)).isNotEqualTo(Crc16.getCrc16(frame, 7, 0xffff));
    }
}
