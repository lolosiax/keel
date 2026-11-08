package moe.lolosia.keel.host

import moe.lolosia.keel.host.console.ConsolePrompt

internal actual fun consoleOutBegin() = ConsolePrompt.beforeLine()

internal actual fun consoleOutEnd() = ConsolePrompt.afterLine()
