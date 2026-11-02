# 测试：框架、命名约定、覆盖率（Rules 06）

## 后端

- 框架：JUnit 5 + Mockito + AssertJ；架构守护 ArchUnit；覆盖率 JaCoCo。
- 测试类与被测类同包，命名 `XxxTest`；一个测试类只覆盖一个特性（上游约定），特性复杂时拆多个类。

### Gherkin 风格（强制，沿用上游 AGENTS.md）

```java
@Test
public void rejectGraphWhenCycleDetected() {
    givenEngineWithTwoComponents();
    givenWireFromReceiverBackToEmitter();

    whenUpdatingGraph();

    thenUpdateFailsWithCode(ErrorCode.TOPOLOGY_CYCLE);
}
```

- 场景方法：`public void`，无参数，名字描述行为，正反路径都要覆盖。
- 步骤方法：`private void`，`given` / `when` / `then` 前缀，按顺序分组、组间空行；
  步骤不返回值，断言写在 `then` 步骤里；中间状态用测试类字段保存。

### 测试反模式（评审必查）

三条坏味道，`wires-code-review` 的 Standards 轴会逐条对照：

- **实现耦合（implementation-coupled）**：mock 内部协作者、反射调私有方法、绕过接口直接查库断言。
  识别线：**行为没变、只是重构，测试就红了** → 测试写在了错的 seam 上。
- **同义反复（tautological）**：断言用与被测代码相同的算法重算期望值（`assertEquals(a + b, add(a, b))`、
  按同样逻辑手工推导出的常量、字面量断言等于自身、手工按同样方式生成的快照）。
  识别线：**这个断言在任何实现下都不可能红**。期望值必须来自独立事实源：上游 Kura 的行为、
  已知正确字面量、规格文档、导出的快照 JSON 原文。
- **横向切分（horizontal slicing）**：先把所有测试写完再写实现。批量写出的测试验证的是**想象中**的行为，
  会锁死测试结构、对真实变化不敏感。按垂直切片推进：一个测试 → 一个实现 → 再下一个。

覆盖率门槛不是目标函数：**为了凑数字去给 getter/setter 补无断言测试，等同于违反本节。**

### 测试 seam 必须预先约定（M/L 级强制）

seam = 可观行为的公共边界：Service 接口、Controller 端点、引擎的 `validate()` / `get()` / `update()` / `delete()`。

M/L 变更在 RFC 里必须列出「本次测哪些 seam、各自覆盖什么、不覆盖什么」；未列入的 seam 不写测试
（无法穷尽，先约定才能把力气花在关键路径和复杂逻辑上）。判断不了 seam 在哪时，
先用 `wires-graph-domain` / `wires-rest-api` 等技能把模块形状确认清楚。

### 覆盖率门槛

| 模块 | 行覆盖率 |
|---|---|
| kura-edge-core | ≥ 85%（图引擎、环检测、快照是核心资产） |
| kura-edge-api / components | ≥ 80% |
| kura-edge-rest | ≥ 70%（Controller 以 WebMvcTest 覆盖） |

不达标 `mvn verify` 即失败（Jacoco check 规则配在父 POM）。

### 必测场景（来自上游行为）

- 环检测（上游前端 `checkForCycleExistence` + 引擎侧校验，后端必须兜底）
- 端口校验：emitterPort ≤ outputPortCount、receiverPort ≤ inputPortCount
- 快照保存/加载往返一致；上游 Kura 快照 JSON 导入
- 图更新时组件重建顺序（stop → reconnect → start，对照 `WireGraphServiceImpl.refresh`）

## 前端

- 框架：Vitest + Vue Test Utils；文件 `*.spec.ts` 与被测文件同目录。
- 组件：测试渲染与关键交互（拖拽建线、删除组件联动删线、表单校验）；`useGraphValidation` 等组合式函数必须有纯逻辑单测（环检测、端口匹配）。
- store：`wiresGraph` store 的增删改与快照序列化往返测试。
- 覆盖率门槛：statements ≥ 70%，`src/composables` 与 `src/stores` ≥ 80%。

## 集成测试（二阶段）

- 后端用 `@SpringBootTest` + MockMvc 走通「建图 → 保存快照 → 重启加载 → 删除」全链路；
  测试配置用 H2 内存库 + 禁用安全或测试账号。
