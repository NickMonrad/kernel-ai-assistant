package com.kernel.ai.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.inference.InferenceEngine
import com.kernel.ai.core.memory.dao.NoteDao
import com.kernel.ai.core.memory.entity.NoteEntity
import com.kernel.ai.core.skills.SkillRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale
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

enum class NoteAction {
    NONE,
    VOICE_INPUT,
}

@HiltViewModel
class NotesViewModel @Inject constructor(
    private val noteDao: NoteDao,
    private val inferenceEngine: InferenceEngine,
    private val skillRegistry: SkillRegistry,
) : ViewModel() {

    // ── State ────────────────────────────────────────────────────────────────────────────────────

    var showArchived by mutableStateOf(false)
        private set

    var listSort by mutableStateOf(NoteSort.MANUAL)
    var listFilter by mutableStateOf(NoteFilter.ALL)
    var noteSearchQuery by mutableStateOf("")

    var selectedNoteIds = mutableSetOf<Long>()
        private set
    var isMultiSelectMode by mutableStateOf(false)
        private set

    var pendingNoteAction by mutableStateOf(NoteAction.NONE)
        private set

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
                compareBy<NoteEntity> { it.displayOrder }
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
    val activeNoteCount: StateFlow<Int> = noteDao
        .observeAllActiveNotes()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // ── CRUD ─────────────────────────────────────────────────────────────────────────────────────

    fun createNote(title: String?, content: String) {
        viewModelScope.launch {
            val note = NoteEntity(
                title = title,
                content = content,
                displayOrder = activeNotesFlow.value.size.toDouble(),
            )
            val id = noteDao.insertNote(note)
            if (id > 0 && title.isNullOrBlank()) {
                generateNoteTitle(id, content)
            }
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
        isMultiSelectMode = true
        selectedNoteIds.clear()
    }

    fun exitMultiSelect() {
        isMultiSelectMode = false
        selectedNoteIds.clear()
    }

    fun toggleNoteSelection(noteId: Long) {
        if (selectedNoteIds.contains(noteId)) selectedNoteIds.remove(noteId)
        else selectedNoteIds.add(noteId)
    }

    fun selectAllNotes(allIds: List<Long>) {
        selectedNoteIds = allIds.toMutableSet()
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

    fun bulkPinSelected(pinned: Boolean) {
        val ids = selectedNoteIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            noteDao.bulkPin(ids, pinned, System.currentTimeMillis())
            exitMultiSelect()
        }
    }

    // ── Reorder ──────────────────────────────────────────────────────────────────────────────────

    fun reorderNotes(noteOrder: Map<Long, Double>) {
        viewModelScope.launch {
            noteDao.reorderNotes(noteOrder)
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

    // ── Title generation ─────────────────────────────────────────────────────────────────────────

    private suspend fun generateNoteTitle(noteId: Long, content: String) {
        try {
            val firstLines = content.take(200)
            val titlePrompt = "Reply with ONLY a short note title, 3-5 words, no quotes, " +
                "no markdown, no preamble. Just the title on one line.\n\n" +
                "Note content: $firstLines"

            val raw = inferenceEngine.generateOnce(titlePrompt, systemPrompt = null, thinkingEnabled = false)
            val title = raw
                .trim()
                .lines().first().trim()
                .replace(Regex("^(?:Title|Note|Subject)[:\\s]*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("[*_]+"), "")
                .trim('"', '\'', '\u201C', '\u201D')
                .trimEnd('.', '?', '!')
                .trim()
                .take(60)

            if (title.isNotBlank()) {
                noteDao.updateNote(
                    NoteEntity(
                        id = noteId,
                        title = title,
                        content = content,
                        smartTitleGenerated = true,
                    )
                )
            }
        } catch (e: Exception) {
            // Silently fail — note still created, just without smart title
        }
    }

    // ── Voice action ─────────────────────────────────────────────────────────────────────────────

    fun triggerVoiceAction() {
        pendingNoteAction = NoteAction.VOICE_INPUT
        // TODO: Navigate to voice input activity
    }

    fun clearNoteAction() {
        pendingNoteAction = NoteAction.NONE
    }
}
