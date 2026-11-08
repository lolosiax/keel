# Keel 配置读写设计（YAML + 注释保留）

> 目标：`application.yaml` / 每插件 YAML 可读可写，**写回时保留原文件的注释与排版**；
> 同时适配 Kotlin/Native —— 不依赖 JVM 反射，不用 `KType` / `KClass` 还原对象。
> 参考实现：`E:\Ventures\Kotlin\med-agent` 的 `NodeChainConfig`（SnakeYAML 节点 + 注释处理 + 反射）。

---

## 1. 结论

| 关注点 | 做法 |
| --- | --- |
| 类型绑定 | `kotlinx.serialization` 的 `KSerializer`，**不用反射**（med-agent 那套 `KType` 在 K/N 不可用） |
| YAML 解析 | 读取用 **KAML**（有 native 产物）；KAML 不保留注释 |
| 注释保留 | 写入不走 KAML 的 emit，而是在**原始文本**上按路径做文本替换 / 插入（Host 侧实现） |
| 跨运行时 | 插件只拿到「配置快照 + 写入 API」；注释与文本编辑永远在 Host 里 |
| 文件 | Host：`application.yaml`；插件：`config/plugins/<id>.yaml`，各自独立、各自加锁 |
| 落盘 | 临时文件 + 原子 move；保留 `.bak`；同一文件串行写 |

核心是**读用树、写用文本**：

```
读取：raw text ──KAML──> YamlNode ──serializer──> 业务对象
写入：业务对象 ──serializer──> JsonElement ──diff──> 原始文本按路径替换 ──> 文件
```

---

## 2. 与 med-agent 的差异

| med-agent（JVM） | Keel（Kotlin/Native） |
| --- | --- |
| SnakeYAML `isProcessComments=true`，节点自带 `blockComments` / `inLineComments` | KAML 无注释模型；改用文本节点编辑 |
| `KType` / `KClass` / `memberProperties` 反射还原对象 | `KSerializer<T>`，编译期生成，无反射 |
| `by notnull(default)` 属性代理 | `Config.read<T>(path)` / `Config.write(value, path)` |
| 一个 `application.yaml` 管全部 | Host 一份 + 每插件一份，隔离写冲突 |
| 同步写文件 | Host 串行 + 原子替换 + 防抖 |

med-agent 的「节点级 set 保留旧节点注释」思路可以借鉴：我们做的是**按路径定位文本片段**，
保留该片段前后的注释。

---

## 3. 文件与层级

```
application.yaml                     # 主线：server / logging / plugins 目录等
config/plugins/queue.yaml            # 插件 queue 自己的配置
config/plugins/queue.yaml.bak        # 上次成功写入的备份
```

### 3.1 主线配置 vs 插件配置

两套配置**共用同一个 `Config` 接口**，只有底层实现不同：

| 访问点 | 文件 | 读 | 写 / save |
| --- | --- | --- | --- |
| Host 内部 `config` | `application.yaml` | 直接 | 直接 |
| 插件 `ctx.server.config` | `application.yaml` | **委托 Host** | **委托 Host** |
| 插件 `ctx.config` | `config/plugins/<id>.yaml` | 本地快照 | **委托 Host** |
| Host 管理侧 `pluginConfig(id)` | 插件 YAML | 直接 | 直接 |

- 插件对主线配置**读写都走 Host**，不在插件里缓存可写副本（避免双写冲突）。
- 插件对自己的配置可以本地读快照，但写同样委托 Host；文件只有一个所有者。
- 权限（P1）：插件在 `plugin.json` 里声明可访问的主线配置前缀，Host 拒绝越界路径。
- 插件不能通过 `ctx.config` 访问别的插件配置；Host 侧才有 `pluginConfig(id)`。

合并顺序（低 → 高）：

1. `@Serializable` 数据类里的默认值；
2. 内嵌资源 `application.yaml`（发行版默认值）；
3. 磁盘 `application.yaml` / 插件 YAML；
4. 环境变量（`KEEL_<SECTION>_<KEY>`，P1）；
5. 命令行参数（仅 Host，P1）。

插件默认模板：首次启用且文件不存在时，从插件资源
`config-template.yaml` 复制；P1 由 KSP 根据 `@Serializable` 配置类和 KDoc 生成模板。

---

## 4. API（keel-api）

```kotlin
// 同一接口，两处暴露：ctx.config（插件自己的）/ ctx.server.config（主线）
interface Config {
    fun <T> read(serializer: KSerializer<T>, path: String = ""): T
    fun <T> write(serializer: KSerializer<T>, value: T, path: String = "")
    fun save()
    fun reload()
}

// 顶层 reified 扩展，业务代码不用手写 serializer
inline fun <reified T> Config.read(path: String = ""): T
inline fun <reified T> Config.write(value: T, path: String = "")
```

使用：

```kotlin
@Serializable
data class QueueConfig(
    val pollIntervalMs: Long = 1000,
    val workers: Int = 2,
)

@Serializable
data class ServerConfig(val port: Int, val https: HttpsConfig)

// onLoad
val cfg = ctx.config.read<QueueConfig>()              // 自己那份文件
val interval = cfg.pollIntervalMs

// 主线配置：读写都委托 Host
val server = ctx.server.config.read<ServerConfig>("server")
val port = server.port

// 修改并保存
ctx.config.write(cfg.copy(workers = 4))               // 逐字段 diff，只改变化的叶子
ctx.config.save()

ctx.server.config.write(server.copy(port = 8443), "server")
ctx.server.config.save()                              // 触发 Host 落盘
```

- 路径语法与 YAML 一致：`read<ServerConfig>("server.https")`。
- 缺失路径：`T` 可空返回 null，否则抛 `NoSuchElementException`。
- `write` 是**合并**不是覆盖：未出现在新对象里的键保持原样（避免手写 DTO 漏字段时误删配置）。
- 写入只支持可序列化类型；任意对象 / 接口请先转 DTO。
- `Config` 的读写在插件侧是**阻塞**的（主线配置要走 Host 往返），只能在插件 Worker 线程调用，
  与跨模块服务调用同一套规则（见 [abi-design.md](abi-design.md) §6）。

### 4.1 跨运行时

- 插件第一次 `read` 时向 Host 请求整棵文档（`CONFIG_READ { scope, path="" }`），在本地缓存快照；
  之后读取零往返。**不在插件里引入 KAML**。
- 事件信封是 CBOR，但树本身以 **JSON 文本**传输：`JsonElement` 的序列化器只接受 JSON 编码器，
  不能直接塞进 CBOR。插件侧拿到文本后用 `Json.parseToJsonElement` 还原再解码。
- 插件自己的配置：读取走本地快照；写入发 `CONFIG_WRITE { scope=plugin, path, value(JSON) }`。
- 主线配置：读取发 `CONFIG_READ { scope=main, path }`，写入发
  `CONFIG_WRITE { scope=main, path, value(JSON) }`，都由 Host 在事件泵线程上处理。
- `save` 是空操作（Host 每次写入都已原子落盘）；`reload` 重新发一次 `CONFIG_READ`。
- 注释和原始文本**永远不出 Host**。

---

## 5. 注释保留的实现

Host 侧一个 `YamlDocument`：

```
rawLines: List<String>          # 原始文本，逐行
index: Map<Path, LineSpan>      # "server.port" -> 所在行 / 块范围
```

### 5.1 读取

- KAML 解析成 `YamlNode`，按 `path` 取子树；
- `serializer` 解码成业务对象；
- 解析失败给出文件 + 行号 + 路径。

### 5.2 写入

1. 新对象 → `JsonElement`；与旧对象 diff，得到「变化的叶子路径」。
2. 对每个路径：
   - **标量已存在**：替换该行的值 token，保留缩进、行尾注释；
   - **键不存在**：在父映射末尾按同级缩进插入 `key: value`（可带模板注释，P1）；
   - **对象 / 列表整体替换**：替换从 key 行到块结束的文本范围，保留 key 行上方的注释；
     块内部的注释会丢失（文档明确此限制）。
3. 重新渲染 rawLines，写到临时文件，`atomicMove` 成目标文件。

### 5.3 支持的 YAML 子集

写入支持：块映射、块序列、单行标量（plain / 单双引号）、注释、空行、多文档头的单文档。

遇到以下结构时**读取仍可用，写入直接报错**（而不是悄悄丢注释）：

- 锚点 / 别名（`&` / `*`）；
- 流式集合（`{...}` / `[...]`）作为被修改的节点；
- 折叠 / 字面多行标量（`>` / `|`）作为被修改的值；
- 自定义 tag。

如果将来需要完整保真，可替换为 **libfyaml**（C，保留注释与格式）的 cinterop 绑定；
但它是额外的 C 依赖，P0 先用纯 Kotlin 文本编辑，避免把构建链拖重。

---

## 6. 写入安全

- **单文件串行**：Host 内每个配置文件一个 mutex；插件的写事件在 Host 配置线程上排队。
- **原子落盘**：`<file>.tmp` 写完 → `atomicMove` 覆盖；失败时原文件不动。
- **备份**：成功写入前把旧文件复制到 `.bak`（保留一份）。
- **防抖**：`save()` 默认 200ms 合并；进程退出 / 插件 disable 时强制 flush。
- **外部修改**：P1 加文件监听；检测到外部改动时 `reload` 并推新快照，写回前先合并。

---

## 7. 变更通知

Host 写入成功后发布 `ConfigChanged(pluginId, path)` 事件：

- 插件可订阅自己的配置变化，重新 `read` 快照；
- 管理界面 / 运维接口通过同一事件刷新；
- 事件只携带路径，不带值（值从本地快照读）。

---

## 8. 默认值与 KSP（P1）

`@ModuleConfig` 标注一个 `@Serializable` 配置类后，KSP 生成：

- `ctx.config.<module>` 的类型化访问器（免写 `read<QueueConfig>()`）；
- `config-template.yaml`：字段默认值 + KDoc 转成 YAML 注释；
- `config/plugins/<id>.yaml` 首次创建时直接复制模板。

这样新增配置项时，模板、注释、读取代码都不用手写。

---

## 9. 实施阶段

### DEMO

- Host 侧 `YamlDocument`：KAML 读 + 标量替换 + 缺失键插入；
- `application.yaml` + 每插件 `config/plugins/<id>.yaml`；
- `Config` API + 插件快照（事件承载）+ 写入事件；
- 验收：改一个已有键、加一个新键，注释与空行不变。

**DEMO 落地状态**：已完成。工作目录取 Host 启动参数里的缓存目录；KAML 只在 Host 侧引入。
写入为原子替换并保留 `.bak`；插件首次读取时拉取整棵文档做本地快照。
流式集合、多行标量、锚点 / 别名在写入时抛明确错误。

### P0

- 对象 / 列表块替换；`.bak` + 原子写 + 防抖；
- `ConfigChanged` 事件；
- 配置错误带行号；
- 插件模板资源首次复制。

### P1

- `@ModuleConfig` + KSP 生成模板 / 访问器；
- 环境变量覆盖；
- 文件监听与外部修改合并；
- 需要完整 YAML 保真时评估 libfyaml。

---

## 10. 验收清单

- [ ] 修改 `application.yaml` 的已有键后，文件内所有注释、空行、键顺序不变。
- [ ] 新增一个键时插入到正确层级，缩进与同级一致。
- [ ] 插件写配置不直接碰文件，注释不会因插件重启丢失。
- [ ] 写入中断（进程被杀 / 磁盘满）不会留下半个文件，`.bak` 可恢复。
- [ ] 业务代码只写 `@Serializable` 数据类和 `ctx.config.read/write`，不碰 YAML 文本。
- [ ] 不支持的 YAML 结构在写入时给出明确错误，而不是静默丢注释。

---

*本文是实现约定；`Config` 接口已在 keel-api 落地，`YamlDocument` 与 Host 实现按阶段补充。*
