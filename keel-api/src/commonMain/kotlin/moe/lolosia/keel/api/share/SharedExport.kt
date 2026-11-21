package moe.lolosia.keel.api.share

/**
 * 可以在跨界时按需登记自己的本地对象。
 *
 * 生成的代理在编码共享参数时调用 [PublicApiDirectory.handleOf]；命中间接实现本接口的实例时，
 * 先登记再返回句柄。调用方（业务代码）不需要显式登记，也不需要在调用点构造代理。
 *
 * 与 [SharedProxy] 对称：代理是「远端对象的本地视图」，本接口是「本地对象导出为远端视图」。
 * 只在拥有者运行时内使用，自身不跨边界。
 */
interface SharedExport {

    /**
     * 登记并返回句柄；重复调用返回同一个句柄。
     *
     * @param directory 拥有者的共享对象目录，通常就是插件运行时里的那一份
     */
    fun sharedRef(directory: PublicApiDirectory): SharedRef
}
