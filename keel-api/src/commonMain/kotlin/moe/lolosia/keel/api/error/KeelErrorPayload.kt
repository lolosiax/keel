package moe.lolosia.keel.api.error

import kotlinx.serialization.Serializable

/**
 * 跨运行时传递的错误信封。
 *
 * [type] / [message] 是原始异常的首行；[trace] 是 `stackTraceToString()` 去掉首行后的
 * 帧文本（可能包含多段 `Caused by:`），Host 侧据此重建可读的错误链。
 */
@Serializable
data class KeelErrorPayload(
    val plugin: String = "",
    val type: String,
    val message: String,
    val trace: String,
    val truncated: Boolean = false,
)
