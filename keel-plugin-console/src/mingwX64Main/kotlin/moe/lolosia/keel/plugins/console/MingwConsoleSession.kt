package moe.lolosia.keel.plugins.console

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.concurrent.Volatile
import platform.windows.*

/**
 * Windows 实现：启动 helper 子进程，用两条单向命名管道做双向帧通信。
 *
 * - `out` 管道（插件写 / helper 读）承载 `OUT`、`TITLE`、`MODE`；
 * - `in` 管道（helper 写 / 插件读）承载 `HELLO`、`IN`、`RESIZE`、`CLOSE`。
 *
 * 两条单向管道而不是一条双工：同步句柄上并发读写的语义不可靠，阻塞中的读会把写卡住。
 * 阻塞式 `ReadFile` / `ConnectNamedPipe` 全部放在 IO 调度器上，Worker 线程不碰句柄。
 */
@OptIn(ExperimentalForeignApi::class)
internal class MingwConsoleSession(
    helperPath: String,
    initialTitle: String,
    scrollback: Int,
) : ConsoleSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controls = Channel<ByteArray>(Channel.UNLIMITED)

    override val events = Channel<BridgeEvent>(Channel.BUFFERED)
    override val queue = LogQueue()

    private val outPipe: HANDLE
    private val inPipe: HANDLE
    private val process: HANDLE

    @Volatile
    private var closed = false

    init {
        val id = nextPipeId()
        val outName = "\\\\.\\pipe\\keel-console-$id-out"
        val inName = "\\\\.\\pipe\\keel-console-$id-in"

        outPipe = createPipe(outName, PIPE_ACCESS_OUTBOUND.toUInt())
        inPipe = createPipe(inName, PIPE_ACCESS_INBOUND.toUInt())
        process = startHelper(helperPath, outName, inName, initialTitle, scrollback)

        scope.launch { outputLoop() }
        scope.launch { inputLoop() }
    }

    override fun setTitle(text: String) {
        controls.trySend(ConsoleFrame.encode(ConsoleFrame.TITLE, text.encodeToByteArray()))
    }

    override fun isAlive(): Boolean {
        if (closed) return false
        return memScoped {
            val code = alloc<DWORDVar>()
            GetExitCodeProcess(process, code.ptr) != 0 && code.value == STILL_ACTIVE
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // 关闭句柄会让阻塞中的 ReadFile / WriteFile 立即返回，随后直接结束 helper
        CloseHandle(outPipe)
        CloseHandle(inPipe)
        TerminateProcess(process, 1u)
        CloseHandle(process)
        controls.close()
        events.close()
        scope.cancel()
    }

    // -----------------------------------------------------------------------
    // 启动
    // -----------------------------------------------------------------------

    private fun createPipe(name: String, access: UInt): HANDLE = memScoped {
        val handle = CreateNamedPipeW(
            lpName = name.wcstr.ptr,
            dwOpenMode = access,
            dwPipeMode = PIPE_TYPE_BYTE.toUInt() or PIPE_READMODE_BYTE.toUInt() or PIPE_WAIT.toUInt(),
            nMaxInstances = 1u,
            nOutBufferSize = 1u shl 20,
            nInBufferSize = 1u shl 20,
            nDefaultTimeOut = 0u,
            lpSecurityAttributes = null,
        )
        if (handle == null || handle == INVALID_HANDLE_VALUE) {
            throw IllegalStateException("CreateNamedPipeW failed: ${GetLastError()}")
        }
        handle
    }

    private fun startHelper(
        helperPath: String,
        outName: String,
        inName: String,
        title: String,
        scrollback: Int,
    ): HANDLE = memScoped {
        val safeTitle = title.replace('"', '\'')
        val commandLine = "\"$helperPath\" --out \"$outName\" --in \"$inName\" " +
            "--title \"$safeTitle\" --scrollback $scrollback"

        val startup = alloc<STARTUPINFOW>()
        startup.cb = sizeOf<STARTUPINFOW>().toUInt()
        val info = alloc<PROCESS_INFORMATION>()

        val ok = CreateProcessW(
            lpApplicationName = helperPath.wcstr.ptr,
            lpCommandLine = commandLine.wcstr.ptr,
            lpProcessAttributes = null,
            lpThreadAttributes = null,
            bInheritHandles = 0,
            dwCreationFlags = CREATE_NEW_CONSOLE.toUInt(),
            lpEnvironment = null,
            lpCurrentDirectory = null,
            lpStartupInfo = startup.ptr,
            lpProcessInformation = info.ptr,
        )
        if (ok == 0) throw IllegalStateException("CreateProcessW failed: ${GetLastError()}")

        CloseHandle(info.hThread)
        info.hProcess ?: throw IllegalStateException("CreateProcessW returned no process handle")
    }

    // -----------------------------------------------------------------------
    // 两个方向的循环
    // -----------------------------------------------------------------------

    private suspend fun outputLoop() {
        if (!connect(outPipe)) {
            fail("out-connect-failed")
            return
        }
        while (!closed && currentCoroutineContext().isActive) {
            val control = controls.tryReceive().getOrNull()
            if (control != null) {
                if (!writeAll(outPipe, control)) {
                    fail("write-failed")
                    return
                }
                continue
            }

            val batch = queue.nextBatch()
            if (batch == null) {
                delay(20)
                continue
            }
            val frame = ConsoleFrame.encode(ConsoleFrame.OUT, batch.encodeToByteArray())
            if (!writeAll(outPipe, frame)) {
                fail("write-failed")
                return
            }
        }
    }

    private suspend fun inputLoop() {
        if (!connect(inPipe)) {
            fail("in-connect-failed")
            return
        }
        val header = ByteArray(ConsoleFrame.HEADER_SIZE)
        while (!closed && currentCoroutineContext().isActive) {
            if (!readExact(inPipe, header, 0, header.size)) {
                fail("eof")
                return
            }
            if (!ConsoleFrame.magicOf(header)) {
                fail("bad-magic")
                return
            }
            if (ConsoleFrame.versionOf(header) != ConsoleFrame.VERSION) {
                fail("bad-version")
                return
            }
            val length = ConsoleFrame.lengthOf(header)
            if (length < 0 || length > ConsoleFrame.MAX_PAYLOAD) {
                fail("bad-length")
                return
            }
            val payload = ByteArray(length)
            if (length > 0 && !readExact(inPipe, payload, 0, length)) {
                fail("eof")
                return
            }
            when (ConsoleFrame.typeOf(header)) {
                ConsoleFrame.HELLO -> {
                    val cols = if (payload.size >= 2) shortOf(payload, 0) else 0
                    val rows = if (payload.size >= 4) shortOf(payload, 2) else 0
                    deliver(BridgeEvent.Hello(cols, rows))
                }

                ConsoleFrame.IN -> deliver(BridgeEvent.Input(payload.decodeToString()))

                ConsoleFrame.RESIZE -> {
                    val cols = if (payload.size >= 2) shortOf(payload, 0) else 0
                    val rows = if (payload.size >= 4) shortOf(payload, 2) else 0
                    deliver(BridgeEvent.Resize(cols, rows))
                }

                ConsoleFrame.CLOSE -> {
                    fail("helper-close")
                    return
                }

                else -> Unit
            }
        }
    }

    private fun fail(reason: String) {
        closed = true
        deliver(BridgeEvent.Closed(reason))
    }

    private fun deliver(event: BridgeEvent) {
        runCatching { events.trySend(event) }
    }

    // -----------------------------------------------------------------------
    // Win32 小工具
    // -----------------------------------------------------------------------

    private fun connect(handle: HANDLE): Boolean {
        if (ConnectNamedPipe(handle, null) != 0) return true
        return GetLastError() == ERROR_PIPE_CONNECTED.toUInt()
    }

    private fun readExact(handle: HANDLE, buffer: ByteArray, offset: Int, length: Int): Boolean {
        var done = 0
        while (done < length) {
            val read = memScoped {
                val count = alloc<DWORDVar>()
                val ok = buffer.usePinned { pinned ->
                    ReadFile(
                        hFile = handle,
                        lpBuffer = pinned.addressOf(offset + done),
                        nNumberOfBytesToRead = (length - done).toUInt(),
                        lpNumberOfBytesRead = count.ptr,
                        lpOverlapped = null,
                    )
                }
                if (ok == 0) return false
                count.value.toInt()
            }
            if (read <= 0) return false
            done += read
        }
        return true
    }

    private fun writeAll(handle: HANDLE, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        var done = 0
        while (done < bytes.size) {
            val written = memScoped {
                val count = alloc<DWORDVar>()
                val ok = bytes.usePinned { pinned ->
                    WriteFile(
                        hFile = handle,
                        lpBuffer = pinned.addressOf(done),
                        nNumberOfBytesToWrite = (bytes.size - done).toUInt(),
                        lpNumberOfBytesWritten = count.ptr,
                        lpOverlapped = null,
                    )
                }
                if (ok == 0) return false
                count.value.toInt()
            }
            if (written <= 0) return false
            done += written
        }
        return true
    }

    private fun shortOf(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}

/** 同一进程内的管道序号；拼上 pid 后保证不同实例之间不冲突。 */
private var pipeSequence = 0

private fun nextPipeId(): String {
    pipeSequence += 1
    return "${GetCurrentProcessId()}-$pipeSequence"
}
