package dev.mobileforge.core.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * IDE metadata about a project.
 *
 * Deliberately stored in the app's own database rather than as a dotfile inside the user's
 * project. The brief is explicit: IDE metadata and user project files stay separate, and we do
 * not litter someone's repository with our bookkeeping.
 *
 * NOTHING SENSITIVE LIVES HERE. No tokens, no credentials, no .env contents. Room is not
 * encrypted at rest; secrets belong in Keystore-backed storage (see [dev.mobileforge.core.data.secure]).
 * [gitRemote] is the one borderline field and is stored only after credentials are stripped
 * from the URL.
 */
@Entity(tableName = "workspaces")
data class WorkspaceEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    /** Absolute path inside app-managed storage. */
    @ColumnInfo(name = "root_path")
    val rootPath: String,

    /** Persisted name of WorkspaceTrust. Stored as text so a new tier does not break old rows. */
    @ColumnInfo(name = "trust")
    val trust: String,

    /** Persisted name of DetectedFramework. */
    @ColumnInfo(name = "framework")
    val framework: String,

    @ColumnInfo(name = "created_at")
    val createdAtEpochMs: Long,

    @ColumnInfo(name = "last_opened_at")
    val lastOpenedAtEpochMs: Long,

    /** Restored on reopen so the user lands where they left off (crash-recovery requirement). */
    @ColumnInfo(name = "last_active_file")
    val lastActiveFile: String? = null,

    /** Credential-stripped remote URL, or null. Never a URL containing a token. */
    @ColumnInfo(name = "git_remote")
    val gitRemote: String? = null,
)
