package moe.lolosia.keel.host

/** 启动横幅；只在进程入口打印一次。 */
internal fun printBanner(version: String, target: String) {
    println(
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
