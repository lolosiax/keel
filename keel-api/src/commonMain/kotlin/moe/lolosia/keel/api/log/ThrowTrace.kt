@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package moe.lolosia.keel.api.log

import kotlin.concurrent.atomics.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 异常创建 / 抛出点侧表。
 *
 * 编译器插件在异常构造与 `throw` 处写入；日志渲染时按对象身份（`===`）读取。
 * 表有固定容量与 60 秒存活期：过期或超出容量的条目直接丢弃，所以内存有硬上限，
 * 不会随运行时间增长。
 */
object ThrowTrace {

    /** 最多同时保留多少条记录；超过时挤掉最老的。 */
    private const val CAPACITY = 64

    /** 一个异常最多保留多少个抛出点（重抛链）。 */
    private const val MAX_THROW_SITES = 8

    /** 条目最后一次写入后的存活时间；写入时惰性清理。 */
    private val TTL = 60.seconds

    private class Slot(
        val error: Throwable,
        val createdSite: String?,
        val touchedAt: TimeMark,
        val throwSites: List<String>,
    )

    private val slots = AtomicReference<List<Slot>>(emptyList())

    /** 记录异常构造点；同一对象重复记录时覆盖旧条目。 */
    fun created(error: Throwable, site: String) {
        val mark = TimeSource.Monotonic.markNow()
        update { current ->
            trim(current.filter { it.error !== error } + Slot(error, site, mark, emptyList()))
        }
    }

    /** 追加抛出点；没有构造记录（库代码构造或被淘汰）时补一条只有抛出点的记录。 */
    fun thrown(error: Throwable, site: String) {
        val mark = TimeSource.Monotonic.markNow()
        update { current ->
            val index = current.indexOfFirst { it.error === error }
            if (index < 0) {
                trim(current + Slot(error, null, mark, listOf(site)))
            } else {
                current.toMutableList().also { list ->
                    val slot = list[index]
                    list[index] = Slot(
                        slot.error,
                        slot.createdSite,
                        mark,
                        (slot.throwSites + site).distinct().takeLast(MAX_THROW_SITES),
                    )
                }
            }
        }
    }

    /** 读取诊断记录；过期条目按不存在处理。 */
    fun of(error: Throwable): ThrowTraceEntry? {
        val slot = slots.load().lastOrNull {
            it.error === error && it.touchedAt.elapsedNow() < TTL
        } ?: return null
        return ThrowTraceEntry(slot.createdSite, slot.throwSites)
    }

    /** 用完即弃：跨运行时编码完成后调用，让条目立刻可回收。 */
    fun release(error: Throwable) {
        update { current -> current.filter { it.error !== error } }
    }

    private fun update(transform: (List<Slot>) -> List<Slot>) {
        while (true) {
            val current = slots.load()
            if (slots.compareAndSet(current, transform(current))) return
        }
    }

    /** 先按 TTL 过滤，再保留最新的 [CAPACITY] 条。 */
    private fun trim(list: List<Slot>): List<Slot> {
        val fresh = list.filter { it.touchedAt.elapsedNow() < TTL }
        return if (fresh.size <= CAPACITY) fresh else fresh.takeLast(CAPACITY)
    }
}
