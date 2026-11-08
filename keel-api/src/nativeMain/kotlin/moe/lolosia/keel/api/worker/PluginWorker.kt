package moe.lolosia.keel.api.worker

import kotlinx.cinterop.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.lolosia.keel.abi.c.*
import moe.lolosia.keel.api.*
import moe.lolosia.keel.api.error.*
import moe.lolosia.keel.api.web.*

/**
 * 插件 Worker 的通用运行时。
 *
 * KSP 生成的 `keel_plugin_worker_main` 只负责把插件类交给这里；实例的创建、事件分发与
 * 销毁都在本对象内完成。每个插件 dylib 只有一份状态，且只在事件泵线程上访问。
 */
object PluginWorker {

    /** 当前插件实例；只在 Worker 线程读写。 */
    internal var plugin: Plugin? = null

    /** 当前插件 id 与 Host 收件箱；进入 Worker 时一次性填好，之后只读。 */
    internal var pluginId: ULong = 0uL
    internal var hostInbox: CPointer<KeelQueue>? = null

    /**
     * 构造插件实例并回填 C 侧的事件分发表。
     *
     * @param api  Host 传给 `keel_plugin_bootstrap` 的能力表，由 C 侧填好
     * @param out  分发表，C 侧在 START 之后用它转发所有事件
     * @param factory 创建插件实例（KSP 生成，指向 `@KeelPluginMain` 的类）
     * @return `KeelStatus`；C 侧据此回投 COMPLETE
     */
    fun enter(
        api: CPointer<KeelPluginApi>?,
        out: CPointer<KeelPluginHandlers>?,
        factory: () -> Plugin,
    ): UInt {
        if (api == null || out == null) return KEEL_ERR_INVALID
        if (api.pointed.abi_version != KEEL_ABI_VERSION) return KEEL_ERR_UNSUPPORTED

        val pluginId = api.pointed.plugin_id
        PluginWorker.pluginId = pluginId
        val host = api.pointed.host
        PluginWorker.hostInbox = host?.pointed?.host_inbox
        PluginCapabilities.databaseApi = host?.pointed?.database
        PluginCapabilities.databaseHandle = host?.pointed?.database_handle
        val logger = NativePluginLogger(api.pointed.host, pluginId)
        Log.install(logger)
        WorkerScheduler.reset()
        val metadata = PluginMetadata(
            id = pluginId.toString(),
            name = "",
            version = "",
            entry = "",
        )
        val context = PluginContextImpl(metadata, logger)

        return try {
            PluginRoutes.reset()
            val instance = factory()
            instance.onLoad(context)
            instance.onEnable()
            plugin = instance
            PluginRoutes.publish()
            out.pointed.on_event = staticCFunction(::dispatch)
            KEEL_OK
        } catch (e: Throwable) {
            Log.error("插件生命周期失败", cause = e)
            KEEL_ERR_FAILED
        }
    }

    internal fun disable() {
        plugin?.onDisable()
        plugin = null
        PluginCapabilities.databaseApi = null
        PluginCapabilities.databaseHandle = null
        WorkerScheduler.reset()
    }
}

/** 已经告警过的事件类型；只在 Worker 线程访问。 */
private val warnedKinds = mutableSetOf<Int>()

/**
 * 事件分发入口；`on_event` 不能捕获状态，所以放在顶层。
 *
 * 目前处理 SHUTDOWN 与健康检查 PING；其它事件显式告警一次，
 * 避免「发了没人管」还查不到线索。
 */
private fun dispatch(event: CPointer<KeelEvent>?) {
    val e = event?.pointed ?: return
    try {
        dispatchEvent(e)
    } catch (t: Throwable) {
        // 异常绝不能让 staticCFunction 抛回 C 侧，否则整个进程 terminate。
        // COMMAND 的错误回投给 Host，由 Host 统一打印完整错误链；其余事件没有回包，只能本地记录。
        if (e.kind == KEEL_EVENT_COMMAND.toUShort()) {
            replyError(e, t)
        } else {
            Log.error("事件处理失败（kind=${e.kind}）", cause = t)
        }
    }
}

private fun dispatchEvent(e: KeelEvent) {
    val kind = e.kind.toInt()
    when (kind) {
        KEEL_EVENT_SHUTDOWN.toInt() -> PluginWorker.disable()

        KEEL_EVENT_COMMAND.toInt() -> when (e.subject) {
            KEEL_SUBJECT_PING.toULong() -> replyComplete(e, KEEL_OK.toInt())
            KEEL_SUBJECT_TIMER.toULong() -> WorkerScheduler.tick()
            KEEL_SUBJECT_HTTP.toULong() -> handleHttp(e)
            KEEL_SUBJECT_CONSOLE.toULong() -> PluginConsole.handle(e)
            else -> warnOnce(kind)
        }

        else -> warnOnce(kind)
    }
}

/** 把错误编码成信封回给 Host；Host 侧据此重建 `KeelRemoteException`。 */
private fun replyError(request: KeelEvent, error: Throwable) {
    val payload = error.toKeelError(plugin = PluginWorker.pluginId.toString())
    replyCompleteWithPayload(
        request,
        Json.encodeToString(payload).encodeToByteArray(),
        KEEL_ERR_FAILED.toInt(),
    )
}

private fun warnOnce(kind: Int) {
    if (warnedKinds.add(kind)) {
        Log.warn("插件暂不处理事件 kind=$kind（分发尚未接入）")
    }
}

/** 回一条无载荷的 COMPLETE，correlation / subject 原样带回。 */
private fun replyComplete(request: KeelEvent, status: Int) {
    val inbox = PluginWorker.hostInbox ?: return
    memScoped {
        val out = alloc<KeelEvent>()
        out.size = sizeOf<KeelEvent>().toUInt()
        out.kind = KEEL_EVENT_COMPLETE.toUShort()
        out.source = PluginWorker.pluginId
        out.target = 0uL
        out.correlation = request.correlation
        out.subject = request.subject
        out.status = status
        keel_queue_push(inbox, out.ptr, 0u)
    }
}

/** 处理 Host 转来的 HTTP 请求：解码快照 → 查路由表 → 回一次性响应。 */
private fun handleHttp(event: KeelEvent) {
    val bytes = if (event.payload_size > 0u) {
        event.payload?.reinterpret<ByteVar>()?.readBytes(event.payload_size.toInt())
    } else {
        null
    }
    val request = bytes?.let {
        try {
            Json.decodeFromString<HttpRouteRequest>(it.decodeToString())
        } catch (e: Throwable) {
            Log.error("HTTP 请求载荷无法解码", cause = e)
            null
        }
    }
    if (request == null) {
        val wire = HttpRouteResponse(status = 400, body = "bad request".encodeToByteArray())
        replyCompleteWithPayload(event, Json.encodeToString(wire).encodeToByteArray())
        return
    }

    val response = PluginRoutes.dispatch(request.routeId, request.request, request.pathParameters)
    val wire = PluginRoutes.toWire(response)
    replyCompleteWithPayload(event, Json.encodeToString(wire).encodeToByteArray())
}

/** 回一条带载荷的 COMPLETE；载荷用本二进制的 CRT 分配。 */
internal fun replyCompleteWithPayload(
    request: KeelEvent,
    payload: ByteArray,
    status: Int = KEEL_OK.toInt(),
) {
    val inbox = PluginWorker.hostInbox ?: return
    val buffer = keel_payload_alloc(payload.size.toUInt()) ?: return
    payload.usePinned { keel_payload_copy(buffer, it.addressOf(0), payload.size.toUInt()) }

    memScoped {
        val out = alloc<KeelEvent>()
        out.size = sizeOf<KeelEvent>().toUInt()
        out.kind = KEEL_EVENT_COMPLETE.toUShort()
        out.source = PluginWorker.pluginId
        out.target = 0uL
        out.correlation = request.correlation
        out.subject = request.subject
        out.status = status
        out.payload = buffer
        out.payload_size = payload.size.toUInt()
        keel_event_set_default_release(out.ptr)
        if (keel_queue_push(inbox, out.ptr, 0u) != KEEL_OK) {
            keel_event_release_default(null, out.ptr)
        }
    }
}
