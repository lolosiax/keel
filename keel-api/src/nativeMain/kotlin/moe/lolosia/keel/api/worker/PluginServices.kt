package moe.lolosia.keel.api.worker

import moe.lolosia.keel.abi.c.KEEL_SUBJECT_SERVICE_REGISTER
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.service.*

/**
 * 本插件的服务表：同模块直调 + 跨模块分发。
 *
 * 只在 Worker 线程访问；`get` 返回本地实现，[caller] 返回经 Host 转发的调用器。
 * 生成代码通过 [ServiceBinder.bind] 把实现与分发器一起登记进来。
 */
internal object PluginServices : ModuleDirectory, ServiceBinder {

    private val locals = mutableMapOf<String, Any>()
    private val dispatchers = mutableMapOf<String, ServiceDispatcher>()

    fun reset() {
        locals.clear()
        dispatchers.clear()
    }

    /** 把服务 id 表发给 Host；空表也要发，用于清掉上一轮的声明。 */
    fun publish() {
        val registration = ServiceRegistration(dispatchers.keys.sorted())
        sendToHost(
            subject = KEEL_SUBJECT_SERVICE_REGISTER.toULong(),
            payload = ServiceWire.encodeRegistration(registration),
        )
    }

    /** 供 Worker 分发入口使用：按服务 id 找到生成的分发器。 */
    suspend fun dispatch(call: ServiceCall, callerModule: Long): ByteArray {
        val dispatcher = dispatchers[call.service]
            ?: throw ServiceUnavailableException(call.service)
        return dispatcher.dispatch(call.method, call.args, callerModule)
    }

    fun hasAny(): Boolean = dispatchers.isNotEmpty()

    override fun <T : Any> bind(key: ServiceKey<T>, service: T, dispatcher: ServiceDispatcher) {
        if (dispatcher.serviceId != key.id) {
            Log.warn("服务 ${key.id} 的分发器声明了 ${dispatcher.serviceId}，已按 ${key.id} 登记")
        }
        locals[key.id] = service
        dispatchers[key.id] = dispatcher
    }

    override fun <T : Any> get(key: ServiceKey<T>): T? = locals[key.id] as? T

    override fun <T : Any> require(key: ServiceKey<T>): T =
        get(key) ?: throw ServiceUnavailableException(key.id)

    /** 跨模块调用永远走 Host；同模块想省掉往返请直接用 [get]。 */
    override fun <T : Any> caller(key: ServiceKey<T>): ServiceCaller = ServiceCallerImpl(key.id)
}
