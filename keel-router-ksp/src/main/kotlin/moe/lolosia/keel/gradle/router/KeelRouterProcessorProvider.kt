package moe.lolosia.keel.gradle.router

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated

/**
 * Web 路由生成器的 KSP 入口。
 *
 * 扫描插件里的控制器注解，生成路由注册表（无反射）。
 * 当前只验证处理器能被 KSP 加载，不产生文件；生成逻辑待实现。
 */
class KeelRouterProcessorProvider : SymbolProcessorProvider {

    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        KeelRouterProcessor()
}

private class KeelRouterProcessor : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> = emptyList()
}
