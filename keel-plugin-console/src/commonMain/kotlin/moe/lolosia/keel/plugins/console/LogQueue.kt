package moe.lolosia.keel.plugins.console

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 日志行缓冲。
 *
 * Worker 上的 collect 只入队，真正的写管道在后台协程；队列满时丢最旧一行并计数，
 * 由写协程在下一批前面补一行提示。这样用户拖动窗口把输出卡住时也不会阻塞 Worker。
 */
internal class LogQueue(private val capacity: Int = 4096) {

    private val mutex = Mutex()
    private val lines = ArrayDeque<String>()
    private var dropped = 0

    /** 入队；满了先丢最旧。 */
    suspend fun offer(line: String) {
        mutex.withLock {
            if (lines.size >= capacity) {
                lines.removeFirst()
                dropped++
            }
            lines.addLast(line)
        }
    }

    /** 非阻塞入队，给 Worker 上的同步路径（`console clear`）用。 */
    fun tryOffer(line: String): Boolean {
        if (!mutex.tryLock()) return false
        return try {
            if (lines.size >= capacity) {
                lines.removeFirst()
                dropped++
            }
            lines.addLast(line)
            true
        } finally {
            mutex.unlock()
        }
    }

    /** 取一批行（拼接后的文本）；空队列返回 null。 */
    suspend fun nextBatch(maxChars: Int = 64 * 1024): String? = mutex.withLock {
        if (lines.isEmpty()) return@withLock null
        val out = StringBuilder()
        if (dropped > 0) {
            out.append("已丢弃 ").append(dropped).append(" 行（窗口写入过慢）\n")
            dropped = 0
        }
        while (lines.isNotEmpty() && out.length < maxChars) {
            out.append(lines.removeFirst().trimEnd('\r', '\n')).append('\n')
        }
        out.toString()
    }

    suspend fun clear() {
        mutex.withLock {
            lines.clear()
            dropped = 0
        }
    }
}
