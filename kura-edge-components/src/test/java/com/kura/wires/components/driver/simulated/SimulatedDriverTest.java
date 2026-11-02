/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.driver.simulated;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.kura.wires.model.channel.ChannelFlag;
import com.kura.wires.model.channel.ChannelRecord;
import com.kura.wires.model.type.DataType;
import com.kura.wires.model.type.TypedValues;
import org.junit.jupiter.api.Test;

/**
 * 模拟驱动的行为测试：读零值、写入后读回、写入按寄存器隔离、类型转换与连接状态。
 */
public class SimulatedDriverTest {

    private static final String REGISTER = SimulatedChannelDescriptor.REGISTER_PARAM;

    private SimulatedDriver driver;
    private List<ChannelRecord> records;

    @Test
    public void readZeroValueWhenRegisterWasNeverWritten() {
        givenDriver();
        this.records = List.of(readRecord("ch", "reg-a", DataType.INTEGER));

        read();

        assertThat(this.records.get(0).getValue().getValue()).isEqualTo(0);
        assertThat(this.records.get(0).getChannelStatus().getChannelFlag()).isEqualTo(ChannelFlag.SUCCESS);
    }

    @Test
    public void readBackValueWrittenToSameRegister() {
        givenDriver();
        final ChannelRecord writable = ChannelRecord.createWriteRecord("ch", DataType.INTEGER,
                TypedValues.newIntegerValue(42));
        writable.setChannelConfig(Map.of(REGISTER, "reg-a"));

        write(List.of(writable));
        this.records = List.of(readRecord("ch", "reg-a", DataType.INTEGER));
        read();

        assertThat(this.records.get(0).getValue().getValue()).isEqualTo(42);
    }

    @Test
    public void keepRegistersIsolated() {
        givenDriver();
        final ChannelRecord writable = ChannelRecord.createWriteRecord("ch", DataType.INTEGER,
                TypedValues.newIntegerValue(7));
        writable.setChannelConfig(Map.of(REGISTER, "reg-a"));

        write(List.of(writable));
        this.records = List.of(readRecord("other", "reg-b", DataType.INTEGER));
        read();

        assertThat(this.records.get(0).getValue().getValue()).isEqualTo(0);
    }

    @Test
    public void convertStoredNumberToRequestedReadType() {
        givenDriver();
        final ChannelRecord writable = ChannelRecord.createWriteRecord("ch", DataType.INTEGER,
                TypedValues.newIntegerValue(3));
        writable.setChannelConfig(Map.of(REGISTER, "reg-a"));

        write(List.of(writable));
        this.records = List.of(readRecord("ch", "reg-a", DataType.DOUBLE));
        read();

        assertThat(this.records.get(0).getValue().getValue()).isEqualTo(3.0d);
    }

    @Test
    public void useDefaultRegisterWhenChannelHasNoParameter() {
        givenDriver();
        final ChannelRecord record = readRecord("ch", null, DataType.INTEGER);
        this.records = List.of(record);

        read();

        assertThat(record.getChannelStatus().getChannelFlag()).isEqualTo(ChannelFlag.SUCCESS);
    }

    @Test
    public void connectOnFirstUse() {
        givenDriver();

        assertThat(this.driver.isConnected()).isFalse();
        readRecord("ch", "reg-a", DataType.STRING);
        this.records = List.of(readRecord("ch", "reg-a", DataType.STRING));
        read();
        assertThat(this.driver.isConnected()).isTrue();

        this.driver.disconnect();
        assertThat(this.driver.isConnected()).isFalse();
    }

    @Test
    public void exposeChannelDescriptorWithSimulatedRegisterParameter() {
        givenDriver();

        final Object descriptor = this.driver.getChannelDescriptor().getDescriptor();
        assertThat(descriptor).isInstanceOf(List.class);
        assertThat((List<?>) descriptor).extracting(item -> ((com.kura.wires.model.definition.Ad) item).getId())
                .containsExactly(REGISTER);
        assertThat(this.driver.getPid()).isEqualTo("driver-1");
        assertThat(this.driver.getProperties()).containsEntry("simulated.description", "demo");
    }

    private void givenDriver() {
        this.driver = new SimulatedDriver("driver-1", Map.of("simulated.description", "demo"));
    }

    private ChannelRecord readRecord(final String channelName, final String register, final DataType dataType) {
        final ChannelRecord record = ChannelRecord.createReadRecord(channelName, dataType);
        if (register != null) {
            record.setChannelConfig(Map.of(REGISTER, register));
        }
        return record;
    }

    private void read() {
        try {
            this.driver.read(this.records);
        } catch (final com.kura.wires.model.driver.Driver.ConnectionException e) {
            throw new IllegalStateException(e);
        }
    }

    private void write(final List<ChannelRecord> toWrite) {
        try {
            this.driver.write(toWrite);
        } catch (final com.kura.wires.model.driver.Driver.ConnectionException e) {
            throw new IllegalStateException(e);
        }
    }
}
