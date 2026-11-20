package org.krost.unidrive.io

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * The Win32 file identity (volume serial number plus file index, `BY_HANDLE_FILE_INFORMATION`): the pair that
 * plays the inode number's role on Windows, where the JDK's `BasicFileAttributes.fileKey()` answers null. The
 * dehydrate's staleness refusal compares it beside (mtime, size), so a fresh copy that happens to share the
 * vetted pair is still recognized as another file. Null when the file cannot be opened or read.
 */
object WindowsFileIdentity {
    private const val FILE_READ_ATTRIBUTES = 0x80
    private const val FILE_SHARE_READ_WRITE_DELETE = 0x7
    private const val OPEN_EXISTING = 3

    // BY_HANDLE_FILE_INFORMATION: ten DWORD-aligned members (attributes, three FILETIMEs, volume serial, size
    // high/low, link count, file index high/low) = 52 bytes; the identity sits at 28, 44 and 48.
    private const val INFO_BYTES = 52
    private const val VOLUME_SERIAL_OFFSET = 28L
    private const val FILE_INDEX_HIGH_OFFSET = 44L
    private const val FILE_INDEX_LOW_OFFSET = 48L

    private val INT = ValueLayout.JAVA_INT
    private val PTR = ValueLayout.ADDRESS
    private val linker by lazy { Linker.nativeLinker() }
    private val kernel32 by lazy { SymbolLookup.libraryLookup("kernel32", Arena.global()) }

    private fun function(
        name: String,
        descriptor: FunctionDescriptor,
    ): MethodHandle = linker.downcallHandle(kernel32.find(name).orElseThrow { IOException("$name not found") }, descriptor)

    private val createFile by lazy { function("CreateFileW", FunctionDescriptor.of(PTR, PTR, INT, INT, PTR, INT, INT, PTR)) }
    private val getFileInformationByHandle by lazy { function("GetFileInformationByHandle", FunctionDescriptor.of(INT, PTR, PTR)) }
    private val closeHandle by lazy { function("CloseHandle", FunctionDescriptor.of(INT, PTR)) }

    /** The identity of the file at [path], or null when it cannot be opened (missing, unreadable) or Windows does not say. */
    fun of(path: Path): WindowsFileId? =
        runCatching {
            Arena.ofConfined().use { arena ->
                val name = arena.allocateFrom(WindowsSecurity.win32Path(path), StandardCharsets.UTF_16LE)
                val handle =
                    createFile.invoke(
                        name,
                        FILE_READ_ATTRIBUTES,
                        FILE_SHARE_READ_WRITE_DELETE,
                        MemorySegment.NULL,
                        OPEN_EXISTING,
                        0,
                        MemorySegment.NULL,
                    ) as MemorySegment
                if (handle.address() == -1L || handle.address() == 0L) return@use null
                try {
                    val info = arena.allocate(INFO_BYTES.toLong())
                    val ok = getFileInformationByHandle.invoke(handle, info) as Int
                    if (ok == 0) {
                        null
                    } else {
                        WindowsFileId(
                            info.get(INT, VOLUME_SERIAL_OFFSET),
                            info.get(INT, FILE_INDEX_HIGH_OFFSET),
                            info.get(INT, FILE_INDEX_LOW_OFFSET),
                        )
                    }
                } finally {
                    closeHandle.invoke(handle)
                }
            }
        }.getOrNull()
}

/** The [WindowsFileIdentity] answer: value equality over the triple, so two views of one file compare equal. */
data class WindowsFileId(
    val volumeSerial: Int,
    val fileIndexHigh: Int,
    val fileIndexLow: Int,
)
