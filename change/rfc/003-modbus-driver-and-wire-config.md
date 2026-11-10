# RFC 003 — 官方 Modbus 驱动移植 + Wire Graph 资产配置区

- 状态：**已实现**（档位 L：新增协议驱动子系统、组件面板配置区、前端新交互）
- 来源：GitHub issue #1「完善 driver：新增官方 Modbus 驱动，并修复 Wire Graph 中缺失的组件配置插件、补充 driver 选项」
- 提出：2026-11-02
- 相关规则：rules/02（命名/行宽）、rules/03（组件落点）、rules/04（分层）、rules/05（无 OSGi/无第三方
  协议栈）、rules/06（Gherkin、seam、覆盖率）、rules/07（REST 契约不变）、rules/08 §7（编辑器功能基线）
- 上游依据：`eclipse-kura/kura-modbus`（`org.eclipse.kura.protocol.modbus`，2026-11-02 的 `develop`）、
  `kura-management-ui` 的 `WiresPanelUi` + `ConfigurationAreaUi` + `AssetConfigurationUi`。

## 0. 结果意象（用户视角）

**现状**

1. Drivers and Assets 只有 `org.eclipse.kura.driver.simulated` 一个驱动工厂；没有可连真实设备的协议驱动。
2. Wire Graph 选中组件后只显示一张扁平 OCD 表单。选中资产（WireAsset）时：
   - 看不到资产通道表，也没有「Driver - <pid>」页签；
   - 从组件面板把已存在的资产拖进图时，组件属性被填成 OCD 默认值，而不是资产已保存的配置；
   - `driver.pid` 是自由文本，不是可选驱动列表。

**之后**

1. 新建 Driver 的工厂下拉里出现 `org.eclipse.kura.driver.modbus`；选它并填实例名后可配置
   IP/端口/连接类型/unit id/超时/重试；新建 Asset 绑定该驱动后，通道表出现 Modbus 通道参数
   （对象类型/地址/寄存器数/字节序），Read/Write 能读写真实 Modbus TCP 从站。
2. Wire Graph 选中资产后，右侧配置区分两个页签：
   - **资产配置**：资产级字段（`asset.desc`、`emit.*`）+ 通道表（基座参数 + 驱动参数），
     可增删通道、改值；`driver.pid` 变成驱动下拉（driver 选项）。
   - **Driver - <pid>**：所选驱动的配置表单（连接参数等），直接保存到驱动实例。
   选中普通组件时仍是原来的单表配置区。
3. 从组件面板把已有资产拖进图时，组件属性取自该资产已保存的配置，保存整图不会把资产配置冲回默认值。
4. **不包含**：串口（RS232/RS485）Modbus、ASCII 帧、从站模式、驱动事件监听（`+listen`）、
   Modbus RTU 的物理串口枚举。串口需要 OSGi `CommConnection` 或新增本地串口库，超出本轮且无法在
   CI 中稳定验证；本轮只做 Modbus TCP 与 RTU-over-TCP 两种主站连接。

## 1. 备选方案

### 方案 A（采用）：移植官方协议实现，适配成项目 Driver

把 `kura-modbus` 的 CRC、功能码、MBAP/RTU 帧、事务与响应校验、异常码搬进
`kura-edge-components` 的 `driver/modbus` 包，去掉 OSGi/Kura `CommConnection`，只保留
TCP 与 RTU-over-TCP；再实现项目自己的 `Driver`/`DriverFactory`/`ChannelDescriptor` 适配层。
保留上游类名与核心方法名（`Crc16`、`ModbusProtocolDevice`、`ModbusDataOrder`、
`ModbusFunctionCodes`、`ModbusTransmissionMode`），便于与官方代码逐段对照。

### 方案 B：只做内存 Modbus 模拟

不碰真实协议，仅新增一个 `modbus` 名字的模拟驱动。**否决**：issue 明确要求「根据官方代码新增到
driver 中」，模拟驱动已经存在（`simulated`），再做一个是重复而非新增能力。

### 方案 C：引入第三方 Modbus 库（j2mod / modbus4j）

**否决**：rules/05 要求依赖面小、且官方已有 EPL-2.0 实现可移植；第三方库会引入新的许可证与
线程模型，且无法满足「根据官方代码」的要求。

## 2. 配置契约

### 2.1 驱动级（Driver OCD）

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `modbus.connection.type` | STRING | `TCP/IP` | `TCP/IP` = Modbus TCP（MBAP）；`TCP-RTU` = RTU 帧跑在 TCP 上 |
| `modbus.ip.address` | STRING | `127.0.0.1` | 从站地址（必填） |
| `modbus.tcp.port` | INTEGER | `502` | TCP 端口，1–65535 |
| `modbus.unit.id` | INTEGER | `1` | Modbus unit/slave id，0–255 |
| `modbus.response.timeout` | INTEGER | `1000` | 单次事务超时（毫秒），1–60000 |
| `modbus.max.retries` | INTEGER | `2` | 超时/IO 失败后的重试次数，0–10 |

### 2.2 通道级（ChannelDescriptor，参数不带 `+` 前缀）

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `modbus.object.type` | STRING | `HOLDING_REGISTER` | `COIL` / `DISCRETE_INPUT` / `HOLDING_REGISTER` / `INPUT_REGISTER` |
| `modbus.address` | INTEGER | `0` | 起始地址，0–65535 |
| `modbus.register.count` | INTEGER | 按取值类型推导 | 读写的 16 位寄存器数/位对象数；1–125 |
| `modbus.data.order` | STRING | `BIG_ENDIAN` | `BIG_ENDIAN`(1234) / `LITTLE_ENDIAN`(4321) / `BIG_ENDIAN_WORD_SWAP`(3412) / `LITTLE_ENDIAN_WORD_SWAP`(2143) |

`modbus.register.count` 缺省时的推导：`FLOAT`→2、`DOUBLE`→4、`LONG`→2、其余→1。
位对象（COIL/DISCRETE_INPUT）只接受 `BOOLEAN`（1 位）或 `BYTE_ARRAY`（count 位打包）；
寄存器对象接受 `INTEGER`（1 或 2 寄存器）、`LONG`（2 或 4）、`FLOAT`（2）、`DOUBLE`（4）、
`STRING`/`BYTE_ARRAY`（count 个寄存器）。`DISCRETE_INPUT`/`INPUT_REGISTER` 只读。

### 2.3 取值映射

- 寄存器按大端 16 位读入；多寄存器的字节顺序按 `modbus.data.order` 重排后再解码成 Java 值。
- `STRING` 使用 UTF-8，尾部 0 字节去掉；`BYTE_ARRAY` 原样返回，尾部 0 保留。
- 写入是读取的逆过程；`INTEGER` 按 16 位（count=1）或 32 位（count=2）截断，`LONG` 按 32/64 位，
  `FLOAT`/`DOUBLE` 用 IEEE-754。
- 单条记录协议失败 → 该记录标记 `FAILURE` + 上游异常文案，其余记录继续；连接/IO 失败 →
  抛 `Driver.ConnectionException`，由资产层统一转 500003。
- 重试只针对传输层失败（响应超时、连接/IO 失败）；异常响应、CRC 不符、回显不符是确定性协议错误，
  不重试（上游本身不重试，本项目按 §2.1 的 OCD 文案补传输层重试）。
- Modbus TCP 响应额外校验 MBAP transaction id 与 protocol id（比上游严格，用于拒绝错配响应）；
  不回显 transaction id 的非标网关会因此失败，这是有意的安全取舍。

## 3. 前端改动

### 3.1 Wire Graph 配置区（对照 `ConfigurationAreaUi`）

- `ConfigurationArea.vue` 识别 `org.eclipse.kura.wire.WireAsset`，切换到
  `WireAssetConfiguration.vue`；普通组件保持现有 `ValidationInput` 表单。
- 资产配置区结构：
  - 页签 1「资产配置」：`asset.desc`、`emit.*` 字段 + 通道表 + `driver.pid` 下拉；
  - 页签 2「Driver - <pid>」：驱动 OCD 表单 + Apply/Reset（调用既有
    `PUT /api/v1/wires/drivers/{pid}`，后端重建驱动实例）。
- 通道表复用 `useAssetChannels` 的纯函数（解析/序列化/校验/新建默认行），与应用内其它资产表同源。
- 资产页签的 Apply 写回 `wiresGraph` store 的组件属性并置脏，随整图保存落库——与上游
  「组件配置随图 dirty/save」一致；驱动页签的 Apply 立即保存驱动自身配置，因为驱动是独立实例。

### 3.2 拖入已有资产

`useSnapshot#createComponent` 对带 `pid` 的 WireAsset 先 `GET /wires/assets/{pid}/configuration`，
用已保存属性而不是 metadata 默认值加入画布；失败时回退默认值并提示。这样「拖资产进图 → 保存」
不会把资产已有通道和 `driver.pid` 覆盖掉。

### 3.3 driver 选项

- 资产配置页签的 `driver.pid` 不再是自由文本，而是后端 `GET /wires/drivers` 实例列表的下拉。
- Driver 页签展示该驱动的全部 OCD 选项（含 Modbus 的 IP/端口/超时等），提供与
  Drivers and Assets 页一致的 Apply/Reset。

## 4. 落点与分层

```
kura-edge-components  driver/modbus/Crc16
                      driver/modbus/ModbusFunctionCodes、ModbusTransmissionMode、ModbusDataOrder
                      driver/modbus/ModbusProtocolException、ModbusProtocolErrorCode
                      driver/modbus/ModbusProtocolDevice（TCP / TCP-RTU 事务）
                      driver/modbus/ModbusChannelDescriptor、ModbusChannelConfig
                      driver/modbus/ModbusValueCodec
                      driver/modbus/ModbusDriver、ModbusDriverFactory
frontend              components/wires/WireAssetConfiguration.vue
                      components/wires/ConfigurationArea.vue（资产分支）
                      composables/useSnapshot.ts（拖入资产读真实配置）
                      api/driversAssets.ts（复用既有端点，不新增 REST）
                      i18n/{zh-CN,en-US}（配置区文案）
```

依赖方向不变：`api ← core ← components ← rest`；前端所有网络仍走 `src/api/`。

## 5. 测试 seam（rules/06 预先约定）

| seam | 覆盖 | 不覆盖 |
|---|---|---|
| `Crc16` | 标准向量、空缓冲、种子 | — |
| `ModbusProtocolDevice` | TCP MBAP 读/写、RTU-over-TCP CRC、异常响应、超时/重试、unit id 校验 | 真实串口、从站模式 |
| `ModbusValueCodec` / `ModbusChannelConfig` | 4 种字节序、各取值类型、位打包、非法参数 | 设备厂商私有扩展 |
| `ModbusDriver` | 多记录读、部分失败继续、只读对象拒写、连接异常上抛 | 驱动事件监听 |
| `ModbusDriverFactory` | 工厂 PID、OCD、通道描述符、实例创建 | — |
| 前端 `useAssetChannels` | 既有纯函数测试（本次仅复用，不重复实现） | — |
| 前端 `WireAssetConfiguration` | 资产字段/通道表渲染、driver 下拉、Apply 写回图 store、Driver 页签保存 | 像素级样式 |
| 前端 `useSnapshot` | 拖入资产时读真实配置 | 后端接口本身 |

前端覆盖率维持 statements ≥ 70%，`composables`/`stores` ≥ 80%；后端 components ≥ 80%。

## 6. 验收

- 后端 `mvn clean verify` 绿（含 Checkstyle、ArchUnit、JaCoCo）。
- 前端 `npm run lint && npm run type-check && npm run test:unit && npm run build` 绿。
- 手工链路：
  1. 起后端 → New Driver 选 `org.eclipse.kura.driver.modbus`，填 IP/端口；
  2. 新建 Asset 绑定该驱动 → Configuration 页出现 Modbus 通道参数；
  3. Wire Graph 里拖入该 Asset → 右侧出现「资产配置 / Driver」两个页签，driver 下拉正确；
  4. 在资产页签改通道 → 保存整图 → 重新加载，通道保持；
  5. 在 Driver 页签改超时 → Apply → 驱动实例重建；
  6. 用本地假从站验证 Read/Write 值往返。
- 截图：改动前（只有扁平 OCD 表单，无通道表/Driver 页签）与改动后（两页签 + driver 下拉）同场景对比，
  放进 PR 描述。
