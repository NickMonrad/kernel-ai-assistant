package com.kernel.ai.core.memory.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kernel.ai.core.memory.KernelDatabase
import com.kernel.ai.core.memory.entity.NoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteDaoTest {
    private lateinit var database: KernelDatabase
    private lateinit var noteDao: NoteDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, KernelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        noteDao = database.noteDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertNote_returnsPositiveId() = runBlocking {
        val id = noteDao.insertNote(NoteEntity(title = "Test", content = "Content"))
        assertTrue(id > 0)
    }

    @Test
    fun insertNote_allowsDuplicates() = runBlocking {
        val note = NoteEntity(title = "Test", content = "Content")
        val id1 = noteDao.insertNote(note)
        val id2 = noteDao.insertNote(note)
        assertNotEquals(id1, id2)
    }

    @Test
    fun updateNote_updatesTitleAndContent() = runBlocking {
        val id = noteDao.insertNote(NoteEntity(title = "Old", content = "Old content"))
        val note = noteDao.getNoteById(id)!!
        noteDao.updateNote(note.copy(title = "New", content = "New content"))
        val updated = noteDao.getNoteById(id)!!
        assertEquals("New", updated.title)
        assertEquals("New content", updated.content)
    }


    @Test
    fun updateNoteTitleConditionally_updatesWhenTitleBlankAndNotGenerated() = runBlocking {
        val id = noteDao.insertNote(NoteEntity(title = null, content = "Old content", createdAt = 1L, updatedAt = 1L))
        val rows = noteDao.updateNoteTitleConditionally(id, "Generated title")

        assertEquals(1, rows)
        val updated = noteDao.getNoteById(id)!!
        assertEquals("Generated title", updated.title)
        assertTrue(updated.smartTitleGenerated)
    }

    @Test
    fun updateNoteTitleConditionally_rejectsWhenTitleAlreadySet() = runBlocking {
        val id = noteDao.insertNote(NoteEntity(title = "User title", content = "content", createdAt = 1L, updatedAt = 1L))
        val rows = noteDao.updateNoteTitleConditionally(id, "Generated title")

        assertEquals(0, rows)
        val updated = noteDao.getNoteById(id)!!
        assertEquals("User title", updated.title)
        assertFalse(updated.smartTitleGenerated)
    }

    @Test
    fun updateNoteTitleConditionally_rejectsWhenAlreadyGenerated() = runBlocking {
        val id = noteDao.insertNote(NoteEntity(title = null, content = "content", createdAt = 1L, updatedAt = 1L))
        // Mark as already generated
        noteDao.updateNoteTitleConditionally(id, "First title")
        val rows = noteDao.updateNoteTitleConditionally(id, "Second title")

        assertEquals(0, rows)
        val final = noteDao.getNoteById(id)!!
        assertEquals("First title", final.title)
    }

    @Test
    fun deleteNote_removesFromDatabase() = runBlocking {
        val id = noteDao.insertNote(NoteEntity(title = "Delete me", content = "gone"))
        val note = noteDao.getNoteById(id)!!
        noteDao.deleteNote(note)
        assertNull(noteDao.getNoteById(id))
    }

    @Test
    fun observeAllNotes_returnsOrderedByUpdatedAtDesc() = runBlocking {
        noteDao.insertNote(NoteEntity(title = "First", content = "a"))
        Thread.sleep(10)
        noteDao.insertNote(NoteEntity(title = "Second", content = "b"))
        Thread.sleep(10)
        noteDao.insertNote(NoteEntity(title = "Third", content = "c"))

        val all = noteDao.observeAllActiveNotes().first()
        assertEquals(3, all.size)
        assertEquals("Third", all[0].title)
        assertEquals("Second", all[1].title)
        assertEquals("First", all[2].title)
    }

    @Test
    fun getNoteById_returnsNullForMissing() = runBlocking {
        assertNull(noteDao.getNoteById(999L))
    }

    @Test
    fun observeNoteById_returnsUpdatedValue() = runBlocking {
        val id = noteDao.insertNote(NoteEntity(title = "Original", content = "content"))
        val note = noteDao.getNoteById(id)!!
        noteDao.updateNote(note.copy(title = "Updated"))

        val result = noteDao.observeNoteById(id).first()
        assertEquals("Updated", result?.title)
    }

    @Test
    fun getNoteCount_returnsCorrectCount() = runBlocking {
        assertEquals(0, noteDao.getActiveNoteCount())
        noteDao.insertNote(NoteEntity(title = "One", content = "a"))
        noteDao.insertNote(NoteEntity(title = "Two", content = "b"))
        assertEquals(2, noteDao.getActiveNoteCount())
    }

    @Test
    fun getAllNotes_returnsAllSorted() = runBlocking {
        noteDao.insertNote(NoteEntity(title = "Z", content = "z"))
        noteDao.insertNote(NoteEntity(title = "A", content = "a"))
        noteDao.insertNote(NoteEntity(title = "M", content = "m"))

        val all = noteDao.getAllActiveNotes()
        assertEquals(3, all.size)
        assertEquals("M", all[0].title)
        assertEquals("A", all[1].title)
        assertEquals("Z", all[2].title)
    }

    @Test
    fun bulkUnarchive_clearsArchivedAtForAllIds() = runBlocking {
        val id1 = noteDao.insertNote(NoteEntity(title = "One", content = "a"))
        val id2 = noteDao.insertNote(NoteEntity(title = "Two", content = "b"))
        val id3 = noteDao.insertNote(NoteEntity(title = "Three", content = "c"))
        val now = System.currentTimeMillis()
        noteDao.bulkArchive(listOf(id1, id2, id3), archivedAt = now - 1000L, updatedAt = now - 1000L)

        // Verify all are archived before restore
        assertEquals(0, noteDao.getActiveNoteCount())
        assertEquals(3, noteDao.getArchivedNoteCount())

        noteDao.bulkUnarchive(listOf(id1, id2), updatedAt = now)

        // id1 and id2 restored; id3 still archived
        assertEquals(2, noteDao.getActiveNoteCount())
        assertEquals(1, noteDao.getArchivedNoteCount())

        val restored1 = noteDao.getNoteById(id1)!!
        val restored2 = noteDao.getNoteById(id2)!!
        val stillArchived = noteDao.getNoteById(id3)!!

        assertEquals(0L, restored1.archivedAt)
        assertEquals(0L, restored2.archivedAt)
        assertTrue("id3 should still be archived", stillArchived.archivedAt > 0L)
    }

}
