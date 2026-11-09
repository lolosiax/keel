# 插件契约形态讨论记录（2026-11-09）

> 状态：**讨论记录，未定稿**。本文只归档 2026-11-09 关于「跨插件访问的声明形态」的讨论与调研结论；
> 与 [codegen-design.md](codegen-design.md) 冲突时以该文档和代码为准，结论确认后再回填规范。

---

## 0. 结论摘要

1. **命名去掉 `-impl`**：主体工件是 `keel-plugin-<id>`，契约工件是 `keel-plugin-<id>-api`。
   `impl` 只作为 Gradle 子项目/目录的内部叫法，**不进入坐标、不进入文档**。
2. **目录以主体为根**：`keel-plugin-<id>/` 根工程就是插件主体；`api/` 是按需存在的子项目，
   不再需要 `impl/` 这一层。没有对外服务的插件（如 hello）不建 `api/`。
3. **两个工件是 Kotlin/Native 链接约束**，不是风格选择：impl klib 一旦进入消费方，
   `@CName` 入口与插件代码会被链进调用方二进制，链接期就失败。详见
   [codegen-design.md §1](codegen-design.md)。
4. **一个 Gradle 模块产两个可解析的 klib 不可行或不划算**：classifier 不参与 KMP 的变体解析；
   第二个 publication / capability 变体需要手写元数据，升级即碎。
5. **注解驱动生成（`@KeelExposed`）分阶段做**：先做校验器与 `plugin.json` / `requires` 生成，
   「从实现生成契约」留到 M2 之后，且优先“生成一次、提交入库”。

---

## 1. 现状与复杂度来源

当前一个「可被其他插件调用的插件」需要：

| 项 | 现状 | 位置 |
|---|---|---|
| 独立契约库 | 手写 `@ModuleService` 接口 + DTO | `keel-plugin-<id>-api` 顶层工程 |
| 实现工程 | `@KeelPluginMain` + `@ModuleServiceProvider` | `keel-plugin-<id>` |
| 部署清单 | 手写 `plugin.json`（id / entry / lib / requires / provides） | `src/commonMain/resources/plugin.json` |
| 消费方声明 | 手写 Maven 坐标依赖 + `plugin.json.requires` | 消费方 `build.gradle.kts` |
| 发布 | 每个工件单独配置 `moe.lolosia.keel.publish` | 各自 `build.gradle.kts` |

复杂度不在概念本身，而在**需要同时维护的文件与坐标数量**：

- 一个插件最少 2 个 Gradle 工程、2 份 build 脚本、1 份手写 manifest、1 份手写坐标依赖；
- `entry` / `lib` 路径、`requires` 与服务 id 都是手填，错了只在运行期暴露；
- 消费者需要知道「只依赖 api、不依赖主体」这条不成文规则。

---

## 2. 命名与目录（本节倾向已明确，待回填规范）

### 2.1 命名

| 角色 | 目录 | Gradle 子项目 | Maven 坐标 | 产物 |
|---|---|---|---|---|
| 插件主体 | `keel-plugin-<id>/` | 根工程 | `moe.lolosia.keel:keel-plugin-<id>` | `.keel` / sharedLib |
| 契约 | `keel-plugin-<id>/api/` | `:api` | `moe.lolosia.keel:keel-plugin-<id>-api` | klib |

**不使用 `keel-plugin-<id>-impl`**：`impl` 是实现细节，消费方只需要 api 坐标；
主体坐标本身就是「这个插件」，加 `-impl` 只会让 `keel-plugin-x` 与 `keel-plugin-x-impl` 之间的关系需要解释。

### 2.2 目录形态

```
keel-plugin-basic-biz/
├── settings.gradle.kts            # include(":api")
├── build.gradle.kts               # 主体：plugins { id("moe.lolosia.keel.plugin") } + keel { id = "basic-biz" }
├── src/commonMain/kotlin/...      # 主体实现
└── api/
    ├── build.gradle.kts           # 契约：plugins { id("moe.lolosia.keel.contract") }
    └── src/commonMain/kotlin/...  # 接口 + DTO
```

- 根工程名默认取目录名，坐标天然是 `keel-plugin-basic-biz`；
- `api` 子项目的坐标由约定插件设为 `keel-plugin-basic-biz-api`；
- 只在本插件内部使用的服务（如 hello）**不建 `api/`**，也不需要发布契约工件。

> 与 [codegen-design.md §2.1](codegen-design.md) 的目录示意（`api/` + `impl/` 两个子项目）不同：
> 本文主张根工程即主体、只保留 `api/` 一个子项目。此差异待确认后统一回填，暂不改动该文档。

---

## 3. Gradle 多工件可行性（调研结论）

问题：能否一个 Gradle 模块同时产出「主体」和「api 工件」，避免多建工程？

| 方案 | 结论 | 原因 |
|---|---|---|
| 第二个 klib 挂 classifier | **不可行** | KMP 依赖解析基于 attributes（platform type / native target / usage），classifier 不参与变体匹配；`artifact { classifier = ... }` 会绕过 Module Metadata，`commonMain` 解析失败、传递依赖丢失 |
| 同一 project 第二个 `MavenPublication` | 能跑但脆弱 | 需要手写 Gradle Module Metadata / variants；Kotlin 插件升级容易失效 |
| capability feature variant（`addVariantsFromConfiguration`） | 能跑但脆弱 | 消费方必须 `requireCapability`；KMP 对额外变体支持很薄 |
| **两个 Gradle 项目** | **推荐** | 官方路径；配合约定插件后，api 工程的 build 脚本只有一行 |

补充：`java-library` 的 `api` / `implementation` 是依赖传递可见性，不是工件拆分；`registerFeature` 在同一 jar 内也无法按类拆分。

---

## 4. 最小声明（目标形态）

一个「可被其他插件调用」的插件，作者只写三类东西：**id、契约、入口**。

### 4.1 契约（`api/` 子项目，唯一手写产物）

```kotlin
package moe.lolosia.keel.plugins.rbac

@KeelService                 // 服务 id 默认取插件 id
interface RbacApi {
    fun roleOf(userId: Long): Role
    suspend fun assign(userId: Long, role: Role)
}

@Serializable
data class Role(val id: Long, val name: String)
```

### 4.2 主体（根工程）

```kotlin
plugins { id("moe.lolosia.keel.plugin") }

keel {
    id = "rbac"              // 其余（name/version/entry/lib/requires）由工具链推导
}
```

```kotlin
@KeelPlugin
class RbacPlugin(ctx: PluginContext) : Plugin, RbacApi {
    override fun onLoad() {}
    override fun roleOf(userId: Long): Role = ...
    override suspend fun assign(userId: Long, role: Role) = ...
}
```

### 4.3 消费方

```kotlin
plugins { id("moe.lolosia.keel.plugin") }

keel { requires("rbac") }    // 自动加 keel-plugin-rbac-api 依赖 + plugin.json.requires
```

```kotlin
@KeelPlugin
class BizPlugin(ctx: PluginContext) : Plugin {
    override fun onLoad() {
        val role = ctx.rbac.roleOf(42)
    }
}
```

### 4.4 应当被推导、而不是手写的内容

- `plugin.json`（id / name / version / entry / lib / resources / requires / provides）；
- 契约依赖坐标与 `requires` 的一致性；
- 服务 Key / Client / `PluginContext` 访问器 / Dispatcher / 注册表（KSP 已有）；
- 发布配置与 `.keel` 打包（已有）。

### 4.5 不能推导的内容

- **契约接口与 DTO 本身**：类型必须有一个唯一的家（见 §5.1）；
- 服务 id 的全局唯一性（需要命名约定或冲突检测）；
- 哪些方法允许跨运行时（重载、泛型、vararg、函数类型参数仍应禁止）。

---

## 5. 注解驱动生成（`@KeelExposed` / `@KeelHidden`）

提案：不单独写 api 文件，在实现类上标 `@KeelExposed` / `@KeelExport`，由 KSP 生成契约；
被暴露声明的类型依赖必须同样被暴露或 `@Serializable`，否则以最小影响隐藏该成员并告警，
除非显式标 `@KeelHidden` / `@KeelExclude`。

### 5.1 三个硬点

1. **类型归属**：KSP 只能生成副本，不能把 impl 里的类型“搬到” api。于是只有两条路：
   - 类型住在 api 源集（推荐，保持编译期类型同一）；
   - 生成镜像类型（impl 保留自己的 DTO，api 生成同结构副本），靠 CBOR 结构兼容对上，
     字段改名 / 可空性变化会从编译错误降级为运行期错误。
2. **两遍编译**：api 必须先于 impl 生成，Gradle/KSP 接线是非标准形态，IDE 与增量构建体验变差。
3. **可评审性**：契约生成到构建目录后，评审发生在生成物里；对会被第三方依赖的契约（rbac / biz）不理想。

### 5.2 分期

| 阶段 | 内容 | 时机 |
|---|---|---|
| 现在 | **契约校验器**：扫描 `@ModuleService` 接口，检查参数/返回类型是否「同 api 库 / `@Serializable` / 白名单」，不满足报出具体签名 | M2 期间 |
| 现在 | `requires(...)` DSL + `plugin.json` 生成 | M2 期间 |
| M2 后 | **生成一次、提交入库**：`gradlew keelContract` 把接口/DTO 骨架写进 `api/src`，之后按普通源文件维护 | M2 完成后 |
| 暂不做 | 构建期自动生成契约（镜像类型 + 两遍编译） | 有真实需求再评估 |

### 5.3 规则细节（若实现校验器）

- 白名单：`String`、基本类型、`UInt`/`ULong`、`List`/`Set`/`Map`（递归检查元素）、`enum`、`@Serializable`；
- `@KeelHidden` 用于整个类型，`@KeelExclude` 用于单个成员，语义分开；
- 诊断信息直接给可执行建议（「给 `Foo` 加 `@Serializable`，或在方法上加 `@KeelExclude`」）；
- 隐藏策略是 fail-soft（成员消失 + WARN），但必须在构建日志里显式列出被隐藏的成员。

---

## 6. 待办与建议顺序

| # | 项 | 位置 | 成本 | 收益 |
|---|---|---|---|---|
| 1 | `plugin.json` 从 `keel { }` 生成 | `keel-plugin-gradle-plugin` | 小 | 不再手写 manifest |
| 2 | `keel { requires("x") }`：自动加 api 依赖并写 requires | 同上 | 小 | 消灭「编译过、运行期 503」 |
| 3 | `moe.lolosia.keel.contract` 约定插件 | 新增 Gradle 插件 | 小 | api 工程 build 脚本降到一行 |
| 4 | 插件类直接实现契约接口，自动注册服务 | `keel-plugin-ksp` | 中 | 去掉 `@ModuleServiceProvider` 额外类 |
| 5 | `keel-plugin-template` 模板 + 一页纸文档 | 新目录 | 小 | 新人复制即开工 |
| 6 | 契约校验器 | `keel-router-ksp` / `keel-plugin-ksp` | 中 | 提前发现不可序列化类型 |

建议顺序：5 → 1 → 2 → 3 → 6 → 4。先让模板跑通 rbac / basic-biz，再按真实痛点补工具。

---

## 7. 未决问题

1. **版本对齐**：插件独立版本化后，`requires("x")` 取哪个版本？共用 `libs.versions.keel` 是否长期可行，还是要引入 BOM / `plugins.lock`。
2. **服务 id 冲突**：现在 Host 只是忽略第二个声明并 WARN，是否改为启动失败并指出冲突双方。
3. **`@ModuleServiceProvider` 是否保留**：若允许插件类直接实现契约接口，是否还需要单独的 provider 类（影响 KSP 的注册生成）。
4. **DTO 归属**：坚持「DTO 在 api 源集」还是允许「镜像类型 + 结构兼容」。
5. **api 子项目能否含实现**：`api` 只放契约是当前共识；如果出现需要共享的工具代码，应放 `keel-api` 还是允许 api 子项目带实现，需要再定。
6. **模板形态**：`gradle init` 风格生成器，还是可复制的目录模板。
