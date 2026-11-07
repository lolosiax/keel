package moe.lolosia.keel.host

/**
 * `--inspect <path>` 诊断入口：读取 `.keel` 的 manifest 与 `resources/`，
 * 并验证卸载后读取立即失效。仅用于开发期验证，不影响正常启动。
 */
internal expect fun inspectKeelPackage(path: String)

/** 读取当前可执行文件内嵌 classpath 中的资源；未嵌入时返回 null。 */
internal expect fun embeddedResource(path: String): String?

/**
 * Host 入口：装配插件加载器、消息总线与调度器，并把 Ktor 引擎拉起。
 *
 * 当前是骨架：只打印版本信息，加载器与引擎在 DEMO 阶段接入（见 doc/roadmap.md）。
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--inspect" && args.size >= 2) {
        inspectKeelPackage(args[1])
        return
    }
    println("keel host 0.1.0-SNAPSHOT (skeleton)")
    println("embedded host.txt: ${embeddedResource("host.txt")}")
}
