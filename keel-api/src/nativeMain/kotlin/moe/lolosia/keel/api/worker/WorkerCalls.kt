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
import moe.lolosia.keel.api.host.HostRequestException

/** 反向请求的默认超时；与 `doc/basic-stack-design.md` 一致。 */
private val HOST_TIMEOUT: Duration = 3000.milliseconds

/** 插件自己的 correlation 源；插件之间互不共享。 */
private var hostCounter = 0uL

private val json = Json { ignoreUnknownKeys = true }

/**
 * 单向通知：投给 Host 后立即返回。
 *
 * 不等待回包，所以 correlation 为 0；Host 不会为它发 COMPLETE。
 */
internal fun postToHost(subject: ULong, payload: ByteArray) {
    sendToHost(subject, payload)
}

/**
 * 请求 Host 并等待同 correlation 的 COMPLETE。
 *
 * 等待期间在插件自己的收件箱上重入事件泵：回调、服务调用、定时器都不会被饿死。
 * 只能在 Worker 线程调用；其它线程进入会因为没有 inbox 或事件泵而失败。
 */
internal fun callHost(
    subject: ULong,
    payload: ByteArray,
    timeout: Duration = HOST_TIMEOUT,
): ByteArray {
    val inbox = PluginWorker.inbox
        ?: throw HostRequestException(subject.toLong(), "插件尚未启动，无法请求 Host")

    val correlation = ++hostCounter
    sendToHost(subject, payload, correlation)

    val deadline = TimeSource.Monotonic.markNow() + timeout
    val event = nativeHeap.alloc<KeelEvent>()
    try {
        while (true) {
            val remaining = deadline - TimeSource.Monotonic.markNow()
            if (remaining <= Duration.ZERO) throw timeout(subject)

            // 分片等待：插件停用后要尽快退出，不能一直等到整个超时
            val slice = if (remaining < CALL_SLICE) remaining else CALL_SLICE
            val status = keel_queue_pop(
                inbox,
                event.ptr,
                slice.inWholeMilliseconds.coerceAtLeast(1).toUInt(),
            )
            if (status == KEEL_ERR_TIMEOUT) {
                if (PluginWorker.plugin == null) throw disabled(subject)
                continue
            }
            if (status != KEEL_OK) throw timeout(subject)

            if (event.kind.toInt() == KEEL_EVENT_COMPLETE.toInt() &&
                event.correlation == correlation
            ) {
                val bytes = readPayload(event)
                val replyStatus = event.status
                event.release?.invoke(event.release_user, event.ptr)
                if (replyStatus != KEEL_OK.toInt()) throw remoteFailure(subject, bytes)
                return bytes
            }

            try {
                dispatchEvent(event)
            } catch (e: Throwable) {
                Log.error("等待 Host 回包期间的事件处理失败", cause = e)
            } finally {
                event.release?.invoke(event.release_user, event.ptr)
            }
            if (PluginWorker.plugin == null) {
                throw disabled(subject)
            }
        }
    } finally {
        nativeHeap.free(event)
    }
}

/** 等待期间的重入等待片：决定「插件已停用」被发现的延迟。 */
private val CALL_SLICE: Duration = 100.milliseconds

private fun disabled(subject: ULong): HostRequestException =
    HostRequestException(subject.toLong(), "插件已停用，Host 请求被取消")

private fun timeout(subject: ULong): HostRequestException =
    HostRequestException(subject.toLong(), "Host 请求超时：subject=$subject")

private fun remoteFailure(subject: ULong, bytes: ByteArray): Throwable {
    val payload = runCatching {
        json.decodeFromString<KeelErrorPayload>(bytes.decodeToString())
    }.getOrNull()
    return if (payload != null) {
        KeelRemoteException(payload.plugin, payload.chain)
    } else {
        HostRequestException(subject.toLong(), "Host 请求失败：subject=$subject")
    }
}

private fun readPayload(event: KeelEvent): ByteArray {
    val size = event.payload_size.toInt()
    if (size <= 0) return ByteArray(0)
    return event.payload?.reinterpret<ByteVar>()?.readBytes(size) ?: ByteArray(0)
}
