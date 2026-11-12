package moe.lolosia.keel.api.worker

import kotlinx.coroutines.channels.Channel
import moe.lolosia.keel.api.host.*
import moe.lolosia.keel.api.log.*
import moe.lolosia.keel.api.service.ServiceWire

/**
 * 插件侧日志订阅表。
 *
 * 正文不经过事件泵：Host 只投一条无载荷、可合并的 `LOG_WAKE`（correlation 是订阅 id），
 * 这里把对应订阅的挂起恢复，Flow 再按游标拉取一页。
 */
internal object LogSubscriptions {

    /** 一个活跃订阅；[wake] 合并重复通知，下游慢时也不会堆积。 */
    internal class Handle(val id: Long, val cursor: Long) {

        private val wake = Channel<Unit>(Channel.CONFLATED)

        fun signal() {
            wake.trySend(Unit)
        }

        suspend fun awaitWake() {
            wake.receive()
        }
    }

    private val handles = mutableMapOf<Long, Handle>()

    fun open(startId: Long): Handle {
        val reply = ServiceWire.decode(
            LogSubscribeReply.serializer(),
            callHost(
                HostSubjects.LOG_SUBSCRIBE.toULong(),
                ServiceWire.encode(
                    LogSubscribeRequest.serializer(),
                    LogSubscribeRequest(startId),
                ),
            ),
        )
        val handle = Handle(reply.subscriptionId, reply.cursor)
        handles[handle.id] = handle
        return handle
    }

    fun close(handle: Handle) {
        handles.remove(handle.id)
        postToHost(
            HostSubjects.LOG_UNSUBSCRIBE.toULong(),
            ServiceWire.encode(
                LogUnsubscribeRequest.serializer(),
                LogUnsubscribeRequest(handle.id),
            ),
        )
    }

    fun signal(subscriptionId: Long) {
        handles[subscriptionId]?.signal()
    }

    /** 一次性拉取（[subscriptionId] 为 0）或订阅内拉取。 */
    fun page(subscriptionId: Long, cursor: Long, limit: Int, format: LogFormat): LogPage =
        ServiceWire.decode(
            LogPage.serializer(),
            callHost(
                HostSubjects.LOG_FETCH.toULong(),
                ServiceWire.encode(
                    LogFetchRequest.serializer(),
                    LogFetchRequest(subscriptionId, cursor, format, limit.coerceAtLeast(1)),
                ),
            ),
        )

    fun get(id: Long): LogRecord? = ServiceWire.decode(
        LogGetReply.serializer(),
        callHost(
            HostSubjects.LOG_GET.toULong(),
            ServiceWire.encode(LogGetRequest.serializer(), LogGetRequest(id)),
        ),
    ).record

    /** 插件停用 / 卸载时丢弃订阅；Host 侧也会在收到离线信号后自行清理。 */
    fun reset() {
        handles.clear()
    }
}
