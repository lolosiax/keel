# 插件原生依赖与资源目录 API 需求

> 提出方：klive（`e:\Dev\Kotlin\kigers-live-helper`）
> 关联：`.keel` 打包、插件加载、`PluginContext`
> 状态：待实施
> 目标：keel 下一版，重新发布 `mavenLocal` 后由 klive 接入

---

## 1. 背景

klive 计划从「JVM + Compose Desktop」改为「keel 插件 + Compose wasmJs UI」，交付形态是一个 `.keel`：

```text
klive-0.1.0.keel
  plugin.json
  lib/mingwX64/keel-plugin-klive.dll     K/N 插件本体（由 keel 打包）
  resources/native/klive_capture.dll     MSVC，D3D11 / WGC 采集核心
  resources/native/klive_webview.dll     MSVC，WebView2 壳（C ABI）
  resources/web/index.html, *.wasm, *.js Compose wasm 产物
```

这些 DLL 和 wasm 产物都不是 Kotlin 代码，不能编进插件 dylib，但需要随 `.keel` 一起发布，
用户侧只看到一个包。插件在 `onLoad` 里需要拿到这些文件的**真实路径**：

- `LoadLibrary` 加载 `klive_capture.dll` / `klive_webview.dll`；
- 把 `web/` 目录交给 `SetVirtualHostNameToFolderMapping`；
- 重载插件时不能覆盖正在使用的 DLL。

当前 keel 的打包侧已经支持把 `resources/` 放进 `.keel`，缺的是**运行期把这些资源变成
插件可访问的文件**的 API。

---

## 2. 现状核对

### 2.1 打包侧：已支持，不需要改

`keel-plugin-gradle-plugin/.../KeelPluginPackagingPlugin.kt` 会把以下目录都打进 `.keel`
的 `resources/`：

- `src/commonMain/resources`
- `src/nativeMain/resources`
- `keelPlugin { resources = [...] }`

所以 `.keel` 里携带多个 DLL 和 wasm 产物本身是可行的。

### 2.2 加载侧：只解插件 dylib

`keel-host/.../plugin/PluginLoader.kt`：

- 只把 `plugin.json.lib[target]` 对应的动态库取出来，落到
  `<cacheRoot>/<id>/<version>/`；
- `resources/` 只是注册成 `zip classpath`，**不会落盘**；
- 默认 `cacheRoot = <pluginsDir>/.cache`（`Plugins.kt`）。

`LibraryCache.kt` 在目标文件被占用时会改名成 `name.1.dll`、`name.2.dll`。
这不是随机目录，但逐个改名对「多个互相 import 的 DLL」不可用（见 §4）。

### 2.3 插件侧拿不到这些资源

- `PluginContextImpl.classpath` 目前是 `EmptyClasspath`；
- `PluginServer.classpath` 同样是 `EmptyClasspath`；
- `PluginContext` 没有 `cacheDir` / `resourcesDir` 之类的字段；
- `KeelPluginApi` / `KeelHostApi` 也没有对应字段。

### 2.4 `PluginStorage` 不能替代

`PluginStorage` 的语义是「按插件隔离的数据对象」：

- 异步反向请求；
- 单对象 8 MB；
- 只接受相对路径，不暴露绝对路径；
- 不能列目录。

`LoadLibrary` 需要真实的绝对路径，wasm 目录也需要目录路径，所以不能用它。

---

## 3. 需求

### P0-1：`PluginContext.cacheDir`

```kotlin
interface PluginContext {
    /**
     * 本插件独占的可写缓存目录，绝对路径。
     *
     * 每次加载 / 重载分配一个新目录：
     *   <cacheRoot>/<manifest.id>/<version>/<loadSeq>/
     *
     * 目录由 Host 创建；插件可以直接往里写临时文件。
     */
    val cacheDir: String
}
```

语义：

- 绝对路径，已存在且可写；
- 按 manifest id 隔离，`..` / 绝对路径不参与拼接；
- **每次 load 新目录**，不复用上一次的目录（原因见 §4）；
- 旧目录在下次 Host 启动时尽力回收，不要求运行期删除；
- 如果 `<pluginsDir>/.cache` 不可写（安装目录只读），Host 应回退到用户可写目录
  （例如 `%LOCALAPPDATA%\keel\cache`），或显式报错说明 cache root 不可写。

实现建议：通过 `HostSubjects` 增加一个请求，在 Worker 线程首次访问时向 Host 取；
也可以在 `PluginWorker.enter` 里预取后注入 `PluginContextImpl`。不要求改 C ABI。

### P0-2：`PluginContext.materialize(path): String`

```kotlin
interface PluginContext {
    /**
     * 把本插件 .keel 里的资源条目物化到 cacheDir，返回绝对路径。
     *
     * - path 是包内相对路径，例如 "native/klive_capture.dll" 或 "web"；
     * - 文件：写出该文件，返回文件路径；
     * - 目录 / 前缀：写出该前缀下的全部条目，保留相对路径，返回目录路径；
     * - 幂等：重复调用返回同一路径，不重复覆盖；
     * - 不允许 ".."、绝对路径、盘符；
     * - 条目不存在时抛 IllegalArgumentException。
     */
    fun materialize(path: String): String
}
```

示例：

```kotlin
val capture = ctx.materialize("native/klive_capture.dll")
val webDir  = ctx.materialize("web")
// capture == "<cacheDir>/native/klive_capture.dll"
// webDir  == "<cacheDir>/web"
```

要求：

- 物化目标必须落在 `cacheDir` 内；
- 大文件（wasm 可能有十几 MB）由 Host 侧流式复制，不要经事件载荷传递；
- 返回的路径使用当前平台的本地分隔符（Windows 为 `\`），可以直接给 `LoadLibrary`；
- 线程约束：如果实现走 HostLink 反向请求，就是 **Worker 线程专用**；
  文档需要写清楚，klive 会在 `onLoad` / `onEnable` 里调用，然后把路径交给 UI 线程；
- 重载后旧引用失效的语义与 `ClasspathRef` 保持一致。

不要求新增 `.keel` 格式；直接读现有 `resources/` 条目即可。

### P1：`PluginContext.classpath` 接到本插件资源

`PluginContext.classpath` 的接口已经在 `keel-api` 里声明，但实现是 `EmptyClasspath`。
建议同时把它接到本插件的 `ClasspathRef`，至少保证：

- `entries` / `read` / `list` / `open` 可用；
- 只能读自己的 `.keel`（跨插件仍走 `of(id)`）；
- 卸载后旧引用立即失效；
- 如果是反向请求实现，同样注明 Worker 线程约束。

`materialize` 解决二进制和大文件；`classpath` 解决模板、配置、小文本资源。两者不冲突。

### P2（可选）：`plugin.json.nativeLibs`

如果希望 Host 在 `dlopen` 之前就把原生依赖准备好，可以在 manifest 里加：

```json
"nativeLibs": {
  "mingwX64": ["native/klive_capture.dll", "native/klive_webview.dll"]
}
```

Host 在加载插件动态库前把这些条目物化到同一个 per-load 目录，并使用：

```text
LoadLibraryExW(path, null,
    LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS)
```

这样插件 dylib 如果静态 import 了这些 DLL，依赖也能被找到。

klive 当前不依赖这一条：插件会在运行时按绝对路径 `LoadLibrary`。如果实现成本高，可以只做 P0。

---

## 4. 生命周期与锁（重要）

### 4.1 为什么不能沿用 `name.1.dll` 的改名策略

Windows 上已加载的 DLL 不能被覆盖。逐个文件改名的问题：

- PE 的 import 表里写的是 `klive_capture.dll`，不是 `klive_capture.1.dll`；
- 一旦某个依赖被改名，加载器就找不到它；
- 多个 DLL 需要成套更新，不能只换其中一个。

所以正确做法是**每次 load 一个新目录**：

```text
<cacheRoot>/<id>/<version>/1/
<cacheRoot>/<id>/<version>/2/   ← 重载后
```

所有文件保持原名，全部写进新目录，再按绝对路径加载。旧目录等进程退出后回收。

### 4.2 目录回收

- 加载目录不能保证在运行期删除（DLL 被占用）；
- Host 启动时扫描 `<cacheRoot>/<id>/`，删除非当前 `loadSeq` 的历史目录；
- 删除失败只记日志，不阻塞启动。

### 4.3 DLL 搜索路径

当前 `NativeLibrary.mingw.kt` 用的是裸 `LoadLibraryA`。它加载插件 dylib 时，
**不会**自动在 dylib 所在目录里找依赖 DLL。

两条可行路径：

1. klive 插件自己按绝对路径 `LoadLibrary`（当前计划），Host 不需要改；
2. Host 改用 `LoadLibraryExW(..., LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | ...)`，
   并保证 P2 的 `nativeLibs` 已在同一目录。

---

## 5. 非目标

- 不提供跨插件的文件访问（已有 `ctx.classpath.of(id)`）；
- 不提供任意文件系统路径；
- 不改变 `.keel` 包格式；
- 不做 DLL 签名 / 哈希校验（可以后续单独做）；
- 不要求 Kotlin/Native 支持 `dlclose`（现状就是不能卸载）。

---

## 6. 验收标准

1. 测试插件在 `resources/native/` 放一个真实 DLL，在 `onLoad` 调
   `ctx.materialize("native/xxx.dll")`，返回的文件存在、字节一致，`LoadLibrary` 成功。
2. `ctx.materialize("web")` 返回目录路径，目录下的 `index.html` / `*.wasm` / `*.js`
   与包内一致。
3. 重新 enable，或替换同版本 `.keel` 后重启：
   - 使用新的 `cacheDir`；
   - 旧 DLL 没有被覆盖；
   - 没有 `ERROR_SHARING_VIOLATION`。
4. Host 重启后，旧的 `loadSeq` 目录被清理。
5. 两个插件拿到的 `cacheDir` 不同；`materialize("../x")`、`materialize("C:\\x")` 被拒绝。
6. 插件目录只读时，Host 使用可写 cache root，或给出明确错误。
7. `ctx.classpath`（若实现）在卸载后读取旧引用报错，而不是返回旧数据。

---

## 7. 实现提示（不强制）

- 分发入口：`HostPump.dispatchHostSubject(source, subject, payload)` 已经按 `HostSubjects`
  分发，新增 subject 不需要改 C ABI。
- `HostPluginIds.nameOf(source)` 可以拿到 manifest id；`HostClasspath.plugin(id)` 可以拿到
  已注册的资源视图（`ClasspathRef` 支持 `entries` / `read` / `list`）。
- `HostPlugins.init(host, cacheDir, pluginsDir)` 目前把 `cacheDir` 透传给 loader 后没有保存，
  需要留一份用于分配 per-plugin 目录。
- `PluginLoader` 已经有 `keelPath`、`cacheRoot`、`FileZipClasspath`，物化实现可以复用。
- `PluginContextImpl` 增加 `cacheDir` / `materialize`；`PluginWorker.enter` 可以在 `onLoad`
  之前预取 `cacheDir`，这样 `ctx.cacheDir` 是普通 `val`。
- `materialize` 写文件建议先写临时名再 rename；目录是新建的，冲突概率很低，但幂等检查
  仍然要做（同名同大小直接返回）。
- `materialize` 在 Host 泵线程上同步执行；单次复制十几 MB 可以接受，但如果要支持很大的
  资源树，建议在实现里限制单次物化大小或加超时，避免长时间阻塞其它插件事件。

---

## 8. 重新发布影响

如果按 P0 + P1 实现：

| 模块 | 是否变化 |
| --- | --- |
| `keel-api` | 是：`PluginContext` 新增 `cacheDir` / `materialize`，`HostSubjects` 新增请求 |
| `keel-host` | 是：per-load 目录、物化、回收、`dispatchHostSubject` 分支 |
| `keel-host-dev-<target>` | 是：随 `keel-host` 重新打包 |
| `keel-classpath` / `keel-classpath-full` | 大概率不变（`ClasspathRef` 已够用） |
| `keel-abi` | 不需要（走 `HostSubjects`，不动 C ABI） |
| `keel-plugin-gradle-plugin` | 不需要（资源已经在打包） |
| `keel-launcher` | 随 `keel-host` 重新链接 |

如果额外做 P2（`nativeLibs`），还需要改 `keel-plugin-gradle-plugin` 的 manifest 校验和
`keel-host` 的加载顺序。

---

## 9. klive 侧的对接方式

实现后 klive 插件会这样做：

```kotlin
override fun onLoad(ctx: PluginContext) {
    // 只做物化，不阻塞事件泵太久；失败直接让启动失败，错误可读
    val nativeDir = ctx.materialize("native")
    val webDir = ctx.materialize("web")

    // 起独立 UI 线程：WebView2 窗口 + 消息循环
    uiThread.start {
        val capture = loadLibrary("$nativeDir/klive_capture.dll")
        val webview = loadLibrary("$nativeDir/klive_webview.dll")
        webview.createWindow(webDir)
    }
}

override fun onDisable() {
    uiThread.stopAndJoin()
}
```

`cacheDir` 用于日志 / 临时文件；`materialize` 返回的目录在本次 load 生命周期内只读使用。
