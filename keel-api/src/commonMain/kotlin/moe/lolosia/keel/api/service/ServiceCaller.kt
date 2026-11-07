package moe.lolosia.keel.api.service

/**
 * 绑定到某个目标模块的调用器，由生成的客户端使用。
 *
 * 普通方法走 [callBlocking]（在模块 Worker 上重入事件泵等待应答），
 * `suspend` 方法走 [call]（挂起本地协程，不阻塞 Worker）。
 */
interface ServiceCaller {

    fun callBlocking(method: String, payload: ByteArray): ByteArray

    suspend fun call(method: String, payload: ByteArray): ByteArray
}
