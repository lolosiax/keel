package moe.lolosia.keel.host.capability

import kotlinx.cinterop.*
import moe.lolosia.keel.abi.c.KEEL_ABI_VERSION
import moe.lolosia.keel.abi.c.KEEL_OK
import moe.lolosia.keel.abi.c.KeelCapabilityDescriptor
import moe.lolosia.keel.host.database.c.KEEL_DB_ABI_VERSION
import moe.lolosia.keel.host.database.c.KeelDbApi
import moe.lolosia.keel.host.database.c.KeelDbConfig
import moe.lolosia.keel.host.plugin.NativeLibrary
import moe.lolosia.keel.host.plugin.hostAbi

/** `keel_capability_entry` 的签名：返回描述符指针。 */
private typealias CapabilityEntryFn = CFunction<() -> CPointer<KeelCapabilityDescriptor>?>

/**
 * 已 `open` 的 database 能力包。
 *
 * 持有库句柄、实例与函数表；[close] 只关闭能力，不卸载库（与插件同样保留在进程内）。
 */
internal class DatabaseCapability private constructor(
    @Suppress("unused") private val library: NativeLibrary,
    private val descriptor: CPointer<KeelCapabilityDescriptor>,
    val handle: COpaquePointer,
    val apiPointer: COpaquePointer,
) {

    val api: KeelDbApi get() = apiPointer.reinterpret<KeelDbApi>().pointed

    fun close() {
        descriptor.pointed.close?.invoke(handle)
    }

    companion object {

        /**
         * `dlopen` → `keel_capability_entry` → ABI 校验 → `open`。
         *
         * @throws IllegalStateException 符号缺失、ABI 不匹配、open 失败
         */
        fun open(capability: InstalledCapability, settings: DatabaseSettings): DatabaseCapability {
            val manifest = capability.manifest
            val library = NativeLibrary(capability.libraryPath)
            val entryAddress = library.symbol(manifest.entry)
                ?: throw IllegalStateException(
                    "${capability.libraryPath} 未导出 ${manifest.entry}"
                )
            val entry = entryAddress.reinterpret<CapabilityEntryFn>()
            val descriptor = entry.invoke()
                ?: throw IllegalStateException("${manifest.id} 的能力描述符为空")

            if (descriptor.pointed.abi_version != KEEL_ABI_VERSION) {
                throw IllegalStateException(
                    "${manifest.id} 的 ABI 版本是 ${descriptor.pointed.abi_version}，Host 支持 $KEEL_ABI_VERSION"
                )
            }
            if (descriptor.pointed.struct_size < sizeOf<KeelCapabilityDescriptor>().toUInt()) {
                throw IllegalStateException(
                    "${manifest.id} 的描述符过小：${descriptor.pointed.struct_size}"
                )
            }
            val provides = descriptor.pointed.provides?.toKString()
            if (provides != "database") {
                throw IllegalStateException("${manifest.id} 声明的是 capability=$provides，不是 database")
            }

            val opened = memScoped {
                val config = alloc<KeelDbConfig>()
                config.struct_size = sizeOf<KeelDbConfig>().toUInt()
                config.url = settings.url.cstr.ptr
                config.username = settings.username?.cstr?.ptr
                config.password = settings.password?.cstr?.ptr
                config.max_connections = settings.maxConnections.toUInt()
                config.connect_timeout_ms = settings.connectTimeoutMs.toUInt()

                val instanceOut = alloc<COpaquePointerVar>()
                val apiOut = alloc<COpaquePointerVar>()
                val status = descriptor.pointed.open!!(
                    hostAbi,
                    config.ptr,
                    config.struct_size,
                    instanceOut.ptr,
                    apiOut.ptr,
                )
                if (status != KEEL_OK) {
                    throw IllegalStateException("${manifest.id} 打开失败：status=$status")
                }
                (instanceOut.value ?: throw IllegalStateException("${manifest.id} 未返回实例")) to
                    (apiOut.value ?: throw IllegalStateException("${manifest.id} 未返回函数表"))
            }

            val database = DatabaseCapability(library, descriptor, opened.first, opened.second)
            if (database.api.abi_version != KEEL_DB_ABI_VERSION) {
                database.close()
                throw IllegalStateException(
                    "${manifest.id} 的 db ABI 版本是 ${database.api.abi_version}，Host 支持 $KEEL_DB_ABI_VERSION"
                )
            }
            return database
        }
    }
}
