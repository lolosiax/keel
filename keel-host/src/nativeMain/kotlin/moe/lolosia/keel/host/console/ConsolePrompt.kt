package moe.lolosia.keel.host.console

import kotlin.concurrent.Volatile
import moe.lolosia.keel.host.ansiSupported
import platform.posix.fflush
import platform.posix.stdout

/**
 * 控制台的 `> ` 提示符。
 *
 * 交互式终端下常驻一行提示符；每条日志输出前用 `\r` 回到行首覆盖它，
 * 输出后补回。提示符必须立即 [fflush]，否则 stdout 的行缓冲会把它一直
 * 憋到下一条日志，用户还是看不到。
 *
 * 只有 stdin 是终端时由 [HostConsole.start] 启用；管道 / 重定向模式完全关闭，
 * 不往脚本输出里掺提示符。
 *
 * 日志可能来自 Ktor 等其它线程，两个标志用 @Volatile；极端并发下提示符
 * 可能重复或被顶掉一次，但日志行本身是完整的一次 println。
 */
internal object ConsolePrompt {

    @Volatile
    private var enabled = false

    @Volatile
    private var visible = false

    /** 启用并立即画一次提示符。 */
    fun enable() {
        enabled = true
        show()
    }

    /**
     * 关闭提示符并擦掉当前那一行。
     *
     * 进程退出前必须先调用：否则停机日志的每一行后面还会补 `> `，
     * 最后在屏幕底部留下一个没有意义的提示符。
     */
    fun disable() {
        enabled = false
        if (!visible) return
        clearLine()
        visible = false
    }

    /** 日志行输出前调用：光标回到行首，让日志覆盖掉提示符。 */
    fun beforeLine() {
        if (!visible) return
        write("\r")
        visible = false
    }

    /** 日志行输出后调用：补回提示符。 */
    fun afterLine() {
        if (!enabled || visible) return
        show()
    }

    private fun show() {
        if (!enabled) return
        write("> ")
        visible = true
    }

    private fun write(text: String) {
        print(text)
        fflush(stdout)
    }

    /** 回到行首并擦掉提示符；支持 VT 时顺便清掉用户没提交的半行输入。 */
    private fun clearLine() {
        if (ansiSupported()) {
            write("\r\u001B[K")
        } else {
            write("\r  \r")
        }
    }
}
