---
name: wires-rest-api
description: Wires REST 层实现指南：对照上游 WireRestService 设计 Controller、DTO、错误处理与安全。
---

# Wires REST API Skill

## When to Use

- 在 `kura-edge-rest` 新增/修改 Controller、DTO、全局异常处理、安全配置时
- 需要对照上游端点行为、请求/响应结构、校验规则时
- 不用于：引擎内部逻辑（见 wires-graph-domain）

## 上游参考（只读）

- `/home/idea/IdeaProjects/kura-wires/bundles/org.eclipse.kura.rest.wire.provider/src/main/java/org/eclipse/kura/internal/rest/wire/WireRestService.java`
  —— 全部端点、权限（`wires.admin`）、校验流程、FailureHandler 聚合错误模式
- 同 bundle `org/eclipse/kura/rest/wire/api/` —— DTO 形态（WireGraphMetadata、WireComponentDefinitionDTO、DriverDescriptorDTO）
- 配置 DTO 复用：`kura` 仓库 `org.eclipse.kura.rest.configuration.provider` 的 DTOUtil（密码脱敏、PropertyDTO 的 type/value 结构）
- 端点 → kura-edge 映射表见 `.claude/rules/07-restful-and-error-codes.md`，以该表为准

## 上游关键行为（必须等价实现）

1. `PUT /graph/snapshot`：先 `configs.validate()`，校验每个 config 的 factoryPid 属于
   Driver/Asset/WireComponent 工厂；WireGraphService 的配置里必须含 STRING 型 `WireGraph` 属性；
   图更新失败若是 BAD_REQUEST 直接抛，其他失败进 FailureHandler；组件配置更新完再 snapshot。
   （多步顺序与"聚合失败、不留半新半旧"的通用要求见 rules/08 §3，此处不重复。）
2. `GET /graph/snapshot`：图服务配置 + 图内组件配置 + 全部 Driver 配置，去除 definition，密码脱敏。
3. `DELETE /configs/byPid`：pid 被图引用 → 400；不是 Driver/Asset/WireComponent 实例 → 400。
4. `PUT /configs`：WireComponent 配置若未被图引用 → 400。
5. `GET /metadata`：一次性返回组件定义 + Driver 定义 + Driver 通道描述 + 基础通道描述（前端面板数据源）。
6. 权限：所有端点 `wires.admin` 角色。

## Procedure（新增一个端点）

1. 在 rules/07 映射表确认对应上游端点，读上游实现记录校验与错误分支。
2. 定义请求/响应 DTO（字段名尽量与上游 DTO 一致）+ Bean Validation 注解；手写 DTO ⇄ 领域模型 Mapper。
3. Controller 方法加 `@PreAuthorize("hasRole('wires.admin')")`、`@Operation` 中文说明；逻辑委托 Service。
4. 领域异常由 `GlobalExceptionHandler` 统一转 `{code, message}`，不得在 Controller try/catch。
5. 写 `@WebMvcTest` 测试（正常 200 / 校验失败 400 / 无权限 403）。
6. 完成标准：`mvn -pl kura-edge-rest test` 通过，`mvn clean verify` 通过。

## Pitfalls

- 上游返回裸 `Response`/DTO，本项目统一响应体 `{code, message, data}` —— 前端拦截器按 code 判错，
  不要把上游的 HTTP-only 风格照搬。
- 密码占位符：GET 类接口输出占位符，PUT 时收到占位符要还原为已存值，否则会把 `PASSWORD` 字面量存进配置。
- 批量配置更新必须保持上游"尽量多做 + 聚合失败"的语义，不能遇错即停留下半更新状态。

## Verification

- `@WebMvcTest` 覆盖每个端点的正常与异常分支；
- springdoc `/v3/api-docs` 能看到全部端点且分组在 `/api/v1/wires` tag 下。
