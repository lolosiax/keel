package moe.lolosia.keel.host

import moe.lolosia.keel.abi.c.keel_crash_install
import moe.lolosia.keel.abi.c.keel_crash_register_module
import moe.lolosia.keel.abi.c.keel_watchdog_spawn
import moe.lolosia.keel.abi.c.keel_watchdog_watch
import moe.lolosia.keel.host.build.BuildInfo

actual fun startWatchdog(): ULong = keel_watchdog_spawn()

actual fun installCrashHandler(logDir: String?) {
    val dir = logDir ?: "${currentWorkingDirectory()}/work/log"
    keel_crash_install(dir, BuildInfo.BUILD_ID)
}

actual fun registerCrashModule(name: String, libraryPath: String) {
    keel_crash_register_module(name, libraryPath)
}

actual fun runWatchdog(args: Array<String>) {
    val parent = args.firstOrNull { it.startsWith("--parent-pid=") }
        ?.substringAfter('=')
        ?.toULongOrNull()
        ?: return
    keel_watchdog_watch(parent)
}
