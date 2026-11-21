package moe.lolosia.keel.gradle.common

import moe.lolosia.keel.gradle.database.KeelDatabaseExtension
import org.gradle.api.Action

/** `keel { database { ... } }`；只有应用了 `moe.lolosia.keel.database` 才存在。 */
val KeelExtension.database: KeelDatabaseExtension
    get() = get(KeelDatabaseExtension::class.java)

fun KeelExtension.database(action: Action<KeelDatabaseExtension>) = action.execute(database)
