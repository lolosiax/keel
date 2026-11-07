package moe.lolosia.keel.host

import moe.lolosia.keel.api.Log

internal const val KEEL_VERSION = "0.1.0-SNAPSHOT"

/**
 * `--inspect <path>` 诊断入口：读取 `.keel` 的 manifest 与 `resources/`，
 * 并验证卸载后读取立即失效。仅用于开发期验证，不影响正常启动。
 */
internal expect fun inspectKeelPackage(path: String)

/**
 * `--plugins <dir> [cacheDir] [--http=<port>] [--serve | --serve-ms=<n>]`：
 * 扫描目录下的 `.keel`，校验后解出动态库并启动插件。
 *
 * 启动是「投 START、等 COMPLETE」，Host 线程从不进入插件运行时。
 * 默认启动、体检、清空收件箱后退出；带 `--serve` 时进入长驻事件循环；
 * 带 `--http` 时同时启动 Ktor（未显式给出 serve 选项则常驻）。
 *
 * @param serveMillis null 表示不常驻；0 表示一直服务到进程被杀；其它值表示服务时长
 */
internal expect fun loadPluginsFromCli(
    pluginsDir: String,
    cacheDir: String?,
    serveMillis: Long?,
    httpPort: Int?,
)

/** 读取当前可执行文件内嵌 classpath 中的资源；未嵌入时返回 null。 */
internal expect fun embeddedResource(path: String): String?

/**
 * Host 入口。
 *
 * 目前只处理 `--inspect` / `--plugins`；其余参数打印版本与内嵌资源信息。
 */
fun main(args: Array<String>) {
    Log.install(HostLogSink)
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
                println(
                    "用法：keel-host --plugins <插件目录> [缓存目录] " +
                        "[--http=<端口>] [--serve | --serve-ms=<毫秒>]"
                )
                return
            }
            val dir = args[1]
            var cache: String? = null
            var serveMillis: Long? = null
            var httpPort: Int? = null
            args.drop(2).forEach { arg ->
                when {
                    arg == "--serve" -> serveMillis = 0L
                    arg.startsWith("--serve-ms=") ->
                        serveMillis = arg.substringAfter('=').toLongOrNull()
                            ?: error("--serve-ms 需要毫秒数：$arg")

                    arg.startsWith("--http=") ->
                        httpPort = arg.substringAfter('=').toIntOrNull()
                            ?: error("--http 需要端口号：$arg")

                    cache == null -> cache = arg
                    else -> error("无法识别的参数：$arg")
                }
            }
            loadPluginsFromCli(dir, cache, serveMillis, httpPort)
            return
        }
    }

    Log.info("keel host $KEEL_VERSION")
    Log.info("embedded host.txt: ${embeddedResource("host.txt")}")
}
