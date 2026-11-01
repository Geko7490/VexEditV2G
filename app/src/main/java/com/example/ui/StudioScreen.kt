package com.example.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.*
import com.example.engine.PlaybackStatus
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ActiveTrimSession(
    val clipId: String,
    val isLeftHandle: Boolean,
    val initialStartTimeMs: Long,
    val initialDurationMs: Long,
    val initialSourceStartMs: Long,
    val sourceDurationMs: Long,
    val isVideo: Boolean,
    val trackId: String,
    val currentDeltaMs: Long = 0L
)

@Composable
fun StudioScreen(
    viewModel: StudioViewModel,
    onNavigate: (Screen) -> Unit
) {
    val project = viewModel.currentProject.collectAsState().value
    val lang = viewModel.settings.collectAsState().value.language
    val playheadMs = viewModel.playheadPositionMs.collectAsState().value
    val isPlaying = viewModel.isPlaying.collectAsState().value
    val selectedClipId = viewModel.selectedClipId.collectAsState().value
    val canUndo = viewModel.canUndo.collectAsState().value
    val canRedo = viewModel.canRedo.collectAsState().value
    val playerState by viewModel.player.playerState.collectAsState()
    val screenWidthDp = LocalConfiguration.current.screenWidthDp

    val showExportDialog = viewModel.showExportDialog.collectAsState().value
    val isExporting = viewModel.isExporting.collectAsState().value
    val exportProgress = viewModel.exportProgress.collectAsState().value
    val exportStage = viewModel.exportStage.collectAsState().value
    val exportResult = viewModel.exportResult.collectAsState().value

    // Active bottom tool panel: "edit", "speed", "volume", "crop"
    var activeTool by remember { mutableStateOf<String?>(null) }
    var saveFeedback by remember { mutableStateOf(false) }
    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var isSnappingEnabled by remember { mutableStateOf(true) }
    var draggingClipId by remember { mutableStateOf<String?>(null) }
    var dragDeltaPx by remember { mutableFloatStateOf(0f) }
    var dragDeltaYPx by remember { mutableFloatStateOf(0f) }
    var snapGuideTimeMs by remember { mutableStateOf<Long?>(null) }
    var activeTrimmingInfo by remember { mutableStateOf<String?>(null) }
    var activeTrimSession by remember { mutableStateOf<ActiveTrimSession?>(null) }
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var multiSelectedClipIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bulkMoveMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(project?.id, project?.clips?.map { it.id }) {
        val existingIds = project?.clips?.mapTo(mutableSetOf()) { it.id } ?: emptySet()
        multiSelectedClipIds = multiSelectedClipIds.intersect(existingIds)
        if (multiSelectedClipIds.isEmpty()) isMultiSelectMode = false
    }

    val selectedClip = remember(project, selectedClipId) {
        project?.clips?.find { it.id == selectedClipId }
    }

    // Determine visual clip active at current playhead
    val visualClips = remember(project) {
        project?.clips?.filter {
            it.trackType == TrackType.VIDEO || it.mediaType == MediaType.PHOTO || it.mediaType == MediaType.VIDEO
        } ?: emptyList()
    }

    val activeVisualClip = remember(visualClips, playheadMs) {
        visualClips.find { playheadMs >= it.startTimeMs && playheadMs < (it.startTimeMs + it.durationMs) }
            ?: (if (visualClips.isNotEmpty() && playheadMs <= 0L) visualClips.first() else null)
    }

    // Synchronize media player to current playhead and clip
    LaunchedEffect(activeVisualClip?.filePath, playheadMs, isPlaying) {
        viewModel.syncPlayerToPlayhead(playheadMs)
    }

    ExportDialog(
        isOpen = showExportDialog,
        isExporting = isExporting,
        progress = exportProgress,
        stage = exportStage,
        result = exportResult,
        onDismiss = { viewModel.closeExportDialog() },
        onStartExport = { config -> viewModel.startExport(config) },
        lang = lang
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VexBackground)
    ) {
        // --- TOP TOOLBAR ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onNavigate(Screen.HOME) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = VexTextPrimary
                )
            }

            Text(
                text = project?.title ?: Strings.get("new_project", lang),
                color = VexTextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
            )

            // Undo
            IconButton(
                onClick = { viewModel.undo() },
                enabled = canUndo,
                modifier = Modifier.testTag("studio_undo_btn")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = Strings.get("undo", lang),
                    tint = if (canUndo) VexTextPrimary else VexTextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Redo
            IconButton(
                onClick = { viewModel.redo() },
                enabled = canRedo,
                modifier = Modifier.testTag("studio_redo_btn")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Redo,
                    contentDescription = Strings.get("redo", lang),
                    tint = if (canRedo) VexTextPrimary else VexTextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Save
            IconButton(
                onClick = {
                    project?.let { viewModel.projectRepo.saveProject(it) }
                    saveFeedback = true
                },
                modifier = Modifier.testTag("studio_save_btn")
            ) {
                Icon(
                    imageVector = if (saveFeedback) Icons.Default.Check else Icons.Default.Save,
                    contentDescription = Strings.get("save", lang),
                    tint = if (saveFeedback) VexMeterGreen else VexTextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // EXPORT BUTTON
            Button(
                onClick = { viewModel.openExportDialog() },
                colors = ButtonDefaults.buttonColors(containerColor = VexAccent),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("studio_export_btn")
            ) {
                Text(
                    text = Strings.get("export", lang).uppercase(),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // --- VIDEO / PHOTO PREVIEW PLAYER ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.95f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val ratio = project?.aspectRatio?.ratio ?: (9f / 16f)

            Box(
                modifier = Modifier
                    .fillMaxHeight(0.92f)
                    .aspectRatio(ratio)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF0C0C10))
                    .clickable {
                        if (project != null && project.clips.isNotEmpty()) {
                            viewModel.togglePlayback()
                        } else {
                            onNavigate(Screen.IMPORT_MEDIA)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (project == null || project.clips.isEmpty()) {
                    // REQUIRED CLEAN EMPTY STATE: "Medya eklemek için + düğmesine dokun"
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(VexPanelElevated)
                                .border(1.dp, VexAccent, CircleShape)
                                .clickable { onNavigate(Screen.IMPORT_MEDIA) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Media",
                                tint = VexAccentLight,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = Strings.get("tap_to_add_media", lang),
                            color = VexTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }
                } else if (activeVisualClip != null) {
                    val scaleFactor = (viewModel.getInterpolatedValue(activeVisualClip, KeyframeParam.SCALE) / 100f).coerceIn(0.2f, 3f)
                    val rot = viewModel.getInterpolatedValue(activeVisualClip, KeyframeParam.ROTATION)
                    val posX = viewModel.getInterpolatedValue(activeVisualClip, KeyframeParam.POSITION_X)
                    val posY = viewModel.getInterpolatedValue(activeVisualClip, KeyframeParam.POSITION_Y)
                    val opacity = (viewModel.getInterpolatedValue(activeVisualClip, KeyframeParam.OPACITY) / 100f).coerceIn(0f, 1f)

                    val activeEffectColorFilter = remember(activeVisualClip.effects) {
                        val enabledEffects = activeVisualClip.effects.filter { it.isEnabled }
                        when {
                            enabledEffects.any { it.type == EffectType.VIOLET_GLOW } -> {
                                val eff = enabledEffects.first { it.type == EffectType.VIOLET_GLOW }
                                ColorFilter.tint(VexAccent.copy(alpha = 0.35f * eff.opacity))
                            }
                            enabledEffects.any { it.type == EffectType.WARMTH } -> {
                                val eff = enabledEffects.first { it.type == EffectType.WARMTH }
                                ColorFilter.tint(Color(0xFFFF9800).copy(alpha = 0.3f * eff.opacity))
                            }
                            enabledEffects.any { it.type == EffectType.COOL_CYAN } -> {
                                val eff = enabledEffects.first { it.type == EffectType.COOL_CYAN }
                                ColorFilter.tint(Color(0xFF00BCD4).copy(alpha = 0.3f * eff.opacity))
                            }
                            enabledEffects.any { it.type == EffectType.VINTAGE } -> {
                                val eff = enabledEffects.first { it.type == EffectType.VINTAGE }
                                ColorFilter.tint(Color(0xFF704214).copy(alpha = 0.35f * eff.opacity))
                            }
                            else -> null
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .offset(x = posX.dp, y = posY.dp)
                            .scale(scaleFactor)
                            .rotate(rot),
                        contentAlignment = Alignment.Center
                    ) {
                        if (activeVisualClip.mediaType == MediaType.PHOTO) {
                            val imgPath = activeVisualClip.filePath.ifEmpty { activeVisualClip.thumbnailPath }
                            if (imgPath.isNotEmpty()) {
                                AsyncImage(
                                    model = imgPath,
                                    contentDescription = activeVisualClip.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    alpha = opacity,
                                    colorFilter = activeEffectColorFilter
                                )
                            } else {
                                Icon(Icons.Default.BrokenImage, contentDescription = null, tint = VexTextMuted)
                            }
                        } else if (activeVisualClip.mediaType == MediaType.VIDEO) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                val thumbPath = activeVisualClip.thumbnailPath.ifEmpty { activeVisualClip.filePath }
                                if (thumbPath.isNotEmpty()) {
                                    AsyncImage(
                                        model = thumbPath,
                                        contentDescription = activeVisualClip.name,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                        alpha = opacity,
                                        colorFilter = activeEffectColorFilter
                                    )
                                }

                                if (playerState.status != PlaybackStatus.ERROR) {
                                    AndroidView(
                                        factory = { ctx ->
                                            TextureView(ctx).apply {
                                                isOpaque = true
                                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                                        val surface = Surface(st)
                                                        viewModel.player.attachSurface(surface)
                                                        viewModel.syncPlayerToPlayhead(viewModel.playheadPositionMs.value)
                                                    }
                                                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                                        viewModel.player.attachSurface(null)
                                                        return true
                                                    }
                                                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                if (playerState.status == PlaybackStatus.LOADING) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(32.dp).align(Alignment.Center),
                                        color = VexAccent,
                                        strokeWidth = 3.dp
                                    )
                                }

                                if (playerState.status == PlaybackStatus.ERROR) {
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.88f),
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252)),
                                        modifier = Modifier.padding(16.dp).align(Alignment.Center)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.WarningAmber,
                                                contentDescription = null,
                                                tint = Color(0xFFFF5252),
                                                modifier = Modifier.size(28.dp)
                                            )
                                            Text(
                                                text = "Video Oynatılamadı",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                            Text(
                                                text = playerState.errorMessage ?: "Desteklenmeyen video veya ses codec'i",
                                                color = VexTextSecondary,
                                                fontSize = 10.sp,
                                                textAlign = TextAlign.Center
                                            )
                                            Button(
                                                onClick = { viewModel.deleteClip(activeVisualClip.id) },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text("Klibi Kaldır", fontSize = 11.sp, color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Render active text overlay clip if present
                    val activeText = project.clips.filter { it.trackType == TrackType.TEXT }
                        .find { playheadMs >= it.startTimeMs && playheadMs < (it.startTimeMs + it.durationMs) }
                    if (activeText != null) {
                        Text(
                            text = activeText.name,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 20.dp)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    // Play/Pause icon badge when paused
                    if (!isPlaying) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // Live Trimming Info Badge on Preview
                    if (activeTrimmingInfo != null) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, VexAccent),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCut,
                                    contentDescription = null,
                                    tint = VexAccentLight,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = activeTrimmingInfo ?: "",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                } else {
                    // Blank space on timeline between clips
                    Text(
                        text = "Playhead: ${formatTimecode(playheadMs)}",
                        color = VexTextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // --- PREVIEW CONTROLS & TIMECODE BAR ---
        Surface(
            color = VexPanelElevated,
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 0.5.dp, color = VexBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Timecode Readout: Current / Total
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTimecode(playheadMs),
                        color = VexAccentLight,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = " / " + formatTimecode(project?.durationMs ?: 0L),
                        color = VexTextSecondary,
                        fontSize = 11.sp
                    )
                }

                // Playback Transport Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Previous Cut / Step Back
                    IconButton(
                        onClick = { viewModel.jumpToPrevCut() },
                        modifier = Modifier.size(36.dp).testTag("studio_prev_cut_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = Strings.get("step_back", lang),
                            tint = VexTextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Main Play / Pause Button (44dp touch target)
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(VexAccent)
                            .clickable { viewModel.togglePlayback() }
                            .testTag("studio_play_pause_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    // Next Cut / Step Forward
                    IconButton(
                        onClick = { viewModel.jumpToNextCut() },
                        modifier = Modifier.size(36.dp).testTag("studio_next_cut_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = Strings.get("step_forward", lang),
                            tint = VexTextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Add Media Shortcut
                IconButton(
                    onClick = { onNavigate(Screen.IMPORT_MEDIA) },
                    modifier = Modifier.size(36.dp).testTag("studio_add_media_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = Strings.get("add_media", lang),
                        tint = VexAccent,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // --- TIMELINE ACTION TOOLBAR ---
        val canSplitAtPlayhead = selectedClip != null &&
                playheadMs > selectedClip.startTimeMs + 200L &&
                playheadMs < (selectedClip.startTimeMs + selectedClip.durationMs - 200L)

        Surface(
            color = VexPanel,
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 0.5.dp, color = VexBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .horizontalScroll(rememberScrollState()),
                if (isMultiSelectMode) {
                    Text(
                        text = "${multiSelectedClipIds.size} selected",
                        color = VexAccentLight,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 5.dp)
                    )
                    FilledTonalButton(
                        onClick = { viewModel.deleteClips(multiSelectedClipIds); multiSelectedClipIds = emptySet(); isMultiSelectMode = false },
                        enabled = multiSelectedClipIds.isNotEmpty(),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = VexPanelElevated, contentColor = VexMeterRed),
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp).testTag("timeline_bulk_delete_btn")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Delete", fontSize = 11.sp)
                    }
                    Box {
                        FilledTonalButton(
                            onClick = { bulkMoveMenuExpanded = true },
                            enabled = multiSelectedClipIds.isNotEmpty(),
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = VexPanelElevated, contentColor = VexAccentLight),
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp).testTag("timeline_bulk_move_btn")
                        ) {
                            Icon(Icons.Default.DriveFileMove, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("Move to", fontSize = 11.sp)
                        }
                        DropdownMenu(expanded = bulkMoveMenuExpanded, onDismissRequest = { bulkMoveMenuExpanded = false }) {
                            (project?.tracks ?: emptyList()).forEach { target ->
                                DropdownMenuItem(
                                    text = { Text(target.title, fontSize = 12.sp) },
                                    onClick = {
                                        viewModel.moveClipsToTrack(multiSelectedClipIds, target.id)
                                        bulkMoveMenuExpanded = false
                                    },
                                    enabled = multiSelectedClipIds.any { id ->
                                        project?.clips?.any { it.id == id && it.trackId != target.id } == true
                                    }
                                )
                            }
                        }
                    }
                    FilledTonalButton(
                        onClick = { isMultiSelectMode = false; multiSelectedClipIds = emptySet() },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = VexPanelElevated, contentColor = VexTextSecondary),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) { Text("Done", fontSize = 11.sp) }
                } else {
                    FilledTonalButton(
                        onClick = { isMultiSelectMode = true; multiSelectedClipIds = selectedClipId?.let { setOf(it) } ?: emptySet() },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = VexPanelElevated, contentColor = VexAccentLight),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp).testTag("timeline_multi_select_btn")
                    ) { Text("Select", fontSize = 11.sp) }
                }

                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Add Media (+)
                FilledTonalButton(
                    onClick = { onNavigate(Screen.IMPORT_MEDIA) },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = VexPanelElevated,
                        contentColor = VexAccent
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp).testTag("timeline_add_btn")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(Strings.get("add_media", lang), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                // Split at Playhead (Scissors)
                FilledTonalButton(
                    onClick = { viewModel.splitClipAtPlayhead() },
                    enabled = canSplitAtPlayhead || (project?.clips?.any { playheadMs > it.startTimeMs + 200L && playheadMs < (it.startTimeMs + it.durationMs - 200L) } == true),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = VexPanelElevated,
                        contentColor = if (canSplitAtPlayhead) VexAccent else VexTextMuted
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp).testTag("timeline_split_btn")
                ) {
                    Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(Strings.get("split", lang), fontSize = 11.sp)
                }

                // Duplicate
                FilledTonalButton(
                    onClick = { selectedClip?.let { viewModel.duplicateClip(it.id) } },
                    enabled = selectedClip != null,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = VexPanelElevated,
                        contentColor = if (selectedClip != null) VexTextPrimary else VexTextMuted
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp).testTag("timeline_duplicate_btn")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(Strings.get("duplicate", lang), fontSize = 11.sp)
                }

                // Delete
                FilledTonalButton(
                    onClick = { selectedClip?.let { viewModel.deleteClip(it.id) } },
                    enabled = selectedClip != null,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = VexPanelElevated,
                        contentColor = if (selectedClip != null) VexMeterRed else VexTextMuted
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp).testTag("timeline_delete_btn")
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(Strings.get("delete", lang), fontSize = 11.sp)
                }

                // Move Left in Sequence
                IconButton(
                    onClick = { selectedClip?.let { viewModel.moveClipLeft(it.id) } },
                    enabled = selectedClip != null,
                    modifier = Modifier.size(34.dp).testTag("timeline_move_left_btn")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = Strings.get("move_left", lang),
                        tint = if (selectedClip != null) VexTextPrimary else VexTextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Move Right in Sequence
                IconButton(
                    onClick = { selectedClip?.let { viewModel.moveClipRight(it.id) } },
                    enabled = selectedClip != null,
                    modifier = Modifier.size(34.dp).testTag("timeline_move_right_btn")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = Strings.get("move_right", lang),
                        tint = if (selectedClip != null) VexTextPrimary else VexTextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                FilledTonalButton(
                    onClick = { isSnappingEnabled = !isSnappingEnabled },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isSnappingEnabled) VexAccent.copy(alpha = 0.22f) else VexPanelElevated,
                        contentColor = if (isSnappingEnabled) VexAccentLight else VexTextSecondary
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp).testTag("timeline_snap_toggle")
                ) {
                    Text("SNAP", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }

                FilledTonalButton(
                    onClick = {
                        val durationMs = project?.durationMs?.coerceAtLeast(1000L) ?: 10000L
                        val availableWidthDp = (screenWidthDp - 110).coerceAtLeast(120)
                        zoomScale = (availableWidthDp.toFloat() / (durationMs * 0.05f)).coerceIn(0.35f, 3.5f)
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = VexPanelElevated,
                        contentColor = VexTextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp).testTag("timeline_zoom_fit_btn")
                ) {
                    Text("FIT", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }

                // Zoom Out
                IconButton(
                    onClick = { zoomScale = (zoomScale / 1.3f).coerceAtLeast(0.35f) },
                    modifier = Modifier.size(34.dp).testTag("timeline_zoom_out_btn")
                ) {
                    Icon(
                        Icons.Default.ZoomOut,
                        contentDescription = Strings.get("zoom_out", lang),
                        tint = VexTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Text(
                    text = "${(zoomScale * 100).toInt()}%",
                    color = VexTextSecondary,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )

                // Zoom In
                IconButton(
                    onClick = { zoomScale = (zoomScale * 1.3f).coerceAtMost(3.5f) },
                    modifier = Modifier.size(34.dp).testTag("timeline_zoom_in_btn")
                ) {
                    Icon(
                        Icons.Default.ZoomIn,
                        contentDescription = Strings.get("zoom_in", lang),
                        tint = VexTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // --- TIMELINE HEADER & TIMECODE RULER ---
        val totalDurationMs = project?.durationMs?.coerceAtLeast(1000L) ?: 10000L
        val timelineScrollState = rememberScrollState()
        val density = LocalDensity.current
        val dpPerMs = (0.05f * zoomScale).coerceIn(0.015f, 0.25f)
        val pxPerMs = with(density) { dpPerMs.dp.toPx() }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(VexPanelElevated)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(85.dp)) // Matches track header width

            // Time ruler ticks
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(timelineScrollState)
                    .pointerInput(totalDurationMs, pxPerMs) {
                        detectTapGestures { offset ->
                            val clickedMs = (offset.x / pxPerMs).toLong()
                            viewModel.setPlayheadPosition(clickedMs.coerceIn(0L, totalDurationMs))
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                val stepMs = when {
                    pxPerMs >= 0.12f -> 1000L
                    pxPerMs >= 0.06f -> 2500L
                    pxPerMs >= 0.03f -> 5000L
                    else -> 10000L
                }
                val steps = (totalDurationMs / stepMs).toInt() + 4
                for (i in 0 until steps) {
                    val tMs = i * stepMs
                    val isNearPlayhead = Math.abs(tMs - playheadMs) < (stepMs / 2)

                    Text(
                        text = formatDurationMinutesSeconds(tMs),
                        color = if (isNearPlayhead) VexAccentLight else VexTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = if (isNearPlayhead) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .width((stepMs * dpPerMs).dp)
                            .clickable { viewModel.setPlayheadPosition(tMs) }
                    )
                }
            }

            // Purple Playhead Readout
            Text(
                text = formatTimecode(playheadMs),
                color = VexAccentLight,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 6.dp)
            )
        }

        // --- MULTI-TRACK TIMELINE CONTAINER ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.05f)
                .background(VexBackground)
                .border(width = 0.5.dp, color = VexBorder)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Left Column: Track Headers
                Column(
                    modifier = Modifier
                        .width(85.dp)
                        .fillMaxHeight()
                        .background(VexPanel)
                        .border(width = 0.5.dp, color = VexBorder)
                ) {
                    val tracks = project?.tracks ?: Project.defaultTracks()
                    tracks.forEachIndexed { trackIndex, track ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .border(width = 0.5.dp, color = VexBorder)
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    text = track.subtitle.ifEmpty { track.type.label },
                                    color = VexTextSecondary,
                                    fontSize = 9.sp,
                                    maxLines = 1
                                )
                                Text(
                                    text = track.title,
                                    color = VexTextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (track.type == TrackType.AUDIO_MUSIC ||
                                        track.type == TrackType.AUDIO_VOICE ||
                                        track.type == TrackType.AUDIO_SFX
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .width(22.dp)
                                                .height(15.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(if (track.isMuted) VexMeterRed.copy(alpha = 0.28f) else VexPanelElevated)
                                                .clickable { viewModel.toggleTrackMute(track.id) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "M",
                                                color = if (track.isMuted) VexMeterRed else VexTextSecondary,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .width(22.dp)
                                                .height(15.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(if (track.isSolo) VexAccent.copy(alpha = 0.28f) else VexPanelElevated)
                                                .clickable { viewModel.toggleTrackSolo(track.id) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "S",
                                                color = if (track.isSolo) VexAccentLight else VexTextSecondary,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.width(1.dp))
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "↑",
                                            color = if (trackIndex > 0) VexTextSecondary else VexTextSecondary.copy(alpha = 0.25f),
                                            fontSize = 13.sp,
                                            modifier = Modifier.clickable(enabled = trackIndex > 0) {
                                                viewModel.reorderTracks(trackIndex, trackIndex - 1)
                                            }
                                        )
                                        Text(
                                            text = "↓",
                                            color = if (trackIndex < tracks.lastIndex) VexTextSecondary else VexTextSecondary.copy(alpha = 0.25f),
                                            fontSize = 13.sp,
                                            modifier = Modifier.clickable(enabled = trackIndex < tracks.lastIndex) {
                                                viewModel.reorderTracks(trackIndex, trackIndex + 1)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Right Column: Horizontal Scrollable Timeline Tracks
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(timelineScrollState)
                        .pointerInput(totalDurationMs, pxPerMs) {
                            detectTapGestures { offset ->
                                val clickedMs = (offset.x / pxPerMs).toLong()
                                viewModel.setPlayheadPosition(clickedMs.coerceIn(0L, totalDurationMs))
                            }
                        }
                ) {
                    val dynamicTotalDuration = if (activeTrimSession != null) {
                        val d = if (activeTrimSession!!.isLeftHandle) -activeTrimSession!!.currentDeltaMs else activeTrimSession!!.currentDeltaMs
                        (totalDurationMs + d).coerceAtLeast(0L)
                    } else totalDurationMs
                    val timelineWidth = (dynamicTotalDuration * dpPerMs).dp.coerceAtLeast(600.dp)

                    Column(modifier = Modifier.width(timelineWidth)) {
                        val tracks = project?.tracks ?: Project.defaultTracks()
                        tracks.forEach { track ->
                            val sourceTrackIndex = tracks.indexOfFirst { it.id == track.id }
                            val trackClips = project?.clips?.filter { it.trackId == track.id }?.sortedBy { it.startTimeMs } ?: emptyList()
                            val draggedClip = project?.clips?.firstOrNull { it.id == draggingClipId }
                            val draggedSourceIndex = draggedClip?.let { tracks.indexOfFirst { item -> item.id == it.trackId } } ?: -1
                            val rowHeightPx = with(density) { 50.dp.toPx() }
                            val dragTargetIndex = if (draggingClipId != null && draggedSourceIndex >= 0) {
                                (draggedSourceIndex + kotlin.math.round(dragDeltaYPx / rowHeightPx).toInt()).coerceIn(tracks.indices)
                            } else -1
                            val isDropTarget = dragTargetIndex == sourceTrackIndex && draggedSourceIndex != sourceTrackIndex
                            val acceptsDraggedClip = draggedClip?.let { moving ->
                                when (moving.mediaType) {
                                    MediaType.AUDIO -> track.type == TrackType.AUDIO_MUSIC ||
                                        track.type == TrackType.AUDIO_VOICE ||
                                        track.type == TrackType.AUDIO_SFX
                                    MediaType.VIDEO, MediaType.PHOTO -> track.type == TrackType.VIDEO
                                }
                            } == true

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .border(width = if (isDropTarget && acceptsDraggedClip) 1.dp else 0.5.dp, color = if (isDropTarget && acceptsDraggedClip) VexAccent else VexBorder)
                                    .background(if (isDropTarget && acceptsDraggedClip) VexAccent.copy(alpha = 0.12f) else VexPanel.copy(alpha = 0.4f))
                            ) {
                                trackClips.forEachIndexed { clipIndex, clip ->
                                    val isSelected = clip.id == selectedClipId || clip.id in multiSelectedClipIds
                                    val isDraggingThis = draggingClipId == clip.id

                                    val trim = activeTrimSession
                                    val (effectiveStartMs, effectiveDurationMs) = if (trim != null && trim.trackId == track.id) {
                                        if (clip.id == trim.clipId) {
                                            if (trim.isLeftHandle) {
                                                val s = (trim.initialStartTimeMs + trim.currentDeltaMs).coerceAtLeast(0L)
                                                val d = (trim.initialDurationMs - trim.currentDeltaMs).coerceAtLeast(300L)
                                                Pair(s, d)
                                            } else {
                                                val s = trim.initialStartTimeMs
                                                val d = (trim.initialDurationMs + trim.currentDeltaMs).coerceAtLeast(300L)
                                                Pair(s, d)
                                            }
                                        } else if (clip.startTimeMs >= trim.initialStartTimeMs + trim.initialDurationMs) {
                                            val rippleDelta = if (trim.isLeftHandle) 0L else trim.currentDeltaMs
                                            Pair((clip.startTimeMs + rippleDelta).coerceAtLeast(0L), clip.durationMs)
                                        } else {
                                            Pair(clip.startTimeMs, clip.durationMs)
                                        }
                                    } else {
                                        Pair(clip.startTimeMs, clip.durationMs)
                                    }

                                    val rawDraggedStartMs = (clip.startTimeMs + (dragDeltaPx / pxPerMs).toLong()).coerceAtLeast(0L)
                                    val snappedDrag = if (isDraggingThis && isSnappingEnabled) {
                                        viewModel.findTimelineSnap(
                                            clip.id,
                                            rawDraggedStartMs,
                                            (28f / pxPerMs).toLong().coerceIn(40L, 1000L)
                                        )
                                    } else null
                                    val visualStartMs = if (isDraggingThis) snappedDrag?.first ?: rawDraggedStartMs else effectiveStartMs
                                    val leftOffset = (visualStartMs * dpPerMs).dp.coerceAtLeast(0.dp)
                                    val clipWidth = (effectiveDurationMs * dpPerMs).dp.coerceAtLeast(36.dp)

                                    Box(
                                        modifier = Modifier
                                            .offset(
                                                x = leftOffset,
                                                y = if (isDraggingThis) with(density) { dragDeltaYPx.toDp() } else 0.dp
                                            )
                                            .width(clipWidth)
                                            .fillMaxHeight()
                                            .padding(vertical = 3.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                if (isDraggingThis) VexAccent.copy(alpha = 0.5f)
                                                else when (clip.trackType) {
                                                    TrackType.VIDEO -> VexPanelElevated
                                                    TrackType.AUDIO_MUSIC -> VexWaveformMusic.copy(alpha = 0.25f)
                                                    TrackType.AUDIO_SFX -> VexWaveformSfx.copy(alpha = 0.25f)
                                                    TrackType.VFX -> VexTrackVfx.copy(alpha = 0.25f)
                                                    TrackType.TEXT -> VexTrackText.copy(alpha = 0.25f)
                                                    else -> VexPanelElevated
                                                }
                                            )
                                            .border(
                                                width = if (isSelected) 2.dp else 1.dp,
                                                color = if (isSelected) VexAccent else VexBorder,
                                                shape = RoundedCornerShape(4.dp)
                                            )
                                            .clickable {
                                                if (isMultiSelectMode) {
                                                    multiSelectedClipIds = if (clip.id in multiSelectedClipIds) {
                                                        multiSelectedClipIds - clip.id
                                                    } else {
                                                        multiSelectedClipIds + clip.id
                                                    }
                                                } else {
                                                    viewModel.selectClip(clip.id)
                                                }
                                            }
                                            .pointerInput(clip.id, pxPerMs, track.id, tracks.size, isMultiSelectMode) {
                                                if (!isMultiSelectMode) detectDragGesturesAfterLongPress(
                                                    onDragStart = {
                                                        draggingClipId = clip.id
                                                        dragDeltaPx = 0f
                                                        dragDeltaYPx = 0f
                                                        viewModel.selectClip(clip.id)
                                                    },
                                                    onDragEnd = {
                                                        val rawStartMs = (clip.startTimeMs + (dragDeltaPx / pxPerMs).toLong()).coerceAtLeast(0L)
                                                        val snappedStartMs = if (isSnappingEnabled) {
                                                            viewModel.findTimelineSnap(
                                                                clip.id,
                                                                rawStartMs,
                                                                (28f / pxPerMs).toLong().coerceIn(40L, 1000L)
                                                            )?.first ?: rawStartMs
                                                        } else rawStartMs
                                                        val rowHeightPx = with(density) { 50.dp.toPx() }
                                                        val rowDelta = kotlin.math.round(dragDeltaYPx / rowHeightPx).toInt()
                                                        val targetTrackIndex = (sourceTrackIndex + rowDelta).coerceIn(tracks.indices)
                                                        val targetTrack = tracks.getOrNull(targetTrackIndex)
                                                        if (targetTrack != null && targetTrack.id != track.id) {
                                                            viewModel.moveClipToTrack(clip.id, targetTrack.id, snappedStartMs)
                                                        } else if (snappedStartMs != clip.startTimeMs) {
                                                            viewModel.moveClip(clip.id, snappedStartMs)
                                                        }
                                                        draggingClipId = null
                                                        dragDeltaPx = 0f
                                                        dragDeltaYPx = 0f
                                                        snapGuideTimeMs = null
                                                    },
                                                    onDragCancel = {
                                                        draggingClipId = null
                                                        dragDeltaPx = 0f
                                                        dragDeltaYPx = 0f
                                                        snapGuideTimeMs = null
                                                    },
                                                    onDrag = { _, dragAmount ->
                                                        dragDeltaPx += dragAmount.x
                                                        dragDeltaYPx += dragAmount.y
                                                        val rawStartMs = (clip.startTimeMs + (dragDeltaPx / pxPerMs).toLong()).coerceAtLeast(0L)
                                                        snapGuideTimeMs = if (isSnappingEnabled) {
                                                            viewModel.findTimelineSnap(
                                                                clip.id,
                                                                rawStartMs,
                                                                (28f / pxPerMs).toLong().coerceIn(40L, 1000L)
                                                            )?.second
                                                        } else null
                                                    }
                                                )
                                            }
                                    ) {
                                        // Clip Media Content Thumbnail / Waveform / Title
                                        if (clip.mediaType == MediaType.PHOTO || clip.mediaType == MediaType.VIDEO) {
                                            Row(modifier = Modifier.fillMaxSize().padding(horizontal = if (isSelected) 18.dp else 4.dp)) {
                                                val thumbPath = clip.thumbnailPath.ifEmpty { clip.filePath }
                                                if (thumbPath.isNotEmpty()) {
                                                    AsyncImage(
                                                        model = thumbPath,
                                                        contentDescription = null,
                                                        modifier = Modifier.fillMaxHeight().width(46.dp),
                                                        contentScale = ContentScale.Crop
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = clip.name,
                                                    color = Color.White,
                                                    fontSize = 9.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.align(Alignment.CenterVertically).padding(end = 6.dp)
                                                )
                                            }
                                        } else if (clip.trackType == TrackType.AUDIO_MUSIC || clip.trackType == TrackType.AUDIO_SFX) {
                                            WaveformRow(
                                                points = clip.waveformPoints,
                                                color = if (clip.trackType == TrackType.AUDIO_MUSIC) VexWaveformMusic else VexWaveformSfx,
                                                title = clip.name,
                                                modifier = Modifier.padding(horizontal = if (isSelected) 18.dp else 4.dp)
                                            )
                                        } else {
                                            Text(
                                                text = clip.name,
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.align(Alignment.Center).padding(horizontal = if (isSelected) 18.dp else 4.dp)
                                            )
                                        }

                                        // Keyframe Diamond Markers
                                        if (clip.keyframes.isNotEmpty()) {
                                            Row(
                                                modifier = Modifier.align(Alignment.BottomStart).padding(start = if (isSelected) 20.dp else 4.dp, bottom = 2.dp)
                                            ) {
                                                clip.keyframes.take(4).forEach { _ ->
                                                    Text("◆", color = VexAccentLight, fontSize = 8.sp)
                                                }
                                            }
                                        }

                                        // Interactive Left & Right Trim Handles when selected
                                        if (isSelected) {
                                            val handleTouchWidth = maxOf(32.dp, minOf(44.dp, clipWidth / 2.2f))

                                            // Left Trim Handle (Start)
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.CenterStart)
                                                    .width(handleTouchWidth)
                                                    .fillMaxHeight()
                                                    .pointerInput(clip.id, pxPerMs) {
                                                        var accumulatedPx = 0f
                                                        detectHorizontalDragGestures(
                                                            onDragStart = {
                                                                accumulatedPx = 0f
                                                                viewModel.pausePlayback()
                                                                val resolvedSourceDur = if (clip.sourceDurationMs > 0L) {
                                                                    clip.sourceDurationMs
                                                                } else {
                                                                    viewModel.mediaManager.queryFileDurationMs(clip.filePath, clip.uriString).takeIf { it > 0L }
                                                                        ?: (clip.sourceStartMs + clip.durationMs)
                                                                }
                                                                activeTrimSession = ActiveTrimSession(
                                                                    clipId = clip.id,
                                                                    isLeftHandle = true,
                                                                    initialStartTimeMs = clip.startTimeMs,
                                                                    initialDurationMs = clip.durationMs,
                                                                    initialSourceStartMs = clip.sourceStartMs,
                                                                    sourceDurationMs = resolvedSourceDur,
                                                                    isVideo = clip.mediaType == MediaType.VIDEO,
                                                                    trackId = clip.trackId,
                                                                    currentDeltaMs = 0L
                                                                )
                                                            },
                                                            onDragEnd = {
                                                                val session = activeTrimSession
                                                                activeTrimSession = null
                                                                activeTrimmingInfo = null
                                                                snapGuideTimeMs = null
                                                                if (session != null && session.currentDeltaMs != 0L) {
                                                                    viewModel.trimClipStart(clip.id, session.currentDeltaMs)
                                                                }
                                                            },
                                                            onDragCancel = {
                                                                activeTrimSession = null
                                                                activeTrimmingInfo = null
                                                                snapGuideTimeMs = null
                                                            },
                                                            onHorizontalDrag = { change, dragAmount ->
                                                                change.consume()
                                                                accumulatedPx += dragAmount
                                                                val session = activeTrimSession ?: return@detectHorizontalDragGestures
                                                                val deltaMs = (accumulatedPx / pxPerMs).toLong()
                                                                val effectiveSpeed = if (clip.speed > 0f) clip.speed else 1.0f
                                                                val maxDelta = session.initialDurationMs - 300L
                                                                val minDelta = maxOf(
                                                                    -(session.initialSourceStartMs / effectiveSpeed).toLong(),
                                                                    -session.initialStartTimeMs
                                                                )
                                                                val clampedDeltaMs = deltaMs.coerceIn(minDelta, maxDelta)
                                                                val proposedStartMs = (session.initialStartTimeMs + clampedDeltaMs).coerceAtLeast(0L)
                                                                val snapBoundaryMs = if (isSnappingEnabled) {
                                                                    viewModel.findTimelineBoundarySnap(
                                                                        clip.id,
                                                                        proposedStartMs,
                                                                        (28f / pxPerMs).toLong().coerceIn(40L, 1000L)
                                                                    )
                                                                } else null
                                                                val snappedDeltaMs = if (snapBoundaryMs != null) {
                                                                    (snapBoundaryMs - session.initialStartTimeMs).coerceIn(minDelta, maxDelta)
                                                                } else clampedDeltaMs
                                                                snapGuideTimeMs = if (snapBoundaryMs != null && snappedDeltaMs == snapBoundaryMs - session.initialStartTimeMs) snapBoundaryMs else null

                                                                activeTrimSession = session.copy(currentDeltaMs = snappedDeltaMs)

                                                                val liveSourceStart = (session.initialSourceStartMs + (clampedDeltaMs * effectiveSpeed).toLong()).coerceIn(0L, session.sourceDurationMs)
                                                                val liveTimelineStart = (session.initialStartTimeMs + clampedDeltaMs).coerceAtLeast(0L)
                                                                viewModel.setPlayheadPosition(liveTimelineStart)
                                                                viewModel.player.seekVideo(liveSourceStart)

                                                                val deltaSec = clampedDeltaMs / 1000f
                                                                val sign = if (deltaSec >= 0) "+" else ""
                                                                activeTrimmingInfo = "${Strings.get("start", lang)}: ${formatTimecode(liveSourceStart)} ($sign${String.format(java.util.Locale.US, "%.1f", deltaSec)}s)"
                                                            }
                                                        )
                                                    },
                                                contentAlignment = Alignment.CenterStart
                                            ) {
                                                // CapCut Tactical Left Handle Grab Bar
                                                Box(
                                                    modifier = Modifier
                                                        .width(16.dp)
                                                        .fillMaxHeight()
                                                        .background(VexAccent, RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Box(modifier = Modifier.width(2.dp).height(12.dp).background(Color.White, RoundedCornerShape(1.dp)))
                                                        Box(modifier = Modifier.width(2.dp).height(12.dp).background(Color.White, RoundedCornerShape(1.dp)))
                                                    }
                                                }
                                            }

                                            // Right Trim Handle (End)
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.CenterEnd)
                                                    .width(handleTouchWidth)
                                                    .fillMaxHeight()
                                                    .pointerInput(clip.id, pxPerMs) {
                                                        var accumulatedPx = 0f
                                                        detectHorizontalDragGestures(
                                                            onDragStart = {
                                                                accumulatedPx = 0f
                                                                viewModel.pausePlayback()
                                                                val resolvedSourceDur = if (clip.sourceDurationMs > 0L) {
                                                                    clip.sourceDurationMs
                                                                } else {
                                                                    viewModel.mediaManager.queryFileDurationMs(clip.filePath, clip.uriString).takeIf { it > 0L }
                                                                        ?: (clip.sourceStartMs + clip.durationMs)
                                                                }
                                                                activeTrimSession = ActiveTrimSession(
                                                                    clipId = clip.id,
                                                                    isLeftHandle = false,
                                                                    initialStartTimeMs = clip.startTimeMs,
                                                                    initialDurationMs = clip.durationMs,
                                                                    initialSourceStartMs = clip.sourceStartMs,
                                                                    sourceDurationMs = resolvedSourceDur,
                                                                    isVideo = clip.mediaType == MediaType.VIDEO,
                                                                    trackId = clip.trackId,
                                                                    currentDeltaMs = 0L
                                                                )
                                                            },
                                                            onDragEnd = {
                                                                val session = activeTrimSession
                                                                activeTrimSession = null
                                                                activeTrimmingInfo = null
                                                                snapGuideTimeMs = null
                                                                if (session != null && session.currentDeltaMs != 0L) {
                                                                    viewModel.trimClipEnd(clip.id, session.currentDeltaMs)
                                                                }
                                                            },
                                                            onDragCancel = {
                                                                activeTrimSession = null
                                                                activeTrimmingInfo = null
                                                                snapGuideTimeMs = null
                                                            },
                                                            onHorizontalDrag = { change, dragAmount ->
                                                                change.consume()
                                                                accumulatedPx += dragAmount
                                                                val session = activeTrimSession ?: return@detectHorizontalDragGestures
                                                                val deltaMs = (accumulatedPx / pxPerMs).toLong()
                                                                val effectiveSpeed = if (clip.speed > 0f) clip.speed else 1.0f

                                                                val maxAvailableSourceMs = (session.sourceDurationMs - session.initialSourceStartMs).coerceAtLeast(300L)
                                                                val maxAllowedDuration = if (session.isVideo) {
                                                                    (maxAvailableSourceMs / effectiveSpeed).toLong().coerceAtLeast(300L)
                                                                } else {
                                                                    120_000L
                                                                }
                                                                val targetDuration = (session.initialDurationMs + deltaMs).coerceIn(300L, maxAllowedDuration)
                                                                val proposedEndMs = session.initialStartTimeMs + targetDuration
                                                                val snapBoundaryMs = if (isSnappingEnabled) {
                                                                    viewModel.findTimelineBoundarySnap(
                                                                        clip.id,
                                                                        proposedEndMs,
                                                                        (28f / pxPerMs).toLong().coerceIn(40L, 1000L)
                                                                    )
                                                                } else null
                                                                val snappedDuration = if (snapBoundaryMs != null) {
                                                                    (snapBoundaryMs - session.initialStartTimeMs).coerceIn(300L, maxAllowedDuration)
                                                                } else targetDuration
                                                                val clampedDeltaMs = snappedDuration - session.initialDurationMs
                                                                snapGuideTimeMs = if (snapBoundaryMs != null && snappedDuration == snapBoundaryMs - session.initialStartTimeMs) snapBoundaryMs else null

                                                                activeTrimSession = session.copy(currentDeltaMs = clampedDeltaMs)

                                                                val liveSourceEnd = (session.initialSourceStartMs + (targetDuration * effectiveSpeed).toLong() - 1L).coerceIn(0L, session.sourceDurationMs)
                                                                val newEndMs = session.initialStartTimeMs + targetDuration
                                                                val livePos = (newEndMs - 1L).coerceAtLeast(session.initialStartTimeMs)
                                                                viewModel.setPlayheadPosition(livePos)
                                                                viewModel.player.seekVideo(liveSourceEnd)

                                                                val deltaSec = clampedDeltaMs / 1000f
                                                                val sign = if (deltaSec >= 0) "+" else ""
                                                                activeTrimmingInfo = "${Strings.get("end", lang)}: ${formatTimecode(newEndMs)} ($sign${String.format(java.util.Locale.US, "%.1f", deltaSec)}s)"
                                                            }
                                                        )
                                                    },
                                                contentAlignment = Alignment.CenterEnd
                                            ) {
                                                // CapCut Tactical Right Handle Grab Bar
                                                Box(
                                                    modifier = Modifier
                                                        .width(16.dp)
                                                        .fillMaxHeight()
                                                        .background(VexAccent, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Box(modifier = Modifier.width(2.dp).height(12.dp).background(Color.White, RoundedCornerShape(1.dp)))
                                                        Box(modifier = Modifier.width(2.dp).height(12.dp).background(Color.White, RoundedCornerShape(1.dp)))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Magnetic alignment guide shown while a clip is being dragged.
                    snapGuideTimeMs?.let { guideTimeMs ->
                        Box(
                            modifier = Modifier
                                .offset(x = (guideTimeMs * dpPerMs).dp)
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(Color.White.copy(alpha = 0.9f))
                        )
                    }

                    // --- PLAYHEAD (Purple vertical line with top triangle indicator) ---
                    val playheadOffset = (playheadMs * dpPerMs).dp
                    Box(
                        modifier = Modifier
                            .offset(x = playheadOffset)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(VexAccent)
                    ) {
                        Canvas(
                            modifier = Modifier
                                .size(24.dp)
                                .offset(x = (-11).dp, y = (-2).dp)
                                .pointerInput(pxPerMs, totalDurationMs) {
                                    var initialPlayheadMs = 0L
                                    var accumulatedDragPx = 0f
                                    detectDragGestures(
                                        onDragStart = {
                                            initialPlayheadMs = viewModel.playheadPositionMs.value
                                            accumulatedDragPx = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            accumulatedDragPx += dragAmount.x
                                            val nextPosition = initialPlayheadMs +
                                                (accumulatedDragPx / pxPerMs).toLong()
                                            viewModel.setPlayheadPosition(
                                                nextPosition.coerceIn(0L, totalDurationMs)
                                            )
                                        }
                                    )
                                }
                        ) {
                            val path = Path().apply {
                                moveTo(size.width / 2f, size.height * 0.78f)
                                lineTo(size.width * 0.18f, 0f)
                                lineTo(size.width * 0.82f, 0f)
                                close()
                            }
                            drawPath(path, color = VexAccent)
                        }
                    }
                }
            }
        }

        // --- SUB-TOOL PANEL (Speed, Volume, Edit, Crop) ---
        if (activeTool != null && selectedClip != null) {
            Surface(
                color = VexPanelElevated,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 0.5.dp, color = VexBorder)
                    .padding(8.dp)
            ) {
                when (activeTool) {
                    "edit" -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                ToolActionButton(
                                    icon = Icons.Default.ContentCut,
                                    label = Strings.get("split", lang),
                                    onClick = { viewModel.splitClipAtPlayhead() }
                                )
                                ToolActionButton(
                                    icon = Icons.Default.ContentCopy,
                                    label = Strings.get("duplicate", lang),
                                    onClick = { viewModel.duplicateClip(selectedClip.id) }
                                )
                                ToolActionButton(
                                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                                    label = Strings.get("move_left", lang),
                                    onClick = { viewModel.moveClipLeft(selectedClip.id) }
                                )
                                ToolActionButton(
                                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                                    label = Strings.get("move_right", lang),
                                    onClick = { viewModel.moveClipRight(selectedClip.id) }
                                )
                                ToolActionButton(
                                    icon = Icons.Default.Delete,
                                    label = Strings.get("delete", lang),
                                    tint = VexMeterRed,
                                    onClick = { viewModel.deleteClip(selectedClip.id) }
                                )
                                ToolActionButton(
                                    icon = Icons.Default.Close,
                                    label = Strings.get("close", lang),
                                    onClick = { activeTool = null }
                                )
                            }

                            // If Photo clip, display duration quick chips & slider
                            if (selectedClip.mediaType == MediaType.PHOTO) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = Strings.get("duration", lang) + ": ${(selectedClip.durationMs / 1000f)}s",
                                        color = VexTextSecondary,
                                        fontSize = 11.sp
                                    )
                                    listOf(1000L, 2000L, 3000L, 5000L, 10000L).forEach { dur ->
                                        val isSel = selectedClip.durationMs == dur
                                        Button(
                                            onClick = { viewModel.setClipDuration(selectedClip.id, dur) },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isSel) VexAccent else VexPanel
                                            ),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("${dur / 1000}s", color = if (isSel) Color.White else VexTextSecondary, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "speed" -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(Strings.get("speed", lang) + ":", color = VexTextSecondary, fontSize = 12.sp)
                            listOf(0.25f, 0.5f, 1.0f, 1.5f, 2.0f, 4.0f).forEach { spd ->
                                val isSel = selectedClip.speed == spd
                                Button(
                                    onClick = { viewModel.setClipSpeed(selectedClip.id, spd) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSel) VexAccent else VexPanel
                                    ),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("${spd}x", color = if (isSel) Color.White else VexTextSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    "volume" -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.VolumeUp, contentDescription = null, tint = VexTextPrimary)
                            Slider(
                                value = selectedClip.volume,
                                onValueChange = { viewModel.setClipVolume(selectedClip.id, it) },
                                valueRange = 0f..1f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = VexAccent,
                                    activeTrackColor = VexAccent
                                )
                            )
                            Text("${(selectedClip.volume * 100).toInt()}%", color = VexTextPrimary, fontSize = 12.sp)
                        }
                    }
                    "crop" -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            listOf(
                                "Original" to (project?.aspectRatio ?: AspectRatio.RATIO_9_16),
                                "16:9" to AspectRatio.RATIO_16_9,
                                "9:16" to AspectRatio.RATIO_9_16,
                                "1:1" to AspectRatio.RATIO_1_1
                            ).forEach { (ratioLabel, ratio) ->
                                val selected = project?.aspectRatio == ratio
                                OutlinedButton(
                                    onClick = {
                                        viewModel.setProjectAspectRatio(ratio)
                                        activeTool = null
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (selected) VexAccent.copy(alpha = 0.2f) else VexPanel
                                    )
                                ) {
                                    Text(ratioLabel, color = if (selected) VexAccentLight else VexTextPrimary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- BOTTOM ACTION TOOLBAR ---
        Surface(
            color = VexPanel,
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 0.5.dp, color = VexBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StudioToolItem(
                    icon = Icons.Default.Tune,
                    label = Strings.get("edit", lang),
                    isSelected = activeTool == "edit",
                    onClick = { activeTool = if (activeTool == "edit") null else "edit" }
                )

                StudioToolItem(
                    icon = Icons.Default.Speed,
                    label = Strings.get("speed", lang),
                    isSelected = activeTool == "speed",
                    onClick = { activeTool = if (activeTool == "speed") null else "speed" }
                )

                StudioToolItem(
                    icon = Icons.Default.VolumeUp,
                    label = Strings.get("volume", lang),
                    isSelected = activeTool == "volume",
                    onClick = { activeTool = if (activeTool == "volume") null else "volume" }
                )

                StudioToolItem(
                    icon = Icons.Default.AutoAwesome,
                    label = Strings.get("effects", lang),
                    isSelected = false,
                    onClick = { onNavigate(Screen.EFFECTS_LAB) }
                )

                StudioToolItem(
                    icon = Icons.Default.Diamond,
                    label = Strings.get("keyframes", lang),
                    isSelected = false,
                    onClick = { onNavigate(Screen.KEYFRAME_EDITOR) }
                )

                StudioToolItem(
                    icon = Icons.Default.Crop,
                    label = Strings.get("crop", lang),
                    isSelected = activeTool == "crop",
                    onClick = { activeTool = if (activeTool == "crop") null else "crop" }
                )
            }
        }

        // Bottom Navigation Bar
        VexBottomBar(
            currentScreen = Screen.STUDIO,
            onNavigate = onNavigate,
            lang = lang
        )
    }
}

@Composable
fun WaveformRow(
    points: List<Float>,
    color: Color,
    title: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 60.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        androidx.compose.foundation.Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val step = 6f
            val barWidth = 3f
            val count = (size.width / step).toInt().coerceAtLeast(1)
            val centerY = size.height / 2f
            for (i in 0 until count) {
                val sample = if (points.isNotEmpty()) {
                    points[i % points.size].coerceIn(0.1f, 1f)
                } else {
                    0.3f + 0.4f * kotlin.math.abs(kotlin.math.sin(i * 0.5f).toFloat())
                }
                val barHeight = (size.height * 0.8f * sample).coerceAtLeast(2f)
                val x = i * step
                drawRect(
                    color = color,
                    topLeft = androidx.compose.ui.geometry.Offset(x, centerY - barHeight / 2f),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight)
                )
            }
        }
    }
}

@Composable
fun ToolActionButton(
    icon: ImageVector,
    label: String,
    tint: Color = VexTextPrimary,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp
        )
    }
}

@Composable
fun StudioToolItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val tint = if (isSelected) VexAccent else VexTextSecondary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
