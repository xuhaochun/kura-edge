# 边界禁令与验证命令（Rules 05）

## 边界禁令（硬性）

**仓库边界**
1. `/home/idea/IdeaProjects/{kura,kura-wires,kura-management-ui}` 是**只读参考**：禁止修改、禁止提交、禁止在其目录运行任何写命令；只允许 read/grep。
2. 禁止把上游代码整体复制进本项目再无限期搁置——移植必须带适配（去 OSGi、改包名、补 Javadoc）一次到位。

**技术边界**
3. 禁止引入任何 OSGi 依赖或 OSGi 编程模型（DS 注解、BundleContext、ServiceTracker 等）；上游的这些机制用 Spring 等价物重写。
4. 禁止 Lombok / MapStruct / Hutool（保持依赖面小、与上游可对照）；工具方法写在 `api` 或 `core` 的 util 包。
5. 禁止直接操作数据库绕过 Repository/Flyway；禁止在 Java 代码里写建表 SQL。
6. 禁止 `System.out/err`、`printStackTrace`、吞异常（catch 后不处理不抛不记）。
7. 禁止在 Controller 写业务逻辑、在 Service 拼 SQL、在组件（components）里访问 HTTP/数据库连接池以外的资源。
8. 前端：禁止绕过 Pinia 直接跨组件改图状态；禁止在组件里写内联 axios 调用（必须走 `src/api/`）；禁止硬编码后端 URL（用 Vite 代理与环境变量）。
9. 禁止修改快照 JSON 的字段名与结构（兼容上游导入是硬需求）；新增字段只能追加，不能改名/删除。
10. 禁止跳过测试提交；禁止为通过覆盖率检查写无断言的凑数测试。

**安全边界**
11. 任何密钥/口令只进环境变量或本地未提交配置；`application.yml` 中只放占位符。
12. 组件配置中的密码字段返回 DTO 时必须脱敏为占位符（对照上游 `replacePasswordsWithPlaceholder`）。
13. `/api/v1/wires/**` 全部要求 `wires.admin` 角色，新增端点必须显式加权限注解。

## 验证命令（提交前全部通过）

```bash
# 后端：编译 + 单测 + JaCoCo 覆盖率门槛 + ArchUnit 架构测试 + Checkstyle
mvn clean verify

# 只跑一个模块（快速迭代）
mvn -pl kura-edge-core test

# 前端：lint + 类型检查 + 单测 + 构建
cd frontend && npm run lint && npm run type-check && npm run test:unit && npm run build

# 本地联调
mvn spring-boot:run -pl kura-edge-rest    # 后端 :8080
cd frontend && npm run dev                # 前端 :5173，/api 代理到 :8080
```

验证口径：改了哪个模块就至少跑通该模块测试 + `mvn clean verify` 整体一次；
前端改动必须 `npm run build` 成功（type-check 包含在 build 中则可省 type-check 单跑）。
