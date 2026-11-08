package moe.lolosia.keel.api.service

/**
 * 服务实现方的分发器，由 KSP 生成。
 *
 * [dispatch] 在提供者自己的 Worker 上执行；参数与返回值已经由生成代码编解码，
 * Host 与调用方都不接触业务类型。
 */
interface ServiceDispatcher {

    val serviceId: String

    /**
     * @param method 接口方法名（重载方法在编译期被拒绝，所以名字唯一）
     * @param payload 参数包装的 CBOR 字节；无参数时为空
     * @param callerModule 调用方模块 id，回调与日志归因用
     */
    suspend fun dispatch(method: String, payload: ByteArray, callerModule: Long): ByteArray
}
