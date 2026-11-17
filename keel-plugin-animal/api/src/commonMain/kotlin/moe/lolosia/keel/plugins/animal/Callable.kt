// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.animal

import moe.lolosia.keel.api.annotation.PublicApi

/**
 * 声明方提供的回调接口：调用时返回一个 `Animal` 引用。
 */
@PublicApi
interface Callable {

    suspend fun invoke(): Animal
}
