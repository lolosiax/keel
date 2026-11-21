// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.api.worker

import moe.lolosia.keel.api.share.PublicApiDirectory
import moe.lolosia.keel.api.share.SharedRef

/** 把 `moe.lolosia.keel.api.worker.RequestContextImpl` 登记为共享对象；销毁实例的一方负责释放句柄。 */
internal object RequestContextImplProvider {

    fun register(directory: PublicApiDirectory, impl: moe.lolosia.keel.api.worker.RequestContextImpl): SharedRef =
        directory.register(
            instance = impl,
            key = moe.lolosia.keel.api.web.ContextKey,
            dispatcher = RequestContextImplDispatcher(impl, directory),
            interfaces = listOf("moe.lolosia.keel.api.web.WebContext"),
            singleton = false,
        )
}
