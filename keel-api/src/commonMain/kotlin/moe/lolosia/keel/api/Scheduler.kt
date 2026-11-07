package moe.lolosia.keel.api

/**
 * 调度器：在插件自己的 Worker 线程上执行任务，不跨运行时、不跨线程迁移协程。
 *
 * @see TaskHandle
 */
interface Scheduler {

    /**
     * @param delayMillis 首次执行延迟；0 表示尽快
     * @param periodMillis 周期；0 表示只执行一次
     */
    fun schedule(delayMillis: Long, periodMillis: Long = 0, task: () -> Unit): TaskHandle
}
