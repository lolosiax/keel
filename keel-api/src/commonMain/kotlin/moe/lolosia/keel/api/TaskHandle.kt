package moe.lolosia.keel.api

/**
 * 定时 / 延时任务句柄。
 */
interface TaskHandle {

    val cancelled: Boolean

    fun cancel()
}
