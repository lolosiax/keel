package moe.lolosia.keel.api.worker

import kotlinx.cinterop.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.lolosia.keel.abi.c.*
import moe.lolosia.keel.api.web.*

/**
 * 插件侧路由表。
 *
 * 只在 Worker 线程访问；启用后由 [publish] 把描述表发给 Host。
 * 运行期分发不依赖反射：Host 带回 routeId，这里按表查找。
 */
internal object PluginRoutes : Routes {

    private class Entry(
        val descriptor: RouteDescriptor,
        val handler: (HttpRequest, Map<String, String>) -> HttpResponse,
    )

    private val entries = mutableListOf<Entry>()

    fun reset() {
        entries.clear()
    }

    override fun register(
        descriptor: RouteDescriptor,
        handler: (HttpRequest, Map<String, String>) -> HttpResponse,
    ) {
        entries.removeAll { it.descriptor.routeId == descriptor.routeId }
        entries += Entry(descriptor, handler)
    }

    /** 启用后把描述表发给 Host；空表也要发，用于清掉上一轮的旧路由。 */
    fun publish() {
        val inbox = PluginWorker.hostInbox ?: return
        val json = Json.encodeToString(entries.map { it.descriptor })
        send(inbox, KEEL_SUBJECT_ROUTE_REGISTER, json.encodeToByteArray())
    }

    /**
     * 按 routeId 执行 handler。
     *
     * 异常不在这里吞掉：原样抛给 [PluginWorker] 的分发边界，由它编码成错误信封回投 Host，
     * 这样 Host 才能重建完整的 `caused by` 链。
     */
    fun dispatch(
        routeId: String,
        request: HttpRequest,
        pathParameters: Map<String, String>,
    ): HttpResponse {
        val entry = entries.firstOrNull { it.descriptor.routeId == routeId }
            ?: return HttpResponse(status = 404, body = ResponseBody.Text("route not found: $routeId"))
        return entry.handler(request, pathParameters)
    }

    /** 把插件响应压成跨 ABI 的一次性响应。 */
    fun toWire(response: HttpResponse): HttpRouteResponse {
        val (contentType, body) = when (val b = response.body) {
            ResponseBody.Empty -> "text/plain; charset=utf-8" to ByteArray(0)
            is ResponseBody.Text -> b.contentType to b.text.encodeToByteArray()
            is ResponseBody.Bytes -> b.contentType to b.bytes
            is ResponseBody.Stream -> "text/plain; charset=utf-8" to
                "streaming not supported yet".encodeToByteArray()
        }
        return HttpRouteResponse(response.status, contentType, body)
    }

    private fun send(inbox: CPointer<KeelQueue>, subject: UInt, payload: ByteArray) {
        val buffer = keel_payload_alloc(payload.size.toUInt()) ?: return
        payload.usePinned { keel_payload_copy(buffer, it.addressOf(0), payload.size.toUInt()) }

        memScoped {
            val event = alloc<KeelEvent>()
            event.size = sizeOf<KeelEvent>().toUInt()
            event.kind = KEEL_EVENT_COMMAND.toUShort()
            event.source = PluginWorker.pluginId
            event.target = 0uL
            event.subject = subject.toULong()
            event.payload = buffer
            event.payload_size = payload.size.toUInt()
            keel_event_set_default_release(event.ptr)
            if (keel_queue_push(inbox, event.ptr, 0u) != KEEL_OK) {
                keel_event_release_default(null, event.ptr)
            }
        }
    }
}
