package moe.lolosia.keel.host

import kotlinx.cinterop.*
import moe.lolosia.keel.abi.c.KeelLogFile
import moe.lolosia.keel.abi.c.keel_logfile_open
import moe.lolosia.keel.abi.c.keel_logfile_write

/** 由 C 侧持有的按日切割日志文件；进程内只开一次。 */
private var logFile: CPointer<KeelLogFile>? = null

actual fun startLogFile(): String? {
    val dir = "${currentWorkingDirectory()}/work/log"
    val handle = keel_logfile_open(dir) ?: return null
    logFile = handle
    return dir
}

actual fun appendLogFile(line: String) {
    val handle = logFile ?: return
    val bytes = line.encodeToByteArray()
    if (bytes.isEmpty()) return
    bytes.usePinned { pinned ->
        keel_logfile_write(handle, pinned.addressOf(0), bytes.size.toUInt())
    }
}
