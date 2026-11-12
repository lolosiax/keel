package moe.lolosia.keel.host

import moe.lolosia.keel.abi.c.keel_console_available

internal actual fun consoleAvailable(): Boolean = keel_console_available() != 0

/** Linux 没有 GUI 子系统：`console` 只表示是否往 stdout 写日志，无需附着。 */
internal actual fun prepareConsole(args: Array<String>) = Unit
