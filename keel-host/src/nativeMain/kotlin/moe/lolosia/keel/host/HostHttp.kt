package moe.lolosia.keel.host

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import moe.lolosia.keel.abi.c.KEEL_OK
import moe.lolosia.keel.abi.c.KEEL_SUBJECT_PING
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.host.plugin.HostPump

/**
 * Host 独占的 HTTP 入口。
 *
 * 插件路由接入前提供健康检查与一次插件往返：
 * - `/healthz`：Host 自身存活；
 * - `/ping`：经事件泵向插件发 PING 并等 COMPLETE，验证「HTTP 线程挂起 → 泵线程 resume」。
 *
 * 请求进入插件时，handler 用挂起（`suspendCancellableCoroutine` 的等价语义）把工作交给
 * 事件泵，不把 `ApplicationCall` 或 `Continuation` 传过 C ABI。
 */
internal object HostHttp {

    private var engine: EmbeddedServer<*, *>? = null

    /** 启动并立即返回；重复调用无副作用。 */
    fun start(port: Int, pingPluginId: ULong?) {
        if (engine != null) return

        val server = embeddedServer(CIO, port = port) {
            routing {
                get("/healthz") {
                    call.respondText("ok")
                }

                get("/ping") {
                    val id = pingPluginId
                    if (id == null) {
                        call.respondText("没有已启动的插件", status = HttpStatusCode.ServiceUnavailable)
                        return@get
                    }
                    val reply = HostPump.request(id, KEEL_SUBJECT_PING.toULong(), null, 2000)
                    if (reply != null && reply.status == KEEL_OK.toInt()) {
                        call.respondText("ok")
                    } else {
                        call.respondText("插件未响应", status = HttpStatusCode.GatewayTimeout)
                    }
                }
            }
        }
        server.start(wait = false)
        engine = server
        Log.info("http listening on 0.0.0.0:$port", tag = "Http")
    }

    /** 先停止接收新请求，再交给插件 shutdown。 */
    fun stop() {
        engine?.stop(500, 2000)
        engine = null
    }
}
