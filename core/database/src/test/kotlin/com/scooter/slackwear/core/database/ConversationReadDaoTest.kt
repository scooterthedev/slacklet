package com.scooter.slackwear.core.database

import com.scooter.slackwear.core.database.dao.ACKNOWLEDGE_READ_SQL
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationReadDaoTest {
    @Test
    fun clearsOnlyThroughAcknowledgedTimestampAndNeverRegresses() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { statement ->
                statement.execute("CREATE TABLE conversations (id TEXT PRIMARY KEY, latestTs TEXT, lastSeenTs TEXT, unreadCount INTEGER, mentionCount INTEGER, unreadConfidence TEXT)")
                statement.execute("INSERT INTO conversations VALUES ('C1', '1700000000.000002', '1700000000.000000', 5, 2, 'EXACT')")
            }
            fun acknowledge(ts: String) {
                db.prepareStatement(ACKNOWLEDGE_READ_SQL).use {
                    it.setString(1, ts)
                    it.setString(2, "C1")
                    it.executeUpdate()
                }
            }
            fun row(): List<String?> = db.createStatement().use { statement ->
                statement.executeQuery("SELECT lastSeenTs, unreadCount, mentionCount, unreadConfidence FROM conversations").use {
                    it.next()
                    (1..4).map(it::getString)
                }
            }
            acknowledge("1700000000.000001")
            assertEquals(listOf("1700000000.000001", "5", "2", "DERIVED"), row())
            acknowledge("1700000000.000000")
            assertEquals(listOf("1700000000.000001", "5", "2", "DERIVED"), row())
            acknowledge("1700000000.000002")
            assertEquals(listOf("1700000000.000002", "0", "0", "DERIVED"), row())
        }
    }
}
