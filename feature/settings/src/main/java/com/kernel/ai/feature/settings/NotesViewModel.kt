package com.kernel.ai.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.dao.NoteDao
import com.kernel.ai.core.memory.entity.NoteEntity
import com.kernel.ai.core.memory.usecase.NoteSmartTitleUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import javax.inject.Inject

// ── Enums ──────────────────────────────────────────────────────────────────────────────────────

enum class NoteSort(val displayName: String) {
    MANUAL("Manual Order"),
    LAST_MODIFIED("Last Modified"),
    NAME_ASC("Name (A-Z)"),
    NAME_DESC("Name (Z-A)"),
    CREATED_ASC("Created (Oldest)"),
    CREATED_DESC("Created (Newest)"),
}

enum class NoteFilter(val displayName: String) {
    ALL("All"),
    PINNED_ONLY("Pinned Only"),
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel @Inject constructor(
    private val noteDao: NoteDao,
    private val noteSmartTitleUseCase: NoteSmartTitleUseCase,
) : ViewModel() {

    // ── State ────────────────────────────────────────────────────────────────────────────────────

    var showArchived by mutableStateOf(false)
        private set

    var listSort by mutableStateOf(NoteSort.MANUAL)
    var listFilter by mutableStateOf(NoteFilter.ALL)
    var noteSearchQuery by mutableStateOf("")

    var selectedNoteIds by mutableStateOf<Set<Long>>(emptySet())
    val isMultiSelectMode: Boolean get() = selectedNoteIds.isNotEmpty()
    private var reorderJob: Job? = null

    // ── Data flows ───────────────────────────────────────────────────────────────────────────────

    private val activeNotesFlow: StateFlow<List<NoteEntity>> = noteDao
        .observeAllActiveNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val archivedNotesFlow: StateFlow<List<NoteEntity>> = noteDao
        .observeArchivedNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val searchNotesFlow: StateFlow<List<NoteEntity>> = snapshotFlow { noteSearchQuery }
        .flatMapLatest { query ->
            if (query.isBlank()) activeNotesFlow else noteDao.observeSearchNotes(query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun applySortFilter(notes: List<NoteEntity>, sort: NoteSort, filter: NoteFilter): List<NoteEntity> {
        val base = when (filter) {
            NoteFilter.ALL -> notes
            NoteFilter.PINNED_ONLY -> notes.filter { it.pinned }
        }
        return when (sort) {
            NoteSort.MANUAL -> base.sortedWith(
                compareByDescending<NoteEntity> { it.pinned }
                    .thenBy { it.displayOrder }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title.orEmpty() }
            )
            NoteSort.LAST_MODIFIED -> base.sortedByDescending { it.updatedAt }
            NoteSort.NAME_ASC -> base.sortedWith(
                compareBy<NoteEntity> { it.title.orEmpty() }
                    .thenBy { it.updatedAt }
            )
            NoteSort.NAME_DESC -> base.sortedWith(
                compareByDescending<NoteEntity> { it.title.orEmpty() }
                    .thenByDescending { it.updatedAt }
            )
            NoteSort.CREATED_ASC -> base.sortedBy { it.createdAt }
            NoteSort.CREATED_DESC -> base.sortedByDescending { it.createdAt }
        }
    }

    /** Notes to display — active or archived, filtered by search. */
    val displayedNotes: StateFlow<List<NoteEntity>> = combine(
        snapshotFlow { showArchived },
        searchNotesFlow,
        archivedNotesFlow,
        snapshotFlow { listSort },
        snapshotFlow { listFilter },
    ) { showArchived, activeNotes, archivedNotes, sort, filter ->
        val notes = if (showArchived) archivedNotes else activeNotes
        applySortFilter(notes, sort, filter)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All notes (for detail screen to find by ID). */
    val notes: StateFlow<List<NoteEntity>> = combine(
        activeNotesFlow,
        archivedNotesFlow,
    ) { active, archived -> active + archived }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Active note count for badge/display. */
    val activeNoteCount: StateFlow<Int> = activeNotesFlow
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // ── CRUD ─────────────────────────────────────────────────────────────────────────────────────


    fun createNote(title: String?, content: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val normalizedTitle = title?.trim()?.takeIf { it.isNotBlank() }
            val trimmedContent = content.trim()
            val note = NoteEntity(
                title = normalizedTitle,
                content = trimmedContent,
                createdAt = now,
                updatedAt = now,
                displayOrder = noteDao.getMaxActiveDisplayOrder() + 1.0,
            )
            val id = noteDao.insertNote(note)
            if (id > 0 && normalizedTitle == null) {
                noteSmartTitleUseCase.schedule(id)
            }
        }
    }

    fun updateNote(note: NoteEntity) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            // Reset smartTitleGenerated if the title was cleared so the use case
            // can generate a new one; preserve the flag otherwise.
            val titlePresent = !note.title.isNullOrBlank()
            val toSave = note.copy(
                updatedAt = now,
                smartTitleGenerated = titlePresent && note.smartTitleGenerated,
            )
            noteDao.updateNote(toSave)
            if (!titlePresent) {
                noteSmartTitleUseCase.schedule(toSave.id)
            }
        }
    }

    fun deleteNote(note: NoteEntity) {
        viewModelScope.launch {
            noteDao.deleteNote(note)
        }
    }

    fun pinNote(noteId: Long) {
        viewModelScope.launch {
            noteDao.pinNote(noteId, System.currentTimeMillis())
        }
    }

    fun unpinNote(noteId: Long) {
        viewModelScope.launch {
            noteDao.unpinNote(noteId, System.currentTimeMillis())
        }
    }

    fun archiveNote(noteId: Long) {
        viewModelScope.launch {
            noteDao.archiveNote(noteId, System.currentTimeMillis(), System.currentTimeMillis())
        }
    }

    fun unarchiveNote(noteId: Long) {
        viewModelScope.launch {
            noteDao.unarchiveNote(noteId, System.currentTimeMillis())
        }
    }

    // ── Bulk operations ──────────────────────────────────────────────────────────────────────────

    fun enterMultiSelect() {
        selectedNoteIds = emptySet()
    }

    fun exitMultiSelect() {
        selectedNoteIds = emptySet()
    }

    fun toggleNoteSelection(noteId: Long) {
        selectedNoteIds = if (noteId in selectedNoteIds) selectedNoteIds - noteId else selectedNoteIds + noteId
    }

    fun selectAllNotes(allIds: List<Long>) {
        selectedNoteIds = allIds.toSet()
    }

    fun bulkArchiveSelected() {
        val ids = selectedNoteIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            noteDao.bulkArchive(ids, System.currentTimeMillis(), System.currentTimeMillis())
            exitMultiSelect()
        }
    }

    fun bulkDeleteSelected() {
        val ids = selectedNoteIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            noteDao.bulkDelete(ids)
            exitMultiSelect()
        }
    }

    fun bulkRestoreSelected() {
        val ids = selectedNoteIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            try {
                noteDao.bulkUnarchive(ids, System.currentTimeMillis())
            } finally {
                exitMultiSelect()
            }
        }
    }

    fun bulkPinSelected(pinned: Boolean) {
        val ids = selectedNoteIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            noteDao.bulkPin(ids, pinned, System.currentTimeMillis())
            exitMultiSelect()
        }
    }

    // ── Reorder ──────────────────────────────────────────────────────────────────────────────────

    fun onNotesReordered(pinnedIds: List<Long>, unpinnedIds: List<Long>) {
        listSort = NoteSort.MANUAL
        reorderJob?.cancel()
        reorderJob = viewModelScope.launch(Dispatchers.IO) {
            val updates = (pinnedIds.mapIndexed { i, id -> id to i.toDouble() } +
                          unpinnedIds.mapIndexed { i, id -> id to i.toDouble() }).toMap()
            noteDao.reorderNotes(updates)
        }
    }

    // ── Archived view ────────────────────────────────────────────────────────────────────────────

    fun toggleArchivedView() {
        showArchived = !showArchived
        exitMultiSelect()
    }

    fun setSearchQuery(query: String) {
        noteSearchQuery = query
    }

    /**
     * Builds a plain-text representation of a note for sharing/copying.
     * Format: title (or "Untitled") followed by a blank line then the full content.
     */
    fun buildShareText(note: NoteEntity): String {
        val heading = note.title?.takeIf { it.isNotBlank() } ?: "Untitled"
        return "$heading\n\n${note.content}"
    }
}
