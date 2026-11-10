# 工程目录结构（Rules 03）

## 后端模块布局

> **例外（RFC 009）**：`kura-edge-release-server` 是发布服务器，8081 端口只提供 jar 下载与
> `feed.json` 清单。它是**运维基础设施**，不是业务装配的一环——只依赖 `kura-edge-api`（拿更新协议的
> 共享契约），**禁止**依赖 core / components / rest，也不得读写数据库。这是"rest 是唯一业务启动模块"
> 的唯一例外，新增第二个可执行模块须先改这条规则。

```
kura-edge/
├── pom.xml                              # 父 POM：Java 17、dependencyManagement、插件管理
├── kura-edge-api/
│   └── src/main/java/com/kura/wires/
│       ├── model/                       # WireEnvelope、WireRecord、WireComponent（SPI）、
│       │                                #   WireEmitter、WireReceiver、WireConfiguration、
│       │                                #   MultiportWireConfiguration、WireGraphConfiguration、
│       │                                #   WireComponentConfiguration（对照上游 org.eclipse.kura.wire[.graph]）
│       ├── engine/                      # WireGraphService SPI、校验接口（只放接口与模型，无实现）
│       ├── update/                      # 更新协议的共享契约：VersionComparator、FeedManifestDto
│       │                                #   （设备侧与发布服务器必须用同一套规则，RFC 008/009）
│       └── exception/                   # 领域异常与 ErrorCode 枚举
├── kura-edge-core/
│   └── src/main/java/com/kura/wires/core/
│       ├── engine/                      # WireGraphEngine（对照 WireGraphServiceImpl）、环检测、端口校验
│       ├── registry/                    # 组件注册表（对照 WireComponentTrackerCustomizer：注册/发现 Wire 组件）
│       ├── config/                      # 组件配置服务（对照 ConfigurationService 子集：创建/更新/删除工厂配置）
│       ├── driver/                      # DriverService：驱动工厂注册表 + 驱动实例生命周期（对照 Driver 服务注册）
│       ├── asset/                       # AssetService + BaseAsset + 通道解析/scale-offset（对照 asset.provider）
│       └── snapshot/                    # 快照持久化（对照 ConfigurationAdmin snapshot + WireGraph 属性）
├── kura-edge-components/
│   └── src/main/java/com/kura/wires/components/
│       ├── timer/                       # Timer（simple/cron，对照 internal/wire/timer）
│       ├── logger/                      # Logger（对照 internal/wire/logger）
│       ├── regexfilter/                 # RegexFilter（对照 internal/wire/regexfilter）
│       ├── fifo/                        # Fifo
│       ├── publisher/                   # CloudPublisher（对接消息平台可后续替换实现）
│       ├── subscriber/                  # CloudSubscriber
│       ├── db/                          # DbWireRecordStore / DbWireRecordFilter / Query（对照 db.component.provider）
│       ├── asset/                       # WireAsset + WireAssetFactory + WireAssetOcd（对照 wire.component.provider 的 asset）
│       └── driver/simulated/            # 模拟驱动（本项目新增，供 Drivers and Assets 可运行/可验收）
├── kura-edge-rest/
│   └── src/main/java/com/kura/wires/rest/
│       ├── KuraEdgeApplication.java     # Spring Boot 启动类
│       ├── controller/                  # WireGraphController、WireMetadataController、WireConfigController
│       ├── dto/                         # DTO 与手写 Mapper（对照 rest.wire.api 的 DTO 类）
│       ├── advice/                      # GlobalExceptionHandler（@RestControllerAdvice）
│       ├── security/                    # SecurityConfig、wires.admin 角色初始化
│       └── config/                      # Bean 装配、OpenAPI 配置
├── kura-edge-rest/src/main/resources/
│   ├── application.yml
│   └── db/migration/                    # Flyway 脚本 V1__init.sql 起
├── desktop/                             # 桌面外壳（RFC 005）：Tauri 2 工程 + 打包脚本
│   ├── package.json                     # 只装 @tauri-apps/cli；npm run build:exe
│   ├── scripts/                         # build-desktop.sh（一键打包）、fetch-windows-jre.sh
│   └── src-tauri/                       # Cargo.toml、tauri.conf.json、src/main.rs（拉起内嵌 JRE）
└── frontend/
    ├── package.json  vite.config.ts
    └── src/
        ├── api/                         # axios 实例 + 按资源拆分的 API 模块（wireGraph.ts、metadata.ts…）
        ├── types/                       # 与后端 DTO 对应的 TS 类型（WireGraphConfig、WireComponentDef…）
        ├── stores/                      # Pinia：wiresGraph、metadata、componentConfig
        ├── layouts/                     # 应用外壳（左侧菜单：Wire Graph / Drivers and Assets）
        ├── views/                       # 页面级组件（WiresPage、DriversAssetsPage）
        ├── components/drivers/          # Drivers and Assets：列表、驱动表单、通道表、数据页、新建弹窗
        ├── components/wires/            # 图编辑器：WiresComposer、WiresPalette、ConfigurationArea、
        │                                #   WireComponentNode、PortHandle、ValidationInput…
        ├── composables/                 # useGraphValidation（环检测/端口匹配）、useSnapshot（导入导出）、
        │                                #   useAssetChannels（通道列派生/属性表 ⇄ 通道行/通道名校验）
        ├── i18n/                        # zh-CN.ts / en-US.ts
        └── router/
```

## 规则

- 新文件先找归属包：**模型/接口进 api，实现进 core，业务组件进 components，Web 进 rest**；都放不进来说明分层设计有问题，先讨论再建包。
- 包名 `com.kura.wires.*`，与 Maven 模块一一对应；禁止在 api 模块里写 Spring 注解实现类（允许 `@Nullable` 之类纯注解除外，推荐直接用 Javadoc 描述可空性）。
- 前端组件超过 300 行必须拆分；图编辑器逻辑放 composables，不放组件内。
- 测试目录与主代码镜像：`src/test/java/com/kura/wires/...`；前端 `src/**/__tests__/*.spec.ts` 或同目录 `*.spec.ts`。
