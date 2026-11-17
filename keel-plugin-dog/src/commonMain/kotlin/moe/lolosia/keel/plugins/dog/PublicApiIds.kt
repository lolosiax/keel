package moe.lolosia.keel.plugins.dog

import moe.lolosia.keel.api.share.PublicApiKey

/** 本插件具体类型的 id；`createInstance` 用它找构造器。 */
const val SPAWN_ANIMAL_TYPE_ID: String = "dog:moe.lolosia.keel.plugins.dog.SpawnAnimal"

/** `SpawnAnimal` 的构造键。 */
val SpawnAnimalKey = PublicApiKey<SpawnAnimal>(SPAWN_ANIMAL_TYPE_ID)
