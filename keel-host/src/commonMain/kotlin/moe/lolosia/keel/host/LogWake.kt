package moe.lolosia.keel.host

import kotlin.concurrent.Volatile

/**
 * 「有新日志」的合并标记。
 *
 * 任意线程都可以 [mark]；泵线程每轮 [consume] 一次，把标记转成每个订阅的唤醒。
 * 只用一个布尔量，日志风暴不会在事件泵里堆积正文。
 */
internal object LogWake {

    @Volatile
    private var dirty = false

    fun mark() {
        dirty = true
    }

    /** @return true 表示自上次消费以来有日志写入 */
    fun consume(): Boolean {
        if (!dirty) return false
        dirty = false
        return true
    }
}
