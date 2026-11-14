package moe.lolosia.keel.host.plugin

import kotlinx.cinterop.*
import moe.lolosia.keel.abi.c.*
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.debug.Debug
import moe.lolosia.keel.host.registerCrashModule
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** `keel_plugin_bootstrap(host, plugin_id, worker_main, out)`。 */
private typealias BootstrapFn =
    CFunction<(CPointer<KeelHostApi>?, ULong, KeelPluginWorkerMain?, CPointer<KeelPluginApi>?) -> UInt>

/** 正常停用最多等多久；超过就放弃 join，避免进程退不出去。 */
private const val SHUTDOWN_WAIT_MS = 5_000L

/** 启动失败时等泵线程收尾的窗口；只用于消费它最后的日志，不承担业务。 */
private const val FAILED_START_WAIT_MS = 2_000L

/**
 * 一次成功启动的插件。
 *
 * Host 只持有两个 C 函数指针，从不直接调用插件 Kotlin 代码：
 * 启动是投递 `START` 等 `COMPLETE`，关闭是投递 `SHUTDOWN` 等泵线程退出。
 */
internal class PluginRuntime(
    val pluginId: ULong,
    /** manifest 里的 id，仅用于日志与运维面。 */
    val pluginName: String,
    private val shutdownFn: CPointer<CFunction<() -> Unit>>?,
    private val requestShutdownFn: CPointer<CFunction<() -> Int>>?,
    private val pumpRunningFn: CPointer<CFunction<() -> Int>>?,
    private val joinFn: CPointer<CFunction<() -> Unit>>?,
) {

    /**
     * 让 C 侧投递 SHUTDOWN 并结束泵线程。
     *
     * 新版插件支持「先投递、再轮询、最后 join」：等待期间继续 `HostPump.serve`，
     * 这样 `onDisable` 里的日志、配置、存储与服务调用都能拿到 Host 应答。
     * 旧版插件没有这三个符号，退回一次性的阻塞 shutdown。
     */
    fun shutdown() {
        if (requestShutdownFn != null && pumpRunningFn != null && joinFn != null) {
            requestShutdownFn.invoke()
            val deadline = TimeSource.Monotonic.markNow() + SHUTDOWN_WAIT_MS.milliseconds
            while (pumpRunningFn.invoke() != 0 && !deadline.hasPassedNow()) {
                HostPump.serve(50u)
            }
            if (pumpRunningFn.invoke() == 0) {
                joinFn.invoke()
            } else {
                Log.warn(
                    "$pluginName 在 ${SHUTDOWN_WAIT_MS}ms 内没有退出，跳过 join；" +
                        "动态库仍留在进程内，需重启进程回收",
                    tag = "Loader",
                )
            }
        } else {
            shutdownFn?.invoke()
        }
        // 队列已在 keel_plugin_join / shutdown 里销毁，之后不能再往里转发事件
        unregisterPluginInbox(pluginId)
    }
}

/**
 * 启动失败后的清理：请求泵线程退出，并尽量在窗口期内 join。
 *
 * K/N 运行时不能卸载，泵线程若还活着，下一次 bootstrap 会被 C 侧拒绝；
 * 这里只做「尽力而为」，不阻塞启动流程。
 */
private fun stopFailedPump(
    pluginName: String,
    shutdownFn: CPointer<CFunction<() -> Unit>>?,
    requestShutdownFn: CPointer<CFunction<() -> Int>>?,
    pumpRunningFn: CPointer<CFunction<() -> Int>>?,
    joinFn: CPointer<CFunction<() -> Unit>>?,
) {
    if (requestShutdownFn != null && pumpRunningFn != null && joinFn != null) {
        requestShutdownFn.invoke()
        val deadline = TimeSource.Monotonic.markNow() + FAILED_START_WAIT_MS.milliseconds
        while (pumpRunningFn.invoke() != 0 && !deadline.hasPassedNow()) {
            HostPump.serve(20u)
        }
        if (pumpRunningFn.invoke() == 0) {
            joinFn.invoke()
        } else {
            Log.warn(
                "$pluginName 启动失败后事件泵仍在运行；它退出前再次启用会被拒绝",
                tag = "Loader",
            )
        }
        return
    }
    // 旧版插件只有一次性 shutdown；这里不能保证线程会退出，但至少尝试一次
    runCatching { shutdownFn?.invoke() }
        .onFailure { Log.warn("$pluginName 启动失败后的 shutdown 出错：${it.message}", tag = "Loader") }
}

/**
 * 启动一个已解包插件：`dlopen` → 解析 bootstrap / entry → bootstrap → 投 START → 等 COMPLETE。
 *
 * bootstrap 返回时插件 Kotlin 还没有运行过；`START` 由 C 的事件泵线程转成第一次进入
 * 插件运行时，结果以 `COMPLETE` 回投 Host 收件箱。
 *
 * @throws IllegalStateException 库或符号缺失、bootstrap 失败、超时、启动返回非 OK
 */
internal fun startPlugin(
    plugin: LoadedPlugin,
    pluginId: ULong,
    timeoutMs: UInt = 5000u,
): PluginRuntime {
    val manifest = plugin.manifest
    val library = NativeLibrary(plugin.libraryPath)
    // 崩溃归因：故障地址落在哪个模块，就归到哪个插件
    registerCrashModule(manifest.id, plugin.libraryPath)

    val bootstrapAddress = library.symbol("keel_plugin_bootstrap")
        ?: throw IllegalStateException("${plugin.libraryPath} 未导出 keel_plugin_bootstrap")
    val bootstrap = bootstrapAddress.reinterpret<BootstrapFn>()

    val entryAddress = library.symbol(manifest.entry)
        ?: throw IllegalStateException("${plugin.libraryPath} 未导出 entry：${manifest.entry}")
    val workerMain = entryAddress.reinterpret<
        CFunction<(CPointer<KeelPluginApi>?, CPointer<KeelPluginHandlers>?) -> UInt>
        >()

    // 关闭相关的符号在启动前就解析好：启动失败时也要用它把泵线程收掉。
    // 旧版插件只有 keel_plugin_shutdown，三个新符号为 null 时走兼容路径。
    val shutdownFn = library.symbol("keel_plugin_shutdown")
        ?.reinterpret<CFunction<() -> Unit>>()
    val requestShutdownFn = library.symbol("keel_plugin_request_shutdown")
        ?.reinterpret<CFunction<() -> Int>>()
    val pumpRunningFn = library.symbol("keel_plugin_pump_running")
        ?.reinterpret<CFunction<() -> Int>>()
    val joinFn = library.symbol("keel_plugin_join")
        ?.reinterpret<CFunction<() -> Unit>>()

    val api = nativeHeap.alloc<KeelPluginApi>()
    val bootstrapStatus = bootstrap(hostAbi, pluginId, workerMain, api.ptr)
    if (bootstrapStatus != KEEL_OK) {
        throw IllegalStateException("${manifest.id} bootstrap 失败：status=$bootstrapStatus")
    }
    val inbox = api.inbox
        ?: throw IllegalStateException("${manifest.id} bootstrap 未返回 inbox")
    registerPluginInbox(pluginId, inbox)

    val correlation = nextCorrelation()
    try {
        memScoped {
            val event = alloc<KeelEvent>()
            event.size = sizeOf<KeelEvent>().toUInt()
            event.kind = KEEL_EVENT_COMMAND.toUShort()
            event.seq = correlation
            event.target = pluginId
            event.correlation = correlation
            event.subject = KEEL_SUBJECT_START.toULong()
            event.status = KEEL_OK.toInt()
            val pushed = keel_queue_push(inbox, event.ptr, 0u)
            if (pushed != KEEL_OK) {
                throw IllegalStateException("${manifest.id} 投递 START 失败：status=$pushed")
            }
        }
        awaitComplete(manifest, correlation, timeoutMs)
    } catch (e: Exception) {
        // 启动失败时插件不会再有 Worker，登记与泵线程都要一并撤回；
        // 否则再次 enable 会让两个泵线程共享同一份 C 全局状态。
        unregisterPluginInbox(pluginId)
        stopFailedPump(manifest.id, shutdownFn, requestShutdownFn, pumpRunningFn, joinFn)
        throw e
    }

    return PluginRuntime(
        pluginId = pluginId,
        pluginName = manifest.id,
        shutdownFn = shutdownFn,
        requestShutdownFn = requestShutdownFn,
        pumpRunningFn = pumpRunningFn,
        joinFn = joinFn,
    )
}

/** 等这次启动对应的 `COMPLETE`；期间其它插件的事件直接丢弃。 */
private fun awaitComplete(manifest: KeelPackageManifest, correlation: ULong, timeoutMs: UInt) {
    val mark = TimeSource.Monotonic.markNow()
    // 调试会话里 onLoad / onEnable 可能在断点上停很久：放宽启动等待，
    // 否则 Host 会把「正在单步」误判成启动失败并开始拆插件
    val timeout = Debug.debugTimeout(timeoutMs.toLong().milliseconds)
    while (true) {
        val remaining = timeout - mark.elapsedNow()
        if (remaining <= 0.milliseconds) break

        val event = popHostEvent(remaining.inWholeMilliseconds.toUInt()) ?: break
        // 先认本次启动的回包，再交给通用处理（日志 / 路由 / 其它请求的回包）
        if (event.kind == KEEL_EVENT_COMPLETE.toInt() && event.correlation == correlation) {
            if (event.status != KEEL_OK.toInt()) {
                throw IllegalStateException("${manifest.id} 启动失败：status=${event.status}")
            }
            return
        }
        if (HostPump.handleAsync(event)) continue
    }
    throw IllegalStateException(
        "${manifest.id} 启动超时（${timeout.inWholeMilliseconds}ms 内没有 COMPLETE）",
    )
}

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
private val correlationCounter = kotlin.concurrent.atomics.AtomicLong(0)

/** Host 发起请求时使用的 correlation 源；启动握手、HTTP 转发与 ping 共用，可跨线程调用。 */
@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
internal fun nextCorrelation(): ULong = correlationCounter.addAndFetch(1).toULong()
