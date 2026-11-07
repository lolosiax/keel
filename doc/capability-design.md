# 能力插件（Capability Provider）设计

> 状态：设计草案，待可行性验证。
> 首个示例：Lua 中间件引擎 `keel-plugin-lua`。
> 原则：**接口在 core，实现按需安装，ABI 稳定，宿主默认不背引擎体积。**

---

## 1. 要解决的问题

有些能力不属于业务插件，而是 Host / 其他插件的基础设施：

- 脚本引擎（Lua / JS / WASM）；
- 图像、音视频编解码；
- 存储驱动（S3、本地盘、WebDAV）；
- 认证提供方（OIDC、LDAP）；
- 中间件执行器、限流器、协议适配。

它们有三个共同点：

1. **不是每个部署都需要**，不应该让 Host 永久背体积与依赖；
2. **需要稳定 ABI**，能被独立升级；
3. **不应该引入第二个 Kotlin/Native 运行时**，否则又会踩 TLS / GC / 不可卸载的坑。

因此引入第四种插件形态：`capability`。

---

## 2. 分层

| 层 | 位置 | 内容 | 体积 |
| --- | --- | --- | --- |
| 通用 SPI | `keel-api` | `CapabilityId`、`CapabilityDescriptor`、`CapabilityRegistry`、`CapabilityProvider` | 很小 |
| 类型化 API | `keel-capability-<name>-api` | 只含接口 / 常量，例如 `LuaCapability` | 很小 |
| 加载器 | `keel-host` | 发现、校验、`dlopen`、注册、卸载 | 小 |
| 实现 | `keel-plugin-<name>` 包 | 纯 C 动态库 + `plugin.json` + 配置 | 按能力 |
| 使用方 | 业务插件 / 脚本插件 | `requires: ["lua"]`，或用类型化 API | — |

默认 Host 只有前两层和加载器；装 `keel-plugin-lua` 才有 Lua 引擎。

---

## 3. 插件类型

| kind | 产物 | 运行时 | 可卸载 | 典型用途 |
| --- | --- | --- | --- | --- |
| `native` | K/N dylib | 独立 K/N 运行时 | 否 | 业务插件 |
| `capability` | 纯 C dylib（或空包 + 静态内嵌） | 无 K/N 运行时 | 是 | Lua / WASM / 编解码 / 存储 |
| `script` | `.lua` / `.wasm` | 由 capability 提供 | 是 | 中间件、策略 |
| `declarative` | YAML / JSON | 无 | 是 | CORS、限流、头改写 |

`capability` 与 `native` 的包长得像，但入口不同：

- `native`：`keel_plugin_bootstrap`，会初始化 K/N 运行时；
- `capability`：`keel_capability_entry`，纯 C，不初始化 Kotlin 运行时。

---

## 4. 包布局

```
plugins/keel-plugin-lua/
  plugin.json
  lib/
    mingwX64/keel_capability_lua.dll
    linuxArm64/libkeel_capability_lua.so
    linuxX64/libkeel_capability_lua.so
  sandbox.yaml
```

```json
{
  "id": "lua",
  "kind": "capability",
  "version": "1.0.0",
  "abiVersion": 1,
  "provides": ["lua"],
  "entry": "lib/<target>/keel_capability_lua",
  "config": "sandbox.yaml"
}
```

Host 按当前目标选择 `lib/<target>/`，找不到则拒绝启用并给出明确错误。

---

## 5. C ABI

### 5.1 通用入口

```c
typedef struct KeelHostApi {
    uint32_t abi_version;
    uint32_t struct_size;
    void (*log)(int level, const char *msg);
    void (*metric)(const char *name, double value);
    const char *(*config_json)(void);
} KeelHostApi;

typedef struct KeelCapabilityDescriptor {
    uint32_t abi_version;     // 破坏性变更时 +1
    uint32_t struct_size;     // 只追加字段
    const char *id;           // "lua"
    const char *version;
    void *(*create)(const KeelHostApi *host);
    void (*destroy)(void *instance);
} KeelCapabilityDescriptor;

const KeelCapabilityDescriptor *keel_capability_entry(void);
```

### 5.2 能力自己的函数表

以 Lua 为例，另一张只追加的表：

```c
typedef struct KeelLuaApi {
    uint32_t abi_version;
    uint32_t struct_size;
    int  (*load_script)(void *instance, const char *id, const char *src, uint32_t len);
    int  (*reload_script)(void *instance, const char *id, const char *src, uint32_t len);
    int  (*run_middleware)(void *instance,
                           const char *script_id,
                           const KeelHttpFrame *req,
                           KeelHttpFrame *res);
    void (*stats)(void *instance, KeelLuaStats *out);
} KeelLuaApi;
```

- 所有结构体首字段是 `abi_version + struct_size`；
- 只追加，不修改既有字段顺序与语义；
- 符号默认隐藏，只导出 `keel_capability_entry`；
- Kotlin 侧通过 cinterop 包一层，业务代码不直接碰 C。

---

## 6. 生命周期

```
发现插件
  → 读 plugin.json，kind=capability
  → 选择 lib/<target>
  → dlopen / LoadLibrary
  → dlsym keel_capability_entry
  → 校验 abi_version / struct_size
  → create(hostApi)
  → 注册到 CapabilityRegistry（id → instance + vtable）
  → 启用依赖它的插件（拓扑顺序）

禁用：
  → 拒绝新调用
  → 等待在途调用 drain（超时）
  → destroy(instance)
  → dlclose / FreeLibrary
```

因为纯 C、没有 K/N 运行时，`dlclose` 是安全的，可以热替换。
若某平台不允许卸载，则退化为“标记失效 + 进程重启后清理”，与普通 C 插件一致。

---

## 7. 激活与依赖

- capability 插件声明 `provides: ["lua"]`；
- 使用方声明 `requires: ["lua"]`：
  - 脚本插件：`kind=script, runtime=lua`；
  - Kotlin 业务插件：`requires: ["lua"]`，并依赖 `keel-capability-lua-api`；
- Host 按拓扑顺序启用；提供方缺失时，使用方**拒绝启用**，不进入半可用状态；
- capability 被禁用时，依赖它的插件先停，再卸载引擎；
- 若没有使用方，capability 可以保持加载但不执行任何脚本。

---

## 8. 默认体积与两种分发

### 8.1 外部 C 库（推荐默认）

- Host 只含 SPI + 加载器；
- `keel-plugin-lua` 按平台发布，包内带 `liblua`；
- 不装 = 零引擎体积、零依赖；
- 可热替换。

### 8.2 静态内嵌（标准发行版可选）

- `keel-host` 编译时链接 `keel-capability-lua`；
- capability 插件退化为“清单 + 配置 + 激活”的空包；
- 启动更快、无 `dlopen`，但体积固定。

两种形态共用同一 ABI 与 `plugin.json`，业务代码不变。

---

## 9. Lua 示例

### 9.1 脚本插件

```
plugins/rate-limit/
  plugin.json
  middleware.lua
```

```json
{
  "id": "rate-limit",
  "kind": "script",
  "runtime": "lua",
  "requires": ["lua"],
  "middleware": [
    { "phase": "ingress", "order": 100, "entry": "middleware.lua" }
  ]
}
```

### 9.2 沙箱 API

只暴露必要能力：

| API | 说明 |
| --- | --- |
| `req.method` / `req.path` / `req.headers` | 只读请求元数据 |
| `req:query(name)` | 查询参数 |
| `req:body(max)` | 读取 body，带上限；默认关闭 |
| `res:set_status(code)` | 设置状态码 |
| `res:set_header(k, v)` | 设置响应头 |
| `res:write(bytes)` | 写响应体 / 短路 |
| `log.info/warn/error` | 写 Host 日志 |
| `metrics.inc(name)` | 指标 |

不提供：`os`、`io`、`package`、`debug`、`ffi`、网络、文件系统。

### 9.3 执行模型

- Lua state **不是线程安全的**：每线程一个 state，Host 维护小执行池；
- 中间件调用投递到池中执行，不直接在 Ktor 协程线程跑任意脚本；
- 每次调用有超时（默认 5ms，可配）；
- 指令数 / 内存 / body 读取量都有上限；
- 脚本错误只影响本次请求，记录 `pluginId + traceId`。

### 9.4 失败策略

- 默认 **fail-closed**（脚本错误 / 超时 → 503），安全相关阶段必须如此；
- 非安全阶段可逐插件配置 `fail-open`；
- 连续失败触发熔断，暂停该脚本并告警。

### 9.5 热更新

- 脚本内容哈希写入注册表；
- 文件变化 → 编译新 state → 原子替换；
- 在途请求继续用旧 state 完成，新请求用新 state；
- 失败则保留旧版本并告警。

---

## 10. 中间件集成

Host 的 `MiddlewareRegistry` 记录：

```kotlin
data class MiddlewareRegistration(
    val pluginId: String,
    val phase: Phase,          // ingress / auth / preRoute / postHandler / egress / error
    val order: Int,
    val kind: Kind,            // DECLARATIVE / LUA / KOTLIN_TRUSTED
    val sourceHash: String,
    val capabilities: Set<String>,
)
```

- capability 只提供“执行引擎”，不直接注册业务中间件；
- 脚本插件 / 声明式插件负责注册具体项；
- Host 原子替换注册表，插件 disable 时整批移除；
- 同阶段按 `order` 排序，冲突显式报错。

---

## 11. 安全与崩溃边界

| 风险 | 处理 |
| --- | --- |
| 脚本死循环 / 内存爆炸 | 指令数、内存、超时限制 |
| 脚本越权访问文件 / 网络 | 不暴露 `os` / `io` / `ffi`，只给白名单 API |
| C 引擎自身 bug | 同进程仍可能崩溃；彻底隔离需独立进程（P2） |
| 未签名能力包 | P1 起支持签名 / 哈希校验 |
| 能力包与 ABI 不匹配 | 入口校验 `abi_version` / `struct_size`，不匹配拒绝加载 |

**注意**：capability 是受信代码（纯 C dylib），和 native 插件同一信任级别；
它解决的是“体积、生命周期、K/N 运行时”问题，不是“不可信代码隔离”。
不可信逻辑应放在脚本 / WASM 沙箱里。

---

## 12. 版本兼容

- `abi_version`：破坏性变更 +1；Host 支持一个范围（如 1..2）；
- `struct_size`：只追加字段，旧 Host 忽略新字段；
- `plugin.json` 的 `abiVersion` 与入口表一致；
- 能力包按目标平台分别发布，坐标带 classifier（`mingwX64` / `linuxArm64` / `linuxX64`）；
- API 工件 `keel-capability-lua-api` 版本独立于引擎版本，但声明兼容范围。

---

## 13. 后续能力

同一套机制可以接入：

| 能力 | 形态 | 备注 |
| --- | --- | --- |
| Lua | 纯 C dylib | 首个示例 |
| WASM | 纯 C 运行时（wasm3/WAMR） | 多语言、强隔离 |
| QuickJS | 纯 C dylib | 仅当确实需要 JS |
| 图像 / 视频编解码 | 纯 C / 静态库 | 从 `backend/modules/codecs` 迁移 |
| 存储驱动 | C / K/N 均可 | 若要访问 Host 句柄则用 native |
| OIDC / LDAP | 纯 C 或 native | 认证提供方 |

---

## 14. 可行性验证清单

- [ ] `liblua` 能在 `mingwX64` / `linuxArm64` / `linuxX64` 三目标构建并加载。
- [ ] `dlopen` → `keel_capability_entry` → `create` → 执行一段 Lua → `destroy` → `dlclose` 全流程通过。
- [ ] Windows 上 `FreeLibrary` 后替换 DLL 成功；Linux 上替换 `.so` 成功。
- [ ] 脚本超时 / 死循环 / 内存超限被拦截，Host 不崩。
- [ ] 中间件链（声明式 + Lua）在 RK3566 上的单请求开销有基线。
- [ ] 脚本热更新时，在途请求不中断。
- [ ] 能力包缺失 / ABI 不匹配时，依赖插件拒绝启用且错误信息清晰。

---

## 15. 开放问题

1. 默认发行版用“外部 C 库”还是“静态内嵌 Lua”？
2. capability 包是否需要签名，签名由谁签发？
3. 中间件是否允许读 body；允许时的大小上限与流式上传如何兼容？
4. 脚本失败默认 fail-open 还是 fail-closed（当前建议 fail-closed）？
5. 是否需要“脚本引擎独立进程”的最高隔离形态？
6. 多目标能力包在 Maven 上的坐标与 classifier 规范。
7. Lua 脚本的调试 / 日志 / 单测工具链如何提供。

---

*本文只确认设计方向，尚未实现；Lua 作为首个可行性验证示例。*
