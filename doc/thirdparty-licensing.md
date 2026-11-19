# 第三方原生库：封装发布与许可合规

> 状态：方案（2026-11-19，插件与首批工件已落地）。本文回答两件事：
> keel 如何发布第三方原生库的封装；分发这些产物时如何满足许可义务。
> `keel-thirdparty-gradle-plugin` 已实现消费接线与 check / notices / verify / publish；
> `keel-thirdparty/` 已迁入 6 个 codec（zlib / png / webp / jpeg / gif / msgpack），
> SQLite 等其余库仍待补。与实现冲突时以代码为准，并回头修本文。

---

## 0. 结论

- **仓库不 vendor 上游源码**：只保存 manifest、`.def`、wrapper（Kotlin / C）、patch 与许可文本。
- **发布产物按「库 × 目标」拆成 zip**：头文件 + 各目标静态 / 动态库 + `.def` + 许可 + SBOM。
- **宽松许可静态分发；LGPL 只动态分发；GPL / AGPL 默认拒绝**。
- **声明随产物走**：每个 `.keel` 包与最终可执行文件都带 `licenses/` 与 `THIRD-PARTY-NOTICES.md`。
- **声明由 manifest 生成**，CI 门禁校验，不允许手写漂移。
- **发布与消费解耦**：`keel-thirdparty-gradle-plugin` 是独立 Gradle 插件，任何 Kotlin/Native
  工程都能用，不依赖 keel 运行时。
- 数据库驱动属于「能力包内部使用」，不产生 Kotlin 工件，见 §1/A。

---

## 1. 两类使用场景

| 场景 | 例子 | 产出的工件 |
| --- | --- | --- |
| A. 能力包内部使用 | 数据库驱动（SQLite / MariaDB） | 只有 `.keel`；库源码或预编译库是能力包的构建输入 |
| B. Kotlin 代码直接调用 | zlib / libpng / libwebp / msgpack | 第三方原生库工件（zip），由 `keel-thirdparty-gradle-plugin` 生成 cinterop |

A 类不发布 cinterop 工件：插件永远只经过能力 ABI，不直接调用 `sqlite3_*` / `mysql_*`。
B 类才需要头文件、`.def` 与链接参数。

---

## 2. 分发模型

### 2.1 仓库里放什么

| 放 | 不放 |
| --- | --- |
| `thirdparty/manifest.json` | 上游源码 tarball / zip |
| `<lib>/<lib>.def` | 编译产物（`.a` / `.dll` / `.so`） |
| wrapper（`.kt` 门面 / `.c` 胶水） | 上游构建目录 |
| `patches/*.patch` | 上游示例、测试与文档 |
| `licenses/<lib>/LICENSE` | — |

构建时由 CI 按 manifest 的 URL + sha256 下载上游源码，校验后应用 patch 再编译；
换机器、换时间都能复现同一份产物。

### 2.2 工件命名与布局

```
moe.lolosia.keel.thirdparty:<lib>-<target>:<upstream>-keel.<revision>@zip
```

例：`moe.lolosia.keel.thirdparty:sqlite-linuxArm64:3.46.0-keel.1@zip`

zip 固定结构：

```
include/…                      cinterop 的头文件
def/<lib>.def                  随包分发，消费方不需要自己写
lib/<target>/…                 静态库或动态库
licenses/<lib>/LICENSE         上游许可全文
licenses/<lib>/NOTICE          Apache-2.0 等要求的 NOTICE（没有则省略）
THIRD-PARTY-NOTICES.md         由 manifest 生成
SBOM.json                      SPDX / CycloneDX，含上游 URL、sha256、版本
upstream-source.txt            源码地址、校验和、应用的 patch 列表
```

### 2.3 消费方式

```kotlin
import moe.lolosia.keel.gradle.common.*

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("moe.lolosia.keel.thirdparty")
}

keel {
    thirdparty {
        library("sqlite")
        library("zlib")
    }
}
```

插件负责：

- 按当前目标解析对应 zip；
- 解压到 `build/thirdparty/<lib>/<target>`；
- 用包内 `.def` 建 cinterop（`includeDirs` 指向 `include/`）；
- 追加 `linkerOpts`（`-libraryPath`、库名、系统库）；
- 把 `.a` / `.so` 声明成 cinterop 与链接任务的输入（不声明会导致改库不重链）。

消费方**不写 `.def`**、不手配 `includeDirs`。需要自定义包名 / `headerFilter` / 链接参数时，
再提供可选的覆盖 DSL：

```kotlin
keel {
    thirdparty {
        library("sqlite") {
            packageName = "com.example.sqlite"
            headerFilter = "sqlite3*.h"
            linkerOpts("-lpthread")
        }
        library("zlib", dir = file("local/zlib"))   // 离线 / 自带产物
    }
}
```

### 2.4 版本与 BOM

- 原生库独立 semver：`<upstream>-keel.<revision>`，revision 是封装修订号。
- 发布一个 BOM（`keel-thirdparty-bom`），`keel { thirdparty { library("sqlite") } }` 不写版本时按 BOM 解析。
- 原生库可以单独发补丁版，不必等 keel 主版本；BOM 负责把一组版本钉成一套。

### 2.5 可选：预编译 klib

不作为默认发布物。klib 与 Kotlin/Native 编译器版本绑定，外部插件换版本就会失效。
需要加速时，由 `keel-thirdparty-gradle-plugin` 在消费方本地生成一次并缓存；
真要发布，按 Kotlin 版本命名（`sqlite-klib-2.4.20`），并标明只适用于该版本。

### 2.6 脱离 keel 使用

`keel-thirdparty-gradle-plugin` 只做「解析工件 + 建 cinterop + 配链接」，与 keel 无关：

- 不依赖 `keel-api`、Host、`.keel` 或任何 keel 运行时；
- 只依赖 Kotlin Gradle 插件，普通 KMP + native 目标即可用；
- 工件就是普通 Maven zip，仓库地址可配，也可用 `dir(...)` 指向本地产物；
- 许可声明任务同样独立：`keelThirdpartyNotices` 生成当前项目链接到的库的声明。

DSL 统一挂在 `keel { }` 根容器下。`keel-gradle-common` 只提供容器本身
（`register` / `find` / `get`），不知道任何 section 类型；每个插件 apply 时注册自己的 section，
并在 `moe.lolosia.keel.gradle.common` 包里提供 DSL 扩展函数。
build 脚本需要一行 `import moe.lolosia.keel.gradle.common.*`；应用了哪个插件，classpath 里才存在哪个 section。
因此 thirdparty 可以单独分发，不会把别的插件的 section 类型拖进来（版本规则见 §2.7）。

因此它和 `keel-classpath` 的定位一致：keel 自己用，外部工程也能直接依赖。
**命名统一带 keel 前缀**——这是系列归属，不是运行时耦合：

| 项 | 坐标 |
| --- | --- |
| Gradle 插件 | `moe.lolosia.keel.thirdparty`（工件 `moe.lolosia.keel.toolchain:keel-thirdparty-gradle-plugin`） |
| 原生库工件 | `moe.lolosia.keel.thirdparty:<lib>-<target>` |
| BOM | `moe.lolosia.keel.thirdparty:keel-thirdparty-bom` |

外部工程只需要配置仓库、apply 这个插件；不引入 `keel-api`、Host 或任何运行时依赖。
keel 作为插件平台，工具链插件统一在 `moe.lolosia.keel.toolchain` 下，第三方库统一在
`moe.lolosia.keel.thirdparty` 下，能力包（包括将来的 web / ktor 能力）按 `provides` 区分。

### 2.7 统一 `keel { }` 容器的限制

容器的类身份必须唯一：**所有 `keel-*-gradle-plugin` 必须依赖同一份 `keel-gradle-common`**。
各插件自带一份 `KeelExtension` 类时，`findByType` 会因为类身份不同而找不到，
最后变成「extension already exists」或新开一个同名容器。

规则：

- `keel-gradle-common` 只提供 `KeelExtension` 容器（`register` / `find` / `get`），
  **不包含任何 section 类型**；各插件在 apply 时
  `findByType(KeelExtension::class.java) ?: create("keel", …)`，再 `register(自己的 section)`。
- 每个插件在 `moe.lolosia.keel.gradle.common` 包里提供 `KeelExtension.xxx` 扩展函数；
  build 脚本用 `import moe.lolosia.keel.gradle.common.*` 引入。
  应用了哪个插件，classpath 里才存在哪个函数，所以不存在「空壳 section」。
- 新增 section 不改 common，插件可以独立升级；只有容器 API 变化时才需要所有工具链插件同版本，
  否则 classloader 里会出现两份 `keel-gradle-common`。
- section 的注册发生在 `plugins { }` 应用阶段；用旧的 `apply(plugin = "…")` 且写在
  `keel { }` 之后时，函数在 classpath 上但运行期尚未注册，`get()` 会给出明确报错。
- 旧顶层扩展名（`keelPlugin` / `keelClasspath` / `keelAbi` / `keelLogger` / `keelPublish` / `keelThirdparty`）
  保留为同一对象的别名，不需要改现有脚本。
- 如果将来连一行 import 都不想要，退回「每个插件一个顶层扩展」即可，
  插件实现层面没有其他差别。

### 2.8 生成侧布局（`keel/keel-thirdparty/`）

生成侧是一个独立 Gradle 构建，默认不参与根 `buildAll`：

```text
keel-thirdparty/
  settings.gradle.kts
  build.gradle.kts        # compileAll / stageAll / publishAll / rebuildAndPublish
  manifest.json           # groupId = moe.lolosia.keel.thirdparty
  gradle/codecs.gradle.kts  # 源码下载/解压 + clang/cmake 交叉编译（Kotlin，跨 OS）
  zlib/ png/ webp/ jpeg/ gif/ msgpack/
    <lib>.def
  licenses/<lib>/         # 上游许可全文
  build/thirdparty/out/   # 源码编译产物（include/ + <target>/）
  build/dist/             # 按 manifest 拆出的 <lib>/<target>/{include,lib}
  build/thirdparty/repo/  # keelThirdpartyPublish 产出的 Maven 布局
```

- `compileAll` 负责源码编译：`gradle/codecs.gradle.kts` 按 manifest 下载源码包
  （先自建镜像 `/sources`、再上游，校验 sha256），用 K/N 自带 clang 与各目标 sysroot
  一次产出 mingwX64 / linuxArm64 / linuxX64。Windows 与 Linux 共用同一份实现，
  OS 差异只在工具链定位（`llvm-*`、`msys2-mingw-w64-*`、`*-unknown-linux-gnu-gcc-*`）
  与 CMake 生成器选择上；不依赖 PowerShell / bash / Python。
- `manifest.json` 的每个 `source` 除了 `url` / `sha256`，还要有 `name`（上游包名，
  如 png → `libpng`、msgpack → `msgpack-c`）：下载文件名、解压根目录与
  `/sources/<name>/<version>/` 镜像路径都用它。
- `stageAll` / `publishAll` 是纯 JVM 任务，任何平台都能执行；
  用 `-Pkeel.thirdparty.out=<目录>` 复用别处编译好的 `out/`。
- 单个目标可单独跑：`compileCodecsMingwX64` / `compileCodecsLinuxArm64` /
  `compileCodecsLinuxX64`；源码缓存与 CMake 构建目录都在 `build/thirdparty/` 下（不入库），
  重跑 `downloadCodecSources` / `extractCodecSources` 即可重建。

---

## 3. 许可策略

### 3.1 允许列表（按链接方式）

| 许可 | 静态链接 | 动态链接 | 附加要求 |
| --- | --- | --- | --- |
| MIT / BSD-2 / BSD-3 / ISC / Zlib / BSL-1.0 | 允许 | 允许 | 保留版权与许可文本 |
| Apache-2.0 | 允许 | 允许 | 保留 LICENSE 与上游 NOTICE；标注修改 |
| Public Domain（SQLite） | 允许 | 允许 | 建议保留来源说明 |
| FTL（FreeType） | 允许 | 允许 | 文档中保留 FreeType 版权声明 |
| LGPL-2.1 / LGPL-3.0 | **禁止** | 允许 | 附许可全文、源码地址、可替换说明 |
| GPL / AGPL | 禁止 | 禁止 | 除非该插件本身以同许可发布 |
| 未识别 / 自定义 | 禁止 | 禁止 | 先确认再进允许列表 |

### 3.2 LGPL 的落地方式

默认动态链接：把 `.dll` / `.so` 作为**独立文件**随能力包或插件目录分发，运行期加载；
附 LGPL 全文、上游源码地址，以及「用户可以替换该动态库」的说明。

不默认采用静态链接。若确有必要，必须同时提供：

- 使用方可以重新链接的目标文件（插件自身的 `.o` / 静态库），或等价的书面提供；
- 上游源码获取方式与修改说明；
- 许可全文。

MariaDB Connector/C 属于 LGPL：实现上优先动态链接，不要把它静态并进插件。

### 3.3 修改上游

- 所有修改以 `patches/*.patch` 形式入库，manifest 记录 patch 列表；
- Apache-2.0 需标注「修改过的文件」；
- LGPL 需说明修改内容与日期；
- 只做构建适配（如交叉编译参数）也算修改，同样要记录。

### 3.4 常见库速查

| 库 | 许可 | 建议 |
| --- | --- | --- |
| SQLite | Public Domain | 静态，附来源说明 |
| zlib | Zlib | 静态，附许可证 |
| libpng | libpng License v2 | 静态；打 patch 时注明修改版 |
| libjpeg-turbo | IJG + BSD-3 + Zlib | 静态，合并列出 |
| libwebp | BSD-3 | 静态 |
| giflib | MIT | 静态 |
| msgpack-c | BSL-1.0 | 静态 |
| SDL3 | Zlib | 静态或动态均可 |
| FreeType | FTL | 静态；保留 FTL 声明句 |
| RmlUi | MIT | 静态 |
| MariaDB Connector/C | LGPL-2.1 | 动态 |
| OpenSSL 3 | Apache-2.0 | 动态或静态；带 NOTICE |

### 3.5 随源码分发的例外

如果确实把上游源码（或其中的胶水代码）复制进了仓库，必须单独列在
`THIRD-PARTY-NOTICES.md` 的「随源码分发」一节，保留原版权头，并说明是否修改。
这是例外路径，不是默认做法。

---

## 4. Manifest

```json
{
  "groupId": "moe.lolosia.keel.thirdparty",
  "libraries": {
    "sqlite": {
      "version": "3.46.0",
      "revision": 1,
      "source": {
        "url": "https://sqlite.org/2024/sqlite-amalgamation-3460000.zip",
        "sha256": "…"
      },
      "headers": ["sqlite3.h"],
      "def": "sqlite.def",
      "libs": {
        "mingwX64": ["sqlite3.a"],
        "linuxX64": ["libsqlite3.a"],
        "linuxArm64": ["libsqlite3.a"]
      },
      "link": "static",
      "license": "PD",
      "licenseFiles": ["licenses/sqlite/LICENSE"],
      "patches": []
    }
  }
}
```

字段约束：

| 字段 | 要求 |
| --- | --- |
| `version` | 上游版本，原样记录 |
| `revision` | 封装修订号，patch / 编译参数变化时 +1 |
| `source.url` / `sha256` | 必填；CI 只接受这两种来源 |
| `def` | 必填；随 zip 分发 |
| `libs` | 每个参与构建的目标都要有 |
| `link` | `static` / `dynamic`，与许可策略联动 |
| `license` | SPDX 标识；多许可用 `OR` / `AND` |
| `licenseFiles` | 必填；文件必须在 zip 内 |
| `patches` | 有 patch 就必须列全 |

---

## 5. 声明生成与产物携带

### 5.1 生成物

发布任务从 manifest 生成：

- `THIRD-PARTY-NOTICES.md`（按「静态度 / 动态库 / 构建期依赖」分组）；
- `SBOM.json`；
- `licenses/<lib>/…`（从 `licenseFiles` 复制）；
- `upstream-source.txt`。

生成物随 zip 发布，不允许手工编辑。仓库根的 `THIRD-PARTY-NOTICES.md` 只保留
「随源码分发」与工具链说明，native 部分由发布时生成。

### 5.2 插件包与最终产物

- 每个 `.keel` 包内带 `licenses/` 与生成的 `THIRD-PARTY-NOTICES.md`；
- `plugin.json` 可选 `licenses: [{ name, spdx, file }]`，供 Host 聚合；
- `keel-host --licenses` 打印已加载插件 / 能力包的许可清单；
- 单 exe 打包时把内嵌 `.keel` 的声明合并成一份 `THIRD_PARTY_NOTICES.txt` 一并嵌入；
- 桌面应用在 About 页读取同一份聚合清单。

静态链接进最终可执行文件的库，其声明必须出现在**最终产物**的分发里，
不能只出现在 Maven 工件里。

---

## 6. CI 门禁

| 任务 | 作用 |
| --- | --- |
| `keelThirdpartyCheckLicenses` | SPDX 在允许列表；`licenseFiles` 存在；`source.sha256` 存在；Apache 带 NOTICE；LGPL 不允许 `link=static`；有 patch 必须记录 |
| `keelThirdpartyCheckManifest` | 每个目标都有 `libs`；`def` 存在；版本 / revision 合法 |
| `keelThirdpartyVerify` | 逐个目标做一次最小链接，确认 `.a` / `.so` 与系统库能链上 |
| `keelThirdpartyPublish` | 生成声明 / SBOM 后发布 zip；不带生成物不允许发布 |

发布前检查声明是否过期：重新生成并与仓库中的副本比较，不一致直接失败。

---

## 7. 不采用的方案

- **仓库 vendor 上游源码**：无法复用上游安全更新，许可与体积都失控。
- **只发预编译 klib**：Kotlin/Native 版本绑定，外部插件换版本即失效。
- **静态链接 LGPL**：需要额外提供 relink 目标文件与书面材料，维护成本高于动态链接。
- **把 GPL / AGPL 库当普通依赖引入**：会改变整个插件的分发许可。
- **只在 Maven 工件里放声明**：最终 exe / `.keel` 的分发仍然缺声明。
- **把接线塞进 `moe.lolosia.keel.plugin`**：会让非 keel 工程无法复用，也把构建逻辑和插件打包耦合在一起；
  正确做法是独立插件，keel 自己的插件构建再 apply 它。

---

## 8. 待办

1. 落地 `keelThirdparty` manifest 与 `keelThirdpartyCheckLicenses` / `keelThirdpartyVerify`。
   **插件已实现**（`keel-thirdparty-gradle-plugin`）：manifest 为空时门禁任务跳过。
2. 第一个工件用 SQLite（公有领域、单文件 amalgamation）打通全流程；
   需要上游源码的 `source.sha256` 与各目标产物。
3. MariaDB Connector/C 先定动态链接的部署形态（`.dll` / `.so` 放在哪、如何替换）。
4. 把 `--licenses` 与单 exe 声明聚合接进 Host。
5. LGPL 静态链接、AGPL 等边界场景走一次法务确认。
