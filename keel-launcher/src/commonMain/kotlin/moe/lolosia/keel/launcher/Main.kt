package moe.lolosia.keel.launcher

import moe.lolosia.keel.host.main as runHost

/**
 * 单文件发行版入口。
 *
 * 最终形态：插件清单与插件原生库作为资源内嵌进可执行文件，启动时在内存中装配；
 * 无法内存加载的平台退化为「解包到缓存目录再加载」，但仍只分发一个文件。
 * 当前只是骨架，直接进入 Host。
 */
fun main() {
    println("keel launcher 0.1.0-SNAPSHOT (skeleton)")
    runHost(emptyArray())
}
