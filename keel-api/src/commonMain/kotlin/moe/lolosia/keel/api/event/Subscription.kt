package moe.lolosia.keel.api.event

/**
 * 订阅句柄。`cancel()` 之后不再收到事件，重复取消是幂等的。
 */
interface Subscription {

    val active: Boolean

    fun cancel()
}
