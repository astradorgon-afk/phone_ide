package dev.mobileforge.core.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * IDE metadata store.
 *
 * Phase 1 has exactly one table. Entities for terminal sessions, agent sessions, extensions
 * and permission grants arrive with the phases that actually use them — creating empty tables
 * now would only produce migrations for features that do not exist.
 *
 * Schemas are exported (see build.gradle.kts) so every migration is reviewed as a diff rather
 * than reconstructed from memory.
 */
@Database(
    entities = [WorkspaceEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MobileForgeDatabase : RoomDatabase() {

    abstract fun workspaceDao(): WorkspaceDao

    companion object {
        const val NAME = "mobileforge.db"

        fun create(context: Context): MobileForgeDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MobileForgeDatabase::class.java,
                NAME,
            )
                // No fallbackToDestructiveMigration: silently dropping a user's project list
                // on a schema change is not an acceptable failure mode. Migrations are written.
                .build()
    }
}
