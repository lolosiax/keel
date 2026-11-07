package moe.lolosia.keel.host.plugin

import kotlinx.cinterop.*
import moe.lolosia.keel.abi.c.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** `keel_plugin_bootstrap(host, plugin_id, worker_main, out)`。 */
private typealias BootstrapFn =
    CFunction<(CPointer<KeelHostApi>?, ULong, KeelPluginWorkerMain?, CPointer<KeelPluginApi>?) -> UInt>

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
) {

    /** 让 C 侧投递 SHUTDOWN 并 join 泵线程；调用后该插件不再处理事件。 */
    fun shutdown() {
        shutdownFn?.invoke()
        // 队列已在 keel_plugin_shutdown 里销毁，之后不能再往里转发事件
        unregisterPluginInbox(pluginId)
    }
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

    val bootstrapAddress = library.symbol("keel_plugin_bootstrap")
        ?: throw IllegalStateException("${plugin.libraryPath} 未导出 keel_plugin_bootstrap")
    val bootstrap = bootstrapAddress.reinterpret<BootstrapFn>()

    val entryAddress = library.symbol(manifest.entry)
        ?: throw IllegalStateException("${plugin.libraryPath} 未导出 entry：${manifest.entry}")
    val workerMain = entryAddress.reinterpret<
        CFunction<(CPointer<KeelPluginApi>?, CPointer<KeelPluginHandlers>?) -> UInt>
        >()

    val api = nativeHeap.alloc<KeelPluginApi>()
    val bootstrapStatus = bootstrap(hostAbi, pluginId, workerMain, api.ptr)
    if (bootstrapStatus != KEEL_OK) {
        throw IllegalStateException("${manifest.id} bootstrap 失败：status=$bootstrapStatus")
    }
    val inbox = api.inbox
        ?: throw IllegalStateException("${manifest.id} bootstrap 未返回 inbox")
    registerPluginInbox(pluginId, inbox)

    val correlation = nextCorrelation()
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

    try {
        awaitComplete(manifest, correlation, timeoutMs)
    } catch (e: Exception) {
        // 启动失败时插件不会再有 Worker，登记要一并撤回
        unregisterPluginInbox(pluginId)
        throw e
    }

    val shutdownFn = library.symbol("keel_plugin_shutdown")
        ?.reinterpret<CFunction<() -> Unit>>()
    return PluginRuntime(pluginId, manifest.id, shutdownFn)
}

/** 等这次启动对应的 `COMPLETE`；期间其它插件的事件直接丢弃。 */
private fun awaitComplete(manifest: KeelPackageManifest, correlation: ULong, timeoutMs: UInt) {
    val mark = TimeSource.Monotonic.markNow()
    val timeout = timeoutMs.toLong().milliseconds
    while (true) {
        val remaining = timeout - mark.elapsedNow()
        if (remaining <= 0.milliseconds) break

        val event = popHostEvent(remaining.inWholeMilliseconds.toUInt()) ?: break
        if (event.kind == KEEL_EVENT_LOG.toInt()) {
            handleLogEvent(event)
            continue
        }
        if (event.kind != KEEL_EVENT_COMPLETE.toInt()) continue
        if (event.correlation != correlation) continue
        if (event.status != KEEL_OK.toInt()) {
            throw IllegalStateException("${manifest.id} 启动失败：status=${event.status}")
        }
        return
    }
    throw IllegalStateException("${manifest.id} 启动超时（${timeoutMs}ms 内没有 COMPLETE）")
}

private var correlationCounter = 0uL

/** Host 发起请求时使用的 correlation 源；启动握手与 ping 共用。 */
internal fun nextCorrelation(): ULong = ++correlationCounter
