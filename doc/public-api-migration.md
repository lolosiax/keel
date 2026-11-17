# `@PublicApi`：v1 → v2 迁移

> 状态：**迁移计划**（2026-11-18）。
> 配套 [public-api-design.md](public-api-design.md)。本文只讲「怎么从 `@ModuleService` 迁到 `@PublicApi`」，不重复设计细节。

## 0. 范围与结论

- **目标**：删除 v1 的 `@ModuleService` 机制，只保留 v2 `@PublicApi`。
- **下游**：当前项目 + klive。两边都重新编译即可，没有外部 ABI 包袱。
- **原则**：每个插件独立迁移、独立冒烟；v1 代码在最后一步统一删除，迁移期间不破坏现有构建。
- **坐标不变**：`keel-plugin-<id>-api` 仍是同一个 Maven 坐标；消费方只改 import（如有）并重新编译。

## 1. 为什么迁移

v1 与 v2 并存会带来：

- 两套注解、两套 Key / 访问器、两套路由（`SERVICE_CALL` vs `SHARED_CALL`）；
- 同一个插件同时有 v1 和 v2 时，impl 依赖 v1 api，而 v2 镜像与 impl 同 FQN → 编译冲突；
- 为避免冲突要引入 `.public` 包、显式 id、两套生成器，规则复杂度高于一次性迁移。

v1 的设计经验全部保留（见 §3），只删除机制本身。

## 2. 映射表

| v1 | v2 |
| --- | --- |
| `@ModuleService("hello") interface HelloApi`（在 `-api`） | `@PublicApi interface HelloApi`（在 impl） |
| `@ModuleServiceProvider class HelloServiceImpl : HelloApi` | `@Singleton class HelloServiceImpl : HelloApi`（不导出到 api） |
| `@KeelPluginMain class XxxPlugin` | 入口 + 公开单例（语义上等价 `@PublicApi` + `@Singleton`） |
| 生成的 `HelloApiKey` | 同名 `HelloApiKey`（api 生成） |
| 生成的 `ctx.helloApi` | 同名 `ctx.helloApi`（api 生成） |
| 生成的 `HelloApiClient` | `HelloApiProxy`（api 生成） |
| 生成的 `HelloApiDispatcher` + `KeelPluginServices` | `HelloApiDispatcher` + `KeelPluginPublicApi` |
| `ctx.modules.require<HelloApi>()` | `ctx.helloApi` |
| `ServiceCall(service, method, args)` | `SharedCall(typeId, handle, method, args)` |
| `ServiceCaller.callBlocking / call` | `PublicApiBridge.call`（由生成的代理调用） |
| `ServiceKey(id)` | `PublicApiKey(typeId)` |
| `HostServices`：`service id → plugin` | `HostShared.singletons`：`typeId → handle`；`PUBLIC_API_SINGLETON` 解析 |
| `SERVICE_REGISTER` / `SERVICE_CALL` | `PUBLIC_API_DECLARE` / `PUBLIC_API_REGISTER` / `PUBLIC_API_SINGLETON` / `SHARED_CALL` / `PUBLIC_API_CREATE` |
| `ServiceWire` + `serializer<T>()` | 保留；共享对象额外用 `SharedRef` |
| `-api` 手写契约 + DTO | `api/` 子工程全生成；DTO 由 impl 镜像过去 |

## 3. 从 v1 保留什么

1. **契约侧 KSP**：`XxxKey` / `ctx.xxx` / `XxxClient` 的生成模式，平移到 v2 的 `XxxKey` / `ctx.xxx` / `XxxProxy`。
2. **提供侧 KSP**：`XxxDispatcher` + 自动注册，平移到 v2 的 `XxxDispatcher` + `KeelPluginPublicApi.setup`。
3. **从 klib 读契约**：v1 的 `buildImportedService` 证明 KSP 能从依赖的 klib 读取注解读模型；v2 的 2c（消费方子类胶水）与提供侧接口都复用它。
4. **`serializer<T>()` + Args DTO**：多参数方法与任意 `@Serializable` DTO 的编码方式，替换 v2 目前只支持 0/1 参数的限制。
5. **命名约定**：`XxxKey`、`ctx.xxx`、`XxxProxy`、`XxxDispatcher` 保持一致，降低迁移时的认知成本。
6. **单例路由**：v1 的 `service id → provider` 映射为 v2 的 `typeId → singleton handle`；无状态服务 = `@PublicApi` 契约 + `@Singleton` 实现。

## 4. 删除清单

迁移完成后删除：

- 注解：`@ModuleService`、`@ModuleServiceProvider`
- API：`ModuleDirectory`、`ServiceCaller`、`ServiceCall`、`ServiceKey`、`ServiceRegistry`、`ServiceRegistration`、`ServiceDispatcher`、`ServiceBinder`、`GeneratedServices`
- 插件侧：`ctx.modules`、`KeelPluginServices`
- Host 侧：`HostServices`、`SERVICE_REGISTER` / `SERVICE_CALL` 的处理器
- 生成器：`KeelServiceGenerator` 的契约/提供侧生成逻辑（保留其中可复用的编码/模型代码）
- api 工程里的手写 `@ModuleService` 契约与对应的生成物

保留：`ServiceWire`（CBOR 编解码）与 `PublicApiBridge`；如需统一命名，可后续把 `ServiceWire` 改成 `KeelWire`，不影响协议。

## 5. 迁移顺序

### 步骤 1：animal 的 2b 跑通

- api 子工程 `keel-plugin-animal/api/` 全生成；
- dog 的调用点不变；
- 冒烟：`createInstance → 拒绝未初始化 invoke → initialization → 两段 bark → describe`；
- 验收后再动 v1。

### 步骤 2：hello（无 DTO，最小闭环）

- `HelloApi` 已经在 impl 模块（`keel-plugin-hello`），加 `@PublicApi`；
- `HelloServiceImpl` 加 `@Singleton`；
- `HelloPlugin` 已经标了 `@KeelPluginMain`，会自动成为公开单例；如果没有其它 `@PublicApi` 且不建 api 工程，则跳过 api 生成；
- 新建 `keel-plugin-hello/api/`（或在现有工程上加 `api` 子工程），生成镜像 / 代理 / Key / 访问器；
- 删除 v1 生成的 `HelloApiKeel.kt` 与 `@ModuleService*` 注解；
- 验收：`ctx.helloApi.greet("world")` 与 `ctx.helloApi.echo("x")` 正常，`keel-plugin-hello` 冒烟通过。

这一步验证：

- 单例契约 + `@Singleton` 实现的 `ctx.xxx` 访问器；
- 无状态方法的 `SharedCall` 路径；
- 提供侧自动注册与 `KeelPluginPublicApi` 生命周期。

### 步骤 3：basic-biz / rbac（有 DTO）

这是主要工作量，依赖 **DTO 镜像生成**（2b-2）：

1. 生成器支持 `@Serializable` 数据类镜像（字段、默认值、可空、嵌套、枚举、集合）；
2. 把契约接口与 DTO 从 `-api` 移到 impl（或把跨插件共享的 DTO 放进公共 `-dto` 模块）；
3. impl 的契约接口加 `@PublicApi`，实现类加 `@Singleton`；
4. 生成新的 `api/`，替换旧 `-api` 内容；
5. 消费方改为依赖新坐标（坐标不变）并重新编译；
6. 冒烟：`UserDirectory.byId / page / search` 等原调用全部通过。

如果 DTO 被多个 provider 共用，优先放公共 `-dto` 模块，避免每个 impl 各复制一份。

### 步骤 4：删除 v1

- 全仓库搜索 `@ModuleService` / `ctx.modules` / `ServiceCall`，清零；
- 删除 §4 的代码与 Host 处理器；
- `SERVICE_REGISTER` / `SERVICE_CALL` 常量可以在下一个 ABI 版本移除；在那之前保留但不使用；
- 跑全量构建 + animal / hello / basic-biz / rbac 冒烟。

## 6. 兼容性与回滚

- **坐标**：`keel-plugin-<id>-api` 不变；消费方只重新编译，不改依赖。
- **协议**：v2 使用 `SHARED_CALL`；v1 的 `SERVICE_CALL` 在迁移期间保留，二者独立。没有结构体布局变化。
- **线格式**：`ServiceWire` / CBOR 不变；共享对象用 `SharedRef`；DTO 编码与 v1 一致。
- **回滚**：按插件回滚。每个插件迁移完成并冒烟后再删它的 v1 代码；最后一步才删公共 v1 代码。任何一步失败都可以保留该插件的 v1 实现，不影响其它插件。

## 7. 验收清单

- [x] animal：2b 生成物替换手写 api，dog 冒烟输出与现在一致。
- [x] hello：`@PublicApi` + `@Singleton` 接入，`ctx.helloApi` 生成，自检经 `requireInstance` 往返通过，无 `@ModuleService`。
- [ ] basic-biz：`UserDirectory` 原调用通过，DTO 字段/默认值一致。
- [ ] rbac：原有权限查询调用通过。
- [ ] `keelApiCheck` 通过（V1 提交制）。
- [ ] 全仓库无 `@ModuleService` / `ServiceCall` / `ctx.modules` 引用。
- [ ] Host 侧 `SERVICE_CALL` 处理器删除后，全量构建通过。

## 8. 风险

| 风险 | 处理 |
| --- | --- |
| DTO 镜像生成复杂（默认值、嵌套、枚举、集合） | 先做 hello（无 DTO），再单独做 DTO 生成；不要提前迁 basic-biz |
| `ctx.modules` 删除破坏 klive | klive 与当前项目同步迁移；访问器名保持 `ctx.xxx`，调用点尽量不改 |
| 访问器 / Key 命名冲突 | 生成器扫描已有声明，冲突跳过 + warning；命名约定优先 |
| 提供侧接口与 api 镜像 typeId 不一致 | 统一由 `@PublicApi(id)` 推导；生成期校验 |
| 迁移期间两套机制同时存在 | 按插件迁移，公共 v1 代码最后删；不做跨插件的混合调用 |
