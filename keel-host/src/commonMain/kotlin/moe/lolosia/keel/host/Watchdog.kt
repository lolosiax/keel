package moe.lolosia.keel.host

/**
 * 拉起同一可执行文件的 `--watchdog` 子进程（工作目录不变）。
 *
 * @return 子进程 id；0 表示拉起失败
 */
internal expect fun startWatchdog(): ULong

/**
 * 安装崩溃处理器：致命信号 / 异常时冻结进程，等看门狗转储现场。
 *
 * @param logDir 文件日志目录；beacon 与报告都落在这里
 */
internal expect fun installCrashHandler(logDir: String?)

/** 登记一个已加载模块，崩溃时用来把故障地址归因到插件。 */
internal expect fun registerCrashModule(name: String, libraryPath: String)

/** `--watchdog` 入口：监控父进程，必要时转储现场并写报告。 */
internal expect fun runWatchdog(args: Array<String>)
