package moe.lolosia.keel.plugins.console

import kotlinx.coroutines.channels.ReceiveChannel

/**
 * 一个已经拉起的控制台窗口。
 *
 * 实现由目标平台提供：创建 helper 进程、双向管道与读写协程；
 * 插件只通过这个接口入队日志、改标题、探活与关闭。
 */
internal interface ConsoleSession {

    /** 后台线程投过来的事件；由插件在 Worker 上消费。 */
    val events: ReceiveChannel<BridgeEvent>

    /** 待写日志行；由写协程取走并编码成 `OUT` 帧。 */
    val queue: LogQueue

    fun setTitle(text: String)

    fun isAlive(): Boolean

    fun close()
}

/**
 * 拉起 helper 并连接双向管道。
 *
 * @throws IllegalStateException 管道创建、helper 启动或连接失败；消息会作为 `failed:<reason>` 回给调用方
 */
internal expect fun openConsoleSession(
    helperPath: String,
    title: String,
    scrollback: Int,
): ConsoleSession
