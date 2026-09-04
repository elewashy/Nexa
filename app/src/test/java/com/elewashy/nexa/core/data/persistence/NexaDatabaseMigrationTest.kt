package com.elewashy.nexa.core.data.persistence

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the migration chain: a database created at version 1 (history only)
 * must upgrade through [NexaDatabase.MIGRATION_1_2],
 * [NexaDatabase.MIGRATION_2_3], [NexaDatabase.MIGRATION_3_4], and
 * [NexaDatabase.MIGRATION_4_5], [NexaDatabase.MIGRATION_5_6],
 * [NexaDatabase.MIGRATION_6_7], and [NexaDatabase.MIGRATION_7_8] with every
 * row intact; a version 2 database must upgrade to 8.
 */
@RunWith(RobolectricTestRunner::class)
class NexaDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `migration 1 to 8 preserves history and adds download tab and bookmark tables`() {
        val dbName = "migration-1-5-test.db"

        // Build a genuine version-1 database (Phase 1 schema) outside Room.
        context.deleteDatabase(dbName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { v1 ->
            v1.execSQL(
                "CREATE TABLE IF NOT EXISTS `history` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`url` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`visitedAt` INTEGER NOT NULL)"
            )
            v1.execSQL("CREATE INDEX IF NOT EXISTS `index_history_visitedAt` ON `history` (`visitedAt`)")
            v1.execSQL("CREATE INDEX IF NOT EXISTS `index_history_url` ON `history` (`url`)")
            // Empty download_meta is expected by Phase 2 migrations.
            v1.execSQL(
                "CREATE TABLE IF NOT EXISTS `download_meta` (" +
                    "`id` INTEGER NOT NULL, " +
                    "`last_id` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
            v1.execSQL(
                "INSERT INTO history (url, title, visitedAt) " +
                    "VALUES ('https://kept.example/', 'Kept', 123)"
            )
            v1.version = 1
        }

        // Open through Room with the full production migration chain.
        val db = Room.databaseBuilder(context, NexaDatabase::class.java, dbName)
            .addMigrations(
                NexaDatabase.MIGRATION_1_2,
                NexaDatabase.MIGRATION_2_3,
                NexaDatabase.MIGRATION_3_4,
                NexaDatabase.MIGRATION_4_5,
                NexaDatabase.MIGRATION_5_6,
                NexaDatabase.MIGRATION_6_7,
                NexaDatabase.MIGRATION_7_8,
            )
            .allowMainThreadQueries()
            .build()

        try {
            val sqliteDb = db.openHelper.readableDatabase

            // History survived unchanged.
            sqliteDb.query("SELECT url, title, visitedAt FROM history").use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals("https://kept.example/", cursor.getString(0))
                assertEquals("Kept", cursor.getString(1))
                assertEquals(123L, cursor.getLong(2))
            }

            assertDownloadTablesPresent(sqliteDb)
            assertPhase3TablesPresent(sqliteDb)
            assertMetaV4(sqliteDb, expectedLastId = 0L, expectedImported = 0)

            // Version advanced to the end of the chain.
            sqliteDb.query("PRAGMA user_version").use { cursor ->
                cursor.moveToFirst()
                assertEquals(8, cursor.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 2 to 8 preserves history and all download data`() {
        val dbName = "migration-2-4-test.db"

        // Build a genuine version-2 database (Phase 2 schema) outside Room.
        context.deleteDatabase(dbName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { v2 ->
            v2.execSQL(
                "CREATE TABLE IF NOT EXISTS `history` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`url` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`visitedAt` INTEGER NOT NULL)"
            )
            v2.execSQL("CREATE INDEX IF NOT EXISTS `index_history_visitedAt` ON `history` (`visitedAt`)")
            v2.execSQL("CREATE INDEX IF NOT EXISTS `index_history_url` ON `history` (`url`)")
            v2.execSQL(
                "CREATE TABLE IF NOT EXISTS `downloads` (" +
                    "`id` INTEGER NOT NULL, " +
                    "`url` TEXT NOT NULL, " +
                    "`file_name` TEXT NOT NULL, " +
                    "`file_path` TEXT NOT NULL, " +
                    "`total_bytes` INTEGER NOT NULL, " +
                    "`downloaded_bytes` INTEGER NOT NULL, " +
                    "`status` INTEGER NOT NULL, " +
                    "`mime_type` TEXT, " +
                    "`user_agent` TEXT, " +
                    "`referer` TEXT, " +
                    "`origin` TEXT, " +
                    "`cookies` TEXT, " +
                    "`source` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "`was_waiting_for_network` INTEGER NOT NULL, " +
                    "`error_message` TEXT, " +
                    "PRIMARY KEY(`id`))"
            )
            v2.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_downloads_status_created_at` " +
                    "ON `downloads` (`status`, `created_at`)"
            )
            v2.execSQL(
                "CREATE TABLE IF NOT EXISTS `download_segments` (" +
                    "`download_id` INTEGER NOT NULL, " +
                    "`start_byte` INTEGER NOT NULL, " +
                    "`end_byte` INTEGER NOT NULL, " +
                    "`downloaded_bytes` INTEGER NOT NULL, " +
                    "`completed` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`download_id`, `start_byte`), " +
                    "FOREIGN KEY(`download_id`) REFERENCES `downloads`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            v2.execSQL(
                "CREATE TABLE IF NOT EXISTS `download_meta` (" +
                    "`id` INTEGER NOT NULL, " +
                    "`last_id` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
            v2.execSQL(
                "INSERT INTO history (url, title, visitedAt) " +
                    "VALUES ('https://h.example/', 'H', 55)"
            )
            v2.execSQL(
                "INSERT INTO downloads (id, url, file_name, file_path, total_bytes, " +
                    "downloaded_bytes, status, source, created_at, was_waiting_for_network) " +
                    "VALUES (7, 'https://d.example/f.bin', 'f.bin', '/x/f.bin', 100, 42, 3, 'browser', 9, 0)"
            )
            v2.execSQL(
                "INSERT INTO download_segments (download_id, start_byte, end_byte, downloaded_bytes, completed) " +
                    "VALUES (7, 0, 99, 42, 0)"
            )
            v2.execSQL("INSERT INTO download_meta (id, last_id) VALUES (1, 7)")
            v2.version = 2
        }

        val db = Room.databaseBuilder(context, NexaDatabase::class.java, dbName)
            .addMigrations(
                NexaDatabase.MIGRATION_1_2,
                NexaDatabase.MIGRATION_2_3,
                NexaDatabase.MIGRATION_3_4,
                NexaDatabase.MIGRATION_4_5,
                NexaDatabase.MIGRATION_5_6,
                NexaDatabase.MIGRATION_6_7,
                NexaDatabase.MIGRATION_7_8,
            )
            .allowMainThreadQueries()
            .build()

        try {
            val sqliteDb = db.openHelper.readableDatabase

            // History untouched.
            sqliteDb.query("SELECT COUNT(*) FROM history").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }

            // Download row survived byte-for-byte.
            sqliteDb.query(
                "SELECT url, file_name, total_bytes, downloaded_bytes, status FROM downloads WHERE id = 7"
            ).use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals("https://d.example/f.bin", cursor.getString(0))
                assertEquals("f.bin", cursor.getString(1))
                assertEquals(100L, cursor.getLong(2))
                assertEquals(42L, cursor.getLong(3))
                assertEquals(3, cursor.getInt(4))
            }

            // Segment survived.
            sqliteDb.query(
                "SELECT end_byte, downloaded_bytes, completed FROM download_segments " +
                    "WHERE download_id = 7 AND start_byte = 0"
            ).use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals(99L, cursor.getLong(0))
                assertEquals(42L, cursor.getLong(1))
                assertEquals(0, cursor.getInt(2))
            }

            // Meta survived and gained the v4 marker column (default 0).
            assertMetaV4(sqliteDb, expectedLastId = 7L, expectedImported = 0)

            assertPhase3TablesPresent(sqliteDb)

            sqliteDb.query("PRAGMA user_version").use { cursor ->
                cursor.moveToFirst()
                assertEquals(8, cursor.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 3 to 8 normalizes tabs and keeps existing data`() {
        val dbName = "migration-3-4-test.db"

        context.deleteDatabase(dbName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { v3 ->
            // History table required by Phase 2 migrations.
            v3.execSQL(
                "CREATE TABLE IF NOT EXISTS `history` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`url` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`visitedAt` INTEGER NOT NULL)"
            )
            v3.execSQL("CREATE INDEX IF NOT EXISTS `index_history_visitedAt` ON `history` (`visitedAt`)")
            v3.execSQL("CREATE INDEX IF NOT EXISTS `index_history_url` ON `history` (`url`)")

            // Downloads table expected by Phase 2 migrations.
            v3.execSQL(
                "CREATE TABLE IF NOT EXISTS `downloads` (" +
                    "`id` INTEGER NOT NULL, " +
                    "`url` TEXT NOT NULL, " +
                    "`file_name` TEXT NOT NULL, " +
                    "`file_path` TEXT NOT NULL, " +
                    "`total_bytes` INTEGER NOT NULL, " +
                    "`downloaded_bytes` INTEGER NOT NULL, " +
                    "`status` INTEGER NOT NULL, " +
                    "`mime_type` TEXT, " +
                    "`user_agent` TEXT, " +
                    "`referer` TEXT, " +
                    "`origin` TEXT, " +
                    "`cookies` TEXT, " +
                    "`source` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "`was_waiting_for_network` INTEGER NOT NULL, " +
                    "`error_message` TEXT, " +
                    "PRIMARY KEY(`id`))"
            )
            v3.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_downloads_status_created_at` ON `downloads` (`status`, `created_at`)"
            )
            v3.execSQL("INSERT INTO downloads (id, url, file_name, file_path, total_bytes, downloaded_bytes, status, source, created_at, was_waiting_for_network) VALUES (7, 'https://d.example/f.bin', 'f.bin', '/x/f.bin', 100, 42, 3, 'browser', 9, 0)")

            // Segments table (no rows needed for this test).
            v3.execSQL(
                "CREATE TABLE IF NOT EXISTS `download_segments` (" +
                    "`download_id` INTEGER NOT NULL, " +
                    "`start_byte` INTEGER NOT NULL, " +
                    "`end_byte` INTEGER NOT NULL, " +
                    "`downloaded_bytes` INTEGER NOT NULL, " +
                    "`completed` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`download_id`, `start_byte`), " +
                    "FOREIGN KEY(`download_id`) REFERENCES `downloads`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )

            // download_meta without legacy_imported (v3 schema).
            v3.execSQL(
                "CREATE TABLE IF NOT EXISTS `download_meta` (" +
                    "`id` INTEGER NOT NULL, " +
                    "`last_id` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
            v3.execSQL("INSERT INTO download_meta (id, last_id) VALUES (1, 42)")

            // Tabs/bookmarks expected by Phase 3 migrations.
            v3.execSQL(
                "CREATE TABLE IF NOT EXISTS `tabs` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`url` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`position` INTEGER NOT NULL, " +
                    "`is_active` INTEGER NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "`last_accessed_at` INTEGER NOT NULL)"
            )
            v3.execSQL("CREATE INDEX IF NOT EXISTS `index_tabs_position` ON `tabs` (`position`)")
            v3.execSQL("CREATE INDEX IF NOT EXISTS `index_tabs_is_active` ON `tabs` (`is_active`)")
            v3.execSQL(
                "INSERT INTO tabs (url, title, position, is_active, created_at, last_accessed_at) VALUES " +
                    "('https://later.example/', 'Later', 9, 1, 20, 20), " +
                    "('https://earlier.example/', 'Earlier', 3, 0, 10, 10)"
            )

            v3.execSQL(
                "CREATE TABLE IF NOT EXISTS `bookmarks` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`url` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL)"
            )
            v3.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bookmarks_url` ON `bookmarks` (`url`)")
            v3.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_created_at` ON `bookmarks` (`created_at`)")
            v3.execSQL("INSERT INTO bookmarks (url, title, created_at, updated_at) VALUES ('https://b.example/', 'B', 30, 30)")

            v3.version = 3
        }

        val db = Room.databaseBuilder(context, NexaDatabase::class.java, dbName)
            .addMigrations(
                NexaDatabase.MIGRATION_3_4,
                NexaDatabase.MIGRATION_4_5,
                NexaDatabase.MIGRATION_5_6,
                NexaDatabase.MIGRATION_6_7,
                NexaDatabase.MIGRATION_7_8,
            )
            .allowMainThreadQueries()
            .build()

        try {
            val sqliteDb = db.openHelper.readableDatabase
            // Sequence preserved; marker present with NOT NULL + DEFAULT 0.
            assertMetaV4(sqliteDb, expectedLastId = 42L, expectedImported = 0)
            sqliteDb.query("SELECT url, position, is_pinned FROM tabs ORDER BY position").use { cursor ->
                assertEquals(2, cursor.count)
                cursor.moveToFirst()
                assertEquals("https://earlier.example/", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
                cursor.moveToNext()
                assertEquals("https://later.example/", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
            }
            sqliteDb.query("PRAGMA user_version").use { cursor ->
                cursor.moveToFirst()
                assertEquals(8, cursor.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `fresh install creates version 8 directly`() {
        val dbName = "fresh-test.db"
        context.deleteDatabase(dbName)
        val db = Room.databaseBuilder(context, NexaDatabase::class.java, dbName)
            .addMigrations(
                NexaDatabase.MIGRATION_1_2,
                NexaDatabase.MIGRATION_2_3,
                NexaDatabase.MIGRATION_3_4,
                NexaDatabase.MIGRATION_4_5,
                NexaDatabase.MIGRATION_5_6,
                NexaDatabase.MIGRATION_6_7,
                NexaDatabase.MIGRATION_7_8,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val sqliteDb = db.openHelper.readableDatabase
            // Room created tables, but download_meta needs a row for queries on id=1.
            sqliteDb.execSQL("INSERT INTO download_meta (id, last_id, legacy_imported) VALUES (1, 0, 0)")

            sqliteDb.query("PRAGMA user_version").use { cursor ->
                cursor.moveToFirst()
                assertEquals(8, cursor.getInt(0))
            }
            assertDownloadTablesPresent(sqliteDb)
            assertPhase3TablesPresent(sqliteDb)
            assertMetaV4(sqliteDb, expectedLastId = 0L, expectedImported = 0)
        } finally {
            db.close()
        }
    }

    /** v4 download_meta: sequence intact + marker column NOT NULL DEFAULT 0. */
    private fun assertMetaV4(
        sqliteDb: androidx.sqlite.db.SupportSQLiteDatabase,
        expectedLastId: Long,
        expectedImported: Int,
    ) {
        sqliteDb.query("SELECT last_id, legacy_imported FROM download_meta WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expectedLastId, cursor.getLong(0))
            assertEquals(expectedImported, cursor.getInt(1))
        }
        sqliteDb.query("PRAGMA table_info(download_meta)").use { cursor ->
            var sawColumn = false
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == "legacy_imported") {
                    sawColumn = true
                    assertEquals(1, cursor.getInt(3)) // notnull
                    assertEquals("0", cursor.getString(4)) // dflt_value
                }
            }
            assertTrue("legacy_imported column missing", sawColumn)
        }
    }

    private fun assertDownloadTablesPresent(sqliteDb: androidx.sqlite.db.SupportSQLiteDatabase) {
        val tables = mutableSetOf<String>()
        sqliteDb.query(
            "SELECT name FROM sqlite_master WHERE type = 'table'"
        ).use { cursor ->
            while (cursor.moveToNext()) tables.add(cursor.getString(0))
        }
        assertTrue(tables.contains("downloads"))
        assertTrue(tables.contains("download_segments"))
        assertTrue(tables.contains("download_meta"))

        val downloadColumns = mutableSetOf<String>()
        sqliteDb.query("PRAGMA table_info(downloads)").use { cursor ->
            while (cursor.moveToNext()) downloadColumns.add(cursor.getString(1))
        }
        assertEquals(
            setOf(
                "id", "url", "file_name", "file_path", "total_bytes",
                "downloaded_bytes", "status", "mime_type", "user_agent",
                "referer", "origin", "cookies", "source", "created_at",
                "was_waiting_for_network", "error_message"
            ),
            downloadColumns
        )
    }

    private fun assertPhase3TablesPresent(sqliteDb: androidx.sqlite.db.SupportSQLiteDatabase) {
        val tables = mutableSetOf<String>()
        sqliteDb.query(
            "SELECT name FROM sqlite_master WHERE type = 'table'"
        ).use { cursor ->
            while (cursor.moveToNext()) tables.add(cursor.getString(0))
        }
        assertTrue(tables.contains("tabs"))
        assertTrue(tables.contains("bookmarks"))
        assertTrue(tables.contains("bookmark_folders"))

        val tabColumns = mutableSetOf<String>()
        sqliteDb.query("PRAGMA table_info(tabs)").use { cursor ->
            while (cursor.moveToNext()) tabColumns.add(cursor.getString(1))
        }
        assertEquals(
            setOf(
                "id", "url", "title", "position", "is_pinned", "is_active",
                "created_at", "last_accessed_at"
            ),
            tabColumns
        )

        val bookmarkColumns = mutableSetOf<String>()
        sqliteDb.query("PRAGMA table_info(bookmarks)").use { cursor ->
            while (cursor.moveToNext()) bookmarkColumns.add(cursor.getString(1))
        }
        assertEquals(
            setOf(
                "id", "url", "title", "created_at", "updated_at",
                "folder_id", "position", "last_opened_at"
            ),
            bookmarkColumns
        )

        // The UNIQUE index on bookmarks.url is the duplicate policy — pin it.
        val indexes = mutableSetOf<String>()
        sqliteDb.query(
            "SELECT name FROM sqlite_master WHERE type = 'index'"
        ).use { cursor ->
            while (cursor.moveToNext()) indexes.add(cursor.getString(0))
        }
        assertTrue(indexes.contains("index_bookmarks_url"))
        assertTrue(indexes.contains("index_bookmarks_created_at"))
        assertTrue(indexes.contains("index_tabs_position"))
        assertTrue(indexes.contains("index_tabs_is_pinned_position"))
        assertTrue(indexes.contains("index_tabs_is_active"))
        sqliteDb.query("PRAGMA index_info(index_bookmarks_url)").use { cursor ->
            cursor.moveToFirst()
            assertEquals("url", cursor.getString(2))
        }
    }
}
