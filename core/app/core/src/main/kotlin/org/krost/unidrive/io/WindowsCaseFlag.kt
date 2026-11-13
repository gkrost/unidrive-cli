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
 * The per-directory case-sensitivity flag of Windows (`FILE_CASE_SENSITIVE_INFO`, the one
 * `fsutil file setCaseSensitiveInfo` sets), read with `GetFileInformationByHandleEx`. It answers for the directory
 * itself, empty or not, and writes nothing.
 */
internal object WindowsCaseFlag {
    private const val FILE_READ_ATTRIBUTES = 0x80
    private const val FILE_SHARE_READ_WRITE_DELETE = 0x7
    private const val OPEN_EXISTING = 3
    private const val FILE_FLAG_BACKUP_SEMANTICS = 0x02000000 // needed to open a directory
    private const val FILE_CASE_SENSITIVE_INFO_CLASS = 23
    private const val FILE_CS_FLAG_CASE_SENSITIVE_DIR = 0x1
    private const val FLAGS_BYTES = 4

    private val INT = ValueLayout.JAVA_INT
    private val PTR = ValueLayout.ADDRESS
    private val linker by lazy { Linker.nativeLinker() }
    private val kernel32 by lazy { SymbolLookup.libraryLookup("kernel32", Arena.global()) }

    private fun function(
        name: String,
        descriptor: FunctionDescriptor,
    ): MethodHandle = linker.downcallHandle(kernel32.find(name).orElseThrow { IOException("$name not found") }, descriptor)

    private val createFile by lazy { function("CreateFileW", FunctionDescriptor.of(PTR, PTR, INT, INT, PTR, INT, INT, PTR)) }
    private val getFileInformationByHandleEx by lazy {
        function("GetFileInformationByHandleEx", FunctionDescriptor.of(INT, PTR, INT, PTR, INT))
    }
    private val closeHandle by lazy { function("CloseHandle", FunctionDescriptor.of(INT, PTR)) }

    /**
     * True when [dir] is a case-sensitive directory, false when it folds case; null when Windows does not say: the
     * directory cannot be opened, or its file system keeps no such flag (FAT, exFAT, some network shares).
     */
    fun isCaseSensitive(dir: Path): Boolean? =
        Arena.ofConfined().use { arena ->
            val name = arena.allocateFrom(WindowsSecurity.win32Path(dir), StandardCharsets.UTF_16LE)
            val handle =
                createFile.invoke(
                    name,
                    FILE_READ_ATTRIBUTES,
                    FILE_SHARE_READ_WRITE_DELETE,
                    MemorySegment.NULL,
                    OPEN_EXISTING,
                    FILE_FLAG_BACKUP_SEMANTICS,
                    MemorySegment.NULL,
                ) as MemorySegment
            if (handle.address() == -1L || handle.address() == 0L) return@use null
            try {
                val flags = arena.allocate(INT)
                val ok = getFileInformationByHandleEx.invoke(handle, FILE_CASE_SENSITIVE_INFO_CLASS, flags, FLAGS_BYTES) as Int
                if (ok == 0) null else flags.get(INT, 0) and FILE_CS_FLAG_CASE_SENSITIVE_DIR != 0
            } finally {
                closeHandle.invoke(handle)
            }
        }
}
