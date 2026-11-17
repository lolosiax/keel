// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.animal

import moe.lolosia.keel.api.annotation.PublicApi

/** `moe.lolosia.keel.plugins.animal.Callable` 在 api 工件里的镜像；对应 impl 的同名类型。 */
@PublicApi
interface Callable {
    suspend fun invoke(): moe.lolosia.keel.plugins.animal.Animal
}
