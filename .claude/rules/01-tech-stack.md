# 技术栈（Rules 01）

## 后端

| 项 | 选型 | 说明 |
|---|---|---|
| 语言/版本 | Java 17 | 与父 POM 一致；上游 kura 用 21，本项目统一 17，不要擅自升级 |
| 框架 | Spring Boot 3.x | 只用 Spring MVC（Servlet 栈），不引入 WebFlux |
| 构建 | Maven 多模块 | 父 POM 统一管依赖版本（BOM 风格 dependencyManagement），子模块不写版本号 |
| Web | Spring MVC + spring-boot-starter-validation | Bean Validation 注解校验入参 DTO |
| 安全 | Spring Security | 方法级 `@PreAuthorize("hasRole('wires.admin')")`，对应上游 `@RolesAllowed(WIRES_ADMIN_ROLE)` |
| 持久化 | Spring Data JPA + Flyway | 默认 H2（文件模式），生产可切 PostgreSQL；数据库迁移只走 Flyway 脚本 |
| JSON | Jackson | DTO 序列化；快照 JSON 结构必须与上游字段名一致 |
| 代码生成 | 不用 Lombok、不用 MapStruct | 保持与上游代码风格可对照；DTO 转换手写（上游就是手写 DTO 构造器） |
| 日志 | SLF4J + Logback | logger 变量名统一 `log`（上游允许 log/logger/auditLogger，本项目收敛为 `log`） |

## 前端（frontend/）

| 项 | 选型 |
|---|---|
| 框架 | Vue 3（Composition API + `<script setup lang="ts">`）+ TypeScript |
| 构建 | Vite |
| 状态 | Pinia |
| 路由 | Vue Router 4 |
| UI 库 | Element Plus |
| 图编辑器 | Vue Flow（`@vue-flow/core`）；若能力不足可回退自研 SVG composer（参照上游 wires_composer.js） |
| HTTP | Axios（统一实例，拦截器处理统一响应体与错误码） |
| 国际化 | vue-i18n，文案不得硬编码在组件里（上游 web2 是双语 properties 文件） |
| 测试 | Vitest + Vue Test Utils |
| 规范 | ESLint（flat config）+ Prettier |

## 禁止引入

- 任何 OSGi 依赖（osgi.core、osgi.service.*、bnd 等）——本项目就是去 OSGi 化
- Quartz/第三方调度器实现 Timer 组件时优先 `TaskScheduler`；确需 Quartz 须先在设计中说明
- 除上述列表外的 UI 库 / 状态库（一个项目一套栈）
