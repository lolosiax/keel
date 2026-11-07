package moe.lolosia.keel.host.plugin

import moe.lolosia.keel.classpath.full.ClasspathRef

/**
 * 已通过校验、动态库已落到缓存目录的插件。
 *
 * bootstrap 与 Worker 生命周期由 [startPlugin] 接管。
 *
 * @property libraryPath 缓存目录里的动态库绝对路径，可直接交给 `dlopen` / `LoadLibrary`
 * @property classpathRef 绑定当前 generation 的资源引用，卸载后读取失效
 */
class LoadedPlugin(
    val manifest: KeelPackageManifest,
    val keelPath: String,
    val libraryPath: String,
    val classpathRef: ClasspathRef,
)
