/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.model.definition;

/**
 * 组件配置属性的类型标量，对照上游 metatype {@code org.eclipse.kura.configuration.metatype.Scalar}。
 * <p>
 * 快照/配置 JSON 中的 {@code type} 字段必须使用本枚举名（如 {@code STRING}），
 * 与上游导入保持兼容。
 * </p>
 */
public enum Scalar {

    /** 布尔 */
    BOOLEAN,
    /** 字节 */
    BYTE,
    /** 字符 */
    CHAR,
    /** 双精度浮点 */
    DOUBLE,
    /** 单精度浮点 */
    FLOAT,
    /** 整数 */
    INTEGER,
    /** 长整数 */
    LONG,
    /** 短整数 */
    SHORT,
    /** 字符串 */
    STRING,
    /** 密码（返回前端前必须脱敏为占位符） */
    PASSWORD,
    /** 引脚号 */
    PIN
}
