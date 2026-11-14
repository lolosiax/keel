package moe.lolosia.keel.idea.debug

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Keel 调试会话。
 *
 * 进程仍然由 [moe.lolosia.keel.idea.run.KeelRunProfileState] 启动，这里负责：
 * 监听调试端口、给每个接入的 image（Host / 每个插件 dylib）下发它自己模块的断点、
 * 把 `SUSPENDED` 翻成 XDebugger 的暂停上下文。
 *
 * 源码位置由 `.keel-debug` 位置表反查：`SUSPENDED` 只带 `locationId` + 模块 id，
 * 而 `locationId` 只在模块内唯一，所以每个通道只下自己模块的断点。
 */
class KeelDebugProcess(
    private val session: XDebugSession,
    private val processHandler: ProcessHandler,
    private val server: ServerSocket,
    debugDir: File,
) : XDebugProcess(session) {

    /** 一个接入的运行时 image（Host 或某个插件 dylib）。 */
    private class Channel(
        val moduleId: String,
        val socket: Socket,
        val input: DataInputStream,
        val output: DataOutputStream,
    ) {
        fun send(type: Int, payload: String) {
            synchronized(output) { KeelDebugWire.write(output, type, payload) }
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    private val project: Project = session.project
    private val console: ConsoleView = TextConsoleBuilderFactory.getInstance()
        .createBuilder(project).console
    private val editorsProvider: XDebuggerEditorsProvider = KeelEditorsProvider()
    private val symbolsDir: File = debugDir
    private val symbols: KeelDebugSymbols = KeelDebugSymbols.load(debugDir)

    private val channels = ConcurrentHashMap<String, Channel>()

    /** 断点监听的寿命；跟着会话走，`stop()` 时释放。 */
    private val breakpointBus = Disposer.newDisposable("keel-debug-breakpoints")

    @Volatile private var suspendedModule: String? = null
    @Volatile private var closed = false

    /** 当前挂起帧的参数快照；`VALUES` 总在 `SUSPENDED` 之前到，只有一条通道会挂起。 */
    @Volatile private var pendingValues: List<Pair<String, String>> = emptyList()

    /** 已经校验过的断点；只在 EDT 上读写，用来避免「校验 → 事件 → 再校验」自激。 */
    private val verified = HashSet<XLineBreakpoint<*>>()

    private val invalid = HashSet<XLineBreakpoint<*>>()

    /** 每个模块上一次下发的 id 表；内容没变就不再发。 */
    private val lastPayloads = HashMap<String, String>()

    /** correlation → （等待方模块, 它的栈）；插件命中时用来跨 image 拼栈。 */
    private val waiters = ConcurrentHashMap<Long, Pair<String, List<Int>>>()

    private var breakpointsVersion = 0

    init {
        console.attachToProcess(processHandler)
        processHandler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                ApplicationManager.getApplication().invokeLater {
                    if (!closed) session.stop()
                }
            }
        })
        // 进程由 ProgramRunner 直接启动；stdout 的泵由 XDebuggerManagerImpl.startSession 负责开启
        Thread({ acceptLoop() }, "keel-debug-acceptor").apply { isDaemon = true }.start()
        watchBreakpoints()
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider = editorsProvider

    override fun doGetProcessHandler(): ProcessHandler = processHandler

    override fun createConsole(): ExecutionConsole = console

    override fun resume(context: XSuspendContext?) {
        // 先发 RESUME：sendToSuspended 依赖 suspendedModule，清早了这个帧就发不出去
        sendToSuspended(KeelDebugWire.RESUME, "all")
        suspendedModule = null
        session.sessionResumed()
    }

    override fun startStepOver(context: XSuspendContext?) = step("over")

    override fun startStepInto(context: XSuspendContext?) = step("into")

    override fun startStepOut(context: XSuspendContext?) = step("out")

    override fun stop() {
        if (closed) return
        closed = true
        Disposer.dispose(breakpointBus)
        channels.values.forEach { channel ->
            runCatching { channel.send(KeelDebugWire.DISCONNECT, "") }
            channel.close()
        }
        channels.clear()
        suspendedModule = null
        runCatching { server.close() }
        if (!processHandler.isProcessTerminated) processHandler.destroyProcess()
    }

    private fun step(mode: String) {
        sendToSuspended(KeelDebugWire.RESUME, "step:$mode")
        suspendedModule = null
        session.sessionResumed()
    }

    private fun sendToSuspended(type: Int, payload: String) {
        val module = suspendedModule ?: return
        val channel = channels[module] ?: return
        runCatching { channel.send(type, payload) }
            .onFailure { consolePrint("调试通道写入失败（$module）：${it.message}") }
    }

    /** 接受各 image 的接入；每条通道一个读线程，互不影响。 */
    private fun acceptLoop() {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                if (!closed) consolePrint("调试监听已关闭：${e.message}")
                return
            }
            Thread({ serveChannel(socket) }, "keel-debug-channel").apply { isDaemon = true }.start()
        }
    }

    private fun serveChannel(socket: Socket) {
        val channel = try {
            socket.tcpNoDelay = true
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            // 第一条帧必须是 HELLO：模块 id 决定这条通道用哪张位置表
            val hello = KeelDebugWire.read(input) ?: return closeQuietly(socket)
            if (hello.type != KeelDebugWire.HELLO) return closeQuietly(socket)
            val module = hello.fields.firstOrNull().orEmpty()
            if (module.isEmpty()) return closeQuietly(socket)

            Channel(module, socket, input, output).also { channel ->
                channels[module]?.close()
                channels[module] = channel
                val buildId = hello.fields.getOrElse(1) { "" }
                consolePrint(
                    "调试模块已连接：$module" + if (buildId.isNotEmpty()) " ($buildId)" else ""
                )
                pushBreakpoints()
            }
        } catch (e: Exception) {
            if (!closed) consolePrint("调试通道建立失败：${e.message}")
            return
        }

        readLoop(channel)
        channels.remove(channel.moduleId, channel)
        channel.close()
        if (!closed) consolePrint("调试模块已断开：${channel.moduleId}")
    }

    private fun closeQuietly(socket: Socket) {
        runCatching { socket.close() }
    }

    private fun readLoop(channel: Channel) {
        while (!closed) {
            val frame = runCatching { KeelDebugWire.read(channel.input) }.getOrNull() ?: return
            when (frame.type) {
                KeelDebugWire.VALUES -> pendingValues = parseValues(frame.fields)
                KeelDebugWire.NAMES -> onNamed(channel.moduleId, frame.fields)
                KeelDebugWire.WAITING -> onWaiting(frame.fields)
                KeelDebugWire.SUSPENDED -> onSuspended(channel.moduleId, frame.fields)
                KeelDebugWire.STACK -> onStack(frame.fields)
                else -> Unit
            }
        }
    }

    /** `locationId  name  file  line`：`keel-api` 运行期注册的桥接帧。 */
    private fun onNamed(module: String, fields: List<String>) {
        val id = fields.getOrNull(0)?.toIntOrNull() ?: return
        val name = fields.getOrNull(1).orEmpty()
        val file = fields.getOrNull(2).orEmpty()
        val line = fields.getOrNull(3)?.toIntOrNull() ?: 0
        if (name.isEmpty()) return
        symbols.addNamed(module, id, name, file, line)
    }

    /** `correlation  module  callers`：等待方的栈快照，命中时接在下面。 */
    private fun onWaiting(fields: List<String>) {
        val correlation = fields.getOrNull(0)?.toLongOrNull() ?: return
        val module = fields.getOrNull(1).orEmpty()
        val ids = fields.getOrNull(2).orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
        if (module.isEmpty() || ids.isEmpty()) return
        if (waiters.size > MAX_WAITERS) waiters.clear()
        waiters[correlation] = module to ids
    }

    /** `locationId  count  name  value ...`；名字与值里的控制字符在运行期已转义。 */
    private fun parseValues(fields: List<String>): List<Pair<String, String>> {
        val cells = fields.drop(2)
        if (cells.isEmpty()) return emptyList()
        return cells.chunked(2).mapNotNull { pair ->
            val name = pair.getOrNull(0) ?: return@mapNotNull null
            name to unescape(pair.getOrNull(1).orEmpty())
        }
    }

    private fun unescape(text: String): String {
        if (!text.contains('\\')) return text
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '\\' && index + 1 < text.length) {
                when (val next = text[index + 1]) {
                    't' -> builder.append('\t')
                    'n' -> builder.append('\n')
                    'r' -> builder.append('\r')
                    '\\' -> builder.append('\\')
                    else -> builder.append(next)
                }
                index += 2
            } else {
                builder.append(char)
                index++
            }
        }
        return builder.toString()
    }

    /** `locationId  threadId  module  reason  callers  correlation`：位置表反查出整条栈。 */
    private fun onSuspended(module: String, fields: List<String>) {
        val locationId = fields.getOrElse(0) { "0" }.toIntOrNull() ?: 0
        val threadId = fields.getOrElse(1) { "?" }
        val reason = fields.getOrElse(3) { "breakpoint" }
        val values = pendingValues.also { pendingValues = emptyList() }

        val entry = symbols.location(module, locationId)
        val top = if (entry != null) {
            KeelStackLocation(module, entry.file, entry.line, entry.owner, entry.function)
        } else {
            KeelStackLocation(module, "", 0, "location #$locationId", null)
        }
        val callers = fields.getOrNull(4).orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .mapNotNull { symbols.location(module, it) }
            // 栈顶如果就是当前函数的 enter 帧，会和「停在哪一行」重复；按 functionId 去掉
            .filter { caller ->
                caller.kind != KIND_ENTER || entry == null || caller.functionId != entry.functionId
            }
            .map { KeelStackLocation(module, it.file, it.line, it.owner, it.function) }

        // 协程创建栈：运行期已经把「当前帧」与「创建者栈」切开了，这里分段显示
        val creation = fields.getOrNull(6).orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .mapNotNull { symbols.location(module, it) }
            .map { KeelStackLocation(module, it.file, it.line, it.owner, it.function) }

        // 跨 image：发起这次请求的线程此刻正阻塞等回包，把它的栈接在下面
        val correlation = fields.getOrNull(5)?.toLongOrNull() ?: 0L
        val waiting = if (correlation > 0L) {
            waiters[correlation]?.let { (waiterModule, ids) ->
                ids.mapNotNull { symbols.location(waiterModule, it) }
                    .map { KeelStackLocation(waiterModule, it.file, it.line, it.owner, it.function) }
            }
        } else {
            null
        }

        ApplicationManager.getApplication().invokeLater {
            if (closed) return@invokeLater
            val frames = buildList {
                add(KeelStackFrame(project, top, values, showModule = top.module != module))
                callers.forEach { add(KeelStackFrame(project, it)) }
                if (creation.isNotEmpty()) {
                    add(KeelSeparatorFrame(CREATION_LABEL))
                    creation.forEach {
                        add(KeelStackFrame(project, it, showModule = it.module != module))
                    }
                }
                waiting?.forEach {
                    add(KeelStackFrame(project, it, showModule = it.module != module))
                }
            }
            val stack = KeelExecutionStack("${module} · thread $threadId", frames)
            val context = KeelSuspendContext(listOf(stack))
            suspendedModule = module
            if (entry != null) {
                consolePrint("停住（$reason）：${top.displayName}(${top.fileLabel})")
            } else {
                consolePrint("停住（$reason）：location #$locationId 不在位置表里（$module）")
            }
            if (!waiting.isNullOrEmpty()) {
                consolePrint("拼接等待方栈 ${waiting.size} 帧（correlation=$correlation）")
            }

            // 命中已有断点时走 breakpointReached，断点图标与「停在哪一行」的展示才与平台一致
            val breakpoint = entry?.let { matchedBreakpoint(it) }
            if (breakpoint != null) {
                session.breakpointReached(breakpoint, null, context)
            } else {
                session.positionReached(context)
            }
        }
    }

    /** `threadId count` 后跟 `count` 行 `locationId depth`；M2 用它把整条影子栈建出来。 */
    private fun onStack(fields: List<String>) {
        consolePrint("收到 ${fields.size} 个栈字段（栈帧展开将在后续里程碑启用）")
    }

    /**
     * 会话期间断点变化时整表重发。
     *
     * 用消息总线监听所有类型：`.kt` 文件里点行号槽创建的一般是 Java 行断点
     * （Kotlin 的 JVM 调试也复用它），只要能在位置表里找到就当成 Keel 断点用。
     */
    private fun watchBreakpoints() {
        project.messageBus.connect(breakpointBus).subscribe(
            XBreakpointListener.TOPIC,
            object : XBreakpointListener<XBreakpoint<*>> {
                override fun breakpointAdded(breakpoint: XBreakpoint<*>) = pushBreakpoints()

                override fun breakpointRemoved(breakpoint: XBreakpoint<*>) {
                    if (breakpoint is XLineBreakpoint<*>) {
                        verified.remove(breakpoint)
                        invalid.remove(breakpoint)
                    }
                    pushBreakpoints()
                }

                override fun breakpointChanged(breakpoint: XBreakpoint<*>) = pushBreakpoints()
            },
        )
    }

    /** 断点校验会刷 UI 图标，统一回 EDT 做；通道线程上也会调用。 */
    private fun pushBreakpoints() {
        ApplicationManager.getApplication().invokeLater {
            if (closed) return@invokeLater
            pushBreakpointsOnEdt()
        }
    }

    private fun pushBreakpointsOnEdt() {
        val breakpoints = XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints
            .filterIsInstance<XLineBreakpoint<*>>()

        // 模块 → 该模块要下发的 locationId
        val ids = HashMap<String, MutableList<Int>>()
        breakpoints.forEach { breakpoint ->
            val keelOwned = breakpoint.type is KeelLineBreakpointType
            val file = VirtualFileManager.getInstance().findFileByUrl(breakpoint.fileUrl)
            val entry = file
                ?.takeIf { it.extension == "kt" }
                ?.let { symbols.resolve(it.path, breakpoint.line + 1) }
            when {
                entry == null ->
                    if (keelOwned && invalid.add(breakpoint)) {
                        verified.remove(breakpoint)
                        session.setBreakpointInvalid(breakpoint, "该行没有调试检查点")
                    }

                else -> {
                    // 只在状态真的变化时刷图标：setBreakpointVerified 会再抛 breakpointChanged，
                    // 不加这道闸就会「校验 → 事件 → 再校验」地自激
                    if (verified.add(breakpoint)) {
                        invalid.remove(breakpoint)
                        session.setBreakpointVerified(breakpoint)
                    }
                    ids.getOrPut(entry.moduleId) { ArrayList() } += entry.locationId
                }
            }
        }

        breakpointsVersion++
        channels.values.forEach { channel ->
            val moduleIds = ids[channel.moduleId].orEmpty().distinct().sorted()
            val content = moduleIds.joinToString(",")
            // 重复下发没有意义：既刷屏又会让挂起中的 agent 反复处理控制帧
            if (lastPayloads[channel.moduleId] == content) return@forEach
            lastPayloads[channel.moduleId] = content
            runCatching {
                channel.send(KeelDebugWire.BREAKPOINTS, "$breakpointsVersion\t$content")
            }.onFailure { consolePrint("断点下发失败（${channel.moduleId}）：${it.message}") }
            consolePrint("${channel.moduleId}：已下发 ${moduleIds.size} 个断点（第 $breakpointsVersion 版）")
        }
    }

    /** 在现有断点里找「解析到同一个位置」的那一个。 */
    private fun matchedBreakpoint(entry: KeelDebugSymbols.Entry): XLineBreakpoint<*>? {
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        return manager.allBreakpoints
            .filterIsInstance<XLineBreakpoint<*>>()
            .firstOrNull { breakpoint ->
                val file = VirtualFileManager.getInstance().findFileByUrl(breakpoint.fileUrl)
                val resolved = file
                    ?.takeIf { it.extension == "kt" }
                    ?.let { symbols.resolve(it.path, breakpoint.line + 1) }
                resolved != null &&
                    resolved.moduleId == entry.moduleId &&
                    resolved.locationId == entry.locationId
            }
    }

    private fun consolePrint(text: String) {
        ApplicationManager.getApplication().invokeLater {
            if (closed) return@invokeLater
            console.print(text + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        }
    }

    private companion object {
        /** 位置表 `kind` 列的取值；`enter` 是函数入口帧。 */
        const val KIND_ENTER = "enter"

        /** 当前协程帧与创建者栈之间的分隔行。 */
        const val CREATION_LABEL = "协程创建栈（Coroutine creation stack）"

        /** 等待方快照的保留上限；只是给「当前挂起」找父栈用，不需要长期留存。 */
        const val MAX_WAITERS = 512
    }
}
