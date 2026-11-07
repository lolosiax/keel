# Keel Web 路由与插件请求处理

> 目标：插件像常规单进程 Ktor 一样注册路由、接收 `ctx`、返回对象或流；
> 所有插件共用 Host 的同一个端口与同一条中间件链。
> 参考：`backend` 的 `@Controller` / `@GET` + `Context` + `EnvelopeInterceptor` 设计。

---

## 1. 结论

- **Host 独占 Ktor 服务器与端口**，插件不创建引擎、不 install Ktor 插件。
- 插件在启用时把「路由描述表」注册给 Host；Host 用一个 catch-all 路由把请求分发给对应插件。
- 请求进入插件前，Host 从 `ApplicationCall` 提取 **`Context` 快照**（方法、路径、查询、头、body、用户、traceId）；
  `ApplicationCall` 本身绝不过 C ABI。
- 插件 handler 的写法与 backend 控制器一致：

```kotlin
@Controller("/api/queue")
object QueueController {

    @GET("/{userId}")
    @Permission("queue.read")
    suspend fun first(ctx: Context, @Path userId: String): QueueInfo =
        ctx.modules.queue.first(userId)

    @GET("/{userId}/events")
    suspend fun events(ctx: Context, @Path userId: String): HttpResponse =
        HttpResponse(
            headers = mapOf("Content-Type" to "text/event-stream"),
            body = ResponseBody.Stream(queueEvents(userId), "text/event-stream"),
        )
}
```

- 普通返回值由 KSP 生成的适配器编码成 JSON；需要状态码 / 头 / 流时返回 `HttpResponse`。
- 中间件（Traffic、Ctx、AccessStat、SSE、Auth、Envelope）全部在 Host，作用范围与 backend 一致；
  插件路由通过 `@Permission` 参与同一套鉴权。

---

## 2. 请求生命周期

```
客户端
  │ 同一端口
  ▼
Ktor Server（Host）
  │ Traffic → Ctx → AccessStat → SSE → Auth
  ▼
catch-all route（Host 自建的路由表）
  │ 1. 按 method + path 找到 pluginId + routeId
  │ 2. 检查 @Permission（未通过 → 403）
  │ 3. 提取 Context 快照
  ▼
HTTP_REQUEST 事件 ──► 插件 Worker
                        │ 参数绑定（KSP 生成）
                        │ handler(ctx, @Path ..., @Query ..., @Body ...)
                        ▼
                     HttpResponse / 异常
  ◄── HTTP_RESPONSE / STREAM_* 事件
  │
  ▼
EnvelopeInterceptor（JSON 才包信封）
  │
  ▼
客户端
```

---

## 3. 路由注册与匹配

### 3.1 注册

- KSP 在 `impl` 子项目里生成路由描述：

```kotlin
RouteDescriptor(
    method = "GET",
    path = "/api/queue/{userId}",
    routeId = "queue#first",
    permissions = listOf("queue.read"),
)
```

- 插件 Worker 启用时发 `ROUTE_REGISTER { descriptors }`；Host 校验后**原子替换**路由表。
- 插件 disable 时发 `ROUTE_UNREGISTER`；在途请求返回 503 或按配置排水。
- 冲突（同 method + 同 path 已属于别的插件）**拒绝注册**并记日志，不允许静默覆盖。

### 3.2 匹配

- Host 用自建 matcher，不动态改 Ktor 的路由树：
  - 精确段 > `{param}` > `*`；
  - method 不匹配返回 405，路径不存在返回 404；
  - Host 自己的静态资源 / WebDAV / 健康检查路由优先于 catch-all。
- 建议插件路径统一加自己的前缀（`/api/<pluginId>/...`）；Host 不强制，但冲突时以先注册者为准并报错。

---

## 4. `Context`

```kotlin
interface Context {
    val plugin: PluginContext          // 插件运行时上下文（modules / config / server）
    val request: HttpRequest           // 方法、路径、query、headers、body、remoteAddress、traceId
    val pathParameters: Map<String, String>
    val userId: String?
    val user: UserSnapshot?            // id / username / roles
    val data: MutableMap<String, Any?> // 请求期共享

    val modules: ModuleDirectory       // 快捷方式 = plugin.modules
    val server: KeelServer
    val config: Config

    fun query(name: String): String?
    fun header(name: String): String?
    fun body(): ByteArray
    fun hasPermission(permission: String): Boolean
}
```

与 backend 的差异只有一条：**没有 `ctx.call`**（`ApplicationCall` 绑定 Host 协程与线程，不能跨运行时）。
需要 IP / UA / 请求头时用 `ctx.request`；需要用户时用 `ctx.userId` / `ctx.user`。

- `ctx.hasPermission` 通过 Host 实时判定，结果在本次请求内缓存；不要在插件里复制 RBAC 规则。
- `ctx.data` 只活在本次请求，插件内的服务可以放解析结果、审计字段等。

---

## 5. 响应

| handler 返回 | Host 行为 |
| --- | --- |
| `@Serializable` 对象 | JSON 编码，`Content-Type: application/json`，交给 Envelope |
| `String` | 按 `text/plain` 返回，不包信封（与 backend 一致） |
| `HttpResponse` | 按 status / headers / body 返回；`envelope=false` 时跳过信封 |
| `ResponseBody.Stream` | 分块写回，不包信封 |

```kotlin
// 文件 / SSE / 大响应
HttpResponse(
    status = 200,
    headers = mapOf("Content-Type" to "text/csv; charset=utf-8"),
    body = ResponseBody.Stream(csvFlow(), "text/csv; charset=utf-8"),
    envelope = false,
)
```

### 5.1 流式协议

```
插件 → Host：HTTP_STREAM_OPEN  { correlation, status, headers, contentType }
插件 → Host：HTTP_STREAM_CHUNK { correlation, seq, bytes }
Host → 插件：HTTP_STREAM_ACK   { correlation, bytes }        // 背压
插件 → Host：HTTP_STREAM_END   { correlation }
Host → 插件：HTTP_STREAM_CANCEL{ correlation }                // 客户端断开
```

- 插件 Worker 收集 `Flow<ByteArray>`；每发一块等一个 ACK 再发下一块，避免 Host 侧无限缓冲。
- 客户端断开 / 超时 → Host 发 `CANCEL`；插件侧通过协程取消终止 Flow。
- SSE 只是 `contentType=text/event-stream` 的流，Host 不缓冲、不包信封。

---

## 6. 中间件与鉴权

- Host 安装与 backend 相同的链：`Traffic → Ctx → AccessStat → SSE → Auth → Envelope`。
- 插件路由在 `Auth` 之后、`Envelope` 之前被分发；因此：
  - 登录校验对插件路由同样生效；
  - `@Permission` 由 Host 在分发前检查，未通过直接 403，不进入插件；
  - JSON 响应回到 Host 后由 `EnvelopeInterceptor` 统一包 `{code,msg,data}`。
- 插件**不能**安装 Ktor plugin / 修改全局中间件；需要插件级拦截时，在 handler 内部或插件服务层做。
- 白名单接口（如签名 URL）沿用 Host 规则，插件路由不自动获得豁免。

---

## 7. 并发与 Worker

- 插件运行时可以有多个 Worker 线程；每个线程只属于该插件运行时。
- **DEMO**：1 个 Worker + 协程多路复用，I/O 型请求可并发；CPU 密集 handler 会串行。
- **P0**：按插件配置 `workers = N` 启动 Worker 池：
  - 每个 Worker 一个 inbox，Host 按负载分发新请求；
  - 回复 / 服务调用回包必须投递回**发起请求的 Worker**（`replyTo`）；
  - ABI 增加 `worker_count` 与 `worker_inbox(i)`，`KeelPluginApi` 仍是 append-only 扩展。
- 共享状态需要线程安全（K/N 新内存模型允许跨线程访问，但竞态仍由业务负责）。

---

## 8. 请求体与大文件

- DEMO：`body` 一次性读入内存，默认上限 8 MB，超限 413；普通 JSON / 表单足够。
- P1：大 body 落临时文件，`Context` 暴露 `bodyHandle` / `bodyStream()`，插件经 Host 分块读取；
  避免几 GB 上传在 C ABI 上整块复制。
- 上传接口建议仍走「裸请求体 + 边收边写」的模式，与 backend 文件管理器一致。

---

## 9. 错误、超时与热禁用

| 情况 | 行为 |
| --- | --- |
| 插件 handler 抛异常 | Host 返回 500，JSON 错误体（经 Envelope），日志带 `pluginId + traceId` |
| 请求超时（默认 30s） | 504，并向插件发 `HTTP_CANCEL` |
| 插件被 disable | 新请求 503；在途请求按 drain 超时取消 |
| 路由冲突 | 注册失败，插件启用失败并给出冲突路径 |
| 客户端断开 | `HTTP_CANCEL`，插件协程取消，Flow 停止 |

---

## 10. 构建期生成

`keel-router-ksp`（在 `impl` 子项目运行）生成：

1. `RouteDescriptor` 列表；
2. `dispatch(ctx, routeId)`：参数绑定 + 调用 handler；
3. 返回值适配：DTO → JSON / `HttpResponse` / `Stream`；
4. `registerGeneratedRoutes(router)`：Worker 启动时调用；
5. `@Permission` 提取，进入描述表由 Host 校验。

注解与 backend 同名同义（`@Controller` / `@GET` / `@Path` / `@Query` / `@Header` / `@Body` / `@Permission`），
控制器代码跨项目只需改 import。

---

## 11. 实施阶段

### DEMO

- Host：Ktor CIO + 同一端口 + catch-all + 路由表 + `HTTP_REQUEST/RESPONSE`；
- 插件：`@Controller` + `@GET` + `@Path` / `@Query`，返回 `@Serializable` DTO；
- 无中间件移植（先装最小 Auth / Envelope）；
- 单 Worker，body 内存上限。

### P0

- 完整中间件链与 `@Permission`；
- 流式响应 + ACK / CANCEL；
- 路由热注册 / 注销、冲突检测；
- Worker 池。

### P1

- 大 body 句柄、上传流；
- SSE / WebSocket 专用 API；
- 插件级拦截器；
- 每路由超时 / 限流。

---

## 12. 验收清单

- [ ] 插件只写 `@Controller` / `@GET`，不碰 Ktor 类型，就能在 Host 端口上提供服务。
- [ ] `ctx.userId` / `ctx.hasPermission` 与 Host 登录态、RBAC 一致。
- [ ] 返回对象自动 JSON + 信封；返回流时边收边写。
- [ ] 两个插件注册同一路径时，第二个注册失败并有清晰错误。
- [ ] 插件 disable 后新请求 503，在途请求被取消。
- [ ] 客户端断开后，插件侧协程与 Flow 停止，无残留任务。

---

*本文确认 Web 侧实现；`Context` / `HttpRequest` / `HttpResponse` / 注解已在 keel-api 落地，
Host 与 KSP 按阶段补充。*
