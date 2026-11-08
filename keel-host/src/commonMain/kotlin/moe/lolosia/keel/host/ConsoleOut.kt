package moe.lolosia.keel.host

/**
 * 日志行输出前后的控制台钩子。
 *
 * 交互式控制台常驻一条 `> ` 提示符：日志行前要把光标退回行首覆盖提示符，
 * 日志行后再补回来，否则用户看不出「这里可以继续输入」。
 *
 * 非交互模式（`--once` / 管道 / 重定向）下两个钩子都是空实现。
 */
internal expect fun consoleOutBegin()

internal expect fun consoleOutEnd()
