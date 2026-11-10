# RFC 002 — Drivers and Assets（驱动/资产）功能迁移 + 左侧双菜单壳

- 状态：**已批准并实现**（档位 L：新增 api 公共 SPI、core 新子系统、components 新组件、rest 新端点族、前端新页面与壳）
- 批准：2026-11-01（用户选择「方案 A：完整移植 + 模拟驱动」；CSV/列显隐按 §6 推迟）
- 提出：2026-11-01
- 相关规则：rules/03（模块落点）、rules/04（分层）、rules/06（seam 与覆盖率）、rules/07（REST/错误码）、
  rules/08 §8（Asset/Driver 放最后）、§2（快照兼容）
- 上游依据：`/home/idea/IdeaProjects/kura`（api：asset/driver/channel/type）、
  `/home/idea/IdeaProjects/kura-wires`（asset.provider、wire.component.provider 的 WireAsset、rest.asset/wire.provider）、
  `/home/idea/IdeaProjects/kura-management-ui`（GWT `client/ui/drivers/assets/*`）

## 0. 结果意象（用户视角，先于代码）

**现状**：`GET /api/v1/wires/drivers`、`GET /api/v1/wires/assets` 返回硬编码空数组；前端只有一个全屏线图编辑器，
没有 Drivers and Assets 页面；Asset/Driver 体系按 rules/08 §8 只是 SPI 占位。

**之后**：左侧出现与上游同构的竖排菜单（Wire Graph / Drivers and Assets），点 Drivers and Assets 进入：
列表（Service PID | Type | Factory PID，Driver 行下缩进显示其 Asset）→ 新建 Driver / 新建 Asset / 删除 →
选中 Asset 后两个页签：**Configuration**（通道参数表：表头 = `+enabled/+name/+type/+value.type/+scaleoffset.type/+scale/+offset/+unit/+listen`
+ 驱动自有参数；按类型出复选框/下拉/文本框；Apply/Reset 脏标记）与 **Data**（读取全部通道 → 值表格；
可写通道可改值 → Write 回写；每行状态 Unknown/Success/Failure/Modified/Disabled，失败可点开原因）。

- 用户动作：新建 Driver（选工厂 + 填实例名）→ 新建 Asset（选 Driver + 填实例名）→ 在 Configuration 里加通道、Apply
  → 切到 Data 页 Read 看到值 → 改值 → Write → 再 Read 看到新值。
- 边界：Driver 还有 Asset 时拒绝删除；Asset 被线图引用时拒绝删除（提示走 Wire Graph 删）；
  Asset 配置有未保存变更时禁止切 Data 页；PID 重复/非法、通道名含 `#/+, ` 均拒绝并给出上游同款文案。
- 线图侧：Asset 作为 `org.eclipse.kura.wire.WireAsset` 组件出现在 Wire Graph 组件面板；把 Timer 连到 Asset，
  保存后 Timer 每 tick 触发 Asset 读全部可读通道并向下游发信封（信封属性名沿用上游 `assetName`/`<channel>`/`<channel>_timestamp` 等）。
- **不包含**：CSV 通道导入导出、列显隐弹窗、`+listen` 驱动事件监听、`emit.on.change`/`emit.empty.envelopes` 等运行期选项、
  cloudlet 资产发布、真实协议驱动（modbus/opcua/gpio）。

## 1. 现状与问题

1. **没有 Asset/Driver 领域模型**：`kura-edge-api` 只有 `WireEnvelope/WireRecord/TypedValue/Scalar/Ocd/Ad`；
   缺 `Asset`、`Driver`、`Channel`、`ChannelRecord`、`ChannelStatus`、`ChannelType`、`ScaleOffsetType`、`DataType`、`TypedValues`。
2. **没有配置容器**：Asset 的全部通道都存在**一张扁平属性表**里，键为 `<channelName>#<paramId>`，参数 id 见 §3；
   解析/校验/默认值回填/scale-offset 运算都要从零实现（对照 `BaseAssetConfiguration` + `BaseAsset`）。
3. **生命周期没有归属**：Asset 是「自配置组件」（上游 `SelfConfiguringComponent`）——它的实例由**配置**驱动，
   不由线图驱动；而本项目 `WireComponentRegistry` 只在图里创建实例、图删除时销毁实例。
   直接照搬会把「不在图里的 Asset」做成不存在的对象，Data 页无从读写。
4. **没有 Driver 实现可用**：本地 kura/kura-wires 里**没有生产级 Driver 实现**（只有 `AbstractBlockDriver`、
   测试用 `StubDriver`/`MockDriver`；modbus/opcua 在未克隆的独立仓库）。缺一个可运行的 Driver，UI 的
   「Driver Factory」下拉为空、Asset 永远读不出值。
5. **前端只有单页**：`App.vue` = `<router-view/>`，路由只有 `/` → `/wires`，`WiresPage` 自带三栏且占满全屏，无法挂第二页。

## 2. 备选方案

### 方案 A（推荐）：上游忠实移植 + 一个「模拟驱动」作为可运行依赖

- **api**：新增 `com.kura.wires.model.channel`（`ChannelType/ChannelFlag/ScaleOffsetType/Channel/ChannelRecord/ChannelStatus`）、
  `com.kura.wires.model.driver`（`Driver` + `ChannelDescriptor` + `DriverException`）、
  `com.kura.wires.model.asset`（`Asset` + `AssetConfiguration`）、`com.kura.wires.model.type.DataType` + `TypedValues`、
  `com.kura.wires.engine.DriverFactory`（驱动工厂 SPI）。
- **core**：`core/driver`（`DriverService`：工厂注册表 + 实例生命周期 + 描述符）、
  `core/asset`（`AssetService`：通道属性表解析、实例生命周期、读写编排、scale-offset、通道描述符合并）。
- **components**：`components/asset`（`WireAsset` + `WireAssetFactory` + `WireAssetOcd`，factoryPid `org.eclipse.kura.wire.WireAsset`）、
  `components/driver/simulated`（`SimulatedDriver` + `SimulatedDriverFactory`，factoryPid `org.eclipse.kura.driver.simulated`）。
- **生命周期**：api 新增标记接口 `com.kura.wires.engine.SelfConfiguringComponent`（名字对照上游）；`WireAsset` 实现它。
  `WireComponentRegistry` 增加 `detachInstance(pid)`（自配置组件只摘线不销毁），`WireGraphEngine` 的销毁点改用它；
  `AssetService` 用 `destroyInstance(pid)` 做配置删除时的真销毁。`createInstance` 改为**同 pid 幂等复用**，
  使「启动时 AssetService 已建」与「图引擎挂载时再建」共用同一实例。
- **REST**：`/api/v1/wires/**` 扩展（列表、元数据、创建/更新/删除驱动与资产），另立 `/api/v1/assets/**` 运行期读写照搬
  `/services/assets`（`GET /{pid}`、`GET|POST /{pid}/_read`、`POST /{pid}/_write`）。
- **前端**：`AppLayout`（左侧竖排菜单 + 顶部标题 + 内容区）+ `/wires`、`/drivers-assets` 两条路由。

### 方案 B：Asset 也由线图驱动（不做自配置组件）

Asset 只在被线图引用时存在，Data 页先要求加入线图。**否决**：与上游语义相反（上游 Asset 独立于图存在，
图只是消费方），且删图会把资产一起弄丢，用户无法先配资产再连图。

### 方案 C：只做 SPI + 只读列表（无模拟驱动、无运行期读写）

`/drivers`、`/assets` 返回真实配置列表，Configuration 页可编辑，但 Data 页无值可读。**否决**：功能一半是死的，
「迁移 Drivers and Assets」的核心价值（改资产值并读回）不成立；且没有可运行的 Driver，任何端到端验收都无法进行。

## 3. 关键契约（必须逐字保留，来源见括注）

### 3.1 配置键（`kura-wires/.../asset.provider/AssetConstants.java`、`WireAssetConstants.java`）

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `driver.pid` | String | 必填 | 绑定的 Driver 实例 pid（**Driver 的身份就是它**） |
| `asset.desc` | String | `""` | 资产描述 |
| `<ch>#+enabled` | Boolean | `true` | 通道启用 |
| `<ch>#+name` | String | `Channel-1` | 通道名（UI 只读列） |
| `<ch>#+type` | String | `READ` | `ChannelType`：`READ` / `WRITE` / `READ_WRITE` |
| `<ch>#+value.type` | String | `INTEGER` | `DataType`：`BOOLEAN/BYTE_ARRAY/DOUBLE/INTEGER/LONG/FLOAT/STRING`（枚举声明顺序照抄） |
| `<ch>#+scaleoffset.type` | String | `DEFINED_BY_VALUE_TYPE` | `ScaleOffsetType`：`DEFINED_BY_VALUE_TYPE/FLOAT/DOUBLE/INTEGER/LONG` |
| `<ch>#+scale` | String | 无 | 数值型缩放因子 |
| `<ch>#+offset` | String | 无 | 数值型偏移 |
| `<ch>#+unit` | String | `""` | 单位 |
| `<ch>#+listen` | Boolean | `false` | 监听（本轮只存不跑，见 §6） |
| `<ch>#<driverParamId>` | 驱动定义 | 驱动 AD 默认 | 驱动自有参数（如 `simulated.register`），**不带 `+`** |
| `emit.all.channels` / `timestamp.mode` / `emit.errors` / `emit.connection.errors` / `emit.on.change` / `emit.empty.envelopes` | Boolean/String | `false`/`PER_CHANNEL`/… | WireAsset 的 `emit.*` 选项 AD 保留（写进 OCD 与配置），运行期行为见 §6 |

通道名规则：`#` 位置必须 `0 < pos < len-1`；通道名含 `#/+, ` 任一字符即整条键**静默丢弃**（上游行为，非报错）。
`+enabled` 解析失败 → 视为 `true`。缺 `+type` 或 `+value.type` 的通道**静默丢弃**。

### 3.2 通道描述符合并（`BaseAssetConfiguration.complete/buildDefinition`）

- 合并顺序：**资产基座 AD 在前，驱动 AD 在后**；基座 AD 的 id 以 `+` 开头，因此「前导连续 `+` 段」= 基座部分，
  其余 = 驱动部分。REST 分两个端点分别暴露（不拼在一起）：`/metadata/assets/channelDescriptor`（基座 9 个 AD，含 `+listen`）
  与 `/metadata/drivers/channelDescriptors`（每驱动一份）。
- 绑定驱动时用「基座 + 驱动」的 OCD 默认值回填每个通道缺失的**必填** AD：键 = `<channel>#<ad.id>`。

### 3.3 运行期语义（`BaseAsset` / `WireAsset`）

- `readAllChannels()`：只读 `READ`/`READ_WRITE`，调用 `PreparedRead`（有则复用，无则直接 `driver.read`）；
  IO 异常统一上抛为「连接失败」；之后对结果应用 scale/offset（仅 `DOUBLE/FLOAT/INTEGER/LONG`，
  输出类型 = 通道 `+value.type`；`BOOLEAN/STRING/BYTE_ARRAY` 不处理）。
- `read(Set<String>)`：结果**条数 = 请求条数**；通道不存在/类型不允许 → 该条变成 FAILURE 状态记录
  （`exceptionMessage` 为 `"Channel not available"` 或 `"Channel type not within expected types (READ or READ_WRITE)"`），不整批失败。
- `write(List<ChannelRecord>)`：只允许 `WRITE`/`READ_WRITE`；不允许的记录标记 FAILURE 后**继续**；写失败的记录回填错误。
- `WireAsset.onWireReceive(envelope)`：从信封里取键 = 通道名的 `WireRecord`（值类型需与通道 `+value.type` 一致，否则**静默忽略**），
  有则写、无则读全部可读通道，然后把结果发成新信封：属性 = `assetName` + `<ch>` + `<ch>_timestamp`（`timestamp.mode=PER_CHANNEL` 时）
  + `<ch>_unit`（非空时）+ `<ch>_error`（`emit.errors=true` 时，成功为空串）+ `assetTimestamp`（单时间戳模式）。

## 4. 模块落点与改动清单

```
kura-edge-api      model/channel/*  model/driver/*  model/asset/*  model/type/DataType,TypedValues
                   engine/DriverFactory, SelfConfiguringComponent        exception/ErrorCode(+500003)
kura-edge-core     driver/DriverService(+Impl, DriverRegistry)
                   asset/AssetService(+Impl), AssetChannelParser, ChannelProperty, ScaleOffsetApplier,
                   ChannelDescriptorMerger, AssetComponentResolver?
                   registry/WireComponentRegistry(幂等创建 + detachInstance)
                   engine/WireGraphEngine(销毁改 detachInstance)
kura-edge-components asset/WireAsset, WireAssetFactory, WireAssetOcd
                   driver/simulated/SimulatedDriver(Factory)
kura-edge-rest     controller/WireDriversAssetsController、AssetDataController
                   dto/{DriverDescriptorDto,AssetChannelDto,WriteRequestDto,WriteRequestListDto,
                        CreateDriverRequestDto,CreateAssetRequestDto,PidAndFactoryPidSetDto,…}
                   service/{DriversAssetsService, AssetDataService}
                   controller/WireMetadataController(扩展 4 个列表)
frontend           views/{WireGraphPage(原 WiresPage),DriversAssetsPage}.vue  layouts/AppLayout.vue
                   components/drivers/{DriversAssetsList,DriverForm,AssetMgmt,AssetConfiguration,AssetData,
                                       NewDriverDialog,NewAssetDialog,NewChannelDialog}.vue
                   stores/{driversAssets,metadata(+驱动/通道描述)}  api/{driversAssets,assetData,metadata}
                   types/{driversAssets}  i18n/{zh-CN,en-US}
```

分层校验（ArchUnit）：api 不引 Spring；core 不引 rest/components；components 只引 api+core；rest 唯一 Controller。

## 5. REST 契约（翻译，不是照抄）

| 方法 | 路径 | 对照上游 | 说明 |
|---|---|---|---|
| GET | `/api/v1/wires/metadata` | `GET /metadata` | 扩到 4 个列表：`wireComponentDefinitions`（含 WireAsset）、`driverOCDs`、`driverChannelDescriptors`、`assetChannelDescriptor` |
| GET | `/api/v1/wires/metadata/drivers/factory-pids` | `GET /metadata/drivers/factoryPids` | 驱动工厂 PID（New Driver 下拉） |
| GET | `/api/v1/wires/assets/metadata/channel-descriptor` | `GET /metadata/assets/channelDescriptor` | 基座通道 AD |
| GET | `/api/v1/wires/drivers` | `GET /drivers/pids` | 真实列表 `{components:[{pid,factoryPid}]}` |
| GET | `/api/v1/wires/assets` | `GET /assets/pids` | 同上 |
| POST | `/api/v1/wires/drivers` | `POST /configuration/v2/factoryComponents` | 建 Driver 实例 `{factoryPid,pid,properties?}` |
| POST | `/api/v1/wires/assets` | 同上 | 建 Asset 实例（factoryPid 固定 WireAsset，自动填 `driver.pid`） |
| PUT | `/api/v1/wires/configs` | `PUT /configs` | 放开「Driver/Asset 不需要被图引用」；Asset 走删+建重建语义 |
| DELETE | `/api/v1/wires/drivers/{pid}` | `DELETE /configuration/v2/factoryComponents/byPid` | 有 Asset 关联 → 409 `409003` |
| DELETE | `/api/v1/wires/assets/{pid}` | 同上 | 被图引用 → 409 `409003` |
| GET | `/api/v1/assets/{pid}` | `GET /assets/{pid}` | 通道列表（含 type/valueType/scaleOffsetType/scale/offset/unit/enabled） |
| GET | `/api/v1/assets/{pid}/_read` | `GET /assets/{pid}/_read` | 全部通道 → `{通道名: 原始值}`（无类型信息，BYTE_ARRAY base64） |
| POST | `/api/v1/assets/{pid}/_read` | `POST /assets/{pid}/_read` | 指定通道 `{channels:[...]}`，返回带 status 的记录（见下） |
| POST | `/api/v1/assets/{pid}/_write` | `POST /assets/{pid}/_write` | `{channels:[{name,type,value}]}` → 写后记录 |

- 统一响应体 `{code,message,data}`（本项目约定，上游 GWT/REST 无此包装，属**有意偏离**，规则里登记）。
- 错误码：参数错 `400001`；配置非法/factoryPid 不符 `400003`；pid 不存在 `404001`；被引用不可删 `409003`；
  通道 IO/驱动失败 `500003`（新增，登记进 `ErrorCode` 与 rules/07）。
- 安全：全部 `@PreAuthorize("hasRole('wires.admin')")`（上游 assets 用 `assets` 角色，本轮不拆角色，登记偏离）。

## 6. 明确不做 / 推迟（本轮改动边界）

1. `+listen` 驱动通道事件监听与 `emit.on.change`/`emit.empty.envelopes` 运行期行为：**配置键与 OCD 保留**，行为不实现，
   在类 Javadoc 与 `docs/` 里写明（避免"配置能存但无效"无人知晓）。
2. CSV 通道下载/上传、列显隐弹窗、分页（页面用前端分页 15 行，后端一次给全集）。
3. 真实协议驱动：只交付 `org.eclipse.kura.driver.simulated`（**本移植新增**，上游无对应实现；用于让功能可运行可验收）。
   它的通道参数 `simulated.register`（String）+ 驱动参数（**仅内存寄存器表**：写入即存、读取返回最后写入值）。
4. 资产快照/cloudlet 发布、`WireAsset` 的 `PreparedRead` 复用优化（首版每次读直接 `driver.read`）。

## 7. 测试 seam（rules/06 强制预先约定）

| seam | 覆盖 | 不覆盖 |
|---|---|---|
| `AssetChannelParser`（纯函数） | 通道解析、`+` 前缀键、非法通道名丢弃、缺类型丢弃、默认值回填 | — |
| `ScaleOffsetApplier` | 4 种数值类型的 scale/offset、`DEFINED_BY_VALUE_TYPE` vs 显式类型、非数值类型不动 | — |
| `AssetService` 接口 | 建/改/删、删 Driver 有 Asset → 409003、删 Asset 被图引用 → 409003、改配置后实例重建 | 持久化细节（Repository 用内存实现或 H2） |
| `DriverService` 接口 | 工厂注册、实例创建、`driver.pid` 绑定、描述符查询 | — |
| `Asset`（WireAsset）读写 | readAllChannels、read 的 FAILURE 记录、write 类型校验、scale/offset 应用 | 驱动内部实现 |
| `WireAsset.onWireReceive` | 通道名→写、无色→读全部并发射、错误信封、`emit.errors` 属性 | 驱动事件监听（不做） |
| REST Controller（MockMvc） | 上表每个端点的正反路径与错误码；元数据 4 列表 | 前端 |
| 前端 store/composable | driversAssets store 增删改与脏标记；通道列派生与校验；数据页状态推导 | 像素级样式 |

前端覆盖率维持 statements ≥ 70%、composables/stores ≥ 80%；后端维持 core ≥ 85%、api/components ≥ 80%、rest ≥ 70%。

## 8. 分阶段实施

1. **api**：channel/driver/asset 模型 + `DataType`/`TypedValues` + `DriverFactory`/`SelfConfiguringComponent` + 错误码；单测。
2. **core**：`AssetChannelParser`、`ScaleOffsetApplier`、`DriverService`、`AssetService`、注册表幂等/`detachInstance`、引擎销毁点；单测。
3. **components**：`SimulatedDriver`、`WireAsset`(+Factory/Ocd)；单测。
4. **rest**：DTO/Mapper/Service/Controller + 元数据扩展 + 错误映射；MockMvc 测试。
5. **前端**：`AppLayout` + 路由改造（`WiresPage` → `WireGraphPage`）+ `DriversAssetsPage` 全家；Vitest。
6. **规则/文档**：rules/03（asset 目录落地）、rules/07（新端点与错误码登记）、`docs/drivers-and-assets.md`；全量 `mvn clean verify` + 前端四件套。

## 9. 验收

- 后端：`mvn clean verify` 绿（含 ArchUnit、JaCoCo 门槛）。
- 前端：`npm run lint && npm run type-check && npm run test:unit && npm run build` 全绿。
- 手工链路：启动后端 + 前端 → 左侧两个菜单可切换 → 新建模拟 Driver → 新建 Asset → 配置两个通道（一读一读写）→ Apply →
  Data 页 Read 有值 → 改值 Write → 再 Read 值已变 → 线图里把 Asset 拖入并连 Timer → 保存 → 后端日志出现资产发射信封 →
  删除 Driver 被拒（还有 Asset）→ 删除 Asset 成功；把 Asset 留在图里再删资产被拒（提示走线图）。

## 10. 实现记录（落地时的事实，供后续对照）

- 实现与本文的差异点（都是落地时被验证过的必要修正）：
  1. `WireAssetOcd` 的 BOOLEAN 默认值（含 `emit.empty.envelopes` 的 `true`）用真正的 `Boolean` 而不是 `"false"` 字符串——本项目没有 OSGi metatype 的
     字符串→类型强转层，而 `WireAssetOptions` 的 `instanceof Boolean` 判定要求真布尔（否则 `emit.*` 存字符串会失效）。
  2. `WireGraphEngine.apply()` 的 `toCreate` 分支对「配置已存在」的 pid 跳过 `createFactoryConfiguration`：
     资产先由 Drivers and Assets 建好再拖进线图时，否则会报 already exists。
  3. `ComponentConfigurationServiceImpl.toDomain` 把 `service.factoryPid` 补进属性表：驱动/资产靠它判定实例类型，
     图引擎也不依赖内存映射。
  4. `ChannelRecord.createWriteRecord` 显式接收 `valueType`：本项目 `TypedValue` 没有 `getType()`
     （还多出 Byte/Char 两个上游没有的子类，无法反推），语义等价。
- 线图接线实测（2026-11-01，`--server.port=8085` + curl）：Timer(SIMPLE 1s) → Asset(temperature) → Logger，
  保存后日志每秒出现 `Received WireEnvelope from a1`，记录为 `temperature / assetName / temperature_error / temperature_unit / temperature_timestamp`
  ——即上游 WireAsset 的信封命名规则在运行期成立。
- 端到端手工验证（2026-11-01，`--server.port=8085` + curl）：建模拟驱动 → 建资产 → PUT 两个通道配置
  （`temperature` READ DOUBLE / `setpoint` READ_WRITE INTEGER，均带 `simulated.register`）→ `_write` 5 →
  `_read` 得到 `{"temperature":0.0,"setpoint":5}` → 把资产拖进线图（PUT snapshot）后 `_read` 仍可用、
  删资产被 409003 拦 → 删整图后资产随之删除（上游语义）→ 删驱动成功。
- 测试：后端 `mvn clean verify` 绿（api/core/components/rest 覆盖率门槛全部满足）；
  前端 `lint + type-check + test:unit(153) + build` 绿。
