package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant

data class SubscriptionInfo(
    val subscriptionId: String,
    val expiresAt: Instant,
)

/**
 * Persists webhook subscription state per profile in SQLite.
 * Uses the same DB file as [StateDatabase] (separate table).
 */
class SubscriptionStore(
    private val dbPath: Path,
    private val connector: StateDatabaseConnector = JdbcStateDatabaseConnector,
) {
    private lateinit var conn: Connection

    @Synchronized
    fun initialize() {
        if (::conn.isInitialized && !conn.isClosed) conn.close()
        Files.createDirectories(dbPath.parent)
        // The shared open: this is a second connection to the file StateDatabase keeps in WAL, so it takes the same busy
        // timeout and must not leave the file in another journal mode.
        conn = SqliteConnections.open(connector, dbPath, SETTINGS, log)
        conn.createStatement().use { stmt ->
            stmt.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS webhook_subscriptions (
                    profile     TEXT PRIMARY KEY,
                    sub_id      TEXT NOT NULL,
                    expires_at  TEXT NOT NULL
                )
            """,
            )
        }
        SqliteConnections.restrictSidecars(connector, dbPath, includeMainFile = false, log = log)
    }

    /** The value of a PRAGMA on the connection (a test seam). */
    @Synchronized
    internal fun pragma(name: String): String =
        conn.createStatement().use { st -> st.executeQuery("PRAGMA $name").use { rs -> rs.next(); rs.getString(1) } }

    @Synchronized
    fun close() {
        if (::conn.isInitialized && !conn.isClosed) conn.close()
    }

    @Synchronized
    fun save(
        profileName: String,
        subscriptionId: String,
        expiresAt: Instant,
    ) {
        conn
            .prepareStatement(
                "INSERT OR REPLACE INTO webhook_subscriptions (profile, sub_id, expires_at) VALUES (?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, profileName)
                stmt.setString(2, subscriptionId)
                stmt.setString(3, expiresAt.toString())
                stmt.executeUpdate()
            }
    }

    @Synchronized
    fun get(profileName: String): SubscriptionInfo? {
        conn.prepareStatement("SELECT sub_id, expires_at FROM webhook_subscriptions WHERE profile = ?").use { stmt ->
            stmt.setString(1, profileName)
            val rs = stmt.executeQuery()
            return if (rs.next()) {
                SubscriptionInfo(
                    subscriptionId = rs.getString("sub_id"),
                    expiresAt = Instant.parse(rs.getString("expires_at")),
                )
            } else {
                null
            }
        }
    }

    @Synchronized
    fun delete(profileName: String) {
        conn.prepareStatement("DELETE FROM webhook_subscriptions WHERE profile = ?").use { stmt ->
            stmt.setString(1, profileName)
            stmt.executeUpdate()
        }
    }

    private companion object {
        val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(SubscriptionStore::class.java)
        val SETTINGS = SqliteConnections.Settings()
    }
}
