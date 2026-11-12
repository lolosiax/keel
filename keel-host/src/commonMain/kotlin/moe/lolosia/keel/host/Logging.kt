package moe.lolosia.keel.host

import moe.lolosia.keel.api.Log

/**
 * 安装 Host 日志出口与历史来源。
 *
 * Host 与 Launcher 各自在进程入口调用一次；必须在输出第一条日志（横幅）之前。
 */
fun installHostLogging() {
    Log.install(HostLogSink)
    Log.installHistory(HostLogSource)
}
