package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PatternEntity::class,
        PracticeProgressEntity::class,
        LessonProgressEntity::class,
        RecordingTrackEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun patternDao(): PatternDao
    abstract fun practiceProgressDao(): PracticeProgressDao
    abstract fun lessonProgressDao(): LessonProgressDao
    abstract fun recordingTrackDao(): RecordingTrackDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `lesson_progress` (
                        `lessonId` TEXT NOT NULL PRIMARY KEY,
                        `isCompleted` INTEGER NOT NULL,
                        `stars` INTEGER NOT NULL,
                        `bestScore` INTEGER NOT NULL,
                        `attempts` INTEGER NOT NULL,
                        `lastPracticedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recording_tracks` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `title` TEXT NOT NULL,
                        `date` TEXT NOT NULL,
                        `scaleId` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `eventsJson` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recording_tracks` ADD COLUMN `bpm` INTEGER NOT NULL DEFAULT 70")
                db.execSQL("ALTER TABLE `recording_tracks` ADD COLUMN `timeSignature` TEXT NOT NULL DEFAULT '4/4'")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "handpan_learning_db"
                )
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
