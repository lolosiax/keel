package moe.lolosia.keel.api.event

/**
 * 事件处理器。只在订阅方自己的运行时里执行。
 */
fun interface EventHandler<T : Event> {

    fun handle(event: T)
}
