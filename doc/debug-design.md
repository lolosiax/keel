# 调试支持设计（讨论稿）

> 状态：讨论稿，未定稿。只回答「IDEA 要支持调试需要什么」「自建断点系统需要哪些部件」。
> 结论：**「让 IDEA 支持调试」与「自建调试系统」是两个独立选择**。前者只需让平台的调试器能接上
> debug 产物；后者是把 Keel 自己的 IR 插桩 + C agent 接进 `com.intellij.xdebugger`。
> 有 IR 能力只解决「调试符号从哪来」，解决不了「谁停线程」「谁传变量」「IDE 怎么知道」这三件事。
> 相关：[abi-design.md](abi-design.md)（事件泵与线程模型）、[web-design.md](web-design.md)、[../AGENTS.md](../AGENTS.md) 第 5 节。

---

## 1. 路线 A：让平台原生调试器调 Keel

需要同时满足下列条件，缺一个断点就是灰的，或者停错行：

| 面 | 要求 | Keel 现状 |
|---|---|---|
| 构建 | `linkDebug*`、不 strip；`.keel` 里装的是 debug dll | 已满足：`keelPrepareRun` 固定 debug |
| Host | 带 DWARF 的 host | **不满足**：`keelPrepareHost` 解的是 Maven 预编译 host，没有调试信息 |
| 启动方式 | 进程必须由调试器创建 | **不满足**：[KeelRunProfileState.kt](../keel-idea-plugin/src/main/kotlin/moe/lolosia/keel/idea/run/KeelRunProfileState.kt) 直接 spawn，调试器接不上 |
| 模块加载 | dll 落盘后再 `dlopen`；未加载时断点为 pending | 满足：[LibraryCache.kt](../keel-host/src/nativeMain/kotlin/moe/lolosia/keel/host/plugin/LibraryCache.kt) 物化到 `.cache/<id>/<version>/` |
| 源码路径 | DWARF 里是编译机绝对路径，CI / 容器构建要做重映射 | 未处理 |
| 版本 | 加载的 dll 与 IDE 里的源码必须同版 | 无 buildId 校验，典型症状是「停下来了但行号偏一行」 |
| 平台 | 本地调试器组件（LLDB 集成）在 Ultimate / CLion，**社区版没有** | 插件目标是 IDEA Community 2025.2.6 |

所以路线 A 在社区版上直接不可行；在 Ultimate 上可行，但至少要改两处：**本地构建 Host**、
**让调试器自己启动进程**。

即使做通，K/N 上的天然边界也在：协程 / 内联在 DWARF 里是状态机与合成帧；Host 与插件是两个
独立运行时，只有 OS 线程视图，没有「逻辑栈」；release 无符号；`dlclose` 不可用，
所以会话内换插件版本只能重启。

---

## 2. 路线 B：自建调试系统

要「点 Debug 真能停」，绕不开下面三层。缺任何一层都会退化成「能跑但停不下来」。

```
构建期   IR 插桩 ──(同一趟 pass)──> 位置表 / 符号表 <id>.keel-debug
                                          │ 装载
运行期   checkpoint 调用点 ──命中──> C Agent（keel-abi，每模块一份）
                                          │ 帧协议（CBOR over socket）
IDE 侧   XDebugger UI  <──>  XDebugProcess / XBreakpointType
```

### 2.1 构建期：同一趟 IR 同时产出插桩与位置表

关键是**插桩和元数据必须由同一次 pass 生成**，否则 ID 对不上。

- **位置表**：`locationId → (文件, 行, 函数, 是否 inline / suspend, 语句种类)`。
  [LogSiteTransformer.kt](../keel-logger-compiler-plugin/src/main/kotlin/moe/lolosia/keel/logger/LogSiteTransformer.kt)
  已经是「在 IR 里取 fileEntry 行号、换调用、塞参数」的现成模板；
  [SourcePathIndex.kt](../keel-logger-compiler-plugin/src/main/kotlin/moe/lolosia/keel/logger/SourcePathIndex.kt)
  也已经解决了「IR 只有文件名、拿不到目录」的问题。
- **插桩点**：只在**行号变化处**插，不要每条语句都插，避免体积膨胀；另补函数入口 / 出口，
  否则 step into / out 没有依据。
- **元数据文件**：每个模块产一个 `<module>.keel-debug`，含
  `moduleId / buildId / arch / 文件表 / 函数表 / 位置表 / 局部变量描述`。
  它放在 `.keel` **之外**的运行目录里，IDE 启动前就能读全量，才能给还没 `dlopen` 的插件建 pending 断点。
- **变量**：两条路可选。吃 DWARF（省事，但受编译器调试信息质量限制）；或由 checkpoint 传
  `frame record`，用 IR 把当前帧的局部变量「用一遍」强制 live——代价是放弃部分优化、增大帧，
  **只在 debug 构建开**。
- **异常断点**：[ExceptionSiteTransformer.kt](../keel-logger-compiler-plugin/src/main/kotlin/moe/lolosia/keel/logger/ExceptionSiteTransformer.kt)
  已经在构造 / `throw` 上插桩并维护侧表（`ThrowTrace`），直接复用即可。
- **协程**：逻辑栈需要关联 continuation，成本最高。第一版建议明确「不支持协程逻辑栈」，
  只显示物理状态机帧。

### 2.2 运行期：C 层 agent，放在 `keel-abi`

- **必须是 C，不能是 Kotlin。** 它要在任意线程被调用、不能分配、不依赖运行时是否健康。
  放在 [keel_abi.h](../keel-abi/src/nativeCpp/keel_abi.h) 这一层，和 `keel_queue.c` 平级。
- **每个 image 一份状态。** Host exe 一份、每个插件 dylib 一份（K/N 静态链接运行时，
  每个 dylib 都是独立世界），各带自己的 location 位图与挂起标志。
- **调试通道不能走 `HostLink` / `Messenger` 事件泵。** 在泵线程上断下来会把泵一起停住，
  通道自己把自己锁死。用独立 socket，端口与 token 由 Host 通过环境变量下发给各插件，
  每个 image 各自 `HELLO(moduleId, buildId, arch)`。
- **checkpoint 形态**：读全局 suspend 标志 → 读本 image 的 location 位图 → 命中就上报并阻塞在
  条件变量上等 resume。其他线程在各自经过 checkpoint 时看到 suspend 标志后挂起，
  这就是协作式 stop-the-world。
- **采集**：栈靠影子栈（enter / exit 插桩维护）或 `.eh_frame` unwind；PC → 函数靠自己的函数表；
  对象展开需要类型 / 布局元数据，没有就只显示基本类型。

### 2.3 协议

定长帧 + CBOR / Protobuf（热路径不用 JSON），请求 / 应答 + 事件，握手里带版本、buildId、能力位：

| 方向 | 消息 | 备注 |
|---|---|---|
| agent → IDE | `HELLO(module, buildId, arch)` | 每个 image 一次 |
| IDE → agent | `SET_BREAKPOINTS(version, ids[])` | **整表替换**，不逐条增删 |
| agent → IDE | `SUSPENDED(locationId, threadId, reason)` | 命中 / pause / 异常 |
| IDE → agent | `RESUME(all\|thread)` / `STEP(kind, frameId, depth)` | step 过滤规则放 agent 本地，否则每个 checkpoint 都要一次往返 |
| IDE → agent | `GET_STACK` / `GET_LOCALS(frameId)` / `EVAL` | 在被停住的线程上执行 |
| agent → IDE | `MODULE_LOADED` / `MODULE_GONE` | 让 pending 断点转正 |

### 2.4 IDE 侧：只是一层适配器

[plugin.xml](../keel-idea-plugin/src/main/resources/META-INF/plugin.xml) 加两个扩展点：

```xml
<programRunner implementation="moe.lolosia.keel.idea.debug.KeelDebugRunner"/>
<xdebugger.breakpointType implementation="moe.lolosia.keel.idea.debug.KeelLineBreakpointType"/>
```

| 类 | 作用 |
|---|---|
| `ProgramRunner`（Debug executor） | 拉起进程，再 `XDebuggerManager.startSessionAndShowTab` |
| `XDebugProcess` | 生命周期、`resume` / `startStepOver` / `createSuspendContext`、接上 Run 控制台 |
| `XSuspendContext` / `XExecutionStack` / `XStackFrame` / `XValue` | 线程、栈、变量三栏；`XValue` 用 `XCompositeNode` 懒加载子节点 |
| `XDebuggerEvaluator` | 第一版可以直接返回「不支持」，Watch 面板给提示而不是崩 |
| `XLineBreakpointType<Props>` + `XBreakpointManager` 监听 | 断点持久化、红点渲染、启用 / 禁用全由平台负责，**不需要自己写 storage** |
| 行 → locationId 解析 | 断点行没有 checkpoint 时「下滑」到最近可执行行；位置表是 `collectExecutableLines` 的数据源 |

运行配置模型不用改：`KeelRunConfiguration` 只加 `debugPort` / `waitForDebugger` 两个 option，
Debug executor 走另一条 ProfileState。

### 2.5 可以复用的现成东西

- [KeelPluginDiscovery.kt](../keel-idea-plugin/src/main/kotlin/moe/lolosia/keel/idea/KeelPluginDiscovery.kt)
  与 `KeelPluginProject` 已经能把工程映射到运行目录，找 `.keel-debug` 不用另写扫描。
- `LogSite` 的 `file / line / class / function` 语义与位置表一致。MVP 可以**先拿日志插桩点当位置表**：
  粒度粗一点，但协议、会话、断点 UI 能先跑通，不必等新的 IR pass 上线。
- `HostPump` 的收件箱是「事件从插件到 Host」的成熟范式，但**只适合日志，不适合调试**（同上：泵停则通道停）。

---

## 3. 必须提前认下的语义边界

1. **只能停在插桩点。** 没插桩的库代码和 C 代码不会停，只能等它下一次经过检查点。
2. **暂停是协作式的。** 一个 Worker 停在断点，Host 的 `Messenger.request` 就等不到回包。
   M5 起由 API 层自己处理（见下文「M5 调试感知超时」）：调试会话期间把**我们自己的**等待上限
   放宽，调用方显式写的 `withTimeout` 保持原语义。
3. **没有任意表达式求值。** 要 REPL 就得「编译片段并注入」，K/N 上没有 JVM 的 HotSwap 等价物。
4. **变量展开依赖自己生成的类型 / 布局元数据**，否则只能显示标量。
5. **版本必须 buildId 对齐。** `LibraryCache` 每次重写、文件被占用还会改名成 `name.1.dll`，
   所以会话要按 `HELLO` 报的 `moduleId` 建立，不能按文件名猜。

若只想要「能停能看栈」，也可以实现 DAP 服务端接平台的 DAP 客户端；但 DAP 表达不了
「一个进程里多个独立运行时 image」，且目标 IDE 是否内置 DAP 客户端需要先确认。
自建 `XDebugger` 更可控。

---

## 4. 建议的最小切片

1. 只做 Host：`SET_BREAKPOINTS` + `SUSPENDED` + 单帧栈 + 只读局部变量。
2. 加 pending 断点 + 插件 `HELLO`，把「多 image」跑通（这是 Keel 与普通调试器最大的差异，
   越早暴露越好）。
3. 加 step（agent 本地过滤）与异常断点（复用 `ThrowTrace`）。
4. 最后再碰变量展开、协程逻辑栈。

一句话回答「IDEA 支持 debug 最少要什么」：**Debug executor 真的会创建 `XDebugProcess`，
并且能拿到「位置 → 源码」的映射**；其余都是把这句话补完整。

---

## 5. XDebugger 最小实现路线图

### 5.1 验收标准

1. 运行配置的虫子按钮可点，能建立调试会话，不出现 `Debugger not found`。
2. 在 `.kt` 的行上打断点，进程执行到该行停下。
3. 栈面板显示 `包.类.函数(File.kt:行)`，与日志里的异常帧同形。
4. 不改变 Run 行为；没有调试器时 Run 照常。

### 5.2 关键取舍：第一版 agent 用 Kotlin 写

`keel-api` 加一个 `Debug` 单例，IR 插桩插的就是它的调用；通道用 `nativeMain` 的 POSIX socket。

- **好处**：不动 C ABI、不加 cinterop、不改每目标的静态库；IR 侧只是一次普通函数调用。
- **代价**：运行时被 GC / 事件泵卡住时通道也卡住；调试「泵线程自己」不可行。
- 稳定后再把 `Debug` 的实现换成 `keel-abi` 里的 C 版本，**接口与方法签名不变**，
  IR 与 IDE 侧不用跟着改。

### 5.3 里程碑

> 下面是**实际实施顺序**。它与最早的规划（M3=step+异常、M4=变量+远程）已经分叉：
> 变量被并进 M3，M4 给了协程栈，step 排在 M7。

| 里程碑 | 目标 | 状态 |
|---|---|---|
| **M0 会话骨架** | 虫子亮、会话建立、断点可打、控制台可用 | ✅ |
| **M1 单 image 命中** | Host 里断点真的停，按 `locationId` 反查位置 | ✅ |
| **M2 多 image** | IDE 监听、每个 image 一条通道、按模块下发断点 | ✅ |
| **M3 栈帧 + 变量** | 影子栈多层调用链、参数 / `this` 快照（`VALUES`） | ✅ |
| **M3.5 跨 image 拼栈** | `correlation` + `WAITING`，不真停等待方 | ✅ |
| **M4 协程栈** | 按 `Job` 记账的帧表、creation stack、`suspend` 插桩 | 🟡 M4.1 / M4.2，M4.3 待做 |
| **M5 调试感知超时** | 调试期间放宽 API 层自己的等待上限 | ✅ |
| **M6 API 桥接帧** | keel-api 应用日志插件，`Log.site()` 记命名帧 | ✅ |
| **M7 单步** | `RESUME step:over\|into\|out`，按影子栈深度判定 | ✅ |

剩下的：M4.3（creation stack 渲染成分隔帧）、异常断点、变量展开 / 局部变量、
`KEEL_DEBUG_HOST` 远程、Host 侧 Ktor 协程的帧表包装。

### 5.4 每个里程碑的改动

**M0（只动 IDE 插件）**

- 新增 `debug/` 包：`KeelDebugWire`、`KeelLineBreakpointType`、`KeelBreakpointProperties`、
  `KeelDebugRunner`、`KeelDebugProfileState`、`KeelDebugProcess`、`KeelSuspendContext`、
  `KeelExecutionStack`、`KeelStackFrame`、`KeelStackLocation`、`KeelDebugSymbols`、
  `KeelEditorsProvider`。
- `plugin.xml` 加 `<programRunner>` 与 `<xdebugger.breakpointType>`。
- `KeelRunConfiguration.getState` 按 executor 分叉；`KeelRunProfileState` 支持注入
  `KEEL_DEBUG_PORT`（**只走环境变量**，避免 Host 现有参数解析报「无法识别的参数」）。

**M1（构建期 + Host）**

- `keel-api`：`nativeMain/debug/Debug.kt`（`attach` / `checkpoint` / `poll`）、`DebugSocket.kt`
  （平台差异：`WSAStartup`/`ioctlsocket`/`closesocket` 与 `fcntl`/`close`）。
- `keel-logger-compiler-plugin`：`DebugSiteTransformer` + `DebugLocationTable`。插桩点放在
  **行号变化处**（函数体 + `if` / 循环 / `when` 分支的块），`locationId` 由同一趟 pass 分配；
  位置表写到 `<module>-<target>-<compilation>.keel-debug`（TSV）。
  文件里的 `file` 列相对 Gradle 调用目录，IDE 用「路径后缀」匹配到 `VirtualFile`。
- `keel-logger-gradle-plugin`：`keelLogger { debug = true }` 透传；输出目录默认 `build/keel-debug`。
- `keel-host`：读 `KEEL_DEBUG_PORT` 后 `Debug.attach`，事件循环里 `Debug.poll()`。
- `keel-plugin-gradle-plugin`：把各模块的 `.keel-debug` 汇总到 `build/keel-run/debug/`；
  用本地 Host（`keel.run.host`）时还要配 `keel.run.host.debug`（或 `keelPlugin.runHostDebugDir`）。
- `keel-idea-plugin`：`KeelDebugSymbols` 按模块加载位置表；断点行 → 最近 `locationId`；
  `HELLO` 后按模块过滤整表下发（`locationId` 只在模块内唯一）。

> M2 之后，插件代码在自己的 image 里也能停；每条通道只下发本模块的 `locationId`，
> 所以同一个数字在 Host 与插件之间互不干扰。

**M2（多 image）**

- 方向改成 **IDE 监听**（`KeelDebugProfileState` 先建 `ServerSocket` 再起进程）：Host exe 与
  每个插件 dylib 是独立运行时，各自带一份 `Debug`，启动时都作为客户端接同一个端口。
- `PluginWorker.enter` 里 `Debug.attach`；模块名必须与位置表首行一致，而插件运行期只拿得到
  manifest id，所以新增 Host subject `DEBUG_MODULE(0x1011)` 让插件问一次自己的 id，
  编译期 `keelLogger.debugModule` 也由打包插件从 `plugin.json.id` 填。
- `keel-idea-plugin`：一条通道一个读线程，`HELLO` 带模块名；断点按模块分组、只下发各通道
  自己的 `locationId`（`locationId` 只在模块内唯一）；`RESUME` 发给当前挂起的那个通道。
- 插件侧 `Debug.poll()` 挂在 `dispatchEvent` 入口：收到 HTTP / 定时器等任何事件时顺手
  处理控制帧，空闲时只是一次原子读。

**M3（影子栈 + 参数快照）**

- 影子栈：含检查点的**同步**函数包一层 `Debug.enter(enterId)` + `try { ... } finally { Debug.exit }`，
  每线程一个 `@ThreadLocal` 的 `IntArray`。`enter` 不看 `active`——调试会话是进程跑起来后
  才接上的，否则启动阶段的调用链会整段丢失。
- 命中时把 `[locationId, callers...]` 放进 `SUSPENDED` 的第 5 个字段（`enter` 行 id，由内到外）。
- 参数快照：检查点生成 `if (Debug.shouldStop(id)) { Debug.publish(id, "this", this, 参数..., ...); Debug.checkpoint(id) }`，
  不命中时只有一次位图查询，`toString()` 与装箱都在命中分支里。`VALUES` 帧先于 `SUSPENDED` 发送。
- IDE：一帧一个 `KeelStackFrame`，栈顶帧 `computeChildren()` 给出只读的字符串节点。

**明确的上限**：`suspend` 函数不参与影子栈（协程挂起后会在别的线程恢复，帧无法配对出栈）；
`keel-api` 没有被编译期插桩，它的几层用运行期注册的**命名帧**补上（见下节）；变量只有命中瞬间的
参数与 `this` 的 `toString()`，不能展开、不能修改——K/N 没有运行时反射，读寄存器/栈槽要 DWARF/PDB，
等于自研原生调试后端。

**跨 image 拼栈（M3.5）**

两个 image 的栈不可能真的互相读，做法是**拼**：

- 命名帧一律来自 `Log.site()`（编译器插件在调用点填好），源码里不写死文件名 / 行号；
  没插桩的模块（`keel-api`）里 `Log.site()` 是 null，`enterBridge` 直接不压栈。
- 挂点：`HostPump.request`（suspend，标注 + 快照）、`HostPump.requestPluginEvent`（阻塞，桥接帧 + 快照）、
  `HostHttp.dispatch`（suspend，通过 `trace` 参数往下传一帧）。
- `suspend` 链没法用影子栈，所以用 `requestStart(correlation, site, trace)` 的 `trace`
  逐层传：每一层把自己的命名帧 id 往下带，最内层拼成 `WAITING`。
- 服务方（插件）在 `dispatchEvent` 里 `Debug.serving(correlation)`，`SUSPENDED` 末尾带上它；
  IDE 用 correlation 找到 `WAITING`，把那段栈接在当前栈下面，跨模块的帧标上 `[module]`。
- 唤醒不需要级联：等待方没被停，回包到了自然继续。

实测（`/hello` 打到路由 lambda）：`SUSPENDED = 297 … (callers 空) 4`，
Host 侧 `WAITING 4 keel-host -3,-2` = `HostPump.request ← HostHttp.dispatch`。

限制：`HostPump.request` 是 suspend 调用，Ktor 侧的协程栈不在影子栈里，所以 Host 那一段只有
发起请求的那一帧；真正阻塞线程的 `requestPluginEvent`（插件启动期）能拿到完整调用链。

**M4（协程栈：设计）**

影子栈挂在**线程**上，而线程与协程是多对多：协程可能在 A 线程起步、B 线程恢复，
挂起期间线程还会被别的协程借走。所以栈必须跟着**协程**走，线程只是当前载体。

```
ThreadLocal<FrameStack>  ──update/restore──┐
        ↑                                  │
CoroutineContext[KeelDebugFrame] ──copyForChild──> 子协程（复制父帧 = creation stack）
        ↑
IR: enter(id) / exit(id)  ── 写进「当前协程的」FrameStack
```

1. `DebugFrameElement : CopyableThreadContextElement<FrameStack?>`：元素里装帧表；
   `copyForChild()` 时把**当前执行栈**快照成子协程的初始帧（= creation stack），
   不需要拦截 `launch`；`updateThreadContext` / `restoreThreadContext` 切换线程局部指针。
2. IR 只插 `enter` / `exit`：`enter` 写 `currentCoroutineFrames() ?: threadFrames()`，
   同步与 `suspend` 走同一条路径，`canTrackStack` 里的 `!isSuspend` 限制去掉。
3. `suspend` 函数天然正确：挂起时 `finally` 不执行，帧留在自己的表里；恢复在哪个线程都行，
   因为元素跟着 `CoroutineContext` 走；完成 / 取消时 `finally` 从同一张表弹出。
4. 需要把元素装进：插件 `context.scope`、Host 自己的 `CoroutineScope`、Ktor server 的
   `coroutineContext`（否则那些协程会退回线程栈）。
5. 仍然的边界：C 层不可见；没有该元素的协程按线程栈处理；内存是「每协程一个 IntArray」，
   debug 构建可接受，`debug = false` 时整段插桩不生成。

落地顺序：M4.1 元素 + 帧表（不动 IR）→ M4.2 放开 `suspend` 插桩 → M4.3 creation stack
渲染成分隔帧（对齐 IDEA 的协程视图）。

**M4.1 / M4.2 已落地（实现与设计不同的地方）**

`ThreadContextElement` / `CopyableThreadContextElement` 只有 JVM 版，K/N 的 klib 里没有，
所以不能挂在 `CoroutineContext` 上。改成在**自己的调度器**里做同一件事：

- `WorkerDispatcher.dispatch(context, block)`：投递续体时还在创建者栈上，
  按 `context[Job]` 记账建一张帧表（`Debug.framesForCoroutine(job)`），
  初始内容 = 此刻的栈快照（creation stack）；执行续体时
  `Debug.runWithCoroutineFrames(frames) { block.run() }` 切换线程局部指针。
- `delay` / `withTimeout` 的恢复最终也回到 `dispatch`，所以不需要额外钩子。
- 帧表按 `Job` 放在 `HashMap` 里，超过 4096 个整体丢弃；`debug = false` 时整段不存在。

实测（`context.scope.launch { delay(60); Log.info(...) }` 里断点）：
`SUSPENDED = 349 … 351,353,335,163`，即
`lambda ← checkScope ← onEnable ← keelPluginWorkerMain` —— 跨 `delay` 的整条逻辑栈。

未覆盖：Host 侧 Ktor 协程跑在别人的调度器上，没有这个包装，仍按线程栈处理；
`withContext` 换调度器后同样暂时退回线程栈。

**M5（调试感知超时）**

断点停住的是**对端**的执行线程，它本来就不可能回包。若 API 层照原超时判定「无响应」，
调试期间每一次停顿都会让上游任务白白失败——这是最容易让开发者放弃调试器的噪音。

处理方式是让「是否存在调试会话」成为 API 层的一个已知条件，而不是去猜：

- `Debug.isAttached()`：本 image 的调试通道是否处于连接状态（`HELLO` 之后到 `DISCONNECT` 之前）。
- `Debug.debugTimeout(base)`：已接上时返回 `max(base, 30min)`，否则原样返回。
- 只放宽**我们自己写的**等待上限，五个调用点：
  - `HostPump.request`（Host → 插件，含 HTTP 的 30s、`/ping` 的 2s）；
  - `HostPump.requestPluginEvent`（启动期 / 控制台的阻塞等待）；
  - `HostPump` 的 `Forward.deadlineMs`（插件 → 插件的服务调用与消息）；
  - `WorkerCalls.callHost`（插件 → Host 的 3s）；
  - `PluginRuntime.awaitComplete`（插件启动等 `COMPLETE` 的 5s）。
    这一条最容易被忽略：启动期单步超过 5s，Host 会把「正在单步」判成启动失败，
    接着 `request_shutdown` 并在插件下一次反向请求时报「插件已停用」。
- 停用 / 启动失败的清理窗口（`SHUTDOWN_WAIT_MS`、`FAILED_START_WAIT_MS`）**不**放宽：
  停住的插件本来就处理不了 `SHUTDOWN`，等 30 分钟只会卡住整个进程。
- 调用方显式 `withTimeout` 不受影响：那是它自己的语义，调试器不替它改。

之所以不做「halt 时冻结计时」：暂停发生在对端 image 里，Host 看不到它的 `halted` 标志，
要冻结就得再加一条「谁停了 / 谁继续了」的通知协议。按会话整体放宽已经覆盖真实场景
（停住的时间远小于 30 分钟），而且不需要跨 image 的新帧类型。
代价是：调试会话期间一个真正死掉的插件要等更久才被判超时；断开调试即恢复原值。

**M6（API 桥接帧）**

插桩只作用于「被 debug 打开」的构建；`keel-api` 之前没有应用日志插件，所以
`Log.site()` 在 api 内部恒为 null——`PluginWorker.dispatchEvent` / `PluginRoutes.dispatch`
这些边界帧就记录不下来，插件的栈在 API 层断掉。

改法是让 `keel-api` 自己应用 `moe.lolosia.keel.logger`，但**只开日志改写**：

- `keelLogger { debug = false; exceptionTrace = false }`。不插调试检查点的两个原因：
  `Debug` 自己的检查点会自递归；api 的 `locationId` 属于 api 的位置表，而运行期按
  「上报模块」的表解析，同一个 keel-api 被 Host 和每个插件各链一份，跨 image 解析不到。
- 桥接层改用 `Debug.enterBridge(Log.site())` / `exitBridge`：名字、文件、行由编译器插件
  在调用点填好，以「命名帧」（`NAMES`，id 为负）上报，不依赖任何位置表。
- 目前三处：`PluginWorker.dispatchEvent`（事件边界）、`PluginRoutes.dispatch`（路由分发）、
  Host 侧的 `HostHttp.dispatch` / `HostPump.request`。
- `bridgeId` / `requestStart` 在没接调试会话时直接返回：这些点在生产路径上，
  不能每次调用都拼字符串。

实测（`/api/hello/config` 命中）：`callers = 297,114,-2,-1`，`NAMES` 收到
`-1 PluginWorkerKt.dispatchEvent`、`-2 PluginRoutes.dispatch`，文件列是模块相对路径。

**M7（单步）**

协议早就留了 `RESUME step:over|into|out`，之前运行期把所有 `RESUME` 都当全量恢复，
所以 F7/F8 等价于 Continue。落地方式：

- `Debug.step` 存一条 `StepRequest(mode, depth, stack)`；`armStep` 在**停住的那个线程**上
  取当前执行栈与深度。
- `stepHit()` 只对同一个 `FrameStack` 生效（线程或协程各一份），判断
  `over: depth <= 出发深度`、`into: 任意`、`out: depth < 出发深度`，
  其它线程 / 协程照常跑，不会被顺手停住。
- `shouldStop` 与 `checkpoint` 共用这套判断；命中一次就清掉请求，`SUSPENDED.reason`
  报 `step`，IDE 侧据此走 `positionReached`（而不是 `breakpointReached`）。
- 单步落点靠影子栈深度区分嵌套，所以 API 层没有 `enter` 帧时（M6），
  `into` 会与 `over` 表现一致——这是已知边界。

实测：停在 `HelloController.config` 第一行（294），`step:over` 停在下一行（295），
`reason=step`，再 Continue 请求正常结束。

### 5.5 协议 v0

帧 = `type:u8 + length:i32 + payload`，payload 是 UTF-8、`\t` 分隔；**只传结构化字段，
不传拼好的字符串**，格式化统一在 IDE 侧做一次。

| 方向 | type | payload |
|---|---|---|
| agent → IDE | `HELLO` | `moduleId  buildId  arch`（arch 固定 `native`） |
| IDE → agent | `BREAKPOINTS` | `version  locationId,locationId,...`（整表替换，只含当前模块的 id） |
| agent → IDE | `VALUES` | `locationId  count  name  value  name  value...`（命中瞬间的参数快照，先于 `SUSPENDED`） |
| agent → IDE | `SUSPENDED` | `locationId  threadId  module  reason  callers  correlation`（`callers` = 由内到外的 `enter` 行 id） |
| agent → IDE | `NAMES` | `locationId  owner  file  line`（运行期注册的命名帧，id 为负） |
| agent → IDE | `WAITING` | `correlation  module  callers`（等待方快照，用于跨 image 拼栈） |
| agent → IDE | `STACK` | `threadId  count` + `count` 行 `locationId  depth`（M3 暂未使用，栈随 `SUSPENDED` 走） |
| IDE → agent | `RESUME` | `all` / `thread:<id>` / `step:<over\|into\|out>:<frame>` |
| IDE → agent | `GET_STACK` | `threadId` |
| 任一方 | `DISCONNECT` | 空 |

`SUSPENDED` 在 M0 里带过完整的 `file/line/owner/function`（那时还没有位置表）；
M1 起只传 `locationId`，格式化统一在 IDE 侧做，位置表是唯一的来源。
**`locationId` 只在模块内唯一**：Host 与每个插件的表都从 1 开始，
所以「断点 → id」的反查必须按模块过滤，否则会把别的模块的 id 发过去。

### 5.6 位置表 `.keel-debug`

TSV，首行 `#moduleId  buildId  arch`，其后每行一个位置：

```
locationId  functionId  file  line  package  class  function  kind
```

`file` 用相对 Gradle 工作区的路径（与 `LogSite.file` 同义），`kind` 区分普通行 / 函数入口 / 函数出口。
IDE 侧按 `buildId` 校验：与 `HELLO` 报的不一致就拒绝绑定断点，避免「停下但行号偏一行」。

### 5.7 栈帧渲染：与日志同形

日志的帧格式由 `LogSite.traceLocation()` 定义：`包.类.函数(File.kt:行)`，
已经包含「类名 + 方法名 + 文件 basename + 行号」。

- 协议只传 `owner / function / file / line`；IDE 侧用同一套拼接规则生成文本。
- `KeelStackFrame.customizePresentation` 把主文本设为 `owner.function`、副文本设为 `File.kt:行`，
  树里读起来就是 `moe.lolosia.keel.host.HostHttp.dispatch` + `HostHttp.kt:123`。
- `getSourcePosition()` 指向真实 `VirtualFile` + 行号，双击可跳转。
- 命中时另在 Debug 控制台打一行同样格式的链（复用 `StackTraceFormat` 的缩进与 `at` 风格），
  这样日志和调试器看到的是同一种东西。

### 5.8 明确不做

- 任意表达式求值、变量写入。
- 会话内替换插件版本（`dlclose` 不可用）。
- 真正的 stop-the-world：M1–M2 只保证「停在插桩点 + 其他线程继续跑」。

