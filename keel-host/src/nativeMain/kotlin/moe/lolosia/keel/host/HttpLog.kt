package moe.lolosia.keel.host

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.log.Ansi
import moe.lolosia.keel.api.log.LogLevel
import moe.lolosia.keel.api.log.LogSite

/**
 * 客户端地址。
 *
 * IPv4 显示 `ip:port`（端口灰色）；IPv6 只显示地址，不带方括号也不带端口。
 * IP 本身统一淡绿色。
 */
internal class RemoteInfo(host: String, val port: Int?) {

    val host: String = host.removeSurrounding("[", "]")

    private val isIpv6: Boolean = this.host.contains(':')

    /** 纯文本，用于来源列对齐。 */
    val plain: String = if (isIpv6) this.host else "${this.host}:${port ?: 0}"

    /** 着色版本：IP 淡绿，IPv4 端口灰色。 */
    val ansi: String = if (isIpv6) {
        Ansi.wrap(Ansi.BRIGHT_GREEN, this.host)
    } else {
        Ansi.wrap(Ansi.BRIGHT_GREEN, this.host) + Ansi.wrap(Ansi.FAINT, ":${port ?: 0}")
    }
}

internal fun remoteInfoOf(call: ApplicationCall): RemoteInfo {
    val origin = call.request.origin
    return RemoteInfo(origin.remoteHost, origin.remotePort)
}

/**
 * HTTP 入站 / 出站日志。
 *
 * 模块列固定为 `HTTP IN` / `HTTP OUT [status]`（同一颜色），来源列换成客户端地址。
 */
internal object HttpLog {

    /** HTTP 标签统一配色。 */
    private val tagStyle = Ansi.BRIGHT_CYAN.toString()

    fun inbound(method: String, path: String, remote: RemoteInfo) {
        Log.emitSource(
            level = LogLevel.INFO,
            tag = "HTTP IN",
            source = remote.plain,
            sourceAnsi = remote.ansi,
            message = "$method $path",
            moduleAnsi = tagStyle,
        )
    }

    fun inboundBody(remote: RemoteInfo, body: String) {
        Log.emitSource(
            level = LogLevel.INFO,
            tag = "HTTP IN",
            source = remote.plain,
            sourceAnsi = remote.ansi,
            message = body,
            moduleAnsi = tagStyle,
        )
    }

    fun outbound(status: Int, method: String, path: String, remote: RemoteInfo, elapsedMs: Long) {
        Log.emitSource(
            level = levelFor(status),
            tag = "HTTP OUT",
            source = remote.plain,
            sourceAnsi = remote.ansi,
            message = "$method $path - [$status]  ${elapsedMs}ms",
            moduleAnsi = tagStyle,
        )
    }

    fun outboundBody(status: Int, remote: RemoteInfo, body: String) {
        Log.emitSource(
            level = levelFor(status),
            tag = "HTTP OUT",
            source = remote.plain,
            sourceAnsi = remote.ansi,
            message = body,
            moduleAnsi = tagStyle,
        )
    }

    /** 出站错误：来源列与其它 HTTP 日志一致，保持客户端地址与配色。 */
    fun error(remote: RemoteInfo, message: String, cause: Throwable, site: LogSite? = null) {
        Log.emitSource(
            level = LogLevel.ERROR,
            tag = "HTTP OUT",
            source = remote.plain,
            sourceAnsi = remote.ansi,
            message = message,
            cause = cause,
            moduleAnsi = tagStyle,
            site = site,
        )
    }

    private fun levelFor(status: Int): LogLevel = when {
        status >= 500 -> LogLevel.ERROR
        status >= 400 -> LogLevel.WARN
        else -> LogLevel.INFO
    }
}
