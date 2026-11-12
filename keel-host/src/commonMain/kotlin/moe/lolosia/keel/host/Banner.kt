package moe.lolosia.keel.host

import moe.lolosia.keel.api.Log

/**
 * 启动横幅；只在进程入口输出一次。
 *
 * 走 [Log.banner] 而不是 `println`，让它成为日志归档里的第一条记录：
 * `banner = true`，文本视图原样输出，RAW 视图也能取到。
 */
internal fun printBanner(version: String, target: String) {
    Log.banner(
        """
        >
        >  _  __        _
        > | |/ /___ ___| |
        > | ' </ -_) -_) |
        > |_|\_\___\___|_|
        >   keel $version · $target
        >
        """.trimMargin(">"),
    )
}
