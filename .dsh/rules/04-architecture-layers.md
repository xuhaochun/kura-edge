# 分层架构及模块依赖规则（Rules 04）

## 模块依赖方向（单向，禁止反向与循环）

```
kura-edge-rest ──► kura-edge-core ──► kura-edge-api
       │                                 ▲
       └──► kura-edge-components ────────┘
kura-edge-components ──► kura-edge-core（仅当组件需要引擎内部 SPI 时，经 core 暴露的接口）
kura-edge-release-server ──► kura-edge-api（**仅**取更新协议的共享契约，不许依赖 core/components/rest）
```

- `kura-edge-api`：只依赖 JDK + 少量公共库（Jackson 注解可）。**禁止**依赖 Spring、JPA、任何其他模块。
- `kura-edge-core`：实现图引擎与持久化，可依赖 Spring/JPA；**禁止**依赖 rest、components。
- `kura-edge-components`：实现具体 Wire 组件，可依赖 api 与 core 暴露的 SPI；**禁止**依赖 rest。
- `kura-edge-rest`：唯一**业务** Spring Boot 启动模块，聚合 core + components；业务 Controller 只允许在这里。
- `kura-edge-release-server`（RFC 009）：第二个可执行模块，但**不是业务装配**——8081 端口只提供 jar 下载
  与 `feed.json`，禁止依赖 core/components/rest、禁止读写数据库、禁止出现任何图/组件相关逻辑。
  它之所以存在，是因为设备要的是"算好的清单"，而不是一个目录列表。

用 ArchUnit 测试在 `kura-edge-rest/src/test` 固化以上规则（`class ArchitectureTest`），
`mvn verify` 必须包含依赖检查，违反即构建失败。

## 请求处理分层（对照上游 WireRestService 的职责切分）

```
Controller（rest）        只做：DTO ⇄ 领域模型转换、Bean Validation、权限注解、调 Service
    ↓
Service（core）           图校验（环检测/端口匹配）、图生命周期（build/refresh/delete）、快照读写
    ↓
Repository（core）        JPA 持久化；禁止 Controller/Service 之外的层触碰
    ↓
Component 实例（components） WireComponent.onWireReceive 消息处理，不感知数据库与 HTTP
```

- Controller 方法体超过 ~30 行说明业务下沉不够，把逻辑移入 Service。
- 领域模型（api）不得引用 DTO（rest）；DTO 不得作为 Service 入参，转换在 Controller 层完成。
- 异常从 Service 抛领域异常（`WiresException` + `ErrorCode`），由 rest 的 `GlobalExceptionHandler` 统一转 HTTP 响应；组件内部异常由引擎捕获记录，不中断整图运行（对照上游组件行为）。
- 事件/消息传递：组件间通过 `WireEnvelope`（不可变对象）直接方法调用传递；引擎负责按 `MultiportWireConfiguration` 路由到 `receiverPort`。
