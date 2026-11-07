package moe.lolosia.keel.api.messaging

/**
 * 消息收发入口。
 *
 * 同步调用也走「投递 + 等待应答」，执行权始终留在接收方 Worker 线程上；
 * `request` 的挂起只挂起**本地协程**，往返由事件泵驱动，不跨 C ABI 传 continuation。
 */
interface Messenger {

    suspend fun request(message: Message): Message

    fun post(message: Message)
}
