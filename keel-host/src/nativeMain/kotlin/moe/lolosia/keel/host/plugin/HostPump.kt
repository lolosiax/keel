package moe.lolosia.keel.host.plugin

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.pointed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import moe.lolosia.keel.abi.c.*
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.config.*
import moe.lolosia.keel.api.error.KeelErrorEntry
import moe.lolosia.keel.api.error.KeelErrorPayload
import moe.lolosia.keel.api.host.*
import moe.lolosia.keel.api.service.ServiceCall
import moe.lolosia.keel.api.service.ServiceRegistration
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.storage.*
import moe.lolosia.keel.api.web.RouteRegistration
import moe.lolosia.keel.host.auth.HostAuth
import moe.lolosia.keel.host.config.HostConfigs
import moe.lolosia.keel.host.route.HostPermissionCatalog
import moe.lolosia.keel.host.route.HostRoutes
import moe.lolosia.keel.host.service.HostServices
import moe.lolosia.keel.host.storage.HostStorage

/** 插件间调用的 Host 侧兜底超时；调用方自己的 3s 超时通常先到。 */
private const val SERVICE_TIMEOUT_MS = 5000L

/** 业务 subject 从 0x1000 开始；协议 subject 仍由各自的处理器消费。 */
private const val BUSINESS_SUBJECT_MIN = 0x1000uL

/** 一次 Host → 插件的在途请求；[reply] 由泵线程完成。 */
internal class HostRequest(
    val pluginId: ULong,
    val subject: ULong,
    val payload: ByteArray?,
    val timeoutMs: Long,
) {
    val reply = CompletableDeferred<HostEvent?>()
    var correlation: ULong = 0uL
    var deadlineMs: Long = 0L
}

/** 一条插件 → 插件的在途服务调用；Host 只负责按 id 转发与带回包。 */
private class ServiceForward(
    val callerId: ULong,
    val callerCorrelation: ULong,
    val providerId: ULong,
    val deadlineMs: Long,
)

/**
 * Host 侧事件泵。
 *
 * 长驻 Host 应持续消费收件箱；CLI 在退出前用 [drain] 清空积压的插件日志。
 * 日志就地消费；`target` 非 0 的事件按 plugin_id 转发给目标插件 inbox。
 *
 * 请求 / 应答：外部线程（Ktor）经 [request] 把请求交给这里的队列并挂起，
 * 泵线程持有 pending 表，收到同 correlation 的 COMPLETE 后 resume。
 * pending 表因此只被泵线程访问，不需要锁。
 */
internal object HostPump {

    /** 同一种「无法处理」只告警一次，避免事件风暴时刷屏。 */
    private val warned = mutableSetOf<String>()

    private val clock = TimeSource.Monotonic.markNow()

    /** 外部线程投进来的请求；只由泵线程取出。 */
    private val incoming = Channel<HostRequest>(Channel.UNLIMITED)

    /** correlation → 在途请求；只在泵线程读写。 */
    private val pending = mutableMapOf<ULong, HostRequest>()

    /** Host 分配的 correlation → 插件间在途调用；只在泵线程读写。 */
    private val forwards = mutableMapOf<ULong, ServiceForward>()

    /** 非阻塞地取空当前收件箱。 */
    fun drain() {
        dispatchIncoming()
        expireRequests()
        while (popHostEventRaw(0u) { event -> dispatch(event) }) {
            // 返回 false 表示收件箱已空
        }
        expireRequests()
    }

    /** 阻塞等待并处理一条事件；长驻 Host 的主循环每轮调用一次。 */
    fun serve(timeoutMs: UInt = 200u) {
        dispatchIncoming()
        expireRequests()
        popHostEventRaw(timeoutMs) { event -> dispatch(event) }
        expireRequests()
    }

    /**
     * 从任意线程发起一次请求。
     *
     * 挂起当前协程，泵线程收到同 correlation 的 COMPLETE 后完成 [HostRequest.reply]。
     * `ApplicationCall` / `Continuation` 不跨 ABI，跨界只有 correlation 与载荷。
     *
     * @return 超时或插件不在线时返回 null
     */
    suspend fun request(
        pluginId: ULong,
        subject: ULong,
        payload: ByteArray? = null,
        timeoutMs: Long = 2000,
    ): HostEvent? {
        val request = HostRequest(pluginId, subject, payload, timeoutMs)
        incoming.send(request)
        // 泵线程可能正阻塞在 200ms 的 pop 里；投一条 WAKE 让它立刻处理新请求
        wakeHostPump()
        return withTimeoutOrNull(timeoutMs) { request.reply.await() }
    }

    /**
     * 处理一条原始事件。
     *
     * @return true 表示载荷所有权已转移给目标插件（由它 release）；false 由调用方归还
     */
    fun dispatch(event: CPointer<KeelEvent>): Boolean {
        val e = event.pointed
        val kind = e.kind.toInt()

        // Host 内部唤醒：立刻把外部线程提交的请求转给插件，不再等 pop 超时
        if (kind == KEEL_EVENT_COMMAND.toInt() &&
            e.target == 0uL &&
            e.subject == KEEL_SUBJECT_WAKE.toULong()
        ) {
            dispatchIncoming()
            return false
        }

        if (kind == KEEL_EVENT_COMPLETE.toInt()) {
            val request = pending.remove(e.correlation)
            if (request != null) {
                request.reply.complete(snapshotOf(event))
                return false
            }
            val forward = forwards.remove(e.correlation)
            if (forward != null) {
                completeForward(forward, snapshotOf(event))
                return false
            }
        }

        if (kind == KEEL_EVENT_LOG.toInt()) {
            handleLogEvent(snapshotOf(event))
            return false
        }

        if (kind == KEEL_EVENT_COMMAND.toInt() && e.target == 0uL) {
            when (e.subject) {
                KEEL_SUBJECT_ROUTE_REGISTER.toULong() -> {
                    handleSnapshot(snapshotOf(event))
                    return false
                }

                KEEL_SUBJECT_SERVICE_REGISTER.toULong() -> {
                    handleServiceRegister(snapshotOf(event))
                    return false
                }

                KEEL_SUBJECT_SERVICE_CALL.toULong() -> {
                    handleServiceCall(snapshotOf(event))
                    return false
                }
            }

            // 其余业务 subject 是插件 → Host 的请求；协议 subject 落到下面的告警
            if (e.subject >= BUSINESS_SUBJECT_MIN) {
                handleHostRequest(snapshotOf(event))
                return false
            }
        }

        val target = e.target
        if (target == 0uL) {
            warnOnce("kind=$kind", "Host 没有该事件的处理器")
            return false
        }

        val inbox = pluginInbox(target)
        if (inbox == null) {
            warnOnce("kind=$kind->$target", "目标插件不在线")
            return false
        }
        return keel_queue_push(inbox, event, 0u) == KEEL_OK
    }

    private fun warnOnce(key: String, reason: String) {
        if (!warned.add(key)) return
        Log.warn("丢弃事件 $key：$reason", tag = "EventBus")
    }

    /**
     * 处理不依赖原始指针的事件（启动等待期、阻塞请求期间使用）。
     *
     * 目前只认路由注册；返回 true 表示事件已被消费。
     */
    fun handleSnapshot(event: HostEvent): Boolean {
        if (event.kind != KEEL_EVENT_COMMAND.toInt() ||
            event.target != 0uL ||
            event.subject != KEEL_SUBJECT_ROUTE_REGISTER.toULong()
        ) {
            return false
        }

        val payload = event.payload ?: return true
        try {
            val registration = Json.decodeFromString<RouteRegistration>(payload.decodeToString())
            HostRoutes.register(event.source, registration.routes)
            HostPermissionCatalog.register(event.source, registration.permissions)
        } catch (err: Throwable) {
            Log.warn("路由注册失败：${err.message}", tag = "Router")
        }
        return true
    }

    /** 插件启用的服务声明：只要 id 列表，不需要方法签名。 */
    private fun handleServiceRegister(event: HostEvent) {
        val payload = event.payload
        if (payload == null) {
            HostServices.register(event.source, emptyList())
            return
        }
        try {
            val registration = ServiceWire.decodeRegistration(payload)
            HostServices.register(event.source, registration.services)
        } catch (err: Throwable) {
            Log.warn("服务注册失败：${err.message}", tag = "Service")
        }
    }

    /**
     * 插件 → 插件的服务调用：按 service id 找到提供者，换一个 Host correlation 转发，
     * 回包到达时再换回调用方的 correlation。
     */
    private fun handleServiceCall(event: HostEvent) {
        val payload = event.payload
        if (payload == null) {
            replyServiceError(event.source, event.correlation, "service call without payload")
            return
        }
        val call = try {
            ServiceWire.decodeCall(payload)
        } catch (err: Throwable) {
            Log.warn("服务调用载荷无法解码：${err.message}", tag = "Service")
            replyServiceError(event.source, event.correlation, "malformed service call")
            return
        }

        val provider = HostServices.providerOf(call.service)
        if (provider == null) {
            replyServiceError(
                event.source,
                event.correlation,
                "service unavailable: ${call.service}",
            )
            return
        }

        val correlation = nextCorrelation()
        val pushed = pushToPlugin(
            pluginId = provider,
            kind = KEEL_EVENT_COMMAND.toInt(),
            subject = KEEL_SUBJECT_SERVICE_CALL.toULong(),
            correlation = correlation,
            payload = payload,
            source = event.source,
        )
        if (!pushed) {
            replyServiceError(
                event.source,
                event.correlation,
                "service provider offline: ${call.service}",
            )
            return
        }
        forwards[correlation] = ServiceForward(
            callerId = event.source,
            callerCorrelation = event.correlation,
            providerId = provider,
            deadlineMs = nowMs() + SERVICE_TIMEOUT_MS,
        )
    }

    /** 把提供者的回包送回调用方；correlation 换回调用方当初发出去的值。 */
    private fun completeForward(forward: ServiceForward, reply: HostEvent) {
        val pushed = pushToPlugin(
            pluginId = forward.callerId,
            kind = KEEL_EVENT_COMPLETE.toInt(),
            subject = reply.subject,
            correlation = forward.callerCorrelation,
            status = reply.status,
            payload = reply.payload,
            source = forward.providerId,
        )
        if (!pushed) {
            warnOnce("service-reply->${forward.callerId}", "调用方已离线，丢弃服务回包")
        }
    }

    /** 以错误信封回一条 COMPLETE，调用方会把它重建成 `KeelRemoteException`。 */
    private fun replyServiceError(callerId: ULong, correlation: ULong, message: String) {
        val payload = Json.encodeToString(
            KeelErrorPayload(
                plugin = "host",
                chain = listOf(KeelErrorEntry(type = "ServiceUnavailableException", message = message)),
            )
        ).encodeToByteArray()
        pushToPlugin(
            pluginId = callerId,
            kind = KEEL_EVENT_COMPLETE.toInt(),
            subject = KEEL_SUBJECT_SERVICE_CALL.toULong(),
            correlation = correlation,
            status = KEEL_ERR_FAILED.toInt(),
            payload = payload,
        )
    }

    /**
     * 插件停用 / 卸载时清掉与它相关的在途调用。
     *
     * 提供者离线时调用方会自己超时；这里只负责不再为它保留转发记录。
     */
    fun dropPlugin(pluginId: ULong) {
        forwards.entries.removeAll { it.value.callerId == pluginId || it.value.providerId == pluginId }
    }

    /**
     * 插件 → Host 的业务请求 / 通知。
     *
     * correlation 为 0 视为单向通知（发完即弃）；非 0 回一条同 correlation 的 COMPLETE，
     * 失败时用 `KeelErrorPayload` 让插件侧重建异常。
     */
    private fun handleHostRequest(event: HostEvent) {
        val oneWay = event.correlation == 0uL
        val result = runCatching { dispatchHostSubject(event.source, event.subject, event.payload) }
        result.exceptionOrNull()?.let { error ->
            Log.warn(
                "Host 请求处理失败（subject=${event.subject}）：${error.message}",
                tag = "Host",
            )
        }
        if (oneWay) return

        if (result.isSuccess) {
            pushToPlugin(
                pluginId = event.source,
                kind = KEEL_EVENT_COMPLETE.toInt(),
                subject = event.subject,
                correlation = event.correlation,
                payload = result.getOrThrow(),
            )
            return
        }

        val error = result.exceptionOrNull() ?: IllegalStateException("unknown host request error")
        val payload = Json.encodeToString(
            KeelErrorPayload(
                plugin = "host",
                chain = listOf(
                    KeelErrorEntry(
                        type = error::class.simpleName ?: "Throwable",
                        message = error.message.orEmpty(),
                    )
                ),
            )
        ).encodeToByteArray()
        pushToPlugin(
            pluginId = event.source,
            kind = KEEL_EVENT_COMPLETE.toInt(),
            subject = event.subject,
            correlation = event.correlation,
            status = KEEL_ERR_FAILED.toInt(),
            payload = payload,
        )
    }

    /** 内置 subject 的实现；后续的 Storage 等加在这里。 */
    private fun dispatchHostSubject(source: ULong, subject: ULong, payload: ByteArray?): ByteArray =
        when (subject.toLong()) {
            HostSubjects.PERMISSION_CATALOG -> ServiceWire.encode(
                PermissionCatalogReply.serializer(),
                PermissionCatalogReply(HostPermissionCatalog.snapshot()),
            )

            HostSubjects.CONFIG_READ -> {
                val request = decodePayload(ConfigReadRequest.serializer(), payload, "配置读取")
                ServiceWire.encode(ConfigReadReply.serializer(), HostConfigs.read(source, request))
            }

            HostSubjects.CONFIG_WRITE -> {
                val request = decodePayload(ConfigWriteRequest.serializer(), payload, "配置写入")
                ServiceWire.encode(ConfigDocReply.serializer(), HostConfigs.write(source, request))
            }

            HostSubjects.STORAGE -> {
                val request = decodePayload(StorageRequest.serializer(), payload, "存储请求")
                val data = when (request.op) {
                    StorageOp.READ -> HostStorage.read(source, request.path)
                    StorageOp.WRITE -> {
                        HostStorage.write(source, request.path, request.data ?: ByteArray(0))
                        null
                    }

                    StorageOp.DELETE -> {
                        HostStorage.delete(source, request.path)
                        null
                    }
                }
                ServiceWire.encode(StorageReply.serializer(), StorageReply(data))
            }

            HostSubjects.AUTH_INVALIDATE -> {
                val request = decodeInvalidate(payload)
                HostAuth.invalidateAuthentication(request?.token, request?.userId)
                ByteArray(0)
            }

            HostSubjects.AUTHZ_INVALIDATE -> {
                val request = decodeInvalidate(payload)
                HostAuth.invalidateAuthorization(request?.userId)
                ByteArray(0)
            }

            HostSubjects.CLASSPATH -> {
                val request = decodePayload(ClasspathRequest.serializer(), payload, "资源读取")
                val namespace = request.pluginId
                    ?: HostPluginIds.nameOf(source)
                    ?: throw IllegalArgumentException("插件 $source 没有登记资源命名空间")
                val classpath = HostPlugins.classpathOf(namespace)
                    ?: throw IllegalArgumentException("插件资源不存在：$namespace")
                ServiceWire.encode(
                    ClasspathReply.serializer(),
                    when (request.op) {
                        ClasspathOp.READ -> ClasspathReply(bytes = classpath.read(request.path))
                        ClasspathOp.EXISTS -> ClasspathReply(exists = classpath.exists(request.path))
                        ClasspathOp.LIST -> ClasspathReply(entries = classpath.list(request.path))
                    },
                )
            }

            HostSubjects.CACHE_DIR -> ServiceWire.encode(
                CacheDirReply.serializer(),
                CacheDirReply(
                    PluginResources.cacheDir(source)
                        ?: throw IllegalStateException("插件 $source 尚未分配资源目录")
                ),
            )

            HostSubjects.MATERIALIZE -> {
                val request = decodePayload(MaterializeRequest.serializer(), payload, "资源物化")
                ServiceWire.encode(
                    MaterializeReply.serializer(),
                    MaterializeReply(PluginResources.materialize(source, request.path)),
                )
            }

            else -> error("Host 没有该 subject 的处理器：$subject")
        }

    /** 解码业务请求载荷；缺失或格式错误时抛带上下文的异常，由错误信封回给插件。 */
    private fun <T> decodePayload(serializer: KSerializer<T>, payload: ByteArray?, what: String): T {
        val bytes = payload ?: throw IllegalArgumentException("$what 缺少载荷")
        return try {
            ServiceWire.decode(serializer, bytes)
        } catch (e: Throwable) {
            throw IllegalArgumentException("$what 载荷无法解码：${e.message}", e)
        }
    }

    /** 失效通知解不出来时按「全部失效」处理，宁可多查一次也不能留下旧权限。 */
    private fun decodeInvalidate(payload: ByteArray?): InvalidateRequest? =
        payload?.takeIf { it.isNotEmpty() }?.let { bytes ->
            runCatching { ServiceWire.decode(InvalidateRequest.serializer(), bytes) }
                .onFailure { Log.warn("缓存失效通知无法解码：${it.message}", tag = "Host") }
                .getOrNull()
        }

    /**
     * 处理与当前同步等待无关的事件：日志、注册、插件间调用、其它请求的回包。
     *
     * 运行期 HTTP 线程提交的请求回包可能和启动 / 控制台请求交错，
     * 同步等待循环必须把它们交给这里；漏掉会丢回包，表现为请求超时。
     *
     * @return true 表示事件已被消费
     */
    fun handleAsync(event: HostEvent): Boolean {
        if (event.kind == KEEL_EVENT_LOG.toInt()) {
            handleLogEvent(event)
            return true
        }
        if (event.kind == KEEL_EVENT_COMPLETE.toInt()) {
            val request = pending.remove(event.correlation)
            if (request != null) {
                request.reply.complete(event)
                return true
            }
            val forward = forwards.remove(event.correlation)
            if (forward != null) {
                completeForward(forward, event)
                return true
            }
        }
        if (event.kind == KEEL_EVENT_COMMAND.toInt() && event.target == 0uL) {
            when (event.subject) {
                KEEL_SUBJECT_SERVICE_REGISTER.toULong() -> {
                    handleServiceRegister(event)
                    return true
                }

                KEEL_SUBJECT_SERVICE_CALL.toULong() -> {
                    handleServiceCall(event)
                    return true
                }
            }

            // 其余业务 subject 是插件 → Host 的请求；协议 subject 交给 handleSnapshot / 告警
            if (event.subject >= BUSINESS_SUBJECT_MIN) {
                handleHostRequest(event)
                return true
            }
        }
        return handleSnapshot(event)
    }

    private fun dispatchIncoming() {
        while (true) {
            val request = incoming.tryReceive().getOrNull() ?: break
            start(request)
        }
    }

    private fun start(request: HostRequest) {
        val correlation = nextCorrelation()
        request.correlation = correlation
        request.deadlineMs = nowMs() + request.timeoutMs

        val pushed = pushToPlugin(
            pluginId = request.pluginId,
            kind = KEEL_EVENT_COMMAND.toInt(),
            subject = request.subject,
            correlation = correlation,
            payload = request.payload,
        )
        if (!pushed) {
            request.reply.complete(null)
            return
        }
        pending[correlation] = request
    }

    /** 清理超时请求；回包晚于 deadline 时也会被丢弃。 */
    private fun expireRequests() {
        val now = nowMs()
        if (pending.isNotEmpty()) {
            val expired = pending.filterValues { it.deadlineMs <= now }.keys.toList()
            expired.forEach { correlation -> pending.remove(correlation)?.reply?.complete(null) }
        }
        if (forwards.isNotEmpty()) {
            val expired = forwards.filterValues { it.deadlineMs <= now }.keys.toList()
            expired.forEach { correlation ->
                val forward = forwards.remove(correlation) ?: return@forEach
                replyServiceError(
                    forward.callerId,
                    forward.callerCorrelation,
                    "service call timed out",
                )
            }
        }
    }

    private fun nowMs(): Long = clock.elapsedNow().inWholeMilliseconds

    /**
     * Host 向插件发起一次同步请求，返回完整回包。
     *
     * 只在启动阶段或控制台（都在主线程 / 泵线程上）使用；运行期请用 [request]。
     *
     * @return null 表示超时、目标不在线或队列拒收
     */
    fun requestPluginEvent(
        pluginId: ULong,
        subject: ULong,
        payload: ByteArray? = null,
        timeoutMs: UInt = 2000u,
    ): HostEvent? {
        val correlation = nextCorrelation()
        if (!pushToPlugin(
                pluginId = pluginId,
                kind = KEEL_EVENT_COMMAND.toInt(),
                subject = subject,
                correlation = correlation,
                payload = payload,
            )
        ) {
            return null
        }

        val mark = TimeSource.Monotonic.markNow()
        val timeout = timeoutMs.toLong().milliseconds
        while (true) {
            val remaining = timeout - mark.elapsedNow()
            if (remaining <= 0.milliseconds) return null

            val event = popHostEvent(remaining.inWholeMilliseconds.toUInt()) ?: return null
            // 先认自己的回包，再交给通用处理（否则会被 handleAsync 当成其它请求的回包吞掉）
            if (event.kind == KEEL_EVENT_COMPLETE.toInt() && event.correlation == correlation) return event
            if (handleAsync(event)) continue
            warnOnce("kind=${event.kind}->${event.target}", "请求期间收到无关事件")
        }
    }

    /**
     * 只关心成功与否的同步请求；内部复用 [requestPluginEvent]。
     *
     * @return true 表示插件回了 `KEEL_OK`
     */
    fun requestPlugin(pluginId: ULong, subject: ULong, timeoutMs: UInt = 2000u): Boolean =
        requestPluginEvent(pluginId, subject, null, timeoutMs)?.status == KEEL_OK.toInt()
}
