package com.kernel.ai.core.memory.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kernel.ai.core.memory.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNote(note: NoteEntity): Long

    @Update
    suspend fun updateNote(note: NoteEntity)

    @Delete
    suspend fun deleteNote(note: NoteEntity)

    @Query("SELECT * FROM notes ORDER BY updated_at DESC")
    fun observeAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :noteId")
    suspend fun getNoteById(noteId: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE id = :noteId")
    fun observeNoteById(noteId: Long): Flow<NoteEntity?>

    @Query("SELECT COUNT(*) FROM notes")
    suspend fun getNoteCount(): Int

    @Query("SELECT * FROM notes ORDER BY updated_at DESC")
    suspend fun getAllNotes(): List<NoteEntity>
}
