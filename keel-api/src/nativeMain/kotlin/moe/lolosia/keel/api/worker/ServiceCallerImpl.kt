package moe.lolosia.keel.api.worker

import moe.lolosia.keel.abi.c.KEEL_SUBJECT_SERVICE_CALL
import moe.lolosia.keel.api.host.HostRequestException
import moe.lolosia.keel.api.service.ServiceCall
import moe.lolosia.keel.api.service.ServiceCaller
import moe.lolosia.keel.api.service.ServiceUnavailableException
import moe.lolosia.keel.api.service.ServiceWire

/**
 * 生成的客户端使用的调用器。
 *
 * 请求投给 Host，Host 按服务 id 转发给提供者；等待期间在自己的收件箱上重入事件泵。
 */
internal class ServiceCallerImpl(private val serviceId: String) : ServiceCaller {

    override fun callBlocking(method: String, payload: ByteArray): ByteArray =
        call(ServiceCall(serviceId, method, payload))

    /**
     * `suspend` 方法当前复用阻塞路径：Worker 还没有独立的事件循环，
     * 「挂起不阻塞 Worker」的调度在后续阶段接入。
     */
    override suspend fun call(method: String, payload: ByteArray): ByteArray =
        call(ServiceCall(serviceId, method, payload))

    private fun call(call: ServiceCall): ByteArray =
        try {
            callHost(KEEL_SUBJECT_SERVICE_CALL.toULong(), ServiceWire.encodeCall(call))
        } catch (e: HostRequestException) {
            throw ServiceUnavailableException(serviceId, e.message ?: "服务调用失败：$serviceId")
        }
}
