package com.elewashy.nexa.core.data.persistence

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.elewashy.nexa.feature.bookmarks.data.persistence.BookmarkEntity
import com.elewashy.nexa.feature.bookmarks.data.persistence.BookmarkFolderEntity
import com.elewashy.nexa.feature.bookmarks.data.persistence.BookmarksDao
import com.elewashy.nexa.feature.browser.data.search.SearchHistoryDao
import com.elewashy.nexa.feature.browser.data.search.SearchHistoryEntity
import com.elewashy.nexa.feature.downloads.data.persistence.DownloadEntity
import com.elewashy.nexa.feature.downloads.data.persistence.DownloadMetaEntity
import com.elewashy.nexa.feature.downloads.data.persistence.DownloadSegmentEntity
import com.elewashy.nexa.feature.downloads.data.persistence.DownloadsDao
import com.elewashy.nexa.feature.history.data.persistence.HistoryDao
import com.elewashy.nexa.feature.history.data.persistence.HistoryEntity
import com.elewashy.nexa.feature.tabs.data.persistence.TabEntity
import com.elewashy.nexa.feature.tabs.data.persistence.TabsDao

/**
 * Single application database for all structured, growing, queryable data.
 *
 * This class owns database infrastructure only (configuration, versioning,
 * migrations). Entities and DAOs live in their owning feature packages and
 * are registered here centrally; features obtain DAOs through their own DI
 * modules, never this class directly.
 *
 * Schema changes: bump [version], add a Migration object, add a migration
 * test, and commit the exported schema under app/schemas.
 */
@Database(
    entities = [
        HistoryEntity::class,
        DownloadEntity::class,
        DownloadSegmentEntity::class,
        DownloadMetaEntity::class,
        TabEntity::class,
        BookmarkEntity::class,
        BookmarkFolderEntity::class,
        SearchHistoryEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class NexaDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun downloadsDao(): DownloadsDao
    abstract fun tabsDao(): TabsDao
    abstract fun bookmarksDao(): BookmarksDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        /** v1 (history only, unreleased) → v2 (+ download tables). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_downloads_status_created_at` " +
                        "ON `downloads` (`status`, `created_at`)"
                )
                db.execSQL(
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
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `download_meta` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`last_id` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /** v2 (+ download tables) → v3 (+ tabs and bookmarks). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tabs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`url` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`is_active` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`last_accessed_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tabs_position` " +
                        "ON `tabs` (`position`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tabs_is_active` " +
                        "ON `tabs` (`is_active`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`url` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_bookmarks_url` " +
                        "ON `bookmarks` (`url`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_bookmarks_created_at` " +
                        "ON `bookmarks` (`created_at`)"
                )
            }
        }

        /** v3 → v4: one-shot legacy-import marker in [download_meta]. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `download_meta` ADD COLUMN `legacy_imported` " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
                // Ensure existing row (id=1) gets the marker set explicitly.
                db.execSQL(
                    "INSERT OR IGNORE INTO download_meta (id, last_id, legacy_imported) " +
                        "VALUES (1, COALESCE((SELECT last_id FROM download_meta WHERE id=1), 0), 0)"
                )
            }
        }

        /** v4 → v5: bookmark folders, ordering, and last-opened metadata. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `folder_id` INTEGER")
                db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `last_opened_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `bookmarks` SET `position` = `created_at`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_bookmarks_folder_id` " +
                        "ON `bookmarks` (`folder_id`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmark_folders` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, `parent_id` INTEGER, " +
                        "`position` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_bookmark_folders_parent_id` " +
                        "ON `bookmark_folders` (`parent_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_bookmark_folders_position` " +
                        "ON `bookmark_folders` (`position`)"
                )
            }
        }

        /** v5 → v6: dedicated query history; browsing history remains website-only. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `search_history` (" +
                        "`query` TEXT NOT NULL, `searchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`query`))"
                )
            }
        }

        /** v6 → v7: index query history by recency. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_search_history_searchedAt` " +
                        "ON `search_history` (`searchedAt`)"
                )
            }
        }

        /** v7 → v8: durable tab pin state and normalized canonical ordering. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tabs` ADD COLUMN `is_pinned` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TEMP TABLE `tab_order_v8` AS " +
                        "SELECT `id`, `position` FROM `tabs`"
                )
                db.execSQL(
                    "UPDATE `tabs` SET `position` = (" +
                        "SELECT COUNT(*) FROM `tab_order_v8` AS `before` " +
                        "WHERE `before`.`position` < (" +
                        "SELECT `current`.`position` FROM `tab_order_v8` AS `current` " +
                        "WHERE `current`.`id` = `tabs`.`id`) " +
                        "OR (`before`.`position` = (" +
                        "SELECT `current`.`position` FROM `tab_order_v8` AS `current` " +
                        "WHERE `current`.`id` = `tabs`.`id`) AND `before`.`id` < `tabs`.`id`))"
                )
                db.execSQL("DROP TABLE `tab_order_v8`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tabs_is_pinned_position` " +
                        "ON `tabs` (`is_pinned`, `position`)"
                )
            }
        }

    }
}
