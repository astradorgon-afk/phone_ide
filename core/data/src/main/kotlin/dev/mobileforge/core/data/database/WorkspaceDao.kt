package dev.mobileforge.core.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkspaceDao {

    /** Recent-projects ordering. Flow so the list updates without manual refresh plumbing. */
    @Query("SELECT * FROM workspaces ORDER BY last_opened_at DESC")
    fun observeAll(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces WHERE id = :id")
    suspend fun findById(id: String): WorkspaceEntity?

    @Query("SELECT * FROM workspaces WHERE root_path = :rootPath")
    suspend fun findByRootPath(rootPath: String): WorkspaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(workspace: WorkspaceEntity)

    @Update
    suspend fun update(workspace: WorkspaceEntity)

    @Delete
    suspend fun delete(workspace: WorkspaceEntity)

    @Query("UPDATE workspaces SET last_opened_at = :timestamp WHERE id = :id")
    suspend fun touchLastOpened(id: String, timestamp: Long)

    @Query("UPDATE workspaces SET last_active_file = :relativePath WHERE id = :id")
    suspend fun setLastActiveFile(id: String, relativePath: String?)

    @Query("UPDATE workspaces SET trust = :trust WHERE id = :id")
    suspend fun setTrust(id: String, trust: String)

    @Query("SELECT COUNT(*) FROM workspaces")
    suspend fun count(): Int
}
