package com.kura.wires.model.type;

import static org.assertj.core.api.Assertions.assertThat;


import org.junit.jupiter.api.Test;

/**
 * TypedValue 类型系统行为验证。
 */
class TypedValueTest {

    @Test
    void returnWrappedPrimitiveValue() {
        assertThat(new IntegerValue(42).getValue()).isEqualTo(42);
        assertThat(new LongValue(7L).getValue()).isEqualTo(7L);
        assertThat(new DoubleValue(1.5d).getValue()).isEqualTo(1.5d);
        assertThat(new FloatValue(0.5f).getValue()).isEqualTo(0.5f);
        assertThat(new BooleanValue(true).getValue()).isTrue();
        assertThat(new ByteValue((byte) 3).getValue()).isEqualTo((byte) 3);
        assertThat(new CharValue('x').getValue()).isEqualTo('x');
        assertThat(new StringValue("s").getValue()).isEqualTo("s");
    }

    @Test
    void beEqualOnlyForSameTypeAndValue() {
        assertThat(new IntegerValue(1)).isEqualTo(new IntegerValue(1));
        assertThat(new IntegerValue(1)).isNotEqualTo(new IntegerValue(2));
        // 同值不同类型不相等
        assertThat(new IntegerValue(1)).isNotEqualTo(new LongValue(1L));
        assertThat(new IntegerValue(1)).hasSameHashCodeAs(new IntegerValue(1));
    }

    @Test
    void exposeReadableToString() {
        assertThat(new StringValue("abc").toString()).contains("StringValue").contains("abc");
    }

    @Test
    void copyByteArrayOnCreateAndRead() {
        final byte[] source = { 1, 2, 3 };
        final ByteArrayValue value = new ByteArrayValue(source);
        source[0] = 99;
        // 创建时拷贝，外部修改不影响内部值
        assertThat(value.getValue()[0]).isEqualTo((byte) 1);
        // 读取时拷贝，修改返回数组不影响内部值
        final byte[] read = value.getValue();
        read[0] = 42;
        assertThat(value.getValue()[0]).isEqualTo((byte) 1);
        assertThat(value).isEqualTo(new ByteArrayValue(new byte[] { 1, 2, 3 }));
    }

    @Test
    void handleNullByteArrayAsEmpty() {
        assertThat(new ByteArrayValue(null).getValue()).isEmpty();
        assertThat(new ByteArrayValue(null)).isEqualTo(new ByteArrayValue(new byte[0]));
        assertThat(new ByteArrayValue(new byte[] { 1 }).hashCode())
                .isEqualTo(new ByteArrayValue(new byte[] { 1 }).hashCode());
    }

    @Test
    void handleTypedValueEqualityBranches() {
        final IntegerValue value = new IntegerValue(3);
        assertThat(value).isEqualTo(value);
        assertThat(value).isNotEqualTo(null);
        assertThat(value).isNotEqualTo("3");
        assertThat(new StringValue("x").toString()).contains("x");
    }
}
