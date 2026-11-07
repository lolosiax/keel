package moe.lolosia.keel.launcher

import moe.lolosia.keel.host.Log
import moe.lolosia.keel.host.main as runHost

/**
 * 单文件发行版入口。
 *
 * 插件清单与插件原生库作为资源内嵌进可执行文件，启动时在内存中装配；
 * 无法内存加载的平台退化为「解包到缓存目录再加载」，但仍只分发一个文件。
 * 目前直接转发到 Host 入口，内嵌与装配尚未接入。
 */
fun main() {
    Log.info("keel launcher 0.1.0-SNAPSHOT")
    runHost(emptyArray())
}
