package moe.lolosia.keel.gradle.common

import moe.lolosia.keel.gradle.classpath.KeelClasspathExtension
import org.gradle.api.Action

/** `keel { classpath { ... } }`；只有应用了 `moe.lolosia.keel.classpath` 才存在。 */
val KeelExtension.classpath: KeelClasspathExtension
    get() = get(KeelClasspathExtension::class.java)

fun KeelExtension.classpath(action: Action<KeelClasspathExtension>) = action.execute(classpath)
