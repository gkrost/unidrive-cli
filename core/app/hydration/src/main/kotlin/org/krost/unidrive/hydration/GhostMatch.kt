package org.krost.unidrive.hydration

import kotlinx.coroutines.CancellationException
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.sync.HashVerifier
import org.krost.unidrive.sync.RowHashProof
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * archive#1040 M7 (the owner's decision of 2026-10-28, compare first): whether a cloud item found at the path of a never-uploaded
 * row holds the bytes of that row's cache copy, so that it is the row's own upload that landed without the row learning it (a
 * "ghost") and not another device's file that took the same path ([MountEngine.ghostItemOrNull]).
 *
 * The sizes decide first. Then the content, where both sides have one: the provider's content hash of the cache bytes against
 * the item's hash ([RowHashProof], else [HashVerifier.verify], which skips a value that is not a content hash), or, for a provider
 * without a hash of its own, its proof from the stored object ([CloudProvider.contentMatchesLocal]). A hash or proof that cannot
 * be compared decides nothing; one that differs decides. A row created and never written has no cache copy: its upload was empty.
 */
internal object GhostMatch {
    @Suppress("TooGenericExceptionCaught") // whatever the provider's proof throws, it proved nothing: the size decides
    suspend fun holdsCacheBytes(
        provider: CloudProvider,
        item: CloudItem,
        cache: Path,
        remotePath: String,
        log: Logger,
    ): Boolean {
        // Read in line, as the rename and the unlink move and delete their cache copies.
        val size = if (Files.isRegularFile(cache)) Files.size(cache) else null
        if (item.size != (size ?: 0L)) return false
        if (size == null) return true
        val algorithm = provider.hashAlgorithm()
        if (algorithm != null) {
            val hash = item.hash?.takeIf { it.isNotEmpty() } ?: return true
            return RowHashProof.prove(cache, hash, algorithm)?.same ?: HashVerifier.verify(cache, hash, algorithm)
        }
        val proof =
            try {
                provider.contentMatchesLocal(item.id, remotePath, cache)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The class name only: a message may carry a URL or an id.
                log.debug("the stored object at {} could not be compared with its cache copy: {}", remotePath, e.javaClass.simpleName)
                null
            }
        return proof?.same ?: true
    }
}
