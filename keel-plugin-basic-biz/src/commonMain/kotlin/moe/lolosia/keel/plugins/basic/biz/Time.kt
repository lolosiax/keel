package moe.lolosia.keel.plugins.basic.biz

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** 展示时间固定用东八区，与前端表格的 `yyyy-MM-dd HH:mm:ss` 对齐。 */
private val DISPLAY_ZONE = TimeZone.of("Asia/Shanghai")

internal fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()

internal fun nowDisplay(): String = epochMsToDisplay(nowEpochMs())

/** epoch 毫秒 → `yyyy-MM-dd HH:mm:ss`（东八区）。 */
internal fun epochMsToDisplay(epochMs: Long): String {
    val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(DISPLAY_ZONE)
    fun pad(value: Int): String = if (value < 10) "0$value" else value.toString()
    return buildString {
        append(local.year)
        append('-').append(pad(local.month.ordinal + 1))
        append('-').append(pad(local.day))
        append(' ').append(pad(local.hour))
        append(':').append(pad(local.minute))
        append(':').append(pad(local.second))
    }
}
