package moe.lolosia.keel.api.worker

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*

/**
 * 绑定在插件 Worker 线程上的协程作用域。
 *
 * 与 `WorkerScheduler` 一样是每个插件 dylib 唯一的单例：启动时 [reset] 换一个新的
 * `SupervisorJob`，停用时取消上一个，避免旧协程在插件停用后继续跑。
 */
internal object WorkerScope : CoroutineScope {

    private var job: CompletableJob = SupervisorJob()

    override val coroutineContext: CoroutineContext
        get() = job + WorkerDispatcher

    /** 取消当前所有协程并换一个新的父 Job。 */
    fun reset() {
        job.cancel()
        job = SupervisorJob()
    }
}
