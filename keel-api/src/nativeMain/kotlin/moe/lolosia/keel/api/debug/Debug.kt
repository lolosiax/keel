@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package moe.lolosia.keel.api.debug

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.native.concurrent.ThreadLocal
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.cinterop.toKString
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.log.LogSite
import platform.posix.getenv

/**
 * 运行期调试 agent（每个 image 一份）。
 *
 * Host exe 与每个插件 dylib 是各自独立的运行时，所以 `Debug` 在每个 image 里各有一份状态。
 * 编译器插件在「行号变化处」插入 [checkpoint]；命中断点时当前线程停在这里等 IDE 的
 * `RESUME`，同进程其它线程 / 其它 image 照常往下跑（协作式，不 stop-the-world）。
 *
 * 通道是独立 TCP：**IDE 监听**，每个 image 用 `KEEL_DEBUG_PORT` 主动接入，
 * 一句话一条通道，位置表按 `HELLO` 里报的模块 id 对齐。
 * **不能**复用 `HostLink` / `Messenger` 事件泵——泵线程正是要被停住的对象。
 *
 * 帧格式与 IDE 侧 `KeelDebugWire` 逐字节一致：`type:u8 + length:i32 + payload(UTF-8, TSV)`。
 */
object Debug {

    const val HELLO = 1
    const val BREAKPOINTS = 2
    const val SUSPENDED = 3
    const val STACK = 4
    const val RESUME = 5
    const val GET_STACK = 6
    const val DISCONNECT = 7
    const val VALUES = 8

    /** 运行期注册的「命名帧」（keel-api 里的桥接点，不参与编译期插桩）。 */
    const val NAMES = 9

    /** 等待方快照：`correlation  module  callers`，用于跨 image 拼栈。 */
    const val WAITING = 10

    private const val MAX_PAYLOAD = 1 shl 20
    private const val RX_CAPACITY = 64 * 1024
    private const val PORT_ENV = "KEEL_DEBUG_PORT"

    /** 连接重试总时长：IDE 的监听 socket 在进程启动前就建好了，这里只是兜底。 */
    private const val CONNECT_TIMEOUT_MS = 3000
    private const val CONNECT_RETRY_MS = 50

    /** 等首版断点表的上限；超时就不等了，按空表继续跑。调试器正常几毫秒就会下发。 */
    private const val FIRST_TABLE_TIMEOUT_MS = 2_000

    /** 同时保留帧表的协程数上限；超了整体丢弃，调试用途不需要精确回收。 */
    private const val MAX_COROUTINES = 4096

    /** 调试会话期间跨 image 等待的上限：断点可能停很久。 */
    private val DEBUG_TIMEOUT = 30.minutes

    /** `RESUME step:<mode>` 的三种单步模式；不带 `step:` 前缀就是普通恢复。 */
    private const val STEP_OVER = 1
    private const val STEP_INTO = 2
    private const val STEP_OUT = 3

    /**
     * 一次单步请求。
     *
     * 只对 [stack]（被停住的那个执行栈，线程或协程）生效：单步期间其它线程 / 协程
     * 照常跑，不会被顺带停住。[depth] 是停下时的影子栈深度，用来区分 over / into / out。
     */
    private class StepRequest(val mode: Int, val depth: Int, val stack: FrameStack)

    /** 待生效的单步请求；命中一次就清掉。 */
    private val step = AtomicReference<StepRequest?>(null)

    /** 自旋锁；只在「读 socket / 改连接状态」这种微秒级临界区里持有。 */
    private val guard = AtomicInt(0)

    /** 已接上调试通道（断开后回到 0）。 */
    private val active = AtomicInt(0)

    /** 是否有线程正停在断点上。 */
    private val halted = AtomicInt(0)

    /** 断点整表；按 locationId 升序，[checkpoint] 走二分查找。 */
    private val stops = AtomicReference<IntArray>(IntArray(0))

    /** 每次 `RESUME` 自增；停住的线程据此醒来。 */
    private val resumeEpoch = AtomicInt(0)

    /** 收到 `BREAKPOINTS` 的次数；[attach] 用它判断首表是否已到。 */
    private val breakpointEpoch = AtomicInt(0)

    private var channelFd = -1L
    private var moduleId = ""
    private var stopSeq = 0
    private var stopLocation = 0

    /** 最近一次 [publish] 的快照；命中时随 `VALUES` 帧发出。 */
    private var pendingValues: String? = null

    /** 当前事件对应的 correlation；SUSPENDED 里带出去，IDE 用它找等待方。 */
    private var servingCorrelation = 0L

    /** 命名帧：`名字|文件|行` → 负数 id。 */
    private val namedIds = HashMap<String, Int>()
    private var nextNamedId = -1
    private val namedRows = ArrayList<String>()

    /** 协程（`Job`）→ 帧表；调试会话结束也不清，按上限整体丢弃。 */
    private val coroutineTables = HashMap<Any, FrameStack>()

    private val rx = ByteArray(RX_CAPACITY)
    private var rxSize = 0

    /** `KEEL_DEBUG_PORT` 存在时返回端口；没有调试会话时为 null。 */
    fun requestedPort(): Int? =
        getenv(PORT_ENV)?.toKString()?.trim()?.toIntOrNull()

    /**
     * 接入 IDE 的调试端口。
     *
     * @param moduleId 位置表首行的模块 id（Host 用工程名，插件用 `plugin.json` 的 `id`）
     */
    fun attach(port: Int, moduleId: String, buildId: String = ""): Boolean {
        if (!debugSocketInit()) {
            Log.warn("调试通道初始化失败（Winsock）", tag = "Debug")
            return false
        }
        guarded {
            this.moduleId = moduleId
            channelFd = -1L
            rxSize = 0
            step.store(null)
        }

        val fd = connectWithRetry(port)
        if (fd < 0) {
            Log.warn("调试通道连接失败：127.0.0.1:$port（$moduleId）", tag = "Debug")
            return false
        }
        debugSocketSetNonBlocking(fd)
        guarded {
            channelFd = fd
            rxSize = 0
            sendFrame(HELLO, "$moduleId\t$buildId\tnative")
            // 之前注册过的命名帧补发一遍
            namedRows.forEach { sendFrame(NAMES, it) }
        }
        active.store(1)
        Log.info("调试通道已连接 127.0.0.1:$port（$moduleId）", tag = "Debug")
        // 等一下首版断点表：插件的 onLoad / 启动代码也要能停
        waitForBreakpoints(FIRST_TABLE_TIMEOUT_MS)
        return true
    }

    /** 有界等待首版断点表；IDE 收到 HELLO 就会下发，通常几毫秒。 */
    private fun waitForBreakpoints(timeoutMs: Int) {
        val epoch = breakpointEpoch.load()
        val deadline = TimeSource.Monotonic.markNow() + timeoutMs.milliseconds
        while (breakpointEpoch.load() == epoch && !deadline.hasPassedNow()) {
            if (active.load() == 0) return
            val progressed = guarded { drain { type, fields -> handle(type, fields, stopped = false) } }
            if (!progressed) debugSleep(2)
        }
    }

    /**
     * 轮询入口：没有线程停在断点上时，非阻塞地处理控制帧。
     *
     * 有线程停住时直接返回——此时通道归那个线程读，避免两个线程抢同一个流。
     */
    fun poll() {
        if (active.load() == 0) return
        if (halted.load() != 0) return
        guarded { drain { type, fields -> handle(type, fields, stopped = false) } }
    }

    /** 所有线程都从这里经过；没有断点也没有单步时只有两次原子读。 */
    fun checkpoint(locationId: Int) {
        if (active.load() == 0) return
        if (halted.load() != 0) return

        val stepping = stepHit()
        if (!stepping) {
            val ids = stops.load()
            if (ids.isEmpty()) return
            if (!contains(ids, locationId)) return
        }

        // 已经有线程停住时不打断它：只保证「停住的那个线程停住，其它线程继续跑」
        if (!halted.compareAndSet(0, 1)) return
        // 单步只生效一次；先摘掉，恢复后按普通执行继续（此时若又来了新的 step 请求也不受影响）
        if (stepping) step.store(null)
        try {
            suspendAt(locationId, if (stepping) "step" else "breakpoint")
        } finally {
            stopLocation = 0
            halted.store(0)
        }
    }

    /**
     * 命中前的快速判断；编译器用它把「取参数快照」包在 `if` 里，
     * 不命中时不做任何装箱 / toString。
     */
    fun shouldStop(locationId: Int): Boolean {
        if (active.load() == 0) return false
        if (halted.load() != 0) return false
        if (stepHit()) return true
        val ids = stops.load()
        return ids.isNotEmpty() && contains(ids, locationId)
    }

    /**
     * 当前检查点是否该作为「单步落点」停下。
     *
     * 只在被单步的那个执行栈上生效，深度按影子栈判断：
     * over 不超过出发深度、into 任意深度、out 必须回到更浅的层。
     */
    private fun stepHit(): Boolean {
        val request = step.load() ?: return false
        val stack = currentStack()
        if (stack !== request.stack) return false
        val depth = stack.depth
        return when (request.mode) {
            STEP_OVER -> depth <= request.depth
            STEP_INTO -> true
            STEP_OUT -> depth < request.depth
            else -> false
        }
    }

    /** 记录一次单步：出发深度与执行栈都取当前线程 / 协程的。 */
    private fun armStep(mode: String) {
        val stack = currentStack()
        val kind = when (mode) {
            "into" -> STEP_INTO
            "out" -> STEP_OUT
            else -> STEP_OVER
        }
        step.store(StepRequest(kind, stack.depth, stack))
    }

    /**
     * 上报当前栈帧的参数快照。
     *
     * 编译器在命中分支里生成 `Debug.publish(id, "this", this, "context", context)`，
     * 值在命中瞬间求值（`toString()`），随下一条 `SUSPENDED` 一起发给 IDE。
     */
    fun publish(locationId: Int, vararg values: Any?) {
        if (values.isEmpty()) return
        val text = buildString(64) {
            values.forEachIndexed { index, value ->
                if (index > 0) append('\t')
                append(escape(value?.toString() ?: "null"))
            }
        }
        guarded { pendingValues = "$locationId\t${values.size}\t$text" }
    }

    /**
     * 函数入口压栈；编译器在函数体第一条语句插它。
     *
     * 不看 `active`：调试会话是在进程跑起来之后才接上的，若只看 `active`，
     * 启动阶段的调用链（`PluginWorker.enter` 之前那些帧）会整段丢失。
     */
    fun enter(locationId: Int) {
        currentStack().push(locationId)
    }

    /** 函数出口出栈；`finally` 里调用，协程跨线程恢复时可能对不上，直接忽略。 */
    fun exit(locationId: Int) {
        currentStack().pop(locationId)
    }

    /** 当前执行栈：在协程里就是那个协程的，否则是线程的。 */
    internal fun currentStack(): FrameStack = coroutineFrames ?: threadFrames

    internal fun currentSnapshot(): IntArray = currentStack().snapshot()

    /**
     * 取某个协程（按 `Job` 记账）的帧表；第一次见到它时用**当前**栈快照建表。
     *
     * 快照就是 `kotlinx-coroutines-debug` 里 creation stack 的等价物：
     * 调度器在投递续体的那一刻调用本方法，此时线程上还是创建者的代码。
     */
    internal fun framesForCoroutine(key: Any): FrameStack = guarded {
        coroutineTables[key] ?: FrameStack.of(currentSnapshot()).also { table ->
            if (coroutineTables.size > MAX_COROUTINES) coroutineTables.clear()
            coroutineTables[key] = table
        }
    }

    /** 在指定协程的帧表上执行一个续体；结束后还原线程局部指针。 */
    internal fun runWithCoroutineFrames(frames: FrameStack?, block: () -> Unit) {
        val previous = coroutineFrames
        coroutineFrames = frames
        try {
            block()
        } finally {
            coroutineFrames = previous
        }
    }

    /** 调试会话是否已接上本 image。 */
    fun isAttached(): Boolean = active.load() != 0

    /**
     * 调试期间把跨 image 请求的超时放宽。
     *
     * 断点停住时对端本来就回不了包，按原超时判定「无响应」会让上游任务白白失败。
     * 只放宽我们自己写的等待上限；上游显式 `withTimeout` 仍然由它自己决定。
     */
    fun debugTimeout(base: Duration): Duration =
        if (isAttached()) maxOf(base, DEBUG_TIMEOUT) else base

    /**
     * 压一个「命名帧」，并返回 id。
     *
     * 名字/文件/行全部来自 `Log.site()`：由编译器插件在调用点填好，源码里不写死任何路径。
     * 没插桩的模块里 `Log.site()` 返回 null，这时不压栈（返回 0），调用方照常执行。
     */
    fun enterBridge(site: LogSite?): Int {
        val id = bridgeId(site)
        if (id != 0) enter(id)
        return id
    }

    fun exitBridge(locationId: Int) {
        if (locationId != 0) exit(locationId)
    }

    /**
     * 记录「本线程正在等 correlation 的回包」，并把当前影子栈快照发给 IDE。
     *
     * 等待方阻塞期间栈不会再变，所以快照就等于它的实时位置；
     * 对方命中断点时 IDE 用 correlation 把这段栈接在下面。
     *
     * @param site 等待点本身（`Log.site()`）。`suspend` 调用点所在线程可能一条插桩帧都没有
     *   （Ktor 的协程栈不在影子栈里），这时靠这一帧至少能标出「谁在等」。
     * @param trace 调用方一路收集下来的命名帧 id（由外到内）：`suspend` 链没法用影子栈，
     *   就让每一层在往下传的时候各标注一次。
     */
    fun requestStart(correlation: Long, site: LogSite? = null, trace: IntArray? = null) {
        if (correlation == 0L) return
        if (active.load() == 0) return
        val named = bridgeId(site)
        val stack = currentStack()
        val depth = stack.depth
        if (depth == 0 && named == 0 && (trace == null || trace.isEmpty())) return
        val ids = buildString {
            if (named != 0) append(named)
            // trace 由外到内收集，这里反着放（由内到外）
            trace?.let { frames ->
                for (index in frames.indices.reversed()) {
                    if (frames[index] == 0) continue
                    if (isNotEmpty()) append(',')
                    append(frames[index])
                }
            }
            for (index in depth - 1 downTo 0) {
                if (isNotEmpty()) append(',')
                append(stack.ids[index])
            }
        }
        guarded {
            if (channelFd < 0) return@guarded
            sendFrame(WAITING, "$correlation\t$moduleId\t$ids")
        }
    }

    /** 只注册命名帧、不压栈；给 `suspend` 调用链做显式标注用（可缓存下来复用）。 */
    fun bridgeId(site: LogSite?): Int {
        // 没接调试会话时直接返回：桥接点在生产路径上，不能每次调用都拼字符串
        if (site == null || active.load() == 0) return 0
        return guarded { namedId(frameName(site), site.file, site.line) }
    }

    /** 与日志栈帧同形：`包.类.函数`；属性初始化器没有函数名时退回类名。 */
    private fun frameName(site: LogSite): String =
        if (site.functionName != null) "${site.displayName}.${site.functionName}" else site.displayName

    /** 标记 / 清除当前线程正在服务的事件；返回上一个值，便于 finally 里还原。 */
    fun serving(correlation: Long): Long = guarded {
        val previous = servingCorrelation
        servingCorrelation = correlation
        previous
    }

    private fun namedId(name: String, file: String, line: Int): Int {
        val key = "$name|$file|$line"
        namedIds[key]?.let { return it }
        val id = nextNamedId--
        namedIds[key] = id
        val row = "$id\t$name\t$file\t$line"
        namedRows += row
        if (channelFd >= 0) sendFrame(NAMES, row)
        return id
    }

    private fun connectWithRetry(port: Int): Long {
        val deadline = TimeSource.Monotonic.markNow() + CONNECT_TIMEOUT_MS.milliseconds
        var attempt = 0
        while (true) {
            val fd = debugSocketConnect(port)
            if (fd >= 0) return fd
            if (deadline.hasPassedNow()) return -1
            // 头几次几乎立刻重试，后面稍微放缓，避免空转
            debugSleep(if (attempt++ < 5) 10 else CONNECT_RETRY_MS)
        }
    }

    private fun suspendAt(locationId: Int, reason: String) {
        var epoch = 0
        var sent = false
        guarded {
            if (channelFd < 0) return@guarded
            stopLocation = locationId
            stopSeq++
            epoch = resumeEpoch.load()

            pendingValues?.let { values ->
                pendingValues = null
                if (!sendFrame(VALUES, values)) return@guarded
            }
            sent = sendFrame(
                SUSPENDED,
                "$locationId\t$stopSeq\t$moduleId\t$reason\t${callers()}\t$servingCorrelation\t${creationCallers()}",
            )
        }
        if (!sent) {
            Log.warn("调试通道不可用，断点 $locationId 未上报", tag = "Debug")
            return
        }

        while (active.load() != 0 && resumeEpoch.load() == epoch) {
            val progressed = guarded { drain { type, fields -> handle(type, fields, stopped = true) } }
            if (!progressed) debugSleep(2)
        }
    }

    /**
     * 当前执行的帧（由内到外），不含创建者栈。
     *
     * 不剔除栈顶：`suspend` 函数没有 `enter` 帧，栈顶可能是它的调用者，
     * 剔除会白白丢一帧；重复的那一层（当前函数自己的 `enter`）由 IDE 按
     * `functionId` 过滤掉。
     */
    private fun callers(): String {
        val stack = currentStack()
        return joinFrames(stack, stack.creationBoundary(), stack.depth)
    }

    /** 创建者栈（由内到外）：这条协程是在哪条栈上被创建出来的。线程栈为空。 */
    private fun creationCallers(): String {
        val stack = currentStack()
        return joinFrames(stack, 0, stack.creationBoundary())
    }

    /** `ids[from, to)` 由内到外拼成 `a,b,c`。 */
    private fun joinFrames(stack: FrameStack, from: Int, to: Int): String {
        if (to <= from) return ""
        return buildString {
            for (index in to - 1 downTo from) {
                if (isNotEmpty()) append(',')
                append(stack.ids[index])
            }
        }
    }

    private fun handle(type: Int, fields: List<String>, stopped: Boolean) {
        when (type) {
            BREAKPOINTS -> {
                stops.store(parseIds(fields.lastOrNull().orEmpty()))
                breakpointEpoch.fetchAndAdd(1)
            }
            RESUME -> {
                // `step:over|into|out` 是单步；其它（`all` / `thread:<id>`）就是全量恢复
                val text = fields.lastOrNull().orEmpty()
                if (text.startsWith("step:")) armStep(text.removePrefix("step:"))
                resumeEpoch.fetchAndAdd(1)
            }
            GET_STACK -> if (stopped) sendFrame(STACK, "$stopSeq\t1\t$stopLocation\t0")
            DISCONNECT -> closeChannel("IDE 主动断开")
        }
    }

    private fun parseIds(raw: String): IntArray =
        raw.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .distinct()
            .sorted()
            .toIntArray()

    /** 手写二分：检查点在最热的路径上，不额外分配。 */
    private fun contains(sorted: IntArray, value: Int): Boolean {
        var low = 0
        var high = sorted.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val current = sorted[mid]
            when {
                current < value -> low = mid + 1
                current > value -> high = mid - 1
                else -> return true
            }
        }
        return false
    }

    private fun closeChannel(reason: String) {
        if (channelFd >= 0) debugSocketClose(channelFd)
        channelFd = -1L
        rxSize = 0
        stops.store(IntArray(0))
        step.store(null)
        active.store(0)
        Log.info("调试通道已关闭：$reason", tag = "Debug")
    }

    /** 非阻塞地读空当前可读数据；返回是否读到过字节。 */
    private fun drain(handler: (Int, List<String>) -> Unit): Boolean {
        var progressed = false
        while (channelFd >= 0) {
            if (rxSize >= rx.size) rxSize = 0
            val read = debugSocketRead(channelFd, rx, rxSize, rx.size - rxSize)
            if (read <= 0) break

            rxSize += read
            progressed = true
            while (channelFd >= 0 && rxSize >= HEADER_SIZE) {
                val type = rx[0].toInt() and 0xFF
                val length = ((rx[1].toInt() and 0xFF) shl 24) or
                    ((rx[2].toInt() and 0xFF) shl 16) or
                    ((rx[3].toInt() and 0xFF) shl 8) or
                    (rx[4].toInt() and 0xFF)
                if (length < 0 || length > MAX_PAYLOAD) {
                    rxSize = 0
                    break
                }
                if (rxSize < HEADER_SIZE + length) break

                val end = HEADER_SIZE + length
                val payload = rx.decodeToString(HEADER_SIZE, end)
                val consumed = end
                rx.copyInto(rx, 0, consumed, rxSize)
                rxSize -= consumed
                handler(type, payload.split('\t'))
            }
        }
        return progressed
    }

    private fun sendFrame(type: Int, payload: String): Boolean {
        if (channelFd < 0) return false
        val bytes = payload.encodeToByteArray()
        if (bytes.size > MAX_PAYLOAD) return false

        val frame = ByteArray(HEADER_SIZE + bytes.size)
        frame[0] = type.toByte()
        frame[1] = (bytes.size ushr 24).toByte()
        frame[2] = (bytes.size ushr 16).toByte()
        frame[3] = (bytes.size ushr 8).toByte()
        frame[4] = bytes.size.toByte()
        bytes.copyInto(frame, HEADER_SIZE)

        if (!debugSocketWrite(channelFd, frame, 0, frame.size)) {
            channelFd = -1L
            return false
        }
        return true
    }

    internal inline fun <T> guarded(block: () -> T): T {
        while (!guard.compareAndSet(0, 1)) debugSleep(0)
        try {
            return block()
        } finally {
            guard.store(0)
        }
    }

    /** 值里可能有制表符 / 换行，帧载荷按 `\t` 分隔，先转义。 */
    private fun escape(text: String): String {
        var needs = false
        for (char in text) {
            if (char == '\\' || char == '\t' || char == '\n' || char == '\r') {
                needs = true
                break
            }
        }
        if (!needs) return text
        return buildString(text.length + 8) {
            text.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '\t' -> append("\\t")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(char)
                }
            }
        }
    }

    private const val HEADER_SIZE = 5
}

/** 同步代码的执行栈：每个线程一份。 */
@ThreadLocal
private val threadFrames = FrameStack()

/** 当前线程正在跑的协程的栈；不在协程里时为 null。 */
@ThreadLocal
private var coroutineFrames: FrameStack? = null
