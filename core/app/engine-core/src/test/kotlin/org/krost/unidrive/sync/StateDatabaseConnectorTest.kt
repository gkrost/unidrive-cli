package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// A platform without the xerial driver (Android) hands StateDatabase its own connections: every open goes through
// the connector, and the database works the same on what it returns.
class StateDatabaseConnectorTest {
    private class RecordingConnector : StateDatabaseConnector {
        val opened = mutableListOf<String>()

        override fun open(path: Path?): Connection {
            opened += "open:${path?.fileName ?: "memory"}"
            return JdbcStateDatabaseConnector.open(path)
        }

        override fun openReadOnly(path: Path): Connection {
            opened += "openReadOnly:${path.fileName}"
            return JdbcStateDatabaseConnector.openReadOnly(path)
        }
    }

    @Test
    fun `a file database opens through the connector`() {
        val dir = Files.createTempDirectory("sdb-connector")
        val connector = RecordingConnector()
        val db = StateDatabase(dir.resolve("state.db"), connector = connector)
        db.initialize()
        try {
            db.setSyncState("probe", "1")
            assertEquals("1", db.getSyncState("probe"))
        } finally {
            db.close()
        }

        val ro = StateDatabase(dir.resolve("state.db"), readOnly = true, connector = connector)
        ro.initialize()
        try {
            assertEquals("1", ro.getSyncState("probe"))
        } finally {
            ro.close()
        }
        assertEquals(listOf("open:state.db", "openReadOnly:state.db"), connector.opened)
    }

    @Test
    fun `an in-memory database asks the connector for no path`() {
        val connector = RecordingConnector()
        val db = StateDatabase(Path.of("unused.db"), inMemory = true, connector = connector)
        db.initialize()
        try {
            db.setSyncState("probe", "2")
            assertNotNull(db.getSyncState("probe"))
        } finally {
            db.close()
        }
        assertEquals(listOf("open:memory"), connector.opened)
    }
}
