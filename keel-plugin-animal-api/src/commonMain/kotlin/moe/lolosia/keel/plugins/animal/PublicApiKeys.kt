package moe.lolosia.keel.plugins.animal

import moe.lolosia.keel.api.share.PublicApiKey

/** `Animal` 的跨插件类型 id；声明方与消费方必须一致。 */
const val ANIMAL_TYPE_ID: String = "moe.lolosia.keel.plugins.animal.Animal"

/** `Callable` 的类型 id。 */
const val CALLABLE_TYPE_ID: String = "moe.lolosia.keel.plugins.animal.Callable"

/** 声明方插件本体的类型 id；`getInstance` 用它查单例。 */
const val ANIMAL_PLUGIN_TYPE_ID: String = "moe.lolosia.keel.plugins.animal.AnimalPlugin"

/** `Animal` 的查找键。 */
val AnimalKey = PublicApiKey<Animal>(ANIMAL_TYPE_ID)

/** `Callable` 的查找键。 */
val CallableKey = PublicApiKey<Callable>(CALLABLE_TYPE_ID)

/** 声明方插件本体的查找键。 */
val AnimalPluginKey = PublicApiKey<AnimalPlugin>(ANIMAL_PLUGIN_TYPE_ID)
