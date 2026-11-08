package moe.lolosia.keel.api.worker

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.cinterop.*
import kotlinx.serialization.json.Json
import moe.lolosia.keel.abi.c.*
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.error.KeelErrorPayload
import moe.lolosia.keel.api.error.KeelRemoteException
import moe.lolosia.keel.api.service.ServiceCall
import moe.lolosia.keel.api.service.ServiceCaller
import moe.lolosia.keel.api.service.ServiceUnavailableException
import moe.lolosia.keel.api.service.ServiceWire

/**
 * 生成的客户端使用的调用器。
 *
 * 请求投给 Host，Host 按服务 id 转发给提供者；等待期间在自己的收件箱上重入事件泵，
 * 所以回调、别人发来的请求都不会被饿死。
 */
internal class ServiceCallerImpl(private val serviceId: String) : ServiceCaller {

    override fun callBlocking(method: String, payload: ByteArray): ByteArray =
        call(ServiceCall(serviceId, method, payload))

    /**
     * `suspend` 方法暂时复用阻塞路径：当前 Worker 还没有独立的事件循环，
     * 真正的「挂起不阻塞 Worker」在 M1-4 的反向请求里一起接入。
     */
    override suspend fun call(method: String, payload: ByteArray): ByteArray =
        call(ServiceCall(serviceId, method, payload))

    private fun call(call: ServiceCall): ByteArray {
        val inbox = PluginWorker.inbox
            ?: throw ServiceUnavailableException(serviceId, "插件尚未启动，无法调用服务：$serviceId")
        val correlation = nextCorrelation()
        sendToHost(
            subject = KEEL_SUBJECT_SERVICE_CALL.toULong(),
            payload = ServiceWire.encodeCall(call),
            correlation = correlation,
        )

        val deadline = TimeSource.Monotonic.markNow() + TIMEOUT
        val event = nativeHeap.alloc<KeelEvent>()
        try {
            while (true) {
                val remaining = deadline - TimeSource.Monotonic.markNow()
                if (remaining <= Duration.ZERO) throw timeout(call)

                val status = keel_queue_pop(
                    inbox,
                    event.ptr,
                    remaining.inWholeMilliseconds.coerceAtLeast(1).toUInt(),
                )
                if (status != KEEL_OK) throw timeout(call)

                if (event.kind.toInt() == KEEL_EVENT_COMPLETE.toInt() &&
                    event.correlation == correlation
                ) {
                    val bytes = readPayload(event)
                    val replyStatus = event.status
                    event.release?.invoke(event.release_user, event.ptr)
                    if (replyStatus != KEEL_OK.toInt()) throw remoteError(bytes)
                    return bytes
                }

                try {
                    dispatchEvent(event)
                } catch (e: Throwable) {
                    Log.error("服务调用等待期间的事件处理失败", cause = e)
                } finally {
                    event.release?.invoke(event.release_user, event.ptr)
                }
                if (PluginWorker.plugin == null) {
                    throw ServiceUnavailableException(serviceId, "插件已停用：$serviceId")
                }
            }
        } finally {
            nativeHeap.free(event)
        }
    }

    private fun timeout(call: ServiceCall): ServiceUnavailableException =
        ServiceUnavailableException(serviceId, "服务调用超时：$serviceId.${call.method}")

    private fun remoteError(bytes: ByteArray): Throwable {
        val payload = runCatching {
            json.decodeFromString<KeelErrorPayload>(bytes.decodeToString())
        }.getOrNull()
        return if (payload != null) {
            KeelRemoteException(payload.plugin, payload.chain)
        } else {
            ServiceUnavailableException(serviceId, "服务调用失败：$serviceId")
        }
    }

    private companion object {
        /** 与 `doc/codegen-design.md` 的默认值一致；后续可由配置覆盖。 */
        val TIMEOUT: Duration = 3000.milliseconds

        var counter = 0uL

        val json = Json { ignoreUnknownKeys = true }

        fun nextCorrelation(): ULong = ++counter

        fun readPayload(event: KeelEvent): ByteArray {
            val size = event.payload_size.toInt()
            if (size <= 0) return ByteArray(0)
            return event.payload?.reinterpret<ByteVar>()?.readBytes(size) ?: ByteArray(0)
        }
    }
}
