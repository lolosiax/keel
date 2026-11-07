package moe.lolosia.keel.host

/**
 * `--inspect <path>` 诊断入口：读取 `.keel` 的 manifest 与 `resources/`，
 * 并验证卸载后读取立即失效。仅用于开发期验证，不影响正常启动。
 */
internal expect fun inspectKeelPackage(path: String)

/**
 * `--plugins <dir> [cacheDir]`：扫描目录下的 `.keel`，校验后解出动态库并启动插件。
 *
 * 启动是「投 START、等 COMPLETE」，Host 线程从不进入插件运行时。
 */
internal expect fun loadPluginsFromCli(pluginsDir: String, cacheDir: String?)

/** 读取当前可执行文件内嵌 classpath 中的资源；未嵌入时返回 null。 */
internal expect fun embeddedResource(path: String): String?

/**
 * Host 入口：装配插件加载器、消息总线与调度器，并把 Ktor 引擎拉起。
 *
 * 当前是骨架：只打印版本信息，加载器与引擎在 DEMO 阶段接入（见 doc/roadmap.md）。
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "--inspect" -> {
            if (args.size < 2) {
                println("用法：keel-host --inspect <.keel 路径>")
                return
            }
            inspectKeelPackage(args[1])
            return
        }

        "--plugins" -> {
            if (args.size < 2) {
                println("用法：keel-host --plugins <插件目录> [缓存目录]")
                return
            }
            loadPluginsFromCli(args[1], args.getOrNull(2))
            return
        }
    }

    println("keel host 0.1.0-SNAPSHOT (skeleton)")
    println("embedded host.txt: ${embeddedResource("host.txt")}")
}
