package moe.lolosia.keel.plugins.console

internal actual fun openConsoleSession(
    helperPath: String,
    title: String,
    scrollback: Int,
): ConsoleSession = MingwConsoleSession(helperPath, title, scrollback)
