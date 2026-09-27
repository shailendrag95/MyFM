package com.skg.myfm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val viewModel: AudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AudioPlayerApp(viewModel = viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerApp(viewModel: AudioViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var singleItemToDelete by remember { mutableStateOf<AudioItem?>(null) }

    val lazyListState = rememberLazyListState()

    // Launcher for system deletion confirmation (Android 10/11+)
    val intentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.onPendingDeletionCompleted()
        }
    }

    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val storageGranted = permissions[Manifest.permission.READ_MEDIA_AUDIO] == true ||
                permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED

        hasPermission = storageGranted
        if (storageGranted) {
            viewModel.loadAudioFiles()
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            viewModel.loadAudioFiles()
        } else {
            launcher.launch(permissionsToRequest)
        }
    }

    // Confirmation Dialog for Deletion
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                showDeleteConfirmDialog = false
                singleItemToDelete = null
            },
            title = { Text("Delete Audio File(s)?") },
            text = {
                Text(
                    if (singleItemToDelete != null) {
                        "Are you sure you want to permanently delete '${singleItemToDelete?.title}' from storage?"
                    } else {
                        "Are you sure you want to permanently delete ${uiState.selectedAudioIds.size} selected audio file(s) from storage?"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        val targetSingle = singleItemToDelete
                        singleItemToDelete = null
                        if (targetSingle != null) {
                            viewModel.deleteSingleTrack(targetSingle, context) { intentSender ->
                                intentSenderLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                            }
                        } else {
                            viewModel.deleteSelectedTracks(context) { intentSender ->
                                intentSenderLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                            }
                        }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteConfirmDialog = false
                    singleItemToDelete = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Modal Bottom Sheet for Editing Regex Filter & Ordering Options
    if (uiState.isEditingRegex) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.cancelEditingRegex() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Filter & Sorting Options",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (uiState.appliedRegexPattern.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearRegex() }) {
                            Text("Clear Filter", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = uiState.editingRegexPattern,
                    onValueChange = { viewModel.updateEditingRegex(it) },
                    label = { Text("Regex Pattern (e.g. ^[A-Za-z]{2}\\s+\\d{1,4})") },
                    isError = !uiState.isRegexValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (!uiState.isRegexValid) {
                    Text(
                        text = "Invalid Regex syntax",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Sort By Criterion
                Text(
                    text = "Sort By",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = uiState.sortCriterion == SortCriterion.TITLE,
                        onClick = { viewModel.setSortCriterion(SortCriterion.TITLE) },
                        label = { Text("Title") }
                    )
                    FilterChip(
                        selected = uiState.sortCriterion == SortCriterion.DURATION,
                        onClick = { viewModel.setSortCriterion(SortCriterion.DURATION) },
                        label = { Text("Duration") }
                    )
                    FilterChip(
                        selected = uiState.sortCriterion == SortCriterion.REGEX_MATCH,
                        onClick = { viewModel.setSortCriterion(SortCriterion.REGEX_MATCH) },
                        label = { Text("Regex Match") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Sort Order Direction
                Text(
                    text = "Order Direction",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = uiState.isAscendingOrder,
                        onClick = { viewModel.setSortOrder(true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text("Ascending (A-Z / 1-9)")
                    }
                    SegmentedButton(
                        selected = !uiState.isAscendingOrder,
                        onClick = { viewModel.setSortOrder(false) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text("Descending (Z-A / 9-1)")
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { viewModel.cancelEditingRegex() }) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { viewModel.applyRegex() }) {
                        Text("Apply Options")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    Scaffold(
        topBar = {
            if (uiState.isSelectionMode) {
                TopAppBar(
                    title = { Text("${uiState.selectedAudioIds.size} Selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.exitSelectionMode() }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Close Selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(imageVector = Icons.Default.SelectAll, contentDescription = "Select All")
                        }
                        IconButton(
                            onClick = { showDeleteConfirmDialog = true },
                            enabled = uiState.selectedAudioIds.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Selected",
                                tint = if (uiState.selectedAudioIds.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        // Toggle Order Direction Quick Icon
                        IconButton(onClick = { viewModel.toggleSortOrder() }) {
                            Icon(
                                imageVector = if (uiState.isAscendingOrder) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                contentDescription = if (uiState.isAscendingOrder) "Ascending Order" else "Descending Order",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        // Filter & Sort Bottom Sheet Icon
                        IconButton(
                            onClick = {
                                if (uiState.isEditingRegex) {
                                    viewModel.cancelEditingRegex()
                                } else {
                                    viewModel.startEditingRegex()
                                }
                            }
                        ) {
                            BadgedBox(
                                badge = {
                                    if (uiState.appliedRegexPattern.isNotEmpty() || !uiState.isAscendingOrder || uiState.sortCriterion != SortCriterion.TITLE) {
                                        Badge()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FilterList,
                                    contentDescription = "Filter Options",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(visible = uiState.currentlyPlaying != null && !uiState.isSelectionMode) {
                uiState.currentlyPlaying?.let { playingItem ->
                    PlaybackControlBottomBar(
                        item = playingItem,
                        isPlaying = uiState.isPlaying,
                        currentPositionMs = uiState.currentPositionMs,
                        durationMs = if (uiState.currentDurationMs > 0) uiState.currentDurationMs else playingItem.duration,
                        playbackSpeed = uiState.playbackSpeed,
                        onSeekTo = { viewModel.seekTo(it) },
                        onSeekBackward = { viewModel.seekBackward() },
                        onSeekForward = { viewModel.seekForward() },
                        onSpeedSelected = { viewModel.setPlaybackSpeed(it) },
                        onPlayPauseClick = { viewModel.togglePlayPause() },
                        onNextClick = { viewModel.playNext() },
                        onPreviousClick = { viewModel.playPrevious() }
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (!hasPermission) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Button(onClick = { launcher.launch(permissionsToRequest) }) {
                        Text("Grant Storage Permission")
                    }
                }
            } else if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                // Folder Selection Chips
                FolderSelectorRow(
                    availableFolders = uiState.availableFolders,
                    selectedFolder = uiState.selectedFolder,
                    onFolderSelected = { viewModel.setSelectedFolder(it) }
                )

                // Compact Active Regex Chip (if filter applied)
                if (uiState.appliedRegexPattern.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        InputChip(
                            selected = true,
                            onClick = { viewModel.startEditingRegex() },
                            label = { Text("Regex: \"${uiState.appliedRegexPattern}\"") },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear Regex Filter",
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { viewModel.clearRegex() }
                                )
                            }
                        )
                    }
                }

                PullToRefreshBox(
                    isRefreshing = uiState.isLoading,
                    onRefresh = { viewModel.loadAudioFiles() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (uiState.audioList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when {
                                    uiState.appliedRegexPattern.isNotEmpty() && uiState.selectedFolder != null ->
                                        "No files in '${uiState.selectedFolder}' matching regex '${uiState.appliedRegexPattern}'."
                                    uiState.appliedRegexPattern.isNotEmpty() ->
                                        "No audio files matching regex '${uiState.appliedRegexPattern}'."
                                    uiState.selectedFolder != null ->
                                        "No audio files in folder '${uiState.selectedFolder}'."
                                    else ->
                                        "No audio files found on device."
                                },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    } else {
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(uiState.isSelectionMode) {
                                    if (uiState.isSelectionMode) {
                                        detectDragGestures(
                                            onDragStart = { offset ->
                                                val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
                                                val touchedItem = visibleItems.find { item ->
                                                    offset.y >= item.offset && offset.y <= (item.offset + item.size)
                                                }
                                                touchedItem?.let { item ->
                                                    val track = uiState.audioList.getOrNull(item.index)
                                                    track?.let { audio ->
                                                        viewModel.selectItemIfNotSelected(audio.id)
                                                    }
                                                }
                                            },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                val y = change.position.y
                                                val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
                                                val touchedItem = visibleItems.find { item ->
                                                    y >= item.offset && y <= (item.offset + item.size)
                                                }
                                                touchedItem?.let { item ->
                                                    val track = uiState.audioList.getOrNull(item.index)
                                                    track?.let { audio ->
                                                        viewModel.selectItemIfNotSelected(audio.id)
                                                    }
                                                }
                                            }
                                        )
                                    }
                                },
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {
                            items(
                                items = uiState.audioList,
                                key = { it.id }
                            ) { item ->
                                AudioItemRow(
                                    item = item,
                                    isCurrentlyPlaying = item.id == uiState.currentlyPlaying?.id,
                                    isSelectionMode = uiState.isSelectionMode,
                                    isSelected = uiState.selectedAudioIds.contains(item.id),
                                    onItemClick = { viewModel.playAudio(item) },
                                    onItemLongClick = { viewModel.enterSelectionMode(item.id) },
                                    onDeleteSingle = {
                                        singleItemToDelete = item
                                        showDeleteConfirmDialog = true
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSelectorRow(
    availableFolders: List<String>,
    selectedFolder: String?,
    onFolderSelected: (String?) -> Unit
) {
    if (availableFolders.isEmpty()) return

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Select Folder",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 16.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedFolder == null,
                    onClick = { onFolderSelected(null) },
                    label = { Text("All Folders") }
                )
            }
            items(availableFolders) { folder ->
                FilterChip(
                    selected = selectedFolder == folder,
                    onClick = { onFolderSelected(folder) },
                    label = { Text(folder) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AudioItemRow(
    item: AudioItem,
    isCurrentlyPlaying: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onItemClick: () -> Unit,
    onItemLongClick: () -> Unit,
    onDeleteSingle: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = onItemClick,
            onLongClick = onItemLongClick
        ),
        headlineContent = {
            Text(
                text = item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp,
                fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Normal
            )
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${item.artist} • ${item.folderName} • ${formatDuration(item.duration)}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        },
        leadingContent = {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onItemClick() }
                )
            } else {
                // Playback Status Indicator
                when {
                    item.isCompleted -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Completed",
                            tint = Color(0xFF4CAF50), // Green completed checkmark
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    item.lastPositionMs > 0 -> {
                        val percentage = (item.progressPercentage * 100).toInt()
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "$percentage%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = "Unplayed",
                            tint = if (isCurrentlyPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        trailingContent = {
            if (!isSelectionMode) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More Options"
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Delete from Storage", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                showMenu = false
                                onDeleteSingle()
                            }
                        )
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                isCurrentlyPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else -> Color.Transparent
            }
        )
    )
}

@Composable
fun PlaybackControlBottomBar(
    item: AudioItem,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    onSeekTo: (Long) -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit
) {
    var showSpeedMenu by remember { mutableStateOf(false) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }

    val speedOptions = remember { listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f) }
    val effectiveDuration = durationMs.coerceAtLeast(1L)
    val displayPosition = if (isDraggingSlider) sliderPosition.toLong() else currentPositionMs.coerceIn(0L, effectiveDuration)

    Surface(
        shadowElevation = 8.dp,
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // Interactive Progress Bar Slider & Timestamps
            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = if (isDraggingSlider) sliderPosition else displayPosition.toFloat(),
                    onValueChange = {
                        isDraggingSlider = true
                        sliderPosition = it
                    },
                    onValueChangeFinished = {
                        isDraggingSlider = false
                        onSeekTo(sliderPosition.toLong())
                    },
                    valueRange = 0f..effectiveDuration.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatDuration(displayPosition),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatDuration(effectiveDuration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${item.artist} • ${item.folderName}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = onSeekBackward) {
                    Icon(
                        imageVector = Icons.Default.FastRewind,
                        contentDescription = "Rewind 10s"
                    )
                }

                IconButton(onClick = onPreviousClick) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous"
                    )
                }
                IconButton(onClick = onPlayPauseClick) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
                IconButton(onClick = onNextClick) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next"
                    )
                }

                IconButton(onClick = onSeekForward) {
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = "Fast Forward 10s"
                    )
                }

                // Speed Selector Button & Dropdown
                Box {
                    TextButton(onClick = { showSpeedMenu = true }) {
                        Text(
                            text = formatSpeed(playbackSpeed),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    DropdownMenu(
                        expanded = showSpeedMenu,
                        onDismissRequest = { showSpeedMenu = false }
                    ) {
                        speedOptions.forEach { speed ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = formatSpeed(speed),
                                        fontWeight = if (speed == playbackSpeed) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    onSpeedSelected(speed)
                                    showSpeedMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

fun formatSpeed(speed: Float): String {
    val rounded = (speed * 100).toInt()
    val whole = rounded / 100
    val frac = rounded % 100
    return if (frac == 0) "${whole}.0x" else String.format(Locale.US, "%.2fx", speed)
}

fun formatDuration(durationMs: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
