/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.type;

/**
 * 类型化取值的基类，对照上游 {@code org.eclipse.kura.type.TypedValue}。
 * <p>
 * {@code WireRecord} 的属性值必须是本类型的实例，以保证快照导入后的行为
 * 与上游一致（对照 {@code org.eclipse.kura.wire.WireRecord}）。
 * </p>
 *
 * @param <T> 底层 Java 类型
 */
public abstract class TypedValue<T> {

    /** 底层取值 */
    protected final T value;

    protected TypedValue(final T value) {
        this.value = value;
    }

    /**
     * 返回底层 Java 取值。
     *
     * @return 底层取值
     */
    public T getValue() {
        return this.value;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final TypedValue<?> other = (TypedValue<?>) obj;
        return java.util.Objects.equals(this.value, other.value);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(this.value);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" + this.value + "}";
    }
}
