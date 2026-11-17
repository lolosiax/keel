package moe.lolosia.keel.api.share

/**
 * 对象方法的本地分发器，由声明方或所有者提供。
 *
 * - 所有者侧：把调用转发到真实实例；
 * - 声明方侧：把调用交给该句柄的基类影子，覆盖方法再决定是否转回所有者。
 *
 * [dispatch] 在提供者自己的 Worker 上执行；参数与返回值已经由生成代码编解码。
 */
interface PublicApiDispatcher {

    val typeId: String

    /**
     * @param handle 对象句柄
     * @param method 方法线名；`base.` 前缀表示只跑基类体
     * @param payload 参数的 CBOR 字节；无参数时为空
     * @param callerModule 调用方模块 id，回调与日志归因用
     * @param overrides 实例方实际覆盖的方法线名
     */
    suspend fun dispatch(
        handle: Long,
        method: String,
        payload: ByteArray,
        callerModule: Long,
        overrides: Set<String>,
    ): ByteArray
}
