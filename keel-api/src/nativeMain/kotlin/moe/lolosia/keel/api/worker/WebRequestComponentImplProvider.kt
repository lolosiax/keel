// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.api.worker

import moe.lolosia.keel.api.share.PublicApiDirectory
import moe.lolosia.keel.api.share.SharedRef

/** 把 `moe.lolosia.keel.api.worker.WebRequestComponentImpl` 登记为共享对象；销毁实例的一方负责释放句柄。 */
internal object WebRequestComponentImplProvider {

    fun register(directory: PublicApiDirectory, impl: moe.lolosia.keel.api.worker.WebRequestComponentImpl): SharedRef =
        directory.register(
            instance = impl,
            key = moe.lolosia.keel.api.web.WebRequestComponentKey,
            dispatcher = WebRequestComponentImplDispatcher(impl),
            interfaces = listOf("moe.lolosia.keel.api.web.Component"),
            singleton = false,
        )
}
