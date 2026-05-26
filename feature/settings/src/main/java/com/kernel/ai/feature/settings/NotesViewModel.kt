package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.dao.NoteDao
import com.kernel.ai.core.memory.entity.NoteEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotesViewModel @Inject constructor(
    private val noteDao: NoteDao,
) : ViewModel() {

    val notes: StateFlow<List<NoteEntity>> = noteDao
        .observeAllNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createNote(title: String, content: String) {
        viewModelScope.launch {
            noteDao.insertNote(NoteEntity(title = title, content = content))
        }
    }

    fun updateNote(note: NoteEntity) {
        viewModelScope.launch {
            noteDao.updateNote(note.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    fun deleteNote(note: NoteEntity) {
        viewModelScope.launch {
            noteDao.deleteNote(note)
        }
    }
}
