package moe.lolosia.keel.api.worker

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import moe.lolosia.keel.api.debug.Debug

/**
 * 把协程调度回插件 Worker 线程。
 *
 * 没有独立线程：`dispatch` 只把续体放进 [WorkerScheduler] 的队列，
 * 由下一次 TIMER 事件在 Worker 线程上排空；`delay` / `withTimeout`
 * 同样落在这条定时器上，不会借用 Host 或全局调度器。
 *
 * 自定义 `Delay` 目前只有 internal API，这是把协程真正绑在插件定时器上的唯一入口。
 */
@OptIn(InternalCoroutinesApi::class)
internal object WorkerDispatcher : CoroutineDispatcher(), Delay {

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        // 调试帧：续体投递时还运行在创建者栈上，此刻按 Job 建表就是 creation stack
        val job = context[Job]
        val frames = if (job == null) null else Debug.framesForCoroutine(job)
        WorkerScheduler.dispatch {
            Debug.runWithCoroutineFrames(frames) { block.run() }
        }
    }

    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) {
        val handle = WorkerScheduler.schedule(timeMillis.coerceAtLeast(0)) {
            continuation.resume(Unit, null)
        }
        continuation.invokeOnCancellation { handle.cancel() }
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): DisposableHandle {
        val handle = WorkerScheduler.schedule(timeMillis.coerceAtLeast(0)) { block.run() }
        return DisposableHandle { handle.cancel() }
    }
}
