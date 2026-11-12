package moe.lolosia.keel.host

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.host.build.BuildInfo

/**
 * 启动横幅与构建信息。放在看门狗/插件加载之前，保证 `keel` 徽标是第一屏内容。
 */
internal expect fun printStartupBanner()

/**
 * `--inspect <path>` 诊断入口：读取 `.keel` 的 manifest 与 `resources/`，
 * 并验证卸载后读取立即失效。仅用于开发期验证，不影响正常启动。
 */
internal expect fun inspectKeelPackage(path: String)

/**
 * `--plugins <dir> [cacheDir] [--once] [--http=<port>] [--serve-ms=<n>]`：
 * 扫描目录下的 `.keel`，校验后解出动态库并启动插件。
 *
 * 启动是「投 START、等 COMPLETE」，Host 线程从不进入插件运行时。
 * 默认常驻（事件循环），`--once` 表示启动、体检后立即退出；
 * 带 `--http` 时同时启动 Ktor。
 *
 * @param serveMillis null 表示不常驻（`--once`）；0 表示一直服务；其它值表示服务时长
 */
internal expect fun loadPluginsFromCli(
    pluginsDir: String,
    cacheDir: String?,
    serveMillis: Long?,
    httpPort: Int?,
)

/** 读取当前可执行文件内嵌 classpath 中的资源；未嵌入时返回 null。 */
internal expect fun embeddedResource(path: String): String?

/** 当前可执行文件是否内嵌了插件 bundle（单文件发行版）。 */
internal expect fun hasEmbeddedPluginBundle(): Boolean

/**
 * 加载可执行文件内嵌的 bundle 并运行。
 *
 * 外部 `plugins/` 仍然参与扫描，可覆盖同 id 的内嵌包；
 * 参数语义与 `--plugins` 一致，null 表示不常驻。
 */
internal expect fun runEmbeddedPlugins(serveMillis: Long?, httpPort: Int?)

/**
 * Host 入口。
 *
 * 启动顺序：`--watchdog`（看门狗进程，不加载插件）→ 文件日志 → 日志出口 →
 * 看门狗子进程 → 崩溃处理器 → 参数分发。
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--watchdog") {
        runWatchdog(args)
        return
    }

    val logDir = startLogFile()
    installHostLogging()

    // --inspect 是一次性诊断，不值得为它常驻一个看门狗进程
    if (args.firstOrNull() != "--inspect") {
        // 横幅先于看门狗、插件加载等日志
        printStartupBanner()
        val watchdog = startWatchdog()
        if (watchdog != 0uL) {
            Log.debug("watchdog started (pid=$watchdog)", tag = "Bootstrap")
        } else {
            Log.warn("watchdog 启动失败：崩溃时不会生成报告", tag = "Bootstrap")
        }
        installCrashHandler(logDir)
    }

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
                        "[--once] [--http=<端口>] [--serve-ms=<毫秒>]"
                )
                return
            }
            val dir = args[1]
            var cache: String? = null
            var once = false
            var serveMillis: Long? = null
            var httpPort: Int? = null
            args.drop(2).forEach { arg ->
                when {
                    arg == "--once" -> once = true
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
            // 默认常驻；--once 才「启动、体检后退出」
            val effectiveServe = serveMillis ?: if (once) null else 0L
            loadPluginsFromCli(dir, cache, effectiveServe, httpPort)
            return
        }
    }

    // 单文件发行版：没有子命令时直接跑内嵌插件；--once / --serve-ms / --http 与 --plugins 同义
    if (hasEmbeddedPluginBundle()) {
        val options = parseRunOptions(args)
        val effectiveServe = options.serveMillis ?: if (options.once) null else 0L
        runEmbeddedPlugins(effectiveServe, options.httpPort)
        return
    }

    Log.info("keel host ${BuildInfo.BUILD_ID}")
    Log.info("embedded host.txt: ${embeddedResource("host.txt")}")
    if (args.isNotEmpty()) {
        println(
            "用法：keel-host [--plugins <插件目录> | --inspect <.keel 路径>]\n" +
                "      [--once] [--http=<端口>] [--serve-ms=<毫秒>]"
        )
    }
}

/** 常驻 / HTTP 相关命令行选项；`--plugins` 与内嵌 bundle 共用同一套解析。 */
private class RunOptions(
    val once: Boolean,
    val serveMillis: Long?,
    val httpPort: Int?,
)

/**
 * 解析 `--once` / `--serve` / `--serve-ms=` / `--http=`。
 *
 * @throws IllegalStateException 出现无法识别的参数或取值非法
 */
private fun parseRunOptions(args: Array<String>): RunOptions {
    var once = false
    var serveMillis: Long? = null
    var httpPort: Int? = null
    args.forEach { arg ->
        when {
            arg == "--once" -> once = true
            arg == "--serve" -> serveMillis = 0L
            arg.startsWith("--serve-ms=") ->
                serveMillis = arg.substringAfter('=').toLongOrNull()
                    ?: error("--serve-ms 需要毫秒数：$arg")

            arg.startsWith("--http=") ->
                httpPort = arg.substringAfter('=').toIntOrNull()
                    ?: error("--http 需要端口号：$arg")

            else -> error("无法识别的参数：$arg")
        }
    }
    return RunOptions(once, serveMillis, httpPort)
}
