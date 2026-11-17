package moe.lolosia.keel.api.worker

import moe.lolosia.keel.api.host.HostSubjects
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.*

/**
 * 插件侧的 `@PublicApi` 运行时。
 *
 * 维护两张表：
 * - [locals]：本插件自己持有的对象（句柄 → 实例 + 所有者侧分发器）；
 * - [handlers]：本插件声明的类型（typeId → 声明方侧分发器，内部按句柄持有影子）。
 *
 * 入站调用先查 [locals]，未命中再交给 [handlers]；这样声明方既能服务自己的实例，
 * 也能服务别的插件声明的子类实例。只在 Worker 线程访问。
 */
internal object PublicApiObjects : PublicApiDirectory, PublicApiBridge.Remote {

    private class Local(
        val instance: Any,
        val handle: Long,
        val overrides: Set<String>,
        val dispatcher: PublicApiDispatcher,
    )

    private val locals = mutableListOf<Local>()
    private val handlers = mutableMapOf<String, PublicApiDispatcher>()

    fun reset() {
        locals.clear()
        handlers.clear()
    }

    override fun declare(types: List<PublicApiType>, handlers: List<PublicApiDispatcher>) {
        this.handlers.putAll(handlers.associateBy { it.typeId })
        if (types.isEmpty()) return
        callHost(
            HostSubjects.PUBLIC_API_DECLARE.toULong(),
            ServiceWire.encode(
                PublicApiDeclareRequest.serializer(),
                PublicApiDeclareRequest(types),
            ),
        )
    }

    override fun <T : Any> register(
        instance: T,
        key: PublicApiKey<T>,
        dispatcher: PublicApiDispatcher,
        interfaces: List<String>,
        overrides: Set<String>,
        singleton: Boolean,
    ): SharedRef {
        locals.firstOrNull { it.instance === instance }?.let { return SharedRef(it.handle) }

        val reply = ServiceWire.decode(
            SharedHandleReply.serializer(),
            callHost(
                HostSubjects.PUBLIC_API_REGISTER.toULong(),
                ServiceWire.encode(
                    SharedRegistration.serializer(),
                    SharedRegistration(
                        typeId = key.typeId,
                        interfaces = interfaces,
                        overrides = overrides.toList().sorted(),
                        singleton = singleton,
                    ),
                ),
            ),
        )
        locals += Local(instance, reply.handle, overrides, dispatcher)
        return SharedRef(reply.handle)
    }

    override fun handleOf(instance: Any): Long =
        locals.firstOrNull { it.instance === instance }?.handle
            ?: throw IllegalArgumentException("对象尚未登记为 @PublicApi 共享对象")

    override fun <T : Any> getInstance(key: PublicApiKey<T>, factory: (Long) -> T): T? =
        runCatching { requireInstance(key, factory) }.getOrNull()

    override fun <T : Any> requireInstance(key: PublicApiKey<T>, factory: (Long) -> T): T {
        val reply = ServiceWire.decode(
            SharedHandleReply.serializer(),
            callHost(
                HostSubjects.PUBLIC_API_SINGLETON.toULong(),
                key.typeId.encodeToByteArray(),
            ),
        )
        return factory(reply.handle)
    }

    override fun call(
        handle: Long,
        typeId: String,
        method: String,
        payload: ByteArray,
        owner: Boolean,
    ): ByteArray =
        callHost(
            HostSubjects.SHARED_CALL.toULong(),
            ServiceWire.encode(
                SharedCall.serializer(),
                SharedCall(typeId, handle, method, payload, owner),
            ),
        )

    /** 处理 Host 转来的共享对象调用；找不到对象或类型时抛异常，由 Worker 回错误包。 */
    suspend fun dispatch(call: SharedCall, callerModule: Long): ByteArray {
        val local = locals.firstOrNull { it.handle == call.handle }
        if (local != null) {
            return local.dispatcher.dispatch(
                handle = call.handle,
                method = call.method,
                payload = call.args,
                callerModule = callerModule,
                overrides = local.overrides,
            )
        }
        val handler = handlers[call.typeId]
            ?: throw IllegalStateException("未知的共享类型：${call.typeId}")
        return handler.dispatch(
            handle = call.handle,
            method = call.method,
            payload = call.args,
            callerModule = callerModule,
            overrides = call.overrides.toSet(),
        )
    }
}
