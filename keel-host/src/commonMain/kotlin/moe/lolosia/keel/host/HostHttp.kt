package moe.lolosia.keel.host

import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import moe.lolosia.keel.api.Log

/**
 * Host 独占的 HTTP 入口。
 *
 * 插件路由接入前只提供健康检查；引擎始终跑在 Host 运行时里，插件不碰 Ktor 类型。
 * 请求要进入插件时，由 Ktor handler 走 `suspendCancellableCoroutine` 把工作交给事件泵，
 * 不把 `ApplicationCall` 或协程对象传过 C ABI。
 */
internal object HostHttp {

    private var engine: EmbeddedServer<*, *>? = null

    /** 启动并立即返回；重复调用无副作用。 */
    fun start(port: Int) {
        if (engine != null) return

        val server = embeddedServer(CIO, port = port) {
            routing {
                get("/healthz") {
                    call.respondText("ok")
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
