package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.http.Priority
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.RemoteObjectAuditReport
import org.krost.unidrive.RemoteObjectStatus
import org.krost.unidrive.authenticateAndLog
import org.krost.unidrive.engine.BackupsNamespace
import org.krost.unidrive.engine.CachePaths
import org.krost.unidrive.engine.PlacedNamespace
import org.krost.unidrive.engine.TrashNamespace
import org.krost.unidrive.engine.ViewNamespaces
import org.krost.unidrive.io.OwnerOnlyAtomicWrite
import org.krost.unidrive.io.engineKey
import org.krost.unidrive.sync.HashVerifier
import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.Reconciler
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.SyncScope
import org.krost.unidrive.sync.model.SyncEntry
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Callable

/**
 * Independent convergence audit across the three sources of truth: the local
 * sync_root walk, the `state.db` rows, and a live provider listing. `status`
 * trusts `state.db`; this command exists precisely because the DB can lie, so
 * none of the three views is trusted over the others — each divergence class
 * in [VerifyReport] is one source contradicting another. Strictly read-only:
 * no row is written, no remote call mutates anything.
 *
 * Exit codes are the machine contract for soak crons: 0 converged, 1
 * divergence found, 2 could not audit (missing state.db / sync root,
 * authentication failure, provider listing error).
 */
@Command(
    name = "verify",
    description = [
        "Audit convergence of the local sync_root, state.db, and a live remote listing (report-only).",
        "A mount profile has no sync_root: state.db is audited against the live listing and the hydration cache.",
        "Exit codes: 0 converged, 1 divergence found, 2 could not audit.",
    ],
    mixinStandardHelpOptions = true,
)
class VerifyCommand : Callable<Int> {
    @ParentCommand
    lateinit var parent: Main

    @Option(
        names = ["--deep"],
        description = [
            "Also hash local content with the provider's algorithm and compare against the remote hash " +
                "(skipped for providers without a verifiable hash)",
        ],
    )
    var deep: Boolean = false

    @Option(
        names = ["--sync-path"],
        description = [
            "Audit only a subtree, e.g. --sync-path /Documents. Repeatable. The remote walk starts " +
                "inside the scope instead of listing the whole drive (#532: 170 folder listings in 150 s). " +
                "Default: the profile's sync_path, when it has one.",
        ],
    )
    var syncPaths: MutableList<String> = mutableListOf()

    @Option(
        names = ["--remote-objects"],
        description = ["Audit provider-side object metadata and shard availability without downloading content (currently Internxt)."],
    )
    var remoteObjects: Boolean = false

    @Option(
        names = ["--json-report"],
        paramLabel = "FILE",
        description = ["Write the --remote-objects audit as an atomic JSON report to this path."],
    )
    var jsonReport: Path? = null

    override fun call(): Int {
        val profile = parent.resolveCurrentProfile()
        if (remoteObjects) return auditRemoteObjects(profile.name, jsonReport)
        if (jsonReport != null) {
            System.err.println("verify: --json-report requires --remote-objects")
            return EXIT_UNAUDITABLE
        }
        val configDir = parent.configBaseDir().resolve(profile.name)
        val dbPath = configDir.resolve("state.db")
        if (!Files.exists(dbPath)) {
            System.err.println("verify: no state.db for profile '${profile.name}' — run a sync first, then audit it.")
            return EXIT_UNAUDITABLE
        }
        // The same exclude set the engine syncs with: anything the engine would
        // never transfer (sidecars, OS junk, user patterns) must not count as
        // divergence in any of the three views.
        val config = parent.loadSyncConfig()
        val excludes = config.effectiveExcludePatterns(profile.name)
        // archive#532: all three views must be cut to the same boundary, or the audit reports
        // out-of-scope rows as db-only/remote-only divergences instead of ignoring them.
        // The boundary is the one sync works within: --sync-path, else the profile's sync_path (archive#1083).
        val scope = effectiveScope(config.syncPaths)
        // #825: a mount profile has no sync_root; its audit holds state.db against the live listing alone.
        val mount = profile.mode == ProfileMode.MOUNT
        val syncRoot = profile.syncRoot
        if (!mount && !Files.isDirectory(syncRoot)) {
            System.err.println("verify: sync root '$syncRoot' does not exist — cannot audit.")
            return EXIT_UNAUDITABLE
        }
        if (scope.isNotEmpty()) {
            val origin = if (syncPaths.isNotEmpty()) "--sync-path" else "the profile's sync_path"
            println("verify: audit limited to ${scope.joinToString(", ")} ($origin)")
        }
        if (mount) return auditMount(profile.name, dbPath, excludes, scope)

        // Read-only snapshot of the rows; closed before any other source is walked. The open itself is
        // read-only (#100): the existence check above keeps a missing state.db from being created, and
        // a schema stamp or migration of an existing one is not an audit's business.
        val db = StateDatabase(dbPath, readOnly = true)
        db.initialize()
        val dbEntries =
            try {
                db.getAllEntries().filter { scope.isEmpty() || SyncScope.contains(it.path, scope) }
            } finally {
                db.close()
            }

        val provider = parent.createProvider()
        val algorithm = if (deep) provider.hashAlgorithm() else null
        val remoteFiles = listRemoteOrReport(provider, excludes, scope) ?: return EXIT_UNAUDITABLE
        if (deep && algorithm == null) {
            System.err.println(
                "verify: provider '${provider.displayName}' has no verifiable hash algorithm — " +
                    "--deep degrades to the size comparison.",
            )
        }

        val report =
            VerifyAudit.audit(
                localFiles =
                    walkLocalFiles(syncRoot, excludes).filterKeys { path ->
                        scope.isEmpty() || SyncScope.contains(path, scope)
                    },
                dbEntries = dbEntries.filterNot { isExcluded(it.path, excludes) },
                remoteFiles = remoteFiles.mapValues { it.value.size },
                hashMismatch = { path ->
                    val remoteHash = remoteFiles[path]?.hash
                    algorithm != null &&
                        !remoteHash.isNullOrEmpty() &&
                        !HashVerifier.verify(syncRoot.resolve(path.removePrefix("/")), remoteHash, algorithm)
                },
            )
        println(VerifyAudit.formatReport(profile.name, report))
        return if (report.converged) EXIT_CONVERGED else EXIT_DIVERGED
    }

    /**
     * The live remote listing within [scope], or null after the reason is printed (authentication or listing failure).
     * Closes [provider] either way.
     */
    private fun listRemoteOrReport(
        provider: CloudProvider,
        excludes: List<String>,
        scope: List<String>,
    ): Map<String, CloudItem>? =
        try {
            runBlocking(Priority.Foreground) {
                provider.authenticateAndLog()
                listRemoteFiles(provider, excludes, scope)
            }
        } catch (e: AuthenticationException) {
            System.err.print(
                parent.renderAuthError(
                    e,
                    provider.id,
                    provider.displayName,
                    parent.verbose,
                    runCatching { parent.resolveCurrentProfile().name }.getOrNull(),
                ),
            )
            null
        } catch (e: Exception) {
            System.err.println("verify: provider listing failed: ${e.message ?: e.javaClass.simpleName}")
            null
        } finally {
            provider.close()
        }

    /**
     * #825: the mount-mode audit. No local walk (a mount profile has no sync_root): the state.db rows against the live
     * remote listing, and each hydrated row against its hydration cache copy. Read-only like the three-way audit: the
     * database is opened read-only and closed before the provider is asked, the cache is only stat'ed.
     */
    private fun auditMount(
        profileName: String,
        dbPath: Path,
        excludes: List<String>,
        scope: List<String>,
    ): Int {
        if (deep) System.err.println("verify: --deep has no local content to hash on a mount profile; comparing sizes and etags.")
        val db = StateDatabase(dbPath, readOnly = true)
        db.initialize()
        // The trash and backups folders are rows under a root the daemon places (found by its stable key, whatever it is
        // called); the views are never rows, but a key of theirs is skipped all the same.
        val virtualRoots = listOf(TrashNamespace(), BackupsNamespace())
        val dbEntries =
            try {
                virtualRoots.forEach { it.refresh(db) }
                db.getAllEntries().filter { (scope.isEmpty() || SyncScope.contains(it.path, scope)) && !isExcluded(it.path, excludes) }
            } finally {
                db.close()
            }
        val isVirtual = { row: SyncEntry ->
            virtualRoots.any { it.contains(row.path) } ||
                row.remoteId?.let { PlacedNamespace.isAnyOwnKey(it) || it.startsWith(ViewNamespaces.ROOT_KEY_PREFIX) } == true
        }
        val remoteFiles = listRemoteOrReport(parent.createProvider(), excludes, scope) ?: return EXIT_UNAUDITABLE
        val cacheDir = SyncEngine.hydrationCacheRoot(cacheRoot(), profileName)
        // One pass over the cache folder for every row: the folders the rows share are probed once, not once per
        // row (#585 measured the real-path walk of a per-row resolve at ~8 ms a file; CachePaths.Pass exists for
        // passes over rows). A row the pass cannot answer throws — a copy that is not there — and reads as null.
        val cachePass = CachePaths.Pass(cacheDir)
        val report =
            VerifyAudit.auditMount(
                dbEntries = dbEntries,
                remoteFiles = remoteFiles,
                isVirtual = isVirtual,
                cacheCopySize = { row -> runCatching { cachePass.sizeOf(row.path) }.getOrNull() },
            )
        println(VerifyAudit.formatMountReport(profileName, report))
        return if (report.converged) EXIT_CONVERGED else EXIT_DIVERGED
    }

    /** The audit's boundary: the `--sync-path` given on the command line, else the profile's own `sync_path`. */
    private fun effectiveScope(configured: List<String>): List<String> =
        SyncScope.normalize(SyncCommand.resolveSyncPaths(syncPaths, configured))

    private fun auditRemoteObjects(
        profileName: String,
        reportPath: Path?,
    ): Int {
        if (reportPath == null) {
            System.err.println("verify: --remote-objects requires --json-report FILE so the audit is preserved")
            return EXIT_UNAUDITABLE
        }
        // A drive-long audit must not end at "could not write the report": prove the destination usable first.
        try {
            checkReportWritable(reportPath)
        } catch (e: Exception) {
            System.err.println("verify: cannot write the JSON report: ${e.message ?: e.javaClass.simpleName}")
            return EXIT_UNAUDITABLE
        }
        val config = parent.loadSyncConfig()
        val excludes = config.effectiveExcludePatterns(profileName)
        val scope = effectiveScope(config.syncPaths)
        val provider = parent.createProvider()
        val auditor = provider.remoteObjectAuditor
        if (auditor == null) {
            provider.close()
            System.err.println("verify: provider '${provider.id}' does not support read-only remote object audits")
            return EXIT_UNAUDITABLE
        }
        val report =
            try {
                runBlocking(Priority.Foreground) {
                    provider.authenticateAndLog()
                    val remoteFiles = listRemoteFiles(provider, excludes, scope).values.sortedBy { it.path }
                    auditor.auditRemoteObjects(remoteFiles)
                }
            } catch (e: AuthenticationException) {
                System.err.print(
                    parent.renderAuthError(
                        e,
                        provider.id,
                        provider.displayName,
                        parent.verbose,
                        profileName,
                    ),
                )
                return EXIT_UNAUDITABLE
            } catch (e: Exception) {
                System.err.println("verify: remote object audit failed: ${e.message ?: e.javaClass.simpleName}")
                return EXIT_UNAUDITABLE
            } finally {
                provider.close()
            }
        try {
            writeRemoteAuditReport(reportPath, profileName, provider.id, SyncScope.normalize(syncPaths), report)
        } catch (e: Exception) {
            System.err.println("verify: could not write JSON report: ${e.message ?: e.javaClass.simpleName}")
            return EXIT_UNAUDITABLE
        }
        val findings = report.objects.filter { it.status != RemoteObjectStatus.HEALTHY }
        println("Remote objects checked: ${report.checkedCount}")
        println("Findings:              ${findings.size}")
        println("JSON report:           ${reportPath.toAbsolutePath().normalize()}")
        for (finding in findings) {
            println("${finding.status.name.lowercase()} ${finding.path} drive=${finding.driveSize} bridge=${finding.bridgeSize} shards=${finding.shardBytes}")
        }
        return when {
            findings.any { it.status == RemoteObjectStatus.UNVERIFIED } -> EXIT_UNAUDITABLE
            findings.isNotEmpty() -> EXIT_DIVERGED
            else -> EXIT_CONVERGED
        }
    }

    /** Fails now, with the reason, when [reportPath] cannot take a report; leaves nothing behind but the folder. */
    private fun checkReportWritable(reportPath: Path) {
        val destination = reportPath.toAbsolutePath().normalize()
        if (Files.isDirectory(destination)) throw IOException("$destination is a directory")
        val parentDir = destination.parent ?: throw IOException("report path has no parent directory")
        Files.createDirectories(parentDir)
        Files.deleteIfExists(Files.createTempFile(parentDir, REPORT_TEMP_PREFIX, REPORT_TEMP_SUFFIX))
    }

    internal fun writeRemoteAuditReport(
        reportPath: Path,
        profileName: String,
        providerId: String,
        scope: List<String>,
        report: RemoteObjectAuditReport,
    ) {
        val destination = reportPath.toAbsolutePath().normalize()
        val parentDir = destination.parent ?: throw IOException("report path has no parent directory")
        Files.createDirectories(parentDir)
        val json = Json { prettyPrint = true }
        val body =
            buildJsonObject {
                put("schemaVersion", 1)
                put("profile", profileName)
                put("provider", providerId)
                putJsonArray("scope") {
                    (if (scope.isEmpty()) listOf("/") else scope).forEach { add(it) }
                }
                put("checkedCount", report.checkedCount)
                putJsonObject("summary") {
                    RemoteObjectStatus.entries.forEach { status ->
                        put(status.name.lowercase(), report.objects.count { it.status == status })
                    }
                }
                putJsonArray("objects") {
                    report.objects.forEach { item ->
                        add(
                            buildJsonObject {
                                put("path", item.path)
                                put("status", item.status.name.lowercase())
                                put("driveSize", item.driveSize)
                                item.bridgeSize?.let { put("bridgeSize", it) }
                                item.shardCount?.let { put("shardCount", it) }
                                item.availableShardCount?.let { put("availableShardCount", it) }
                                item.shardBytes?.let { put("shardBytes", it) }
                                item.detail?.let { put("detail", it) }
                            },
                        )
                    }
                }
            }
        // The report lists remote paths and sizes, which the engine keeps owner-only everywhere else
        // (StoragePermissions), so it is restricted before it holds anything. This is the one writer that opts into the
        // warning instead of the refusal (OwnerOnlyAtomicWrite): the destination is a path the user named, possibly on a
        // volume with neither POSIX modes nor ACLs, and the audit that produced the report has already run.
        OwnerOnlyAtomicWrite.replace(
            destination,
            json.encodeToString(JsonObject.serializer(), body).toByteArray(Charsets.UTF_8),
            onUnrestricted = { System.err.println("verify: warning: could not restrict the report to the current user ($it)") },
        )
    }

    private fun isExcluded(
        path: String,
        excludes: List<String>,
    ): Boolean = excludes.any { Reconciler.matchesGlob(path, it) }

    /**
     * Full remote enumeration via the provider's list API — a breadth-first
     * walk over [CloudProvider.listChildren], one call per
     * folder (each call returns that folder's complete child list; providers
     * paginate internally). Deliberately NOT the delta feed: the audit wants
     * the provider's *current* answer, independent of any cursor state the
     * engine maintains.
     *
     * With a non-empty [scope] the walk starts at the scope roots instead of
     * the drive root, so the call count follows the audited subtree rather
     * than the whole drive (archive#532).
     */
    private suspend fun listRemoteFiles(
        provider: CloudProvider,
        excludes: List<String>,
        scope: List<String>,
    ): Map<String, CloudItem> {
        val files = mutableMapOf<String, CloudItem>()
        val pending = ArrayDeque<String>()
        if (scope.isEmpty()) pending.add("/") else scope.forEach { pending.add(it) }
        while (pending.isNotEmpty()) {
            val dir = pending.removeFirst()
            for (item in provider.listChildren(dir)) {
                val path = PathNormalizer.nfc(item.path)
                if (isExcluded(path, excludes)) continue
                if (item.isFolder) pending.add(item.path) else files[path] = item
            }
        }
        return files
    }

    /** Files-only walk keyed like state.db rows: NFC, '/'-separated, leading slash. */
    private fun walkLocalFiles(
        syncRoot: Path,
        excludes: List<String>,
    ): Map<String, Long> {
        val files = mutableMapOf<String, Long>()
        Files.walkFileTree(
            syncRoot,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    val relativePath = PathNormalizer.nfc(engineKey(syncRoot.relativize(file)))
                    if (!isExcluded(relativePath, excludes)) {
                        files[relativePath] = attrs.size()
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir == syncRoot) return FileVisitResult.CONTINUE
                    val relativePath = PathNormalizer.nfc(engineKey(syncRoot.relativize(dir)))
                    return if (isExcluded(relativePath, excludes)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

                // Unreadable entries must not abort the audit; the path simply
                // isn't in the local view (same stance as LocalScanner UD-736).
                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        return files
    }

    companion object {
        const val EXIT_CONVERGED = 0
        const val EXIT_DIVERGED = 1
        const val EXIT_UNAUDITABLE = 2

        /** Where the hydration caches live; a test points it at its own folder. */
        internal var cacheRoot: () -> Path = { SyncEngine.defaultHydrationCacheRoot() }
        private const val REPORT_TEMP_PREFIX = ".unidrive-verify-"
        private const val REPORT_TEMP_SUFFIX = ".json.tmp"
    }
}
