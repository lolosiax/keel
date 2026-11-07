package moe.lolosia.keel.api.event

/**
 * 事件总线。
 *
 * 本运行时内的发布 / 订阅是同步快路径；跨模块订阅由 Host 记录，
 * 发布时转成事件投递到对方队列（异步、每模块 FIFO、无全局顺序）。
 */
interface EventBus {

    fun <T : Event> subscribe(
        type: EventType<T>,
        priority: EventPriority = EventPriority.NORMAL,
        handler: EventHandler<T>,
    ): Subscription

    fun publish(event: Event)
}
