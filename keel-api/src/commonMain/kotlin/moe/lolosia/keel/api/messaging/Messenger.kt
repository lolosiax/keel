package moe.lolosia.keel.api.messaging

/**
 * 消息收发入口。
 *
 * 同步调用也走「投递 + 等待应答」，执行权始终留在接收方 Worker 线程上；
 * `request` 的挂起只挂起**本地协程**，往返由事件泵驱动，不跨 C ABI 传 continuation。
 */
interface Messenger {

    /**
     * 向另一个插件发一条通知，不等待处理结果。
     *
     * at-most-once：目标未启用、队列已满时消息会被丢弃，只记录一次告警。
     */
    fun post(message: Message)

    /**
     * 向另一个插件发起请求并等待应答。
     *
     * 只能在 Worker 线程调用；等待期间会继续分发其它事件。
     *
     * @param timeoutMs 本地等待上限；Host 侧另有一条兜底超时
     * @return 接收方 `onMessage` 返回的原始字节；未处理时抛 [moe.lolosia.keel.api.error.KeelRemoteException]
     */
    suspend fun request(message: Message, timeoutMs: Long = 3_000): ByteArray
}
