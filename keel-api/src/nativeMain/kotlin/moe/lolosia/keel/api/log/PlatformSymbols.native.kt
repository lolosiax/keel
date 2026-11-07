@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.lolosia.keel.api.log

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import moe.lolosia.keel.abi.c.keel_locate_address
import moe.lolosia.keel.abi.c.keel_symbol_demangle

internal actual fun platformDemangle(raw: String): String? = memScoped {
    val out = allocArray<ByteVar>(1024)
    if (keel_symbol_demangle(raw.cstr.ptr, out, 1024u) == 0) return@memScoped null
    out.toKString()
}

internal actual fun platformLocate(address: Long): FrameLocation? = memScoped {
    val module = allocArray<ByteVar>(512)
    val rva = alloc<ULongVar>()
    if (keel_locate_address(address.toULong(), module, 512u, rva.ptr) == 0) return@memScoped null
    FrameLocation(module.toKString(), rva.value.toLong())
}
