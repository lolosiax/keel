package moe.lolosia.keel.host

import kotlinx.cinterop.*
import platform.posix.*

actual fun ansiSupported(): Boolean {
    if (getenv("NO_COLOR") != null) return false
    if (getenv("CLICOLOR")?.toKString() == "0") return false
    val force = getenv("FORCE_COLOR")?.toKString()
    if (force != null && force != "0") return true

    val term = getenv("TERM")?.toKString()
    if (term.isNullOrBlank() || term == "dumb") return false
    return isatty(STDOUT_FILENO) == 1
}
