package moe.lolosia.keel.host

import kotlinx.cinterop.toKString
import moe.lolosia.keel.abi.c.keel_console_attach
import moe.lolosia.keel.abi.c.keel_console_available
import platform.posix.getenv

internal actual fun consoleAvailable(): Boolean = keel_console_available() != 0

internal actual fun prepareConsole(args: Array<String>) {
    if (args.contains("--no-console")) return

    val forced = args.contains("--console")
    val enabled = getenv("KEEL_CONSOLE")?.toKString()?.trim()
    if (!forced && enabled != "1") return

    if (keel_console_available() == 0) keel_console_attach()
}
