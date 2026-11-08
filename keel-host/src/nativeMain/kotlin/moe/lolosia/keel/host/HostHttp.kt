package moe.lolosia.keel.host

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource
import moe.lolosia.keel.abi.c.KEEL_OK
import moe.lolosia.keel.abi.c.KEEL_SUBJECT_HTTP
import moe.lolosia.keel.abi.c.KEEL_SUBJECT_PING
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.error.KeelErrorEntry
import moe.lolosia.keel.api.error.KeelErrorPayload
import moe.lolosia.keel.api.error.KeelRemoteException
import moe.lolosia.keel.api.error.toKeelError
import moe.lolosia.keel.api.web.HttpRequest
import moe.lolosia.keel.api.web.HttpRouteRequest
import moe.lolosia.keel.api.web.HttpRouteResponse
import moe.lolosia.keel.host.auth.*
import moe.lolosia.keel.host.plugin.HostEvent
import moe.lolosia.keel.host.plugin.HostPump
import moe.lolosia.keel.host.plugin.PluginNames
import moe.lolosia.keel.host.route.HostRoutes

/**
 * Host 独占的 HTTP 入口。
 *
 * - `/healthz`：Host 自身存活；
 * - `/ping`：经事件泵向插件发 PING 并等 COMPLETE；
 * - 其余路径：按已注册的路由表分发到插件，载荷是 `HttpRouteRequest` 快照。
 *
 * 请求进入插件时，handler 用 `HostPump.request` 挂起（`suspendCancellableCoroutine`
 * 的等价语义），由泵线程投递并 resume；`ApplicationCall` / `Continuation` 不跨 ABI。
 */
internal object HostHttp {

    /** 请求体在 Monitoring 阶段读一次，后续分发直接复用（避免二次读流）。 */
    private val RequestBodyKey = AttributeKey<ByteArray>("keel.http.requestBody")

    /** 出站日志要打印的响应片段，由具体 handler 在响应前写入。 */
    private val ResponseSnippetKey = AttributeKey<String>("keel.http.responseSnippet")

    private const val BODY_LIMIT = 200

    /** 插件可能比 Host 新：多出来的错误信封字段直接忽略，不要解不出来。 */
    private val JsonLenient = Json { ignoreUnknownKeys = true }

    private fun snippet(bytes: ByteArray): String {
        val text = bytes.decodeToString()
        return if (text.length <= BODY_LIMIT) text else
            text.take(BODY_LIMIT) + "...(${text.length - BODY_LIMIT} more)"
    }

    private suspend fun ApplicationCall.respondLoggedText(
        text: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) {
        attributes.put(ResponseSnippetKey, snippet(text.encodeToByteArray()))
        respondText(text, status = status)
    }

    private var engine: EmbeddedServer<*, *>? = null

    /** 启动并立即返回；重复调用无副作用。 */
    fun start(port: Int, pingPluginId: ULong?) {
        if (engine != null) return

        val listenPort = port
        val environment = applicationEnvironment {
            log = KtorLogBridge
        }

        val server = embeddedServer(
            CIO,
            environment,
            configure = {
                connectors.add(
                    EngineConnectorBuilder().apply {
                        host = "0.0.0.0"
                        this.port = listenPort
                    },
                )
            },
        ) {
            // 入站 / 出站日志：模块列固定 HTTP IN / HTTP OUT [status]，来源列是客户端地址
            intercept(ApplicationCallPipeline.Monitoring) {
                val mark = TimeSource.Monotonic.markNow()
                val remote = remoteInfoOf(call)
                val method = call.request.httpMethod.value
                val path = call.request.path()

                // 请求体只在这里读一次；POST/PUT/PATCH 之外的请求体忽略
                val requestBody = when (method) {
                    "POST", "PUT", "PATCH" -> call.receiveText().encodeToByteArray()
                    else -> ByteArray(0)
                }
                call.attributes.put(RequestBodyKey, requestBody)

                HttpLog.inbound(method, path, remote)
                if (requestBody.isNotEmpty()) HttpLog.inboundBody(remote, snippet(requestBody))

                try {
                    proceed()
                } catch (e: Throwable) {
                    // 对应 ErrorWebFilter：先记完整错误链，再把异常交回引擎
                    HttpLog.error(remote, "$method $path 处理失败", e, Log.site())
                    call.attributes.put(
                        ResponseSnippetKey,
                        snippet(e.toString().substringBefore('\n').encodeToByteArray()),
                    )
                    throw e
                } finally {
                    val status = call.response.status()?.value ?: 500
                    HttpLog.outbound(status, method, path, remote, mark.elapsedNow().inWholeMilliseconds)
                    call.attributes.getOrNull(ResponseSnippetKey)?.let {
                        HttpLog.outboundBody(status, remote, it)
                    }
                }
            }

            routing {
                get("/healthz") {
                    call.respondLoggedText("ok")
                }

                get("/ping") {
                    val id = pingPluginId
                    if (id == null) {
                        call.respondLoggedText("没有已启动的插件", HttpStatusCode.ServiceUnavailable)
                        return@get
                    }
                    val reply = HostPump.request(id, KEEL_SUBJECT_PING.toULong(), null, 2000)
                    if (reply != null && reply.status == KEEL_OK.toInt()) {
                        call.respondLoggedText("ok")
                    } else {
                        call.respondLoggedText("插件未响应", HttpStatusCode.GatewayTimeout)
                    }
                }

                route("{...}") {
                    handle {
                        dispatch(call)
                    }
                }
            }
        }
        server.start(wait = false)
        // 等连接器真正绑定：Ktor 的「Responding at」在绑定后输出，启动完成日志要排在它之后
        runBlocking { server.engine.resolvedConnectors() }
        engine = server
        Log.info("http listening on 0.0.0.0:$port", tag = "Http")
    }

    /** 先停止接收新请求，再交给插件 shutdown。 */
    fun stop() {
        engine?.stop(500, 2000)
        engine = null
    }

    /** 把插件的错误信封重建成 [KeelRemoteException]；载荷缺失时给出状态码说明。 */
    private fun remoteError(pluginId: ULong, reply: HostEvent, callSite: Throwable): KeelRemoteException {
        val payload = reply.payload?.let {
            runCatching { JsonLenient.decodeFromString<KeelErrorPayload>(it.decodeToString()) }.getOrNull()
        }
        return KeelRemoteException(
            plugin = PluginNames.of(pluginId),
            chain = payload?.chain?.takeIf { it.isNotEmpty() }
                ?: listOf(KeelErrorEntry("PluginFailure", "插件返回 status=${reply.status}")),
            // 丢掉 `Throwable.<init>` 这类构造帧，只留真正调用方
            callerFrames = callSite.toKeelError().chain.firstOrNull()?.frames.orEmpty()
                .dropWhile { it.symbol.startsWith("kfun:kotlin.Throwable#<init>") },
        )
    }

    /** 与前端约定的错误体：`{ "data": null, "code": <status>, "msg": "..." }`。 */
    private fun errorBody(code: Int, message: String): String =
        """{"data":null,"code":$code,"msg":${jsonString(message)}}"""

    /** 认证 / 授权失败：直接回 JSON 信封，不再进插件。 */
    private suspend fun ApplicationCall.respondFailure(status: HttpStatusCode, message: String) {
        val body = errorBody(status.value, message)
        attributes.put(ResponseSnippetKey, snippet(body.encodeToByteArray()))
        respondText(body, ContentType.Application.Json, status)
    }

    /** 取 `Authorization: Bearer <token>`；头名大小写不敏感。 */
    private fun bearerToken(headers: Map<String, String>): String? =
        headers.entries
            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
            ?.value
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun jsonString(text: String): String = buildString {
        append('"')
        for (ch in text) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

    private suspend fun dispatch(call: ApplicationCall) {
        val remote = remoteInfoOf(call)
        val path = call.request.path()
        val method = call.request.httpMethod.value
        val match = HostRoutes.match(method, path)
        if (match == null) {
            call.respondLoggedText("not found", HttpStatusCode.NotFound)
            return
        }

        // 请求体已在 Monitoring 阶段读过，这里直接复用，避免二次读流
        val body = call.attributes.getOrNull(RequestBodyKey) ?: ByteArray(0)
        val request = HttpRequest(
            method = method,
            path = path,
            query = call.request.queryParameters.entries().associate { it.key to it.value },
            headers = call.request.headers.entries().associate { it.key to it.value.joinToString(",") },
            body = body,
        )

        // 带 @Permission 但没装授权实现：fail closed，不能因为「看起来是公开路由」就放行
        if (match.permissions.isNotEmpty() && !HostAuth.hasAuthorizer()) {
            Log.warn(
                "$method $path 需要权限 ${match.permissions.joinToString()}，但未安装授权实现",
                tag = "Auth",
            )
            call.respondFailure(HttpStatusCode.ServiceUnavailable, "服务未安装授权实现")
            return
        }

        val token = bearerToken(request.headers)
        val authenticated = if (token == null) {
            null
        } else {
            try {
                HostAuth.authenticate(token)
            } catch (e: AuthUnavailableException) {
                Log.warn("$method $path 认证失败：${e.message}", tag = "Auth")
                call.respondFailure(HttpStatusCode.ServiceUnavailable, e.message ?: "认证服务不可用")
                return
            }
        }

        // 把授权规则并进请求快照，插件的 ctx.hasPermission 才能本地判定
        val user = if (authenticated == null) {
            null
        } else {
            try {
                HostAuth.enrich(authenticated)
            } catch (e: AuthUnavailableException) {
                // 只有需要权限判定的路由必须拒绝；其余路由按未富化快照执行
                if (match.permissions.isNotEmpty()) {
                    Log.warn("$method $path 授权快照不可用：${e.message}", tag = "Auth")
                    call.respondFailure(HttpStatusCode.ServiceUnavailable, e.message ?: "授权服务不可用")
                    return
                }
                Log.warn("$method $path 授权快照不可用，按空规则继续：${e.message}", tag = "Auth")
                authenticated
            }
        }

        // 白名单只免「没登录就 401」，不免权限
        val needsLogin = path.startsWith("/api/") && !HostAuth.isWhitelisted(path)
        if (needsLogin && user == null) {
            call.respondFailure(HttpStatusCode.Unauthorized, "身份认证失败，请重新登录")
            return
        }

        if (match.permissions.isNotEmpty()) {
            val userId = user?.id ?: run {
                call.respondFailure(HttpStatusCode.Unauthorized, "身份认证失败，请重新登录")
                return
            }
            val granted = try {
                HostAuth.authorize(userId, match.permissions)
            } catch (e: AuthUnavailableException) {
                Log.warn("$method $path 授权失败：${e.message}", tag = "Auth")
                call.respondFailure(HttpStatusCode.ServiceUnavailable, e.message ?: "授权服务不可用")
                return
            }
            if (!granted) {
                call.respondFailure(HttpStatusCode.Forbidden, "没有权限访问该资源")
                return
            }
        }

        val payload = Json.encodeToString(
            HttpRouteRequest(
                routeId = match.routeId,
                pathParameters = match.pathParameters,
                request = request,
                user = user,
            )
        ).encodeToByteArray()

        // 进消息泵之前先留一份调用方栈：出错时要能看到「谁发起的」，
        // 而不是泵唤醒后调度器留下的那一串无意义帧
        val callSite = Throwable()

        val reply = HostPump.request(match.pluginId, KEEL_SUBJECT_HTTP.toULong(), payload, 30_000)
        if (reply != null && reply.status != KEEL_OK.toInt()) {
            val error = remoteError(match.pluginId, reply, callSite)
            // 自己记录并返回 JSON，不再把异常抛给 Ktor（避免它再打一遍 Unhandled）
            HttpLog.error(remote, "$method $path 处理失败", error, Log.site())
            call.respondText(
                text = errorBody(500, error.chain.firstOrNull()?.message ?: "插件执行失败"),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.InternalServerError,
            )
            return
        }
        val response = reply?.payload?.let {
            try {
                Json.decodeFromString<HttpRouteResponse>(it.decodeToString())
            } catch (e: Throwable) {
                Log.warn("插件响应无法解码：${e.message}", tag = "Http")
                null
            }
        }
        if (response == null) {
            call.respondLoggedText("插件无响应", HttpStatusCode.GatewayTimeout)
            return
        }

        // 出站日志要打印的响应体（截断）
        call.attributes.put(ResponseSnippetKey, snippet(response.body))
        call.respondBytes(
            bytes = response.body,
            contentType = ContentType.parse(response.contentType),
            status = HttpStatusCode.fromValue(response.status),
        )
    }
}
