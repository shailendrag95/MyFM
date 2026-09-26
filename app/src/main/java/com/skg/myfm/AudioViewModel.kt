package com.skg.myfm

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SortCriterion {
    TITLE, DURATION, REGEX_MATCH
}

data class AudioUiState(
    val audioList: List<AudioItem> = emptyList(),
    val availableFolders: List<String> = emptyList(),
    val selectedFolder: String? = null,
    val editingRegexPattern: String = "",
    val appliedRegexPattern: String = "",
    val isEditingRegex: Boolean = false,
    val isRegexValid: Boolean = true,
    val sortCriterion: SortCriterion = SortCriterion.TITLE,
    val isAscendingOrder: Boolean = true,
    val playbackSpeed: Float = 1.0f,
    val currentlyPlaying: AudioItem? = null,
    val currentPositionMs: Long = 0L,
    val currentDurationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val selectedAudioIds: Set<Long> = emptySet(),
    val isSelectionMode: Boolean = false
)

private data class FilterSortState(
    val audioList: List<AudioItem>,
    val availableFolders: List<String>,
    val selectedFolder: String?,
    val editingRegexPattern: String,
    val appliedRegexPattern: String,
    val isEditingRegex: Boolean,
    val isRegexValid: Boolean,
    val sortCriterion: SortCriterion,
    val isAscendingOrder: Boolean
)

private data class SelectionState(
    val selectedAudioIds: Set<Long>,
    val isSelectionMode: Boolean
)

private data class PlaybackState(
    val playbackSpeed: Float,
    val currentlyPlaying: AudioItem?,
    val currentPositionMs: Long,
    val currentDurationMs: Long,
    val isPlaying: Boolean,
    val isLoading: Boolean
)

class AudioViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AudioRepository(application)
    private val progressManager = PlaybackProgressManager(application)

    private var playerController: Player? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val _rawAudioList = MutableStateFlow<List<AudioItem>>(emptyList())
    private val _selectedFolder = MutableStateFlow<String?>(null)
    private val _editingRegexPattern = MutableStateFlow("")
    private val _appliedRegexPattern = MutableStateFlow("")
    private val _isEditingRegex = MutableStateFlow(false)
    private val _sortCriterion = MutableStateFlow(SortCriterion.TITLE)
    private val _isAscendingOrder = MutableStateFlow(true)
    private val _playbackSpeed = MutableStateFlow(progressManager.getSavedPlaybackSpeed())
    private val _currentlyPlaying = MutableStateFlow<AudioItem?>(null)
    private val _currentPositionMs = MutableStateFlow(0L)
    private val _currentDurationMs = MutableStateFlow(0L)
    private val _isPlaying = MutableStateFlow(false)
    private val _isLoading = MutableStateFlow(false)
    private val _selectedAudioIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _isSelectionMode = MutableStateFlow(false)

    private val filterSortFlow = combine(
        combine(_rawAudioList, _selectedFolder, _editingRegexPattern, _appliedRegexPattern, _isEditingRegex) { raw, folder, editRegex, appliedRegex, isEdit ->
            Tuple5(raw, folder, editRegex, appliedRegex, isEdit)
        },
        combine(_sortCriterion, _isAscendingOrder) { criterion, isAsc ->
            Pair(criterion, isAsc)
        }
    ) { tuple, sortPair ->
        val rawList = tuple.v1
        val folder = tuple.v2
        val editingRegex = tuple.v3
        val appliedRegex = tuple.v4
        val isEditing = tuple.v5

        val criterion = sortPair.first
        val isAscending = sortPair.second

        val folders = rawList.map { it.folderName }.distinct().sorted()

        val folderFiltered = if (folder.isNullOrEmpty()) {
            rawList
        } else {
            rawList.filter { it.folderName == folder }
        }

        var isValidRegex = true
        val regex = if (appliedRegex.isNotBlank()) {
            runCatching { Regex(appliedRegex) }.getOrElse {
                isValidRegex = false
                null
            }
        } else null

        val regexFiltered = if (regex != null) {
            folderFiltered.filter { regex.containsMatchIn(it.title) }
        } else {
            folderFiltered
        }

        val sortedList = when (criterion) {
            SortCriterion.TITLE -> {
                regexFiltered.sortedBy { it.title.lowercase() }
            }
            SortCriterion.DURATION -> {
                regexFiltered.sortedBy { it.duration }
            }
            SortCriterion.REGEX_MATCH -> {
                if (regex != null) {
                    regexFiltered.sortedWith(
                        compareBy<AudioItem> { item ->
                            val match = regex.find(item.title)
                            match?.value?.lowercase() ?: item.title.lowercase()
                        }.thenBy { it.title.lowercase() }
                    )
                } else {
                    regexFiltered.sortedBy { it.title.lowercase() }
                }
            }
        }

        val finalOrderedList = if (isAscending) sortedList else sortedList.reversed()

        FilterSortState(
            audioList = finalOrderedList,
            availableFolders = folders,
            selectedFolder = folder,
            editingRegexPattern = editingRegex,
            appliedRegexPattern = appliedRegex,
            isEditingRegex = isEditing,
            isRegexValid = isValidRegex,
            sortCriterion = criterion,
            isAscendingOrder = isAscending
        )
    }

    private val selectionFlow = combine(
        _selectedAudioIds,
        _isSelectionMode
    ) { ids, isSelectMode ->
        SelectionState(selectedAudioIds = ids, isSelectionMode = isSelectMode)
    }

    private val playbackFlow = combine(
        combine(_playbackSpeed, _currentlyPlaying, _currentPositionMs, _currentDurationMs) { speed, item, pos, dur ->
            Tuple4(speed, item, pos, dur)
        },
        combine(_isPlaying, _isLoading) { isPlaying, isLoading ->
            Pair(isPlaying, isLoading)
        }
    ) { t4, p ->
        PlaybackState(
            playbackSpeed = t4.v1,
            currentlyPlaying = t4.v2,
            currentPositionMs = t4.v3,
            currentDurationMs = t4.v4,
            isPlaying = p.first,
            isLoading = p.second
        )
    }

    val uiState: StateFlow<AudioUiState> = combine(
        filterSortFlow,
        selectionFlow,
        playbackFlow
    ) { fs, sel, pb ->
        AudioUiState(
            audioList = fs.audioList,
            availableFolders = fs.availableFolders,
            selectedFolder = fs.selectedFolder,
            editingRegexPattern = fs.editingRegexPattern,
            appliedRegexPattern = fs.appliedRegexPattern,
            isEditingRegex = fs.isEditingRegex,
            isRegexValid = fs.isRegexValid,
            sortCriterion = fs.sortCriterion,
            isAscendingOrder = fs.isAscendingOrder,
            playbackSpeed = pb.playbackSpeed,
            currentlyPlaying = pb.currentlyPlaying,
            currentPositionMs = pb.currentPositionMs,
            currentDurationMs = pb.currentDurationMs,
            isPlaying = pb.isPlaying,
            isLoading = pb.isLoading,
            selectedAudioIds = sel.selectedAudioIds,
            isSelectionMode = sel.isSelectionMode
        )
    }.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = AudioUiState()
    )

    init {
        // Connect to PlaybackService via SessionToken
        val sessionToken = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(application, sessionToken).buildAsync()
        controllerFuture?.addListener({
            val controller = runCatching { controllerFuture?.get() }.getOrNull()
            if (controller != null) {
                playerController = controller
                setupPlayerListener(controller)
            }
        }, ContextCompat.getMainExecutor(application))

        // Periodic position tracker to update progress
        viewModelScope.launch {
            while (true) {
                delay(500)
                val p = playerController
                if (p != null) {
                    val currentTrack = _currentlyPlaying.value
                    if (currentTrack != null) {
                        val pos = p.currentPosition
                        val dur = if (p.duration > 0) p.duration else currentTrack.duration
                        _currentPositionMs.value = pos
                        _currentDurationMs.value = dur
                        if (p.isPlaying) {
                            progressManager.saveProgress(currentTrack.id, pos, dur)
                            val isCompleted = progressManager.isTrackCompleted(currentTrack.id)
                            updateTrackState(currentTrack.id, isCompleted = isCompleted, positionMs = pos)
                        }
                    }
                }
            }
        }
    }

    private fun setupPlayerListener(player: Player) {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val uri = mediaItem?.localConfiguration?.uri
                val current = _rawAudioList.value.find { it.contentUri == uri }
                _currentlyPlaying.value = current
                if (current != null) {
                    _currentDurationMs.value = current.duration
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val currentTrack = _currentlyPlaying.value
                    if (currentTrack != null) {
                        progressManager.markCompleted(currentTrack.id)
                        updateTrackState(currentTrack.id, isCompleted = true, positionMs = currentTrack.duration)
                    }
                }
            }
        })
    }

    private fun updateTrackState(id: Long, isCompleted: Boolean, positionMs: Long) {
        _rawAudioList.value = _rawAudioList.value.map { item ->
            if (item.id == id) {
                item.copy(isCompleted = isCompleted, lastPositionMs = positionMs)
            } else {
                item
            }
        }
    }

    fun loadAudioFiles() {
        viewModelScope.launch {
            _isLoading.value = true
            val files = repository.getAudioFiles()
            _rawAudioList.value = files
            _isLoading.value = false
        }
    }

    fun setSelectedFolder(folder: String?) {
        _selectedFolder.value = folder
    }

    fun updateEditingRegex(pattern: String) {
        _editingRegexPattern.value = pattern
    }

    fun applyRegex() {
        _appliedRegexPattern.value = _editingRegexPattern.value.trim()
        _isEditingRegex.value = false
    }

    fun startEditingRegex() {
        _editingRegexPattern.value = _appliedRegexPattern.value
        _isEditingRegex.value = true
    }

    fun cancelEditingRegex() {
        _editingRegexPattern.value = _appliedRegexPattern.value
        _isEditingRegex.value = false
    }

    fun clearRegex() {
        _editingRegexPattern.value = ""
        _appliedRegexPattern.value = ""
        _isEditingRegex.value = false
    }

    fun setSortCriterion(criterion: SortCriterion) {
        _sortCriterion.value = criterion
    }

    fun setSortOrder(isAscending: Boolean) {
        _isAscendingOrder.value = isAscending
    }

    fun toggleSortOrder() {
        _isAscendingOrder.value = !_isAscendingOrder.value
    }

    fun setPlaybackSpeed(speed: Float) {
        val coercedSpeed = speed.coerceIn(0.25f, 2.0f)
        _playbackSpeed.value = coercedSpeed
        progressManager.savePlaybackSpeed(coercedSpeed)
        playerController?.playbackParameters = PlaybackParameters(coercedSpeed)
    }

    fun seekTo(positionMs: Long) {
        val p = playerController ?: return
        val target = positionMs.coerceIn(0L, _currentDurationMs.value)
        p.seekTo(target)
        _currentPositionMs.value = target
    }

    fun seekForward(deltaMs: Long = 10000L) {
        val current = _currentPositionMs.value
        val dur = _currentDurationMs.value
        val target = (current + deltaMs).coerceAtMost(dur)
        seekTo(target)
    }

    fun seekBackward(deltaMs: Long = 10000L) {
        val current = _currentPositionMs.value
        val target = (current - deltaMs).coerceAtLeast(0L)
        seekTo(target)
    }

    // --- Selection Mode Methods ---
    fun toggleSelectionMode() {
        _isSelectionMode.value = !_isSelectionMode.value
        if (!_isSelectionMode.value) {
            _selectedAudioIds.value = emptySet()
        }
    }

    fun enterSelectionMode(initialId: Long? = null) {
        _isSelectionMode.value = true
        if (initialId != null) {
            _selectedAudioIds.value = setOf(initialId)
        }
    }

    fun exitSelectionMode() {
        _isSelectionMode.value = false
        _selectedAudioIds.value = emptySet()
    }

    fun toggleSelection(id: Long) {
        val currentSet = _selectedAudioIds.value.toMutableSet()
        if (currentSet.contains(id)) {
            currentSet.remove(id)
        } else {
            currentSet.add(id)
        }
        _selectedAudioIds.value = currentSet
    }

    fun selectItemIfNotSelected(id: Long) {
        if (!_selectedAudioIds.value.contains(id)) {
            _selectedAudioIds.value = _selectedAudioIds.value + id
        }
    }

    fun selectAll() {
        val allVisibleIds = uiState.value.audioList.map { it.id }.toSet()
        if (_selectedAudioIds.value.size == allVisibleIds.size) {
            _selectedAudioIds.value = emptySet()
        } else {
            _selectedAudioIds.value = allVisibleIds
        }
    }

    // --- File Deletion Logic ---
    fun deleteSelectedTracks(context: Context, onNeedsIntentSender: (IntentSender) -> Unit) {
        val selectedIds = _selectedAudioIds.value
        if (selectedIds.isEmpty()) return

        val itemsToDelete = _rawAudioList.value.filter { selectedIds.contains(it.id) }
        if (itemsToDelete.isEmpty()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uris = itemsToDelete.map { it.contentUri }
            val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
            onNeedsIntentSender(pendingIntent.intentSender)
        } else {
            // Direct contentResolver deletion for older Android
            viewModelScope.launch {
                itemsToDelete.forEach { item ->
                    runCatching {
                        context.contentResolver.delete(item.contentUri, null, null)
                        progressManager.clearTrackProgress(item.id)
                    }
                }
                onPendingDeletionCompleted()
            }
        }
    }

    fun deleteSingleTrack(item: AudioItem, context: Context, onNeedsIntentSender: (IntentSender) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, listOf(item.contentUri))
            onNeedsIntentSender(pendingIntent.intentSender)
        } else {
            viewModelScope.launch {
                runCatching {
                    context.contentResolver.delete(item.contentUri, null, null)
                    progressManager.clearTrackProgress(item.id)
                }
                onPendingDeletionCompleted()
            }
        }
    }

    fun onPendingDeletionCompleted() {
        viewModelScope.launch {
            loadAudioFiles()
            exitSelectionMode()
        }
    }

    fun playAudio(item: AudioItem) {
        if (_isSelectionMode.value) {
            toggleSelection(item.id)
            return
        }

        val p = playerController ?: return
        val currentList = uiState.value.audioList
        val mediaItems = currentList.map { audio ->
            val metadata = MediaMetadata.Builder()
                .setTitle(audio.title)
                .setArtist(audio.artist)
                .setAlbumTitle(audio.folderName)
                .build()

            MediaItem.Builder()
                .setUri(audio.contentUri)
                .setMediaId(audio.id.toString())
                .setMediaMetadata(metadata)
                .build()
        }

        val startIndex = currentList.indexOf(item)

        if (startIndex != -1) {
            _currentPositionMs.value = item.lastPositionMs
            _currentDurationMs.value = item.duration
            p.setMediaItems(mediaItems, startIndex, item.lastPositionMs)
            p.playbackParameters = PlaybackParameters(_playbackSpeed.value)
            p.prepare()
            p.play()
            _currentlyPlaying.value = item
        }
    }

    fun togglePlayPause() {
        val p = playerController ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.mediaItemCount > 0) {
                p.play()
            }
        }
    }

    fun playNext() {
        val p = playerController ?: return
        if (p.hasNextMediaItem()) {
            p.seekToNextMediaItem()
        }
    }

    fun playPrevious() {
        val p = playerController ?: return
        if (p.hasPreviousMediaItem()) {
            p.seekToPreviousMediaItem()
        }
    }

    override fun onCleared() {
        super.onCleared()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}

private data class Tuple4<A, B, C, D>(
    val v1: A,
    val v2: B,
    val v3: C,
    val v4: D
)

private data class Tuple5<A, B, C, D, E>(
    val v1: A,
    val v2: B,
    val v3: C,
    val v4: D,
    val v5: E
)
