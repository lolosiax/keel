package moe.lolosia.keel.api.share

/**
 * `-api` 工件里镜像方法的调用入口。
 *
 * 镜像类与代理类只认识句柄与线名，真正的跨运行时投递由插件运行时安装进来。
 * 每个插件 dylib 各有一份状态，不会跨运行时共享。
 */
object PublicApiBridge {

    /** 插件运行时提供的真实投递实现。 */
    interface Remote {

        fun call(
            handle: Long,
            typeId: String,
            method: String,
            payload: ByteArray,
            owner: Boolean,
        ): ByteArray
    }

    private var remote: Remote? = null

    fun install(value: Remote) {
        remote = value
    }

    fun call(
        handle: Long,
        typeId: String,
        method: String,
        payload: ByteArray = ByteArray(0),
        owner: Boolean = false,
    ): ByteArray =
        (remote ?: error("PublicApi 运行时尚未安装")).call(handle, typeId, method, payload, owner)
}
