package moe.lolosia.keel.host

import moe.lolosia.keel.classpath.EmptyClasspath
import moe.lolosia.keel.classpath.installEmbeddedClasspath
import moe.lolosia.keel.classpath.readText
import moe.lolosia.keel.host.classpath.HostClasspath
import moe.lolosia.keel.host.plugin.PluginPackageReader

internal actual fun inspectKeelPackage(path: String) {
    val host = HostClasspath(EmptyClasspath)
    val (manifest, _) = PluginPackageReader.install(host, path)

    println(
        "manifest: id=${manifest.id} version=${manifest.version} " +
            "abi=${manifest.abiVersion} entry=${manifest.entry}"
    )

    val classpath = host.plugin(manifest.id)
    println("entries: ${classpath.entries}")
    println("hello.txt: ${classpath.readText("hello.txt")}")

    host.unregister(manifest.id)
    try {
        classpath.read("hello.txt")
        println("after unload: still readable (unexpected)")
    } catch (e: Exception) {
        println("after unload: ${e.message}")
    }
}

internal actual fun embeddedResource(path: String): String? =
    installEmbeddedClasspath().readText(path)
