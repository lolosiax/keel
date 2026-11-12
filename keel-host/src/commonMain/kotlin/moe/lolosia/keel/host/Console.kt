package moe.lolosia.keel.host

/**
 * 控制台可用性与「桌面应用」启动时的附着。
 *
 * 构建期只决定 PE 子系统（`keelPlugin.executable.console`）；运行期再按
 * `--console` / `--no-console` / `KEEL_CONSOLE` 决定是否要控制台。
 */
internal expect fun consoleAvailable(): Boolean

/**
 * 启动前准备控制台。
 *
 * `--no-console` 优先；`--console` 或 `KEEL_CONSOLE=1` 时附着到父进程控制台，
 * 失败则新建。Linux 上没有 GUI 子系统概念，只有「是不是终端」。
 */
internal expect fun prepareConsole(args: Array<String>)
