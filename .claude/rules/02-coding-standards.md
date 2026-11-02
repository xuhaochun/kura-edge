# 编码规范：命名、格式、注释（Rules 02）

## 命名

| 对象 | 规则 | 示例 |
|---|---|---|
| 类/接口/枚举 | UpperCamelCase | `WireGraphEngine`、`RegexFilter` |
| 方法/变量 | lowerCamelCase | `updateGraph`、`emitterPort` |
| 常量 | UPPER_SNAKE_CASE | `WIRE_GRAPH_PROPERTY` |
| 包名 | 全小写、单词不复数 | `com.kura.wires.core.engine` |
| 测试类 | `XxxTest` / `XxxArchitectureTest` | `WireGraphEngineTest` |
| 测试方法 | 行为式英文小驼峰（沿用上游 Gherkin 风格） | `rejectGraphWhenCycleDetected` |
| 私有测试步骤 | given/when/then 前缀 | `givenValidConfiguration()` |
| Vue 组件 | 多词 PascalCase 文件名 | `WiresComposer.vue`、`PortHandle.vue` |
| composables | `use` 前缀小驼峰 | `useGraphValidation.ts` |
| TS 类型/接口 | PascalCase，不加 `I` 前缀 | `WireGraphConfig` |
| 前端常量 | UPPER_SNAKE_CASE | `MAX_PORT_COUNT` |

**保留上游命名以便对照**：从上游移植的类，类名与核心方法名尽量保持一致
（如 `WireEnvelope`、`WireRecord`、`get()` / `update()` / `delete()`），
包名从 `org.eclipse.kura.*` 改为 `com.kura.wires.*`。`internal` 包语义变为 `core`/实现包。

## 格式

- 行长度 ≤ 150 字符（沿用上游）；缩进：Java 4 空格，Vue/TS 2 空格。
- 换行符 LF，文件编码 UTF-8；禁止提交 CRLF、尾随空格、无末尾换行的文件。
- import：禁止 `import *` 与未使用 import（Java 由 Checkstyle 把关，配置随父 POM；前端由 ESLint）。
- Java：`final` 局部变量/参数尽量加（上游风格）；字段注入改构造器注入。
- Vue：模板 → `<script setup>` → `<style scoped>` 顺序；一个组件一个文件。

## 注释

- 公共 API（api 模块全部接口、rest 对外 DTO）必须有中文 Javadoc / TSDoc，
  说明语义、端口约束、与上游对应关系（如「对照 org.eclipse.kura.wire.graph.WireGraphService」）。
- 从上游移植的代码**保留原有注释**（版权头改为本项目 + 注明基于 EPL-2.0 上游代码修改）。
- 不写口水注释（`// 获取用户`）；注释解释 **为什么**，不解释 **是什么**。
- 上游约定「不加注释除非要求」，本项目放宽为：仅公共 API 与复杂算法（环检测、端口聚合）要求注释。
- TODO 格式：`// TODO(姓名): 描述`，禁止无主 TODO。
