# Classpath 与 `.keel` 插件包设计

> 状态：`keel-classpath` / `keel-classpath-full` / 两个 Gradle 插件均已落地；
> `.keel` 打包、普通嵌入、文件型 zip 读取与卸载失效都已跑通。
> 来源：`backend` 的 `modules/core/util/classpath` 与 `buildSrc/NativeClasspath.kt`，本次是提取 + 多运行时适配。

---

## 1. 语义边界

- Classpath 是**只读资源命名空间**，不是 JVM 类加载器。
- Kotlin/Native 代码在链接期固定，不能从另一个插件加载类；Classpath 只提供模板、静态文件、默认配置、SQL 等资源。
- 路径以资源根为基准，允许前导 `/`、`\` 分隔，拒绝 `..`。
- `.keel` 是插件包，也是资源容器；资源不解包，按 zip 随机读。

---

## 2. 模块分层

```
keel-classpath                 独立 KMP 库，不依赖 keel-api
      ▲
keel-api                       暴露 PluginContext.classpath 的类型
      ▲
keel-classpath-full            Host 注册表 / 租约 / 跨模块代理
      ▲
keel-host

keel-classpath-gradle-plugin   构建期打包 / 嵌入
```

- `keel-classpath`：`Classpath`、`ZipClasspath`、`FileZipClasspath`、`DiskClasspath`、`EmptyClasspath`、`PrefixedClasspath`、路径规范化、流式句柄。可在普通 Kotlin/Native 项目单独使用。
- `keel-classpath-full`：注册表、`ClasspathRef`、generation / lease、Host 回落、跨插件代理、卸载取消；只有它知道 `.keel` 的真实路径。
- `keel-classpath-gradle-plugin`：普通 exe/dll/so 的 `classpath.zip` 嵌入；插件 `.keel` 打包；自动给 `impl` 加 `keel-classpath-full`。

---

## 3. 两种分发形态

### 3.1 普通 Kotlin 产物

沿用 backend 的模式：

- `src/**/resources` 合并成 `classpath.zip`；
- Windows：`windres` 生成 PE 资源；
- Linux：`objcopy -I binary` + `_binary_classpath_zip_start/_end`；
- 运行期 `ZipClasspath.open(image, size)` 读取。

### 3.2 Keel 插件：`.keel`

```
hello-0.1.0.keel                  # 外层就是 zip
  plugin.json
  lib/
    mingwX64/keel_plugin_hello.dll
    linuxArm64/libkeel_plugin_hello.so
    linuxX64/libkeel_plugin_hello.so
  resources/
    static/home/index.html
    permissions.yaml
```

- `plugin.json` 在根，不进 `resources/`；
- 插件 dylib **不链接** `classpath.zip`，资源只在 `.keel` 里；
- 安装后 dylib 解到缓存目录，`resources/` 不解包；
- 插件通过 `FileZipClasspath(path, prefix = "resources/")` 读取自己的资源；
- Host 读其他插件时直接打开对方的 `.keel`，不需要目标插件运行时参与。

`plugin.json` 至少包含：

```json
{
  "id": "hello",
  "version": "0.1.0",
  "abiVersion": 1,
  "entry": "keel_plugin_bootstrap",
  "lib": { "mingwX64": "lib/mingwX64/keel_plugin_hello.dll" },
  "resources": "resources/",
  "requires": [],
  "sha256": "..."
}
```

---

## 4. 查找顺序

| 查询方 | 顺序 | 说明 |
| --- | --- | --- |
| 插件读自己 | 自己的 `.keel` | 本地读取，知道自己的路径 |
| 插件读 Host | `ctx.classpath` 回落 Host | Host 代理 + 缓存 |
| 插件读别的插件 | `ctx.classpath.of("bar")` | 只查对方，不回落 Host |
| Host 读自己 | Host 自己的 classpath | 不走插件优先 |
| Host 读插件 | `server.classpath.of("foo")` | 直接读 `.keel` |

构建期合并顺序沿用 backend：项目自身 > 目标源集 > 中间源集 > `commonMain` > 依赖资源；`DuplicatesStrategy.EXCLUDE`，可复现 zip。

---

## 5. 访问控制与生命周期

### 5.1 注册表

Host 维护：

```
pluginId -> PluginClasspathEntry(
  keelPath, resourcesPrefix, generation, state, handles/caches
)
```

- `.keel` 的真实路径只存在于 Host；插件之间只拿 `ClasspathRef`。
- `ClasspathRef` 不暴露路径；`origin` 只返回 `keel://<pluginId>`。
- `keel-classpath` 只负责读；租约、权限、注册表都在 `keel-classpath-full`。

### 5.2 租约与 generation

每次 `read` / `open` / `of(id)` 绑定当前 `generation`。卸载时：

1. `state = DRAINING`，新读取立即失败；
2. `generation++`，所有旧租约失效；
3. 取消跨插件在途请求；
4. 向插件发 `CLASSPATH_CLOSE`，关闭其本地 classpath；
5. Host 关闭自己的文件句柄与流；
6. 从注册表移除，`state = REMOVED`；
7. 之后 `of(id)` / `read` / `open` 表现为 `FileNotFound`。

不等待“别人读完”，直接让旧 generation 失效；最多给 Host 侧流一个很短的宽限期。

### 5.3 流式读取

- `ClasspathResource.read` 每块都校验 generation；
- 跨插件流由 Host 读 `.keel` 并分块回传，卸载时直接取消；
- 本地流由 `CLASSPATH_CLOSE` 标记失效，下一次 `read` 抛异常；
- 已写出的字节无法收回，这是正常语义。

### 5.4 异常

- 命名空间存在、路径不存在：`read` 返回 `null`；
- 命名空间已卸载 / generation 失效：抛 `ClasspathNotFound`（语义等同 FileNotFound，消息带 `pluginId`，不带真实路径）；
- `of(id)` 在卸载后抛同一个异常，或 `get` 返回 `null`。

### 5.5 文件占用

- Host 只读打开 `.keel`，卸载时关闭自己的句柄；
- Linux：`unlink` 已打开文件安全；
- Windows：先重命名 `.old`，重启后清理；
- `.keel` 用版本化文件名 + manifest 原子切换；
- 插件若拒绝关闭自己的句柄，Host 无法强制（同进程、无沙箱），按插件 bug 处理。

---

## 6. `keel-classpath` 已落地

当前模块：`keel-classpath/`，包 `moe.lolosia.keel.classpath`。

| 文件 | 内容 |
| --- | --- |
| `Classpath.kt` | 接口 + `ClasspathResource` |
| `ByteArrayClasspathResource.kt` | 默认流式句柄 |
| `ZipClasspath.kt` | 内存映像 zip，STORED / DEFLATED |
| `FileZipClasspath.kt` | 磁盘 zip；通过 `ZipFileClasspath` 只读中央目录与目标条目 |
| `RandomAccessFile.kt` | 平台文件随机读（Linux / mingw 各一份 actual） |
| `DiskClasspath.kt` | 目录回退 |
| `PrefixedClasspath.kt` | `.keel` 的 `resources/` 前缀视图 |
| `EmptyClasspath.kt` / `ClasspathHolder.kt` / `ClasspathExt.kt` | 空实现、全局 holder、便捷扩展 |

`FileZipClasspath` 现在只读 EOCD、中央目录与目标条目，不再把整个 `.keel` 读进内存；
`lastModified` 仍返回 null（后续按文件 mtime 补）。

### 6.1 API / Host 已接入的部分

- `keel-api` 依赖 `keel-classpath`，暴露：
  - `PluginContext.classpath: Classpath`
  - `KeelServer.classpath: Classpath`
  - `KeelServer.classpathOf(pluginId): Classpath`
- `keel-classpath-full` 增加：
  - `Classpath.of(pluginId)` 的默认 fail-closed；
  - `CompositeClasspath.lookup`，`pluginClasspath(registry, id)` 支持 `of(id)`；
  - `RegistryClasspath`：Host 自己的资源 + `of(id)`；
  - `ClasspathTransport`：跨运行时读取抽象；
  - `RemoteClasspath` / `remotePluginClasspath`：插件侧“本地 `.keel` → Host”组合；
  - `HostClasspathTransport`：Host 侧把请求落到注册表。
- `keel-host` 增加：
  - `HostClasspath`：注册、卸载、Host 资源、`transport()`；
  - `PluginPackageReader` / `KeelPackageManifest`：读取 `.keel` 包根 `plugin.json` 并注册 Classpath。

插件侧的 `ClasspathTransport` 具体实现（走事件泵 / C ABI）仍待 Host 加载器落地；当前只定义了传输层与 Host 侧实现。

---

## 7. Gradle 插件

### 7.1 普通模式

已落地 `moe.lolosia.keel.classpath`：

- `keelClasspath { }` 扩展：`targets` / `compressed` / `resources` / `wslDistro` / `wslUser`；
- 任务：`keelClasspathZip<Target>` → `keelClasspathRc` → `keelClasspathWindres`（Windows），
  或 `keelClasspathObjcopy`（Linux）；
- 合并顺序沿用 backend：项目自身 > 目标源集 > 中间源集 > `commonMain` > 项目依赖资源；
- 运行期用 `installEmbeddedClasspath()` 读取；`KEEL_CLASSPATH_DIR` 仍可切磁盘目录；
- `keel-host` 已用 `host.txt` 验证：`embedded host.txt: hello from host classpath`。

当前只把嵌入链路挂到 `Executable`；普通 `SharedLibrary` 的每模块资源发现还未接
（插件走 `.keel`，不依赖这条链路）。

### 7.2 插件模式

已落地 `moe.lolosia.keel.plugin`（`keel-classpath-gradle-plugin/`）：

- `keelPlugin { }` 扩展：`manifest` / `buildType` / `targets` / `resources` / `packageName`
  / `autoAddClasspathFull` / `keelVersion`；
- `keelPackage` 任务输出 `build/keel/<project>-<version>.keel`；
- 包内容：根 `plugin.json`、`lib/<target>/<sharedLib>`、`resources/...`；
- 默认自动给 `commonMainImplementation` 加 `keel-classpath-full`，可关闭；
- `keel-plugin-hello` 已接入，`keelPackage` 产出：

```
plugin.json
lib/mingwX64/keel_plugin_hello.dll
resources/hello.txt
```

- Host 的 `--inspect <path>` 已验证：能读取 manifest、`resources/hello.txt`，
  `unregister` 后再次读取抛 `classpath 已卸载`。
- 插件构建不再把 `classpath.zip` 链接进 dylib；普通 exe/dll/so 仍走原嵌入链路。

---

## 8. 从 backend 迁移清单

| backend | keel |
| --- | --- |
| `util/classpath/Classpath.kt` | `keel-classpath` |
| `ZipClasspath.kt` | `keel-classpath` |
| `DiskClasspath.kt` / `EmptyClasspath.kt` | `keel-classpath` |
| `ClasspathExt.kt` / `normalizeClasspathPath` | `keel-classpath` |
| `FileStoreClasspath.kt` | `keel-classpath-full`（抽象 FileStore） |
| `ClasspathHolder.kt` | `keel-classpath` 保留；`-full` 增加每模块安装 |
| `EmbeddedClasspath.*` 平台实际 | 普通构建留给 `-full` / Gradle 生成代码；插件走 `.keel` |
| `NativeClasspath.kt` / `ClasspathExtension.kt` | `keel-classpath-gradle-plugin` |
| `StaticClasspathConfig` / `ClasspathController` | 留在 Host Web 层 |

---

## 9. 待办

1. `FileZipClasspath` 补充文件 mtime（`lastModified`），并考虑 `mmap` 零拷贝。
2. Gradle 插件把嵌入链路扩展到普通 `SharedLibrary`（需要每模块符号 / HMODULE 发现）。
3. 插件侧 `ClasspathTransport` 实现：走事件泵 / C ABI，把 `RemoteClasspath` 接到 Host。
4. `keel-host` 插件加载器：解包 dylib、dlopen、bootstrap 传 `.keel` 路径、卸载时 `unregister`。
5. `.keel` 安装/卸载/升级：版本化文件名、哈希校验、Windows 清理。
6. 测试：前缀读取、流式中断、卸载后 FileNotFound、Windows/Linux 文件占用、RK3566 读取延迟。

---

## 10. 验收

- [ ] 普通 Kotlin exe/dll/so 仍能按原方式嵌入并读取 `classpath.zip`。
- [ ] `.keel` 不释放 `resources/`，插件与 Host 都能按 zip 读取。
- [ ] 插件之间拿不到对方 `.keel` 路径，只能通过 `ctx.classpath.of(id)`。
- [ ] 卸载时在途读取/流立即失效，之后读取表现为 FileNotFound。
- [ ] Windows 上 `.keel` 可替换，旧文件不阻塞新版本。
- [ ] 只读业务插件不直接依赖 `keel-classpath-full` 也能编译（由 Gradle 自动添加）。
