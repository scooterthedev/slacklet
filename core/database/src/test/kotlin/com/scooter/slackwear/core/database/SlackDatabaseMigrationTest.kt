package com.scooter.slackwear.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlackDatabaseMigrationTest {
    @Test
    fun migration5To6AddsOnlyActivityColumnsAndPreservesPendingMessages() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.execute("CREATE TABLE activity (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, conversationName TEXT NOT NULL, authorId TEXT NOT NULL, authorName TEXT NOT NULL, text TEXT NOT NULL, ts TEXT NOT NULL, isRead INTEGER NOT NULL)")
            connection.execute("CREATE TABLE messages (conversationId TEXT NOT NULL, ts TEXT NOT NULL, authorId TEXT NOT NULL, text TEXT NOT NULL, threadTs TEXT, replyCount INTEGER NOT NULL, reactionsJson TEXT NOT NULL, isEdited INTEGER NOT NULL, deliveryState TEXT NOT NULL, PRIMARY KEY(conversationId, ts))")
            connection.execute("CREATE INDEX index_messages_conversationId_ts ON messages (conversationId, ts)")
            connection.execute("CREATE INDEX index_messages_threadTs ON messages (threadTs)")
            connection.execute("INSERT INTO activity VALUES ('C1:10', 'C1', 'general', 'U1', 'Alice', 'unread mention', '10', 0), ('C1:20', 'C1', 'general', 'U2', 'Bob', 'read mention', '20', 1)")
            connection.execute("INSERT INTO messages VALUES ('C1', 'local:1', 'U1', 'unsent message', NULL, 0, '[]', 0, 'PENDING'), ('C1', 'local:2', 'U1', 'unsent reply', '10', 0, '[]', 0, 'PENDING')")
            connection.execute("PRAGMA user_version = 5")
            val activityBefore = connection.rows("SELECT * FROM activity ORDER BY id")
            val messagesBefore = connection.rows("SELECT * FROM messages ORDER BY ts")
            val messageSchemaBefore = connection.rows("SELECT type, name, sql FROM sqlite_master WHERE tbl_name = 'messages' ORDER BY name")
            val activityColumnsBefore = connection.rows("PRAGMA table_info(activity)")
            val statements = mutableListOf<String>()
            val database = Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
            ) { _, method, args ->
                check(method.name == "execSQL" && args?.size == 1)
                val sql = args[0] as String
                statements += sql
                connection.execute(sql)
                null
            } as SupportSQLiteDatabase

            val migration = SlackDatabase.MIGRATION_5_6
            assertEquals(5, migration.startVersion)
            assertEquals(6, migration.endVersion)
            connection.autoCommit = false
            migration.migrate(database)
            connection.commit()

            assertEquals(5, statements.size)
            assertTrue(statements.all { it.startsWith("ALTER TABLE activity ADD COLUMN ") })
            assertEquals(activityBefore, connection.rows("SELECT id, conversationId, conversationName, authorId, authorName, text, ts, isRead FROM activity ORDER BY id"))
            assertEquals(messagesBefore, connection.rows("SELECT * FROM messages ORDER BY ts"))
            assertEquals(messageSchemaBefore, connection.rows("SELECT type, name, sql FROM sqlite_master WHERE tbl_name = 'messages' ORDER BY name"))
            val columns = connection.rows("PRAGMA table_info(activity)")
            assertEquals(activityColumnsBefore, columns.take(8))
            assertEquals(
                listOf(
                    listOf("8", "entryType", "TEXT", "1", "''", "0"),
                    listOf("9", "messageTs", "TEXT", "0", null, "0"),
                    listOf("10", "threadTs", "TEXT", "0", null, "0"),
                    listOf("11", "entryKey", "TEXT", "0", null, "0"),
                    listOf("12", "unreadCount", "INTEGER", "1", "1", "0"),
                ),
                columns.drop(8),
            )
            assertEquals(
                List(2) { listOf("", null, null, null, "1") },
                connection.rows("SELECT entryType, messageTs, threadTs, entryKey, unreadCount FROM activity ORDER BY id"),
            )
            connection.execute("INSERT INTO activity (id, conversationId, conversationName, authorId, authorName, text, ts, isRead) VALUES ('C2:30', 'C2', 'random', 'U1', 'Alice', 'new mention', '30', 0)")
            assertEquals(
                listOf(listOf("", null, null, null, "1")),
                connection.rows("SELECT entryType, messageTs, threadTs, entryKey, unreadCount FROM activity WHERE id = 'C2:30'"),
            )
            connection.execute("UPDATE activity SET entryType = 'thread_v2', messageTs = '20', threadTs = '10', entryKey = 'K', unreadCount = 3 WHERE id = 'C1:20'")
            assertEquals(
                listOf(listOf("thread_v2", "20", "10", "K", "3")),
                connection.rows("SELECT entryType, messageTs, threadTs, entryKey, unreadCount FROM activity WHERE id = 'C1:20'"),
            )
        }
    }

    private fun Connection.execute(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.rows(sql: String): List<List<String?>> =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                buildList {
                    while (result.next()) {
                        add((1..result.metaData.columnCount).map { result.getString(it) })
                    }
                }
            }
        }
}
