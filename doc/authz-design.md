# 权限系统：嵌进 Host，还是做成 `keel-plugin-basic-rbac`？

> 结论：**契约嵌进 core，实现做成插件**（hybrid）。
> core 只保留 `@Permission`、`Context.hasPermission` 和 `Authorizer` SPI；
> RBAC 的表、规则、管理接口、默认权限注册表归默认实现 `keel-plugin-basic-rbac`
> （`plugin.json` 的 `id = "rbac"`，可被第三方实现原位替换）。

---

## 1. 为什么不是二选一

| 方案 | 优点 | 问题 |
| --- | --- | --- |
| 全部嵌进 Host / keel-api | 零跨运行时开销；开箱即用 | core 被一种角色模型绑死；不用权限的部署也背表与服务；ABI 面扩大 |
| 全部做成插件（core 什么都不知道） | 最干净 | `@Permission` 的强制点必须在 Host 分发前；没有 core 契约就会各插件各写一套，安全口径不一致 |
| **契约在 core，实现在插件（推荐）** | 强制点统一、模型可替换、可选依赖 | 需要一次 Host → 授权插件的调用与缓存 |

「用到权限的插件自己加插件和导包」只在**需要管理角色 / 权限**时成立；
**只声明路由权限**的插件不应该被迫依赖 RBAC 的 API 工件。

---

## 2. 职责划分

### 2.1 core（keel-api / keel-host）

- `@Permission("a.b.c")` 注解（已在 `keel-api` 的 web 注解里）；
- `Context.hasPermission(permission)`（插件侧入口）；
- `Authorizer` SPI：
  ```kotlin
  interface Authorizer {
      fun authorize(userId: String?, permission: String): Boolean
  }
  ```
- Host 的路由分发点：
  1. `AuthInterceptor` 确认是否登录；
  2. 路由描述里的 `@Permission` 非空 → 调 `Authorizer.authorize(...)`；
  3. 未通过 → 403；没有 Authorizer → **503 + 明确日志**（fail closed）；
  4. 通过后进入插件 handler。
- Host 侧按 `(userId, permission)` 做短 TTL 缓存；授权插件通过事件失效缓存。
- core **不**知道角色、组、通配符、负权限、数据库表。

### 2.2 `keel-plugin-basic-rbac`（默认实现，可替换）

- 拥有表：`sys_role`、`sys_user_roles`、`sys_role_permission`、`sys_user_permission`（从 backend 迁移）；
- 拥有规则：组链、`+/-` 通配、负号优先、`owner` 放行；
- 拥有默认注册表（内置组规则；库里一旦有规则以库为准）；
- 拥有管理接口（组 / 规则 / 用户直授）与 `@Controller` 路由；
- 实现并注册 `Authorizer`（`plugin.json` 的 `provides: ["authz"]`）；
- 契约 `RbacReader` / `RbacAdmin` + DTO 由本插件生成到 `api/`，以**中性坐标**
  `keel-plugin-rbac-api` 发布；第三方实现只依赖该工件即可原位替换（同一 typeId、同一能力）。
- 权限节点目录不单独定义契约：统一用 `ctx.host.permissionCatalog()`
  （Host 聚合各插件的 `@Permission`）。

### 2.3 只声明权限的业务插件

```kotlin
@GET("/users")
@Permission("system.user.read")
suspend fun users(ctx: Context): List<UserDto> = ...
```

- 只依赖 `keel-api`，**不需要**依赖 rbac 的契约或实现；
- 请求期判定用 `ctx.hasPermission`（快照里的 `rules` / `owner`，本地同步）；需要当前用户的
  角色与规则时直接读 `ctx.user.roles` / `ctx.user.rules`（Host 已通过 `Authorizer.snapshot` 注入）；
- `plugin.json` 不需要声明 `requires("rbac")`（路由强制由 Host 兜底）；
- 只有要**精细查询**（如按组列出规则）或**管理**角色 / 权限时才依赖 `keel-plugin-rbac-api`，
  并用 `ctx.rbacReaderOrNull` 在实现缺席时降级。

---

## 3. 请求路径与开销

```
HTTP → Host Auth（登录） → Host authorizer 校验
                              │ 缓存命中：本地 O(1)
                              │ 未命中：Host → rbac 插件（1 跳）→ Host
                              ▼
                         插件 handler
                              │ ctx.hasPermission(...)
                              └─ 同一缓存 / 同一个 Authorizer
```

- 缓存键 `(sessionId/userId, permission)`，TTL 建议 5–30s；
- 角色 / 规则变更由 rbac 插件发 `AUTHZ_INVALIDATE` 事件，Host 清对应缓存；
- 典型业务每请求只校验 1–2 个权限，命中缓存后开销可忽略；
- 不用权限的部署可以完全不启用 rbac 插件，Host 对无 `@Permission` 的路由零开销。

---

## 4. 失败模式（必须 fail closed）

| 情况 | 行为 |
| --- | --- |
| 没启用 rbac 插件，路由有 `@Permission` | 503，日志提示「未安装授权实现」 |
| rbac 插件崩溃 / 被禁用 | 清空授权缓存；带权限路由 503，公开路由继续 |
| rbac 插件启动中 | 同上，不默认放行 |
| 用户未登录 | 401（AuthInterceptor），不会走到 Authorizer |
| 权限不足 | 403 |

**绝不能**出现「Authorizer 缺失 → 默认 true」的实现。

---

## 5. 与认证（Authentication）的关系

- 认证回答「你是谁」：会话 / Token / 签名 URL；由 Host 的 `AuthInterceptor`（或 `keel-plugin-auth`）负责。
- 授权回答「你能不能做」：`Authorizer` / `keel-plugin-basic-rbac`。
- 两者分开：可以换认证方式而不动 RBAC，也可以换授权模型而不动登录。
- 标准发行版默认同时启用 auth + rbac；最小发行版可以都不装，但带权限的路由会 503。

---

## 6. 标准发行版与最小发行版

| 发行版 | auth | rbac | 行为 |
| --- | --- | --- | --- |
| standard | 启用 | 启用 | 与 backend 现网一致 |
| minimal | 可选 | 不启用 | 公开路由可用；带 `@Permission` 的路由 503 |
| custom | 任意 | 任意 `Authorizer` 插件 | core 不感知实现 |

`keel-plugin-basic-rbac` 的实现只依赖 `Authorizer` SPI，因此也可以**静态编进 Host** 做零跳版本；
同一份实现既能当插件，也能内嵌，不改业务代码。

---

## 7. 数据与迁移

| backend 现状 | Keel |
| --- | --- |
| `sys_role` / `sys_user_role` / `sys_role_permission` | `keel-plugin-basic-rbac` 的表与迁移脚本 |
| `permissions.yaml` 默认注册表 | 随 rbac 插件发布 |
| `PermissionService` / `PermissionResolver` | rbac 插件内部 |
| `@Permission` + `ctx.hasPermission` | 留在 core |
| `AuthInterceptor` 的登录校验 | Host / auth 插件 |

迁移时 core 不保留任何角色表；升级 / 卸载 rbac 插件不影响其他插件。

---

## 8. 备选：什么时候该改成「全内嵌」

- 部署形态固定、永远需要 RBAC，且授权延迟成为瓶颈；
- 只有一个授权模型，不需要替换；
- 愿意让 core 承担表结构与规则语义的版本兼容。

即使那样，也建议保留 `Authorizer` SPI，只是把实现静态编进 Host —— 业务代码与插件依赖不变。

---

## 9. 实施阶段

### P0

- `Authorizer` SPI + Host 分发前校验 + fail closed；
- `keel-plugin-basic-rbac` 迁移表 / 规则 / 管理接口；
- 标准发行版默认启用。

### P1

- 授权缓存与失效事件；
- `keel-plugin-rbac-api` 管理接口；
- 性能基线（RK3566 每请求授权开销）；
- 审计日志（谁在何时被拒绝）。

---

## 10. 验收

- [ ] 只写 `@Permission` 的业务插件不依赖 rbac 工件也能编译。
- [ ] 未安装 rbac 时，带权限的路由返回 503，公开路由正常。
- [ ] rbac 运行时禁用后，缓存清空，受保护路由立即 503。
- [ ] 角色 / 规则变更后，下一次请求立即生效（缓存已失效）。
- [ ] 授权插件可替换：换一个 `Authorizer` 实现不改 Host 与业务插件。
