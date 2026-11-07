package moe.lolosia.keel.api.worker

import kotlin.time.TimeSource
import moe.lolosia.keel.abi.c.keel_plugin_set_wakeup
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.Scheduler
import moe.lolosia.keel.api.TaskHandle

/**
 * 绑定在插件 Worker 线程上的调度器。
 *
 * 没有独立线程：任务表只在 Worker 线程访问；泵线程没有事件时，C 侧按最近截止时间
 * 超时唤醒，投 `KEEL_SUBJECT_TIMER` 事件，再由 [tick] 跑到期任务。
 *
 * 取消不会提前唤醒泵线程：已设置但被取消的定时会让泵空跑一次，随后 [tick] 重新定时。
 */
internal object WorkerScheduler : Scheduler {

    private class Job(
        val period: Long,
        var nextAt: Long,
        val block: () -> Unit,
    ) : TaskHandle {

        override var cancelled: Boolean = false

        override fun cancel() {
            cancelled = true
        }
    }

    private var origin = TimeSource.Monotonic.markNow()
    private val jobs = mutableListOf<Job>()

    /** 插件启动 / 关闭时清空任务表与 C 侧定时。 */
    fun reset() {
        origin = TimeSource.Monotonic.markNow()
        jobs.clear()
        keel_plugin_set_wakeup(-1L)
    }

    override fun schedule(delayMillis: Long, periodMillis: Long, task: () -> Unit): TaskHandle {
        val job = Job(
            period = periodMillis.coerceAtLeast(0),
            nextAt = nowMs() + delayMillis.coerceAtLeast(0),
            block = task,
        )
        jobs += job
        arm()
        return job
    }

    /** 泵线程的 TIMER 事件入口：跑到期任务并重新定时。 */
    fun tick() {
        val now = nowMs()
        jobs.toList().forEach { job ->
            if (job.cancelled || job.nextAt > now) return@forEach

            try {
                job.block()
            } catch (e: Throwable) {
                Log.error("调度任务失败", cause = e)
            }

            if (job.cancelled) return@forEach
            if (job.period > 0) {
                job.nextAt = nextDue(job.nextAt, job.period, now)
            } else {
                job.cancelled = true
            }
        }
        jobs.removeAll { it.cancelled }
        arm()
    }

    private fun nextDue(previous: Long, period: Long, now: Long): Long {
        val next = previous + period
        // 落后超过一个周期时跳过积压，避免恢复后补跑风暴
        return if (next <= now) now + period else next
    }

    private fun arm() {
        val nearest = jobs.asSequence().filterNot { it.cancelled }.minOfOrNull { it.nextAt }
        if (nearest == null) {
            keel_plugin_set_wakeup(-1L)
        } else {
            keel_plugin_set_wakeup((nearest - nowMs()).coerceAtLeast(0))
        }
    }

    private fun nowMs(): Long = origin.elapsedNow().inWholeMilliseconds
}
