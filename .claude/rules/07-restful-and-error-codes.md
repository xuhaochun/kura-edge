# RESTful 规范和错误码（Rules 07）

## URL 与方法

- 前缀 `/api/v1/wires`（上游为 `/wire/v1`，本项目统一 REST 风格前缀；语义对照上游）。
- 资源名用复数名词小写中划线；动作语义放在子资源上，URL 里不出现动词。

| 端点 | 方法 | 对照上游 WireRestService | 说明 |
|---|---|---|---|
| `/api/v1/wires/graph/topology` | GET | `GET /graph/topology` | 当前图拓扑 JSON（与上游字段一致） |
| `/api/v1/wires/graph/snapshot` | GET | `GET /graph/snapshot` | 图 + 全部组件配置 + Driver 配置快照 |
| `/api/v1/wires/graph/snapshot` | PUT | `PUT /graph/snapshot` | 整图保存（校验 → 更新图 → 更新组件配置 → 快照） |
| `/api/v1/wires/graph` | DELETE | `DELETE /graph` | 删除整图 |
| `/api/v1/wires/metadata` | GET | `GET /metadata` | 组件定义 + Driver OCD + 通道描述（前端面板数据源） |
| `/api/v1/wires/metadata/components/factory-pids` | GET | `GET /metadata/wireComponents/factoryPids` | 可用组件工厂 PID |
| `/api/v1/wires/drivers` | GET | `GET /drivers/pids` | Driver 实例 pid/factoryPid 列表 |
| `/api/v1/wires/assets` | GET | `GET /assets/pids` | Asset 实例列表 |
| `/api/v1/wires/configs/by-pids` | POST | `POST /configs/byPid` | 批量按 pid 取配置（密码脱敏） |
| `/api/v1/wires/configs/by-pids` | DELETE | `DELETE /configs/byPid` | 批量删除配置（被图引用的拒绝） |
| `/api/v1/wires/configs` | PUT | `PUT /configs` | 批量更新组件配置（Driver/Asset 走各自服务的重建语义） |
| `/api/v1/wires/metadata/drivers/factory-pids` | GET | `GET /metadata/drivers/factoryPids` | 驱动工厂 PID |
| `/api/v1/wires/metadata/drivers/channel-descriptors` | GET | `GET /metadata/drivers/channelDescriptors` | 驱动通道参数定义 |
| `/api/v1/wires/metadata/assets/channel-descriptor` | GET | `GET /metadata/assets/channelDescriptor` | 资产基座通道参数 |
| `/api/v1/wires/drivers`、`/assets` | GET | `GET /drivers/pids`、`GET /assets/pids` | 实例列表（`{components:[...]}`；资产条目附 `driverPid`/`description`） |
| `/api/v1/wires/drivers`、`/assets` | POST | `POST /configuration/v2/factoryComponents` | 创建实例（Driver 需 `factoryPid`+`pid`；Asset 需 `pid`+`driverPid`） |
| `/api/v1/wires/drivers/{pid}`、`/assets/{pid}` | PUT | `PUT /configs` / 资产「删建重建」 | 整体替换配置并重建实例 |
| `/api/v1/wires/drivers/{pid}`、`/assets/{pid}` | DELETE | `DELETE /configuration/v2/factoryComponents/byPid` | 删除实例；被 Asset / 线图引用时 409003 |
| `/api/v1/wires/{drivers,assets}/{pid}/configuration` | GET | —（上游 GWT 走 RPC） | 原始配置属性表（前端编辑数据源） |
| `/api/v1/assets/{pid}` | GET | `GET /services/assets/{pid}` | 资产通道列表 |
| `/api/v1/assets/{pid}/_read` | GET / POST | `GET|POST /services/assets/{pid}/_read` | 读值：GET 返回纯映射；POST 返回带 `status`/`exceptionMessage` 的记录 |
| `/api/v1/assets/{pid}/_write` | POST | `POST /services/assets/{pid}/_write` | 写值，返回写后记录（带状态与失败原因） |

## 状态码

- 200 成功（PUT/DELETE 无返回体也返回 200）；400 参数/拓扑校验失败；401 未认证；403 无 `wires.admin`；404 资源不存在；409 冲突（如删除被图引用的组件）；500 未分类服务端错误。

## 统一响应体与错误码

```json
{ "code": 0, "message": "ok", "data": { } }
```

- `code=0` 成功；业务错误码 6 位：`HTTP状态码 + 3 位业务序号`。
- 错误码枚举定义在 `kura-edge-api` 的 `ErrorCode`（设计对照上游 `KuraErrorCode` 思路）：

| code | HTTP | 含义 |
|---|---|---|
| 400001 | 400 | 参数校验失败（Bean Validation） |
| 400002 | 400 | 快照 JSON 非法 / 缺少 WireGraph 属性（对照上游 BAD_REQUEST 分支） |
| 400003 | 400 | 组件配置非法（缺 factoryPid / 类型不符） |
| 409001 | 409 | 拓扑校验失败：检测到环 |
| 409002 | 409 | 端口不匹配：emitter/receiver 端口超界或不兼容 |
| 409003 | 409 | 资源被引用不可删除（组件仍被图引用） |
| 404001 | 404 | 组件/PID 不存在 |
| 401001 | 401 | 未认证；403001 无权限 |
| 500001 | 500 | 图引擎内部错误；500002 持久化错误 |
| 500003 | 500 | 通道 IO / 驱动调用失败（资产读写，对照上游 CONNECTION_FAILED） |

## 实现要求

- 所有异常经 `GlobalExceptionHandler`（`@RestControllerAdvice`）转换，Controller 不写 try/catch
  （上游 `DefaultExceptionHandler` 的等价物）；`WiresException(code, message)` 为唯一业务异常类型。
- 校验用 Bean Validation 注解写在 DTO 上（对照上游 `configs.validate()` / `pidSet.validate()`），
  拓扑类校验在 Service 层（`WireGraphEngine.validate()`），两者都返回 400/409 语义错误码。
- 整图保存是多步操作（更新图 + 更新 N 个组件配置 + 快照），沿用上游 `FailureHandler` 模式：
  子步骤失败先记录、全部尝试完后聚合报错，不允许半途抛异常留下不一致状态。
- OpenAPI：springdoc-openapi 自动生成 `/v3/api-docs`，Controller 加 `@Tag`/`@Operation` 中文说明。
