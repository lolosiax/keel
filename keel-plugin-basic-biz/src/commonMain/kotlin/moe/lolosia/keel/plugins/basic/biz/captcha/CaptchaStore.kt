package moe.lolosia.keel.plugins.basic.biz.captcha

import moe.lolosia.keel.plugins.basic.biz.nowEpochMs

/**
 * 验证码答案的进程内缓存（一次性消费）。
 *
 * 只在 Worker 线程访问，不需要锁；有效期 5 分钟，取用即删，避免同一个验证码被反复试。
 */
internal object CaptchaStore {

    private const val TTL_MS = 5 * 60 * 1000L

    private class Entry(val answer: String, val expiresAt: Long)

    private val entries = mutableMapOf<String, Entry>()

    fun put(id: String, answer: String) {
        prune()
        entries[id] = Entry(answer, nowEpochMs() + TTL_MS)
    }

    /** 取出并删除；不存在或过期返回 null。 */
    fun take(id: String): String? {
        prune()
        return entries.remove(id)?.answer
    }

    private fun prune() {
        val now = nowEpochMs()
        entries.entries.removeAll { it.value.expiresAt <= now }
    }
}
