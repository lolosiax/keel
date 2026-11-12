package moe.lolosia.keel.api.log

import kotlinx.serialization.Serializable
import moe.lolosia.keel.api.error.KeelErrorEntry

/**
 * 一条日志携带的错误。
 *
 * [chain] 供读详情（帧、模块、RVA、创建 / 抛出点），[text] 是完整渲染结果，
 * 给只接受字符串的下游使用——它们可能没有把帧渲染成 Java 风格栈的能力。
 *
 * 两者必须来自同一次渲染：先 [StackTraceFormat.throwable] 生成 [text]
 * （读取 [ThrowTrace] 侧表），再 `Throwable.toKeelError()` 生成 [chain]
 * （它会释放侧表条目）；顺序反了文本里就没有 `Created / Throwed at`。
 *
 * @property printedAt 本条日志的打印点 `类.函数(文件:行)`
 * @property truncated 帧数超过上限被截断
 */
@Serializable
data class LogError(
    val chain: List<KeelErrorEntry> = emptyList(),
    val text: String = "",
    val printedAt: String? = null,
    val truncated: Boolean = false,
)
