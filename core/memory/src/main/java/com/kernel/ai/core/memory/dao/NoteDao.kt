package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.kernel.ai.core.memory.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNote(note: NoteEntity): Long

    @Update
    suspend fun updateNote(note: NoteEntity)

    @Query("UPDATE notes SET title = :title, smart_title_generated = 1 WHERE id = :noteId AND updated_at = :expectedUpdatedAt AND smart_title_generated = 0 AND (title IS NULL OR TRIM(title) = '')")
    suspend fun updateNoteTitleConditionally(noteId: Long, title: String, expectedUpdatedAt: Long): Int

    @Delete
    suspend fun deleteNote(note: NoteEntity)

    // ── Active notes (not archived) ──────────────────────────────────────────────────────────────

    @Query("SELECT * FROM notes WHERE archived_at = 0 ORDER BY pinned DESC, display_order ASC, updated_at DESC")
    fun observeAllActiveNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE archived_at = 0 ORDER BY pinned DESC, display_order ASC, updated_at DESC")
    suspend fun getAllActiveNotes(): List<NoteEntity>

    // ── Archived notes ───────────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM notes WHERE archived_at > 0 ORDER BY archived_at DESC")
    fun observeArchivedNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE archived_at > 0 ORDER BY archived_at DESC")
    suspend fun getAllArchivedNotes(): List<NoteEntity>

    // ── Pinned notes ─────────────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM notes WHERE archived_at = 0 AND pinned = 1 ORDER BY display_order ASC, updated_at DESC")
    fun observePinnedNotes(): Flow<List<NoteEntity>>

    // ── Search (active only) ─────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM notes WHERE archived_at = 0 AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') ORDER BY pinned DESC, display_order ASC, updated_at DESC")
    fun observeSearchNotes(query: String): Flow<List<NoteEntity>>

    // ── Single note ──────────────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM notes WHERE id = :noteId")
    suspend fun getNoteById(noteId: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE id = :noteId")
    fun observeNoteById(noteId: Long): Flow<NoteEntity?>

    // ── Count ────────────────────────────────────────────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM notes WHERE archived_at = 0")
    suspend fun getActiveNoteCount(): Int

    @Query("SELECT COUNT(*) FROM notes WHERE archived_at > 0")
    suspend fun getArchivedNoteCount(): Int

    // ── Pin / Unpin ──────────────────────────────────────────────────────────────────────────────

    @Query("UPDATE notes SET pinned = 1, updated_at = :updatedAt WHERE id = :noteId")
    suspend fun pinNote(noteId: Long, updatedAt: Long)

    @Query("UPDATE notes SET pinned = 0, updated_at = :updatedAt WHERE id = :noteId")
    suspend fun unpinNote(noteId: Long, updatedAt: Long)

    // ── Archive / Unarchive ──────────────────────────────────────────────────────────────────────

    @Query("UPDATE notes SET archived_at = :archivedAt, updated_at = :updatedAt WHERE id = :noteId")
    suspend fun archiveNote(noteId: Long, archivedAt: Long, updatedAt: Long)

    @Query("UPDATE notes SET archived_at = 0, updated_at = :updatedAt WHERE id = :noteId")
    suspend fun unarchiveNote(noteId: Long, updatedAt: Long)

    // ── Reorder (bulk display_order update) ──────────────────────────────────────────────────────

    @Transaction
    suspend fun reorderNotes(noteOrder: Map<Long, Double>) {
        noteOrder.forEach { (noteId, order) ->
            updateNoteDisplayOrder(noteId, order)
        }
    }

    @Query("UPDATE notes SET display_order = :displayOrder, updated_at = :updatedAt WHERE id = :noteId")
    suspend fun updateNoteDisplayOrder(noteId: Long, displayOrder: Double, updatedAt: Long = System.currentTimeMillis())

    // ── Bulk operations ──────────────────────────────────────────────────────────────────────────

    @Query("UPDATE notes SET archived_at = :archivedAt, updated_at = :updatedAt WHERE id IN (:noteIds)")
    suspend fun bulkArchive(noteIds: List<Long>, archivedAt: Long, updatedAt: Long)

    @Query("UPDATE notes SET pinned = :pinned, updated_at = :updatedAt WHERE id IN (:noteIds)")
    suspend fun bulkPin(noteIds: List<Long>, pinned: Boolean, updatedAt: Long)

    @Query("DELETE FROM notes WHERE id IN (:noteIds)")
    suspend fun bulkDelete(noteIds: List<Long>)
}
