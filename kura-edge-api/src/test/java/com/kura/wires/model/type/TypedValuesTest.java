/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.type;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

/**
 * 通道取值类型转换的行为测试：对照上游 {@code TypedValues.parseTypedValue} 的转换规则
 * （BOOLEAN 宽松解析、BYTE_ARRAY 标准 base64、数值型各自 parse）。
 */
public class TypedValuesTest {

    private TypedValue<?> parsed;
    private String value;

    @Test
    public void parseBooleanLenientlyWhenValueIsNotTrue() {
        givenRawValue("yes");

        whenParsing(DataType.BOOLEAN);

        thenParsedBooleanIs(false);
    }

    @Test
    public void parseByteArrayFromBase64() {
        givenRawValue(Base64.getEncoder().encodeToString(new byte[] { 0, 1, 2 }));

        whenParsing(DataType.BYTE_ARRAY);

        thenParsedBytesAre(new byte[] { 0, 1, 2 });
    }

    @Test
    public void parseNumericTypes() {
        givenRawValue("42");

        whenParsing(DataType.INTEGER);

        thenParsedNumberIs(42);
    }

    @Test
    public void parseLongAndFloatAndDouble() {
        assertThat(TypedValues.parseTypedValue(DataType.LONG, "42").getValue()).isEqualTo(42L);
        assertThat(TypedValues.parseTypedValue(DataType.FLOAT, "1.5").getValue()).isEqualTo(1.5f);
        assertThat(TypedValues.parseTypedValue(DataType.DOUBLE, "1.5").getValue()).isEqualTo(1.5d);
        assertThat(TypedValues.parseTypedValue(DataType.STRING, "x").getValue()).isEqualTo("x");
    }

    @Test
    public void rejectValueThatCannotBeConverted() {
        givenRawValue("not-a-number");

        assertThatThrownBy(() -> TypedValues.parseTypedValue(DataType.INTEGER, this.value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be converted into a TypedValue of type INTEGER");
    }

    @Test
    public void rejectNullType() {
        assertThatThrownBy(() -> TypedValues.parseTypedValue(null, "1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void renderBytesAsBase64WhenConvertingToString() {
        final byte[] bytes = { 9, 8, 7 };

        assertThat(TypedValues.toStringValue(TypedValues.newByteArrayValue(bytes)))
                .isEqualTo(Base64.getEncoder().encodeToString(bytes));
        assertThat(TypedValues.toStringValue(TypedValues.newIntegerValue(7))).isEqualTo("7");
        assertThat(TypedValues.toStringValue(null)).isNull();
    }

    private void givenRawValue(final String rawValue) {
        this.value = rawValue;
    }

    private void whenParsing(final DataType type) {
        this.parsed = TypedValues.parseTypedValue(type, this.value);
    }

    private void thenParsedBooleanIs(final boolean expected) {
        assertThat(this.parsed.getValue()).isEqualTo(expected);
    }

    private void thenParsedBytesAre(final byte[] expected) {
        assertThat((byte[]) this.parsed.getValue()).isEqualTo(expected);
    }

    private void thenParsedNumberIs(final int expected) {
        assertThat(this.parsed.getValue()).isEqualTo(expected);
    }
}
