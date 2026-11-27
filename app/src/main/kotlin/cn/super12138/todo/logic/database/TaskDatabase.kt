package cn.super12138.todo.logic.database

import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import cn.super12138.todo.constants.Constants

@androidx.room3.ColumnTypeConverters(TaskConverters::class)
@Database(entities = [TaskEntity::class, SyncEnvelope::class, SyncedTag::class], version = 12)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE todo ADD COLUMN rollover INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override suspend fun migrate(connection: androidx.sqlite.SQLiteConnection) {
                connection.execSQL("ALTER TABLE sync_tags ADD COLUMN icon TEXT NOT NULL DEFAULT 'tag'")
            }
        }
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE todo ADD COLUMN sync_id TEXT NOT NULL DEFAULT ''")
                connection.execSQL("UPDATE todo SET sync_id = lower(hex(randomblob(16)))")
                connection.execSQL("CREATE UNIQUE INDEX index_todo_sync_id ON todo(sync_id)")
                connection.execSQL("ALTER TABLE todo ADD COLUMN calendar_date TEXT NOT NULL DEFAULT ''")
                connection.execSQL("ALTER TABLE todo ADD COLUMN time_zone TEXT NOT NULL DEFAULT ''")
                connection.execSQL("ALTER TABLE todo ADD COLUMN sync_parent TEXT NOT NULL DEFAULT ''")
                connection.execSQL("ALTER TABLE todo ADD COLUMN sync_deleted INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE todo ADD COLUMN sync_retracted INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("CREATE TABLE sync_state (id INTEGER NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, journal TEXT NOT NULL, tagsImported INTEGER NOT NULL, sourceId TEXT NOT NULL)")
                connection.execSQL("CREATE TABLE sync_tags (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, color INTEGER NOT NULL, deleted INTEGER NOT NULL)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE ${Constants.DB_TABLE_NAME} ADD COLUMN recurrence TEXT")
                connection.execSQL("ALTER TABLE ${Constants.DB_TABLE_NAME} ADD COLUMN recurrence_parent_id INTEGER")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE ${Constants.DB_TABLE_NAME} ADD COLUMN subtasks TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE ${Constants.DB_TABLE_NAME} ADD COLUMN details TEXT NOT NULL DEFAULT ''")
                connection.execSQL("ALTER TABLE ${Constants.DB_TABLE_NAME} ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'")
                connection.execSQL("ALTER TABLE ${Constants.DB_TABLE_NAME} ADD COLUMN due_time INTEGER")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE ${Constants.DB_TABLE_NAME} ADD COLUMN created_at INTEGER")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE ${Constants.DB_TABLE_NAME} ADD COLUMN custom_subject TEXT NOT NULL DEFAULT ''")
            }
        }

        // 为自定义学科功能进行迁移
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // 创建一个新表，其中不含有subject，并且有一个新的category字段（由custom_subject迁移而来）
                connection.execSQL("CREATE TABLE IF NOT EXISTS todo_new (content TEXT NOT NULL, category TEXT NOT NULL DEFAULT '', completed INTEGER NOT NULL, priority REAL NOT NULL, id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                // 将旧表中的数据迁移到新表中
                connection.execSQL("INSERT INTO todo_new (content, category, completed, priority, id) SELECT content, COALESCE(NULLIF(custom_subject, ''), '') AS category, completed, priority, id FROM todo")
                // 删除旧表
                connection.execSQL("DROP TABLE todo")
                // 重命名新表
                connection.execSQL("ALTER TABLE todo_new RENAME TO todo")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE ${Constants.DB_TABLE_NAME} ADD COLUMN due_date INTEGER")
            }
        }
    }
}
