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
 *
 * gkrost/unidrive-cli#580: an upload whose create the provider refused (the name is taken) settles as done only on a proof, so it asks
 * with `strict`: a hash or proof that cannot be compared, or an item with no hash, is then "not the same". The ghost callers (rename,
 * unlink) keep the lenient answer, where a wrong "same" costs a cloud-side delete of a same-sized file only after the size matched.
 */
internal object GhostMatch {
    @Suppress("TooGenericExceptionCaught") // whatever the provider's proof throws, it proved nothing: the size decides
    suspend fun holdsCacheBytes(
        provider: CloudProvider,
        item: CloudItem,
        cache: Path,
        remotePath: String,
        log: Logger,
        strict: Boolean = false,
    ): Boolean {
        // Read in line, as the rename and the unlink move and delete their cache copies.
        val size = if (Files.isRegularFile(cache)) Files.size(cache) else null
        if (item.size != (size ?: 0L)) return false
        if (size == null) return true
        val algorithm = provider.hashAlgorithm()
        if (algorithm != null) {
            val hash = item.hash?.takeIf { it.isNotEmpty() }
            if (hash != null) {
                return RowHashProof.prove(cache, hash, algorithm)?.same
                    ?: if (strict) HashVerifier.compare(cache, hash, algorithm) == true else HashVerifier.verify(cache, hash, algorithm)
            }
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
        if (proof?.same == true && item.version != null) {
            // A provider may prove a later object than the one the refusal found. Do not adopt across that change.
            val now = try {
                provider.getMetadata(remotePath)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return false
            }
            if (now.id != item.id || now.version != item.version || now.size != item.size) return false
        }
        return proof?.same ?: !strict
    }
}
