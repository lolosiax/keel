// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.animal

import moe.lolosia.keel.api.annotation.PublicApi
import moe.lolosia.keel.api.annotation.Singleton

/** `moe.lolosia.keel.plugins.animal.AnimalPlugin` 在 api 工件里的镜像；对应 impl 的同名类型。 */
@PublicApi
@Singleton
interface AnimalPlugin {
    suspend fun register(callable: moe.lolosia.keel.plugins.animal.Callable): Unit
}
