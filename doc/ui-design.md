# 桌面 UI 系统设计（keel-plugin-deck）

> 状态：设计稿，未实现。
> 目标：让插件用接近 `Activity + R.layout.x` 的写法声明界面，由独立插件 `keel-plugin-deck`
> 在自己的运行时里持有窗口，用 RmlUi + SDL3/SDL_GPU 渲染；插件与 deck 之间只走消息，
> **不共享任何 Kotlin 对象**。
> 相关：[abi-design.md](abi-design.md)（事件泵 / 句柄 / 跨运行时边界）、[console-design.md](console-design.md)
> （窗口 + 子进程 helper 的先例）、[capability-design.md](capability-design.md)、[../AGENTS.md](../AGENTS.md) §5。

---

## 1. 结论

| 问题 | 结论 |
| --- | --- |
| 能不能做 | 能。但要先把「View 对象」从**实例**降级成**声明 + 本地门面** |
| 控件树归谁 | 只有 deck 那一侧（含 helper 进程）；业务插件侧只有一个句柄式的 facade |
| 布局语言 | RML/RCSS（XML + flexbox），不做 Kotlin DSL，也不做可视化设计器 |
| 渲染 | RmlUi + SDL3；出帧走 `SDL_GPU`（Windows 上通常选 D3D12，可强制 Vulkan） |
| 窗口归谁 | OS 窗口在 deck 拉起的 helper 进程里；MainWindow 的**模型**（标签页 / 文档 / 主题）在 deck 插件里，Host 不参与 |
| 通信 | `Messenger.post`（单向、CBOR）为主；`request` 只用于控制面，且**禁止在回调里反向 request** |
| 注册 | `@PublicView` + 显式 `ctx.ui.register(view)`；`ctx.ui.setDefaultView(view)` 指定默认页 |
| 与 `@PublicApi` 的关系 | **对象级透传**是目标形态（1 个 View 句柄 + 少量方法 + 生成的 DTO），依赖尚未落地的句柄表；**控件级透传不做**。见 §7 |
| 命名 | 插件叫 **deck（甲板）**；API 面仍用 `ctx.ui` 这种通用词，不让开发者被迫记框架名 |

支撑这个结论的是三条硬约束（[abi-design.md](abi-design.md) §2 / §5）：

1. **一个线程只能进入一个 Kotlin/Native 运行时**，Host 与每个插件各是一套运行时；
2. **Kotlin 对象不跨边界**，只能传 C 结构体、字节、不透明句柄与纯 C 函数指针；
3. **协程不跨 C ABI**，异步一律退化成「投递 + 回调通知」。

Android 的 `View` 是「同一进程、同一运行时里的真实对象图」，WPF 的 `Visual` 同理。
这里做不到——所以能复刻的是**API 手感**，不是**对象模型**。

---

## 2. 不变量

1. **控件树只有一个所有者**：deck（及其 helper）。业务插件永远不持有真实节点，只持有节点路径字符串。
2. **渲染路径全异步单向**：帧内不发起任何阻塞调用。`callHost` 会占住调用方 Worker，
   且**在等待期间重入事件泵**（`keel-api/src/nativeMain/.../worker/WorkerCalls.kt:38-93`），
   所以双向 `request` 不会硬死锁，而是**调用反转**——等待中的一侧会被插进别的回调。
   结论仍然是不在渲染路径上用 `request`，理由见 §4.6。
3. **一帧一补丁**：同一轮事件里对同一属性的多次写入合并成一条，最后一笔生效。
4. **delegation 是单向的**：业务插件 → deck 只发补丁与事件；deck → 业务插件只发输入事件与查询结果。
   没有「A 等 B，B 又等 A」的调用形状。
5. **没有 deck 是正常状态**：headless 服务器、`--serve`、CI 里都不该被 UI 影响；消息丢了只告警。
6. **关窗不等于退出进程**：Host 继续跑，deck 可以重新开窗。
7. **插件卸载 = 视图销毁**：deck 清文档、清模型、清缓存；K/N 代码仍驻留（运行时不可卸载）。
8. **协议先行**：deck 只是协议的一个实现。将来换 Web/远程渲染器，只要协议不变。
9. **消息处理只入队**：`onMessage` / 动作回调里只改本地状态、往队列里放补丁；
   **不在消息处理中途改渲染状态或发起跨运行时调用**（重入安全，见 §4.6）。

---

## 3. 为什么不能照搬 Android / WPF

### 3.1 概念映射

| Android / WPF | Deck | 差别 |
| --- | --- | --- |
| `Activity` | `BasicView`（一份声明 + 生命周期） | 可能随时被拆掉重建，不是「页面实例的唯一真源」 |
| `setContentView(R.layout.x)` | `loadLayout(R.layout.x)` | 只记录引用，**不 inflate**；inflate 在 deck |
| `findViewById` | `ui.text("title")`（编译期常量路径） | 无反射、无往返；拿到的是 facade |
| `view.setText()` | `text.set("…")` → 下一条消息 | 异步，当前帧之后生效 |
| `view.getText()` | 只能读到本地写穿缓存 | 想要真值要 `suspend snapshot()` |
| `onClick` | 布局里 `action('id')` → 消息 → 本地回调表 | 在插件自己的 Worker 上执行 |
| `invalidate()` / `onDraw` | 无 | RmlUi 负责布局与绘制，插件不能插手 |
| `measure/layout` | 无 | flexbox 由 RCSS 决定 |
| 跨 Activity 共享 View | 无 | 只能共享「声明」，各自实例化 |

### 3.2 所以架构长这样

```
        ┌───────────────── 业务插件运行时（A）─────────────────┐
        │  ViewModel / State / 协程                            │
        │  BasicView（facade）── 节点路径 + 属性写入            │
        │  R.layout.x（编译期生成的资源常量）                   │
        └───────────────────────┬──────────────────────────────┘
                                │ post(CBOR)  ui.patch / ui.event
        ┌───────────────────────▼──────────────────────────────┐
        │  Host（只路由，不解释载荷）                            │
        └───────────────────────┬──────────────────────────────┘
                                │ post(CBOR)
        ┌───────────────────────▼──────────────────────────────┐
        │  keel-plugin-deck（运行时 B）                         │
        │  视图登记 / 标签页 / 补丁合并 / 资源预取 / 焦点仲裁      │
        └───────────────────────┬──────────────────────────────┘
                                │ 帧协议（两条单向命名管道）
        ┌───────────────────────▼──────────────────────────────┐
        │  keel-deck-host（纯 C++ 进程）                        │
        │  SDL3 窗口 · RmlUi 文档/模型 · SDL_GPU 出帧 · 输入     │
        └──────────────────────────────────────────────────────┘
```

三层边界，其中前两层都是仓库里已经验证过的形状：
插件 → Host 是现成的 `Messenger`；插件 → helper 是 `keel-plugin-console`
已经跑通的「materialize + 命名管道 + 定长帧头」。

### 3.3 为什么窗口在 helper 进程里，而不是插件 dylib 里

| 维度 | 插件内开窗（in-process） | helper 进程（推荐） |
| --- | --- | --- |
| GPU / 驱动崩溃 | 带走整个 Host（HTTP + 所有插件） | 只死窗口进程 |
| 静态库进 dylib | Linux 要求 `-fPIC` 重建 SDL3/FreeType/RmlUi；还要处理 libstdc++ | 普通 exe，沿用现有工具链 |
| 帧率与 Host 抖动 | 帧循环占着 deck 的 Worker，任何插件消息都要排在 16ms 之后 | 渲染线程独立，Host 卡顿不影响动画 |
| 迭代速度 | 改一行 C++ 要重链 K/N 插件 | 单独的 cmake/ps 构建，可热替换 exe |
| 复杂度 | 少一个进程 | 多一个进程 + 管道 |

「崩溃别带走 Host」这一条就足够定案了。**主方案 = helper**；插件内开窗保留为
`deck.mode: embedded` 的开发模式（调试 C++ 时少一层管道）。

> 注：用户侧的心智模型仍然是「keel-plugin-deck 持有 MainWindow」——窗口由 deck 拉起、
> 由 deck 独占、Host 连它存在都不知道；只是它恰好落在子进程里，和 console 插件同一个取舍。

---

## 4. 协议

### 4.1 两个边界，两种编码

| 边界 | 载体 | 编码 | 说明 |
| --- | --- | --- | --- |
| 业务插件 ↔ deck | `Messenger.post` / `request` | CBOR（`ServiceWire`） | 走 Host 路由，与 [abi-design.md](abi-design.md) §6 一致 |
| deck ↔ helper | 两条单向命名管道 | 定长 12 字节帧头 + 载荷 | 与 console-design §6.2 同形，magic 换成 `KDK1` |

`Messenger` 已经实现（`PluginMessenger` + `HostPump.routeMessagePost/Request`），
`Plugin.onMessage` 也已经是正式契约，**不需要新增 ABI**。

### 4.2 业务插件 → deck

| channel | 载荷 | 语义 |
| --- | --- | --- |
| `deck.declare` | `ViewDecl` | 登记一个视图：布局 **URL**（`keel://<pluginId>/…`）、标题、顺序；**不传布局正文** |
| `deck.patch` | `ViewPatch(seq, ops)` | 属性补丁；`seq` 单调递增，用于发现丢包 |
| `deck.detach` | `ViewId` | 显式撤销（`onDisable` 时兜底再发一次） |
| `deck.resync` | `ViewId` | 请求全量重发（deck 发现补丁缺口时发） |
| `deck.focus` | `ViewId` | 请求把标签页切到该视图 |

### 4.3 deck → 业务插件

| channel | 载荷 | 语义 |
| --- | --- | --- |
| `deck.hello` | 空 | deck 刚启动 / 重开窗，请重发你的声明 |
| `deck.event` | `UiEvent(viewId, action, payload)` | 点击 / 提交 / 滚到某行 |
| `deck.input` | `FieldBatch(viewId, seq, map)` | 表单双绑读回（一帧一批） |
| `deck.attach` / `deck.detach` | `ViewId` | 视图已挂载 / 已销毁，供生命周期钩子 |
| `deck.resyncReq` | `ViewId` | 缺口确认后请插件重发全量状态 |

`deck.hello` 是**顺序无关**的关键：deck 可能比业务插件晚启动、也可能被用户关窗后重开。
deck 在 `onEnable` 与每次开窗时，遍历 `ctx.server.plugins.ids`（`isEnabled`）逐个 `post` 一次。

### 4.4 补丁操作集（v1）

| op | 参数 | 落到 RmlUi |
| --- | --- | --- |
| `SetText` | `id, text` | 模型的字符串字段 |
| `SetBool` | `id, prop(visible/enabled/checked), value` | `data-if` / `data-checked` |
| `SetNumber` | `id, prop(progress/value), value` | `data-attr-value`（进度条） |
| `SetImage` | `id, resourceKey` | 资源表里已注册的图片 |
| `SetClass` | `id, className, on` | `data-class-*` |
| `SetInnerRml` | `id, rmlText` | `Element::SetInnerRML`，用于列表 / 富文本 |
| `SetFocus` | `id` | 焦点与光标 |

`SetInnerRml` 是把「列表、表格、自定义组合」一次解决掉的逃生口：deck 不解析业务类型，
插件拼片段、负责转义，RCSS 照常生效。代价是整段重排，只在内容变化时发。

`data-for` 在 RmlUi 里是原生能力，但**不要复用 backend 那份「32 槽标量模型」的受限门面**
（它是那份实现的产物，不是 RmlUi 的限制）。deck 的新门面按 RmlUi 的 `DataModel` 原生语义设计，
v2 有性能需求时再给列表加结构化绑定。

### 4.5 批处理、序号与背压

- 补丁在**插件 Worker 一轮事件分发结束时**统一 flush：用一个 `pending` 标志 +
  `ctx.scheduler.schedule(0) { flush() }`，天然合并同一轮里的所有写入，且不引入第二条线程。
- 每条 `deck.patch` 带 `seq`；deck 侧只接受 `seq == last + 1`。发现缺口时发 `deck.resyncReq`，
  插件回一帧全量 `deck.declare`（声明本身就是全量快照）。
- 单条载荷控制在 64 KiB 以内；更大由插件分批（`SetInnerRml` 的超长片段按行切）。
- Host 的 inbox 有界，满了 `post` 会被丢弃并告警一次——这正是需要 `seq` 的原因。
- **不做**：为 UI 单独开一条高优先级通道。它会绕过 Host 的既有背压，收益不抵复杂度。

### 4.6 为什么渲染路径不用 `request`

`callHost` 的等待循环会**在插件自己的 Worker 上同步阻塞**，但等待期间会**重入事件泵**
（`keel-api/src/nativeMain/.../worker/WorkerCalls.kt:38-93`，回包之外的事件就地分发）。
所以双向 `request` 不会硬死锁，真实后果是**调用反转**：

```
deck:  call(view.onAction)  ──等待──▶  插件 Worker 执行 onAction
                                          │ 里面又 call(deck.patch)
                                          ▼
deck:  callHost 的等待循环收到该请求 → 就地执行 → 在 frame 中途改了渲染模型
```

两条具体理由，所以仍然定死：

1. **重入面不可控**：deck 的渲染状态会在 `frame()` 中途被别的回调改掉；
   插件侧同理，自己的业务不变量会在等待期间被插进来的消息打破。
2. **成本**：一帧十几次往返，每次都要再进一次事件泵；批处理的收益被抵消。

规则：

- 渲染路径（补丁、输入、事件）**只用 `post`**；
- `request` 只用于控制面（`deck.status`、`deck.listViews`），且**只能从 API 的显式方法发出**，
  不允许在 `onMessage` / 动作回调内部自动发出；
- deck 侧同理：处理插件消息时**不允许同步回调**插件，只能 `post`；
- 消息处理只入队，渲染状态只在帧边界改（不变量 9）。

真正的死锁只出现在两种写法上：在回调里持锁再发起跨插件调用，以及用 `runBlocking`
（见 [public-api-design.md](public-api-design.md) §6.6）。

---

## 5. 插件侧 API

### 5.1 最小例子

```kotlin
// 业务插件
@PublicView(title = "番组表", order = 100)
class AnimeTimetableView(
    private val api: AnimeApi,
    private val scope: CoroutineScope,
) : BasicView() {

    private val layout = loadLayout(R.layout.anime_timetable)
    private val title = layout.text("title")
    private val status = layout.text("status")
    private val table = layout.node("table")

    private val items = State<List<AnimeSeriesDto>>(emptyList())

    init {
        layout.button("refresh") {
            status.set("正在刷新…")
            load()
        }
        items.observe { list ->
            table.innerRml(renderRows(list))     // 拼片段；转义由这里负责
            status.set("共 ${list.size} 部番")
        }
    }

    override fun onCreate() = load()

    private fun load() {
        scope.launch {
            runCatching { api.timetable() }
                .onSuccess { items.value = it }
                .onFailure { status.set("加载失败：${it.message}") }
        }
    }
}
```

```kotlin
// 插件入口
@KeelPluginMain
class AnimePlugin : DeckPlugin() {

    override fun onEnable() {
        ctx.ui.setDefaultView(AnimeTimetableView(api, ctx.scope))
    }

    override fun onDisable() {
        ctx.ui.close()          // 注销全部视图；deck 收到后销毁文档
    }
}
```

`DeckPlugin` 是 deck-api 提供的薄基类：把 `onMessage` 按 channel 分给内部 dispatcher，
并保证 `deck.hello` 到达时重发全部声明。不想继承也行，调用 `DeckDispatcher(ctx).handle(message)`。

### 5.2 生命周期映射

| BasicView | 触发时机 | 线程 |
| --- | --- | --- |
| 构造 | 插件 `onEnable` | 插件 Worker |
| `onCreate` | 收到 deck 的 `deck.attach` | 插件 Worker |
| `onAttach` / `onDetach` | 标签页切走 / 切回 | 插件 Worker |
| `onDestroy` | `deck.detach`、`ctx.ui.close()`、插件停用 | 插件 Worker |
| 动作回调 | deck → `deck.event` | 插件 Worker |

全部在 Worker 上，所以开发者可以放心用 `ctx`、`scope`、`State`；但**不能阻塞**——
阻塞只会卡住自己的视图，deck 与其它插件不受影响（这正是运行时隔离带来的好处）。

`onCreate` 之前写的属性不会丢：补丁在 attach 之后才发，之前的写入留在本地 `pending` 里。

### 5.3 `R` 与布局资源

- `resources/layout/*.rml` → `R.layout.<文件名>`；`resources/style/*.rcss` → `R.style.<文件名>`。
- v1 先提供字符串重载 `loadLayout("layout/anime_timetable.rml")`；
  `R` 对象由 `moe.lolosia.keel.plugin` 打包时顺带生成（它已经在扫 `resources/`），
  **不新开 Gradle 插件**。
- `loadLayout` 只记录引用；参与声明时把它解析成一个 **URL**（`keel://<pluginId>/layout/x.rml`）。
  deck 按 URL 通过 Host 的 classpath 注册表解析，**不需要插件把正文推过来**；RCSS / 图片同理
  （helper 是独立进程，所以 deck 先 `ctx.materialize` 到 `cacheDir`，再把根目录交给它）。
- 资源缺失在 attach 阶段报错，日志带插件 id 与路径。

### 5.4 绑定与读写语义

```kotlin
val text: TextNode = layout.text("title")
text.set("番组表")          // 写穿缓存 + 入 pending
text.value                  // 本地缓存，可能落后一帧
suspend fun text.await()    // 显式向 deck 要真值（走 request，不在渲染路径里）
```

规则：

- 写永远立即生效（本地），补丁异步下发；
- 读永远读本地缓存，**不隐式往返**；
- 表单控件（`data-value` / `data-checked`）以 deck 为真源：
  deck 发 `deck.input` 批量读回，插件侧回调里更新自己的 `State`；
- 焦点仲裁：deck 知道哪个输入框有焦点，**有焦点的字段忽略插件的写入**，
  避免光标跳动与「打一个字被远端覆盖」。这是 [backend AGENTS §7](../../backend/AGENTS.md) 里
  「不要在 change 回调里当场读」那条经验在跨运行时的对应物。

### 5.5 MVVM / State

deck-api 里放一份极小的 `State<T>` / `ListState<T>`（约 40 行，赋值即通知、同值不触发），
不引入任何响应式框架。开发者当然可以自带（Compose 的 `MutableState` 也能用，只要在 Worker 上写）。

---

## 6. keel-plugin-deck 内部

### 6.1 MainWindow

一份 deck 自有的 RML 文档，插件不可修改：

```
window.rml
├── titlebar      （窗口标题、最小化 / 关闭）
├── tabbar        （每个声明一个 tab，图标 + 标题 + 顺序）
└── content       （同一时刻只显示一个视图文档）
```

- 视图文档按 `document.show()/hide()` 切换，不销毁；
- tab 顺序 = `(order, pluginId, viewId)`；
- 「默认视图」= 配置 `deck.defaultView: "anime:AnimeTimetableView"`，未配置取第一个；
  `setDefaultView` 只是写这个偏好，不独占。
- 窗口标题、初始尺寸、主题走 `config/plugins/deck.yaml`。

### 6.2 文档、模型与上下文

RmlUi 的数据模型挂在 **Context** 上、按名字绑定；文档通过 `data-model="…"` 引用。
所以：

- 一个 Context + 每个视图一个文档 + **每个视图一个命名模型**（`view.<pluginId>.<viewId>`）；
- 不同插件的字段名互不冲突，也不需要「切视图时重绑模型」；
- 样式冲突用「文档根加 `class="keel-view keel-view-<pluginId>"` + 命名约定」控制；
  真要强隔离（防覆盖 chrome、防样式外溢）再升级成「每视图一个 Context 渲到纹理再合成」，
  那是 P3 的事，v1 不需要。

### 6.3 帧循环

helper 自己持有窗口与消息循环，与 Host 的事件泵完全解耦：

```
loop:
  SDL_PumpEvents
  收管道帧（非阻塞，读到没有为止）
  应用补丁 → 更新 DataModel
  处理输入 → RmlUi 事件 → 收集 action / 输入变化
  RmlUi::Context::Update + Render（SDL_GPU）
  release 输入事件（一次性 post 回 deck）
```

- 60fps 常驻；窗口失焦 / 最小化降到 10fps；无窗口时不跑。
- **不占用 `keel_plugin_set_wakeup` 的定时槽位**：60Hz 的 TIMER 事件会挤进 Host 的事件泵，
  和插件业务抢同一条队列。
- 输入事件在帧末统一发出，不在 RmlUi 回调里直接调 C 侧发送——避免在事件分发中途重入。

### 6.4 资源

| 资源 | 路径 | 谁读 |
| --- | --- | --- |
| 布局 / 样式 | 插件 `resources/` | deck 按 URL 解析，物化到磁盘后交给 helper |
| 图片（头像、封面） | 插件 `resources/`、运行时下载 | 静态图按 URL；运行时图片随 `SetImage` 前的 `ResourcePut` 推字节 |
| 主题 / 字体 | deck 自己 | helper 直接读磁盘 |

- 单张图片上限 4 MiB，超出走 `ctx.materialize` 落盘、把路径交给 helper（helper 能读真实文件）；
- 图片按 `(pluginId, key, hash)` 缓存，同 key 同 hash 不重传；
- 渲染期**绝不**跨运行时读资源：RML 里的 `<img src>` 一律指向已注册的 `keel://<pluginId>/<key>`。

### 6.5 关闭与重载

| 场景 | 行为 |
| --- | --- |
| 用户点关闭 | helper 退 0；deck 广播 `deck.detach`（进程内视图状态保留）；Host 无感 |
| `deck.open` 控制消息 | 重新 spawn helper，重放所有视图声明 |
| deck 插件停用 | 先请求 helper 关闭并 join（5s 上限），再清登记表 |
| helper 崩溃 | deck 把窗口状态降为 `failed`，告警一次；下次 `deck.open` 重试 |
| 业务插件停用 | 无需通知机制：deck 发现 `onMessage` 目标消失后按 `ctx.server.plugins.isEnabled` 复查，移除 tab |

最后一条需要说明：Host 目前**不广播插件生命周期**。v1 用 1s 一次的轻量轮询
（`ctx.server.plugins.isEnabled`）兜底；P2 建议 Host 在 `KEEL_EVENT_BUS` 上补
`PluginEnabled/Disabled` 两个事件，这对其它插件同样有用。

---

## 7. 与 `@PublicApi` / 共享对象的关系

> [public-api-design.md](public-api-design.md) 提供两样东西：**生成的代理**（跨插件调方法不用手写 CBOR）
> 与 **句柄化的有状态实例**（`publish` / `create` / `destroy`），另外还支持**跨插件子类**。
> 这一节回答：拿它来传 View，对 §3.2 的结构究竟有多少帮助。
> （本节出现的 `§x.y` 均指 public-api-design.md，除非另有说明。）

### 7.1 四条判断逐条核对

| 判断 | 结论 |
| --- | --- |
| 1. 可以直接把 View 对象传给 deck | **对象级成立，控件级不成立**。一个 View = 一个句柄 + 生成代理，deck 可以按方法调用它；但**每个控件一个句柄**会把一次批量传输变成 N 次往返，还得 deck 自己对账，不划算。 |
| 2. 别人可以继承你的 View | **技术上成立，UI 上不建议**。影子模型把基类半边放在声明方，声明方停用后子类实例就失去超类（§4.5 约束 4）。普通对象是「调用报错」，视图是「整页变白」。 |
| 3. deck 随时调回插件处理函数、注册 MVVM 回调 | **成立**，确实省掉手写分发。但需要补 `oneway`，否则补丁会变成一帧一次往返。MVVM 观察本身不需要跨运行时——VM 与 View 本来就在同一个运行时里。 |
| 4. 失效插件能被快速检测、对象被移除 | **只对一半**。句柄清理成立：插件停用时 Host 把它的句柄标 `Closed`、清单例（§5.2），对象不会泄漏。但**没有广播**（§5.4「失效惰性」），deck 仍然拿不到「该收 tab 了」的信号。 |

### 7.2 它改变的是「消息怎么写」，不是「对象在哪」

- deck 拿到的 `ViewProxy(handle)`，每次调用都是 `SHARED_CALL` 的一次往返；
- deck 问不出「这个视图有哪些节点、节点多大」——那需要 RmlUi 的文档树，只有 deck 自己有；
- 插件也没法让 deck「把这段布局 inflate 进来」——解析与实例化只能发生在 deck 里；
- 所以 §3.2 的分层不变，能替换的只是 §4 那六条 channel 的**实现方式**。

一句话：**它把「手写协议」升级成「生成协议」，但没有把「远程视图」变成「本地视图」。**

### 7.3 三种接法

| 方案 | 边界形态 | 代价 | 建议 |
| --- | --- | --- | --- |
| **U0** | 手写六条 channel + `BasicView` | 自己维护约两百行编解码与分发 | 句柄表落地前**唯一能做的**，也是 §4 的现状 |
| **U1** | **对象级透传**：1 个 View 句柄 + `snapshot()` / `onEvent()` + 生成的 DTO | 依赖句柄表（P1）；帧更新变成一次 pull | 句柄表落地后的**目标形态** |
| U2 | **控件级透传**：每个控件一个句柄 | N 个句柄、每帧 N 次往返、deck 还要对账 | 不做 |

- U0 → U1 是**换传输层，不是重写**：插件内部那份 dirty 队列、合并、焦点仲裁全都不动，
  只是把「`messenger.post` 一条补丁」换成「`snapshot()` 的返回值里带上它」。
- U1 真正省掉的不是代码量，而是**插件侧不必知道 deck 存在**：没有 push 方向，
  `ctx.shared.publish(view)` 之后只被动应答。这反而把不变量 5（没有 deck 是正常状态）守得更严。
- U1 唯一还要留的胶水是**发现**：`@PublicApi` 解决「拿到已知对象」，不解决「谁发布了视图」。
  两条路——Host 补一个「列出某插件的已发布句柄」的 subject（句柄表里本来就有 `types` / `singletons`），
  或保留最薄的一条 `deck.hello` channel。见 §7.7。

### 7.4 为什么 UI 不该用跨插件继承

影子模型的既有代价见 §4.5：`this` 分成两半、基类字段在声明方、虚调用多一跳、
基类必须能无参构造且可调用成员全 `open`。对 UI 还要多两条：

1. **基类构造器不能干活**（§4.6 只允许无参 / `HandleId` / `PluginContext` 三种构造参数），
   `BasicView` 想在构造器里 `loadLayout` 直接踩线；
2. **失效语义不可接受**：声明方停用 → 所有子类实例失去基类半边，视图不是「调用失败」，是「整页消失」。

正确的做法是把「可复用」放在不需要继承的地方：

| 想复用 | 做法 |
| --- | --- |
| 视图基类 / 生命周期 | 放 `keel-plugin-deck-api`（普通 klib，同一份类型，零成本继承） |
| 布局、样式、片段 | 作为资源共享（`ctx.classpath.of(id)`，或复制进自己的包） |
| 通用控件 | deck 自带的 `components` 文档 + RCSS 类 |
| 复杂交互 | 组合（把别的视图当一个子节点挂进来），不是继承 |

### 7.5 如果要用，`@PublicApi` 需要补两样

1. **单向成员**：`@PublicApi(oneway = true)`。补丁是通知、不需要回包；否则一帧十几次 `request`，
   会把 §4.6 的重入面放大。当前生成物只有请求 / 应答（§4.4 / §6.6）。
2. **重入约定**：`callHost` 等待期间会重入事件泵，代理调用因此可能落在 `frame()` 中途。
   生成物最好提供「投递到下一帧」的选项，或至少让调用点显式声明「可重入」。

### 7.6 句柄化的代价（如果选 U1）

- **每个视图一个句柄**：`detach` 必须 `destroy()`，否则活到插件停用；句柄表多一层视图级条目。
- **失效是惰性的**：deck 不能靠「下一帧调用失败」发现插件没了——那一帧已经画错了。
  仍然需要 §6.5 的检测（`isEnabled` 轮询，或 Host 的生命周期事件）。
- 好处是**归属清晰**：谁拥有、谁能销毁、插件停用时清哪些，都由 Host 的表回答，
  比 deck 自己维护「pluginId → 视图」的弱引用可靠。

### 7.7 对象级透传长什么样（U1）

```kotlin
@PublicApi
open class AnimeTimetableView : BasicView() {

    private val layout = loadLayout(R.layout.anime_timetable)   // 本地记录，不导出
    internal lateinit var api: AnimeApi                          // 由插件注入，不导出

    @PublicApi suspend fun layoutUrl(): String = layout.url
    @PublicApi suspend fun snapshot(): FrameState
    @PublicApi suspend fun onEvent(event: UiEvent)
}
```

```kotlin
@PublicApi data class FrameState(
    val seq: Long,
    val texts: Map<String, String> = emptyMap(),
    val flags: Map<String, Boolean> = emptyMap(),
    val numbers: Map<String, Double> = emptyMap(),
    val innerRml: Map<String, String> = emptyMap(),
)

@PublicApi data class UiEvent(
    val kind: String,                              // click / change / attach / detach
    val target: String = "",
    val value: String = "",
    val payload: ByteArray = ByteArray(0),
)
```

`Map<String, …>` 与 `ByteArray` 都在 §4.3 的白名单里（可递归），所以这两个是合法的
`@PublicApi` 值类型，序列化由工具链生成，**不用手写 CBOR**。

deck 侧：

```
每帧前： val st = view.snapshot()            // 1 次往返；超时保留上一帧并标 stale
         applyToModel(st)                    // 唯一改渲染状态的地方
每帧后： helper 的输入 → view.onEvent(...)    // 1 次往返
```

剩下什么、消失什么：

| 项 | U0（现状） | U1 |
| --- | --- | --- |
| 六条 channel 字符串 | 手写常量 | 三个成员（`layoutUrl` / `snapshot` / `onEvent`） |
| CBOR 编解码 + `onMessage` 分发 | 手写 | 工具链生成 |
| 补丁合并 / 焦点仲裁 | 插件侧 | 插件侧（不变） |
| `seq` 缺口检测 | 协议字段 | `FrameState.seq`（不变） |
| 帧更新时机 | 插件 push | deck 每帧 pull |
| 视图发现 | `deck.hello` 重发 | 仍需一条最薄的 hello（或 Host 补 subject） |

三条必须接受的代价：

1. **pull 把帧更新耦合到插件响应时间**。helper 独立出帧，所以窗口不会卡死，
   只是这个视图的内容停滞；超时保留上一帧并打 `stale`。
2. **每帧一次往返 + 一次快照序列化**。靠「只带脏字段」把体积压到几条到几十条；
   全量快照只在 `resync` 时用。
3. **不要给控件属性加 `@PublicApi`**。`SharedVar` 的每次 get / set 都是一次往返
   （§6.3），逐属性透传会把 60fps 拆成几千次 RPC；`FrameState` 存在的意义就是避免这件事。

最后：**句柄表还没落地**（`keel_abi.h` 里 `handle_*` 恒为 null，见 [任务记忆.md](任务记忆.md) §2.2），
所以 U1 最早与 P1 一起上。U0 与 U1 共用同一套插件侧实现，切换只动传输层。

---

## 8. 图形栈与构建

### 8.1 复用什么

同一工作区的 `backend` 已经有一份跑通的栈（SDL3 → FreeType → RmlUi → 自绘 C 门面 +
SDL_GPU 后端 + F8 内置调试器），交叉编译脚本也已覆盖 mingwX64 / linuxArm64 / linuxX64。
deck 的 helper 直接沿用这套源码与构建脚本，**只改门面层**：

- 多文档（load / show / hide / destroy）；
- 命名数据模型（`create_model(name)`，替代单一 `main`）；
- 资源表按 `keel://<pluginId>/<key>` 寻址；
- 补丁入口（`set_text` / `set_bool` / `set_inner_rml` / `put_image`）。

不要照搬那份「32 槽标量、单文档、单模型」的 ABI —— 它是「一个页面的实验」的产物，
不是通用 UI 的边界；`data-for` 与嵌套结构在 RmlUi 里是原生能力。

### 8.2 构建形态

```
keel-plugin-deck/
  src/commonMain/kotlin/.../deck/     登记、标签页、补丁合并、资源预取
  src/mingwX64Main/.../DeckProcess.mingw.kt   CreateProcessW + 命名管道
  src/nativeCpp/deck_host.cpp         helper 入口（窗口 + 帧循环）
  thirdparty/                          SDL3 / FreeType / RmlUi 的源码重建脚本
  build.gradle.kts                    keelPlugin { resources("build/generated/deck") }
```

- helper 产物落 `resources/native/keel-deck-host.exe`，运行期 `ctx.materialize` 拿真实路径；
- 只打 `mingwX64` 起步；linux 目标等 SDL3 的交叉产物与板子上的显示栈确认后再加
  （RK3566 上大概率没有合成器，先当 headless）。
- **图形栈只允许一个插件链接**：SDL 的窗口类名、驱动全局状态都不是多副本安全的。
  规则写进 AGENTS：`deck` 是唯一允许链接 SDL3 / RmlUi 的插件。

---

## 9. 开发者看到 vs 实际发生

| 代码 | 开发者以为 | 实际发生 |
| --- | --- | --- |
| `loadLayout(R.layout.x)` | 解析 XML、建控件树 | 记一个资源引用；attach 时把文本推给 deck |
| `text.set("a")` | 改了一个 TextView | 写本地缓存；本轮结束发一条 CBOR 补丁 |
| `text.value` | 读 TextView | 读本地缓存，可能落后一帧 |
| `button("ok") { }` | 注册了 listener | 注册了 action id；deck 收到点击后回发事件 |
| `items.observe { table.innerRml(...) }` | 刷新列表 | 拼 RML 片段 + 一条 `SetInnerRml` |
| `ctx.ui.setDefaultView(v)` | 切页面 | `Messenger.post` 到 `deck`，deck 建 tab |
| `onCreate()` | 视图创建完成 | 收到 `deck.attach` 之后才回调 |
| 插件卡 200ms | 界面卡 200ms | 只有自己的视图卡；其它 tab 与 Host 不受影响 |
| 点关闭 | 关掉应用 | 只关 helper 进程；Host 与所有插件继续运行 |

---

## 10. 做不到的事（明确边界）

| 想要 | 结论 | 替代 |
| --- | --- | --- |
| 同步读控件真实值 | 不能 | 本地缓存 + `await()`（显式、不在渲染路径） |
| `onDraw(Canvas)` 自绘 | 不能 | 基础图元 / `SetInnerRml` / 插件自己画位图后当图片推过去（类 `TextureView`） |
| 阻塞式模态对话框 | 不能 | `suspend` + 回调 |
| 拿到纹理 / GPU 句柄 | 不能 | 推字节；需要 GPU 时在 deck 里实现控件 |
| 跨插件共享 View 实例 | 不能 | 共享声明，各自实例化 |
| 自己决定尺寸与定位 | 部分 | flexbox + RCSS 类；`SetStyle` 只开白名单属性 |
| 在回调里再同步调 deck | 不能 | 违反 §4.6，只能 `post` |
| 多窗口 / 浮动面板 | v1 不做 | 先单窗口多标签；P3 再评估每视图独立 Context |

**自定义控件怎么办**：两条路。简单的用 `SetInnerRml` 组合现有元素；需要专用渲染
（图表、视频、画布）的，在 helper 里加一个元素类型，通过 `ui.custom("chart")` 暴露给所有插件——
这类需求天然属于 deck，不属于业务插件。

---

## 11. 生命周期与失败模式

| 情况 | 期望 |
| --- | --- |
| 没有安装 deck | `ctx.ui` 的所有调用静默成功（消息丢弃），插件照常跑业务 |
| deck 比业务插件晚启用 | deck `onEnable` 遍历 `ctx.server.plugins` 发 `deck.hello`，插件重发声明 |
| 布局资源缺失 | attach 失败，日志带路径；tab 显示「视图不可用」，不崩 |
| 补丁丢包（inbox 满） | `seq` 缺口 → `deck.resyncReq` → 全量重放 |
| 业务插件崩溃 / 停用 | deck 移除 tab（1s 内），保留占位与重试入口 |
| deck 插件停用 | 先关 helper，再清表；业务插件下次 `onEnable` 重新声明 |
| helper 崩溃 | 所有 tab 变灰，日志一条；`deck.open` 可重开 |
| 无 GPU / 远程桌面 | helper 启动失败 → `failed:gpu`，Host 不受影响 |
| 关窗后插件继续发补丁 | deck 丢弃并计数，重开窗口后靠 `deck.hello` 全量重放 |

---

## 12. 安全与隔离

- **同进程不代表同信任**：deck 与业务插件都在 Host 进程里，任何插件都能给 `deck` 发声明。
  v1 不做权限门（与「任何插件能读全部日志」同级），只在 tab 上标明来源插件。
- **样式不能越界覆盖 chrome**：v1 靠「标签页只显示一个文档 + chrome 文档最后加载」；
  想让业务视图物理上不可能覆盖，就得走每视图独立 Context 渲到纹理（P3）。
- **RML 无脚本**：RmlUi 不执行 Lua/JS，风险面主要是布局炸弹与超大图片。
  限制：单文档节点数、单张图片 4 MiB、单帧补丁 64 KiB。
- **资源可见性**：deck 会读任意插件的 `resources/`（`ctx.classpath.of(id)`），
  这与现有跨插件资源语义一致，不额外开口子。
- **不做**：沙箱、插件间 UI 权限、远程渲染。等 rbac 与远程 UI 真有必要时再谈。

---

## 13. 分阶段与验收

**D0 可行性尖刀（最高风险，先做）**

- helper 最小进程：SDL3 开窗 + RmlUi 加载一份静态 RML + SDL_GPU 出帧 + 关窗退出；
- deck 插件最小版：materialize helper、spawn、两条管道、`LOAD_DOC` 一帧指令；
- 验收：`--plugins` 启动 Host 时*不*开窗；`deck.open` 后出窗口；关窗 Host 不退出；
  `keel-host.exe` 进程与窗口进程互相独立（任务管理器能看到两个）。

**D1 声明与标签页**

- `@PublicView` + `ctx.ui.register` / `setDefaultView`、`deck.declare/hello/detach`；
- MainWindow：tabbar + 单文档切换 + 主题；
- 验收：hello 插件声明两个视图 → 两个 tab；停用 hello → 1s 内 tab 消失；重开窗口自动恢复。

**D2 补丁与事件**

- 补丁批处理、`seq` 与 resync、`SetText/SetBool/SetNumber/SetClass/SetInnerRml`；
- 动作回调、表单双绑、焦点仲裁；
- 验收：点击按钮 → 计数 +1 → 文本更新，端到端 < 2 帧；连续写 100 个属性只发 1 条补丁；
  制造一次丢包能自动恢复。

**D3 资源与观感**

- 图片注册与缓存、`keel://` 寻址、HiDPI、窗口 resize、字号/CJK 字体、IME；
- 验收：封面列表 100 行滚动流畅；中文输入法组合输入正常；150% 缩放下不错位。

**D4 打磨与可选**

- 每视图独立 Context（强隔离）、多窗口、`data-for` 结构化绑定、F8 调试器接入；
- 控件库（`components.rml` 那种目录页）作为 deck 自带文档，供插件参考。

---

## 14. 风险与待验证

1. **helper 能否在无桌面会话 / 远程桌面下创建 SDL_GPU 设备**；失败要落到 `failed:` 而不是崩。
2. **管道吞吐**：60fps 下每帧多条补丁、单条 64 KiB 时的稳定性；必要时改成共享内存环形缓冲。
3. **RmlUi 的多文档 + 多命名模型**在现有 C++ 代码上的改动量（比想象中小，但要实测）。
4. **中文 IME**：RmlUi 的 SDL 后端只做了一部分 `WM_IME_*` 处理，登录 / 搜索框会暴露问题。
5. **`SetInnerRml` 的重排代价**：大列表整段重建的帧时间；必要时退回 `data-for`。
6. **`create_process` / 管道 interop** 在 mingw 目标上的完整性（console 插件已验证一大部分）。
7. **K/N 无法卸载**：deck 停用后再启用，helper 与 RmlUi 的全局状态要能干净重建。
8. **跨运行时调用的重入**：`callHost` 等待时会重入事件泵，必须靠「消息处理只入队」兜住；
   否则渲染状态会在 `frame()` 中途被改。
9. **`ctx.scheduler` 的 `schedule(0)` 合并 flush**：与 `callHost` 的重入等待叠加时的行为需要实测。
10. **单 exe 场景**：helper 从内嵌 bundle `materialize` 后启动是否被杀软拦截（console 同风险）。
11. **pull 模式的响应时间**：U1 下 deck 每帧拉一次快照，插件 Worker 忙时该视图内容会停滞；
    需要超时 + `stale` 兜底，并在 D2 量一次 p95。
12. **句柄表的 P1 依赖**：U1 与 `@PublicApi` 的共享对象同批交付；U0 不受影响。

---

## 15. 未决问题

1. 插件视图的**主题覆盖**边界：允许改字号/圆角，还是只允许换颜色 token？
2. 「默认视图」是多插件竞争还是全局配置说了算（当前设计是后者，`setDefaultView` 只是提名）。
3. 是否需要 `@PublicView` 之外再加一个「无 UI 也能跑」的显式声明（便于 `--inspect` 展示）。
4. 业务插件能否声明「后台任务进度」这类常驻区域（类似状态栏），还是必须自己画在视图里。
5. Host 侧是否补 `PluginEnabled/Disabled` 总线事件（已列为 P2，影响的不止 UI）。
6. deck 是否要提供「单文件预览模式」（直接加载目录里的 RML，不连 Host），给样式调试用。

---

## 16. 文件地图（预期）

```
keel-plugin-deck-api/
  src/commonMain/kotlin/moe/lolosia/keel/deck/api/
    BasicView.kt                 视图基类：loadLayout / 绑定 / 生命周期 / 补丁队列
    LayoutScope.kt               text / button / node / table 的访问器工厂
    TextNode.kt  NodeRef.kt      节点 facade（写穿缓存 + pending）
    State.kt  ListState.kt       极小的可观察状态
    DeckClient.kt                ctx.ui 的实现：声明 / 补丁 / 注销 / hello 重放
    DeckPlugin.kt                可选基类：把 onMessage 分给 DeckClient
    annotation/PublicView.kt     @PublicView(title, order, icon)
    protocol/ViewDecl.kt · ViewPatch.kt · UiEvent.kt · FieldBatch.kt
keel-plugin-deck/
  src/commonMain/kotlin/moe/lolosia/keel/plugins/deck/
    DeckPlugin.kt                @KeelPluginMain；窗口状态机、插件目录轮询
    ViewRegistry.kt              视图登记与 tab 顺序
    ViewDocument.kt              单视图文档与命名模型
    FrameProtocol.kt             KDK1 帧编解码
    ResourceCache.kt             图片注册与 keel:// 解析
    DeckWindow.kt                expect：spawn / 管道 / 关闭
  src/mingwX64Main/.../DeckWindow.mingw.kt   CreateProcessW + CreateNamedPipeW
  src/nativeCpp/deck_host.cpp                窗口 + 帧循环 + RmlUi 门面
  src/nativeCpp/build.ps1                    SDL3 / FreeType / RmlUi / helper 构建
  thirdparty/                                manifest + 各层重建脚本
```
