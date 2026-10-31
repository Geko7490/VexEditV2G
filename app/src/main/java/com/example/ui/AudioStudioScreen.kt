package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.*
import com.example.ui.theme.*
import java.io.File

@Composable
fun AudioStudioScreen(
    viewModel: StudioViewModel,
    onNavigate: (Screen) -> Unit
) {
    val lang = viewModel.settings.collectAsState().value.language
    val project = viewModel.currentProject.collectAsState().value
    val playheadMs = viewModel.playheadPositionMs.collectAsState().value
    val isPlaying = viewModel.isPlaying.collectAsState().value

    // Track fader levels for the 6 mixer channels
    val faderLevels = remember {
        mutableStateListOf(2.0f, -5.0f, -2.0f, -2.0f, -3.0f, -5.0f)
    }
    val muteStates = remember { mutableStateListOf(false, false, false, false, false, false) }
    val soloStates = remember { mutableStateListOf(false, false, false, false, false, false) }

    var selectedAudioTrack by remember { mutableStateOf(0) } // 0: MUSIC, 1: VOICE, 2: SOUND FX
    var activeToolDialog by remember { mutableStateOf<String?>(null) } // "fade", "eq", "reverb"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VexBackground)
    ) {
        // Top Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onNavigate(Screen.STUDIO) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = VexTextPrimary
                )
            }
            Text(
                text = "Audio Studio",
                color = VexTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = { viewModel.undo() }) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = VexTextPrimary)
            }
            IconButton(onClick = { viewModel.redo() }) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", tint = VexTextPrimary)
            }
            IconButton(onClick = {
                project?.let { viewModel.projectRepo.saveProject(it) }
            }) {
                Icon(Icons.Default.Save, contentDescription = "Save", tint = VexTextPrimary)
            }
        }

        // Mini Video Preview with aspect ratio
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val visualClip = project?.clips?.firstOrNull { it.trackType == TrackType.VIDEO || it.mediaType == MediaType.PHOTO }
            val visualFile = visualClip?.let { File(it.thumbnailPath.ifEmpty { it.filePath }) }
            if (visualFile != null && visualFile.exists()) {
                coil.compose.AsyncImage(
                    model = visualFile,
                    contentDescription = "Preview",
                    modifier = Modifier
                        .fillMaxHeight(0.9f)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxHeight(0.9f)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF15141D)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MovieCreation, contentDescription = null, tint = VexAccentLight.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
                }
            }
        }

        // Preview resolution bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Preview: Auto · 720p", color = VexTextSecondary, fontSize = 11.sp)
            Icon(Icons.Default.Fullscreen, contentDescription = null, tint = VexTextSecondary, modifier = Modifier.size(16.dp))
        }

        // Timecode ruler
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(VexPanelElevated)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("00:00", "00:05", "00:10", "00:15", "00:20").forEach { tc ->
                val isCurrent = tc == "00:15"
                Text(
                    text = tc,
                    color = if (isCurrent) VexAccentLight else VexTextSecondary,
                    fontSize = 10.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                )
            }
        }

        // --- AUDIO TIMELINE (Real audio clips or clean empty state) ---
        val audioClips = remember(project) {
            project?.clips?.filter {
                it.trackType == TrackType.AUDIO_MUSIC || it.trackType == TrackType.AUDIO_SFX || it.mediaType == MediaType.AUDIO
            } ?: emptyList()
        }

        if (audioClips.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .background(VexPanel, RoundedCornerShape(6.dp))
                    .border(0.5.dp, VexBorder, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Audiotrack, contentDescription = null, tint = VexAccentLight, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(Strings.get("no_audio_in_project", lang), color = VexTextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(Strings.get("add_audio_prompt", lang), color = VexTextSecondary, fontSize = 10.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = { onNavigate(Screen.IMPORT_MEDIA) },
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = VexPanelElevated),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(VexAccent)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(Strings.get("tab_audio", lang), color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                audioClips.forEachIndexed { idx, clip ->
                    val color = when (idx % 3) {
                        0 -> VexWaveformMusic
                        1 -> VexWaveformVoice
                        else -> VexWaveformSfx
                    }
                    AudioTimelineTrackRow(
                        label = clip.name,
                        color = color,
                        isSelected = clip.id == viewModel.selectedClipId.collectAsState().value,
                        waveformPoints = clip.waveformPoints,
                        onClick = { viewModel.selectClip(clip.id) }
                    )
                }
            }
        }

        // --- 6-CHANNEL MIXER FADERS (Matching Screenshot 6) ---
        Surface(
            color = VexPanel,
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 0.5.dp, color = VexBorder)
                .padding(vertical = 8.dp, horizontal = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (ch in 0 until 6) {
                    AudioMixerChannelColumn(
                        channelIndex = ch,
                        db = faderLevels[ch],
                        isMuted = muteStates[ch],
                        isSolo = soloStates[ch],
                        onDbChange = { faderLevels[ch] = it },
                        onToggleMute = { muteStates[ch] = !muteStates[ch] },
                        onToggleSolo = { soloStates[ch] = !soloStates[ch] }
                    )
                }
            }
        }

        // --- AUDIO TOOLS GRID ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            // Row 1 Tools
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                AudioToolTile(icon = Icons.Default.ContentCut, label = "Trim") { viewModel.splitClipAtPlayhead() }
                AudioToolTile(icon = Icons.Default.Tune, label = "Fade") { activeToolDialog = "fade" }
                AudioToolTile(icon = Icons.Default.VolumeUp, label = "Volume") { /* Adjust volume */ }
                AudioToolTile(icon = Icons.Default.Equalizer, label = "EQ") { activeToolDialog = "eq" }
                AudioToolTile(icon = Icons.Default.AutoAwesome, label = "Effects") { onNavigate(Screen.EFFECTS_LAB) }
                AudioToolTile(icon = Icons.Default.SensorsOff, label = "Noise Gate") { /* Toggle noise gate */ }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 2 Tools
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                AudioToolTile(icon = Icons.Default.Speed, label = "Compressor") {}
                AudioToolTile(icon = Icons.Default.GraphicEq, label = "Reverb") { activeToolDialog = "reverb" }
                AudioToolTile(icon = Icons.Default.AvTimer, label = "Delay") {}
                AudioToolTile(icon = Icons.Default.Leaderboard, label = "Limiter") {}
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Row 3: Split, Duplicate, Delete, Apply + Transport Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioMiniButton(icon = Icons.Default.Crop, label = "Split") { viewModel.splitClipAtPlayhead() }
                    AudioMiniButton(icon = Icons.Default.ContentCopy, label = "Duplicate") {
                        viewModel.selectedClipId.value?.let { viewModel.duplicateClip(it) }
                    }
                    AudioMiniButton(icon = Icons.Default.Delete, label = "Delete") {
                        viewModel.selectedClipId.value?.let { viewModel.deleteClip(it) }
                    }
                    AudioMiniButton(icon = Icons.Default.Check, label = "Apply", color = VexAccent) {
                        onNavigate(Screen.STUDIO)
                    }
                }

                // Transport Controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = { viewModel.setPlayheadPosition(0L) }) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = null, tint = VexTextPrimary)
                    }
                    IconButton(
                        onClick = { viewModel.togglePlayback() },
                        modifier = Modifier
                            .size(36.dp)
                            .background(VexAccent, CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = {
                        viewModel.setPlayheadPosition(project?.durationMs ?: 20000L)
                    }) {
                        Icon(Icons.Default.SkipNext, contentDescription = null, tint = VexTextPrimary)
                    }
                }
            }
        }

        // Bottom Navigation
        VexBottomBar(
            currentScreen = Screen.STUDIO,
            onNavigate = onNavigate,
            lang = lang
        )
    }
}

@Composable
fun AudioTimelineTrackRow(
    label: String,
    color: Color,
    isSelected: Boolean,
    waveformPoints: List<Float>,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(VexPanel)
            .border(
                width = if (isSelected) 1.5.dp else 0.5.dp,
                color = if (isSelected) VexAccent else VexBorder,
                shape = RoundedCornerShape(4.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = VexTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(75.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Waveform Canvas
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(vertical = 4.dp)
        ) {
            val pts = if (waveformPoints.isEmpty()) List(50) { 0.5f } else waveformPoints
            val stepX = size.width / pts.size.coerceAtLeast(1)
            val midY = size.height / 2f
            pts.forEachIndexed { i, p ->
                val barH = (size.height * p * 0.85f).coerceAtLeast(3f)
                drawLine(
                    color = color,
                    start = Offset(i * stepX, midY - barH / 2f),
                    end = Offset(i * stepX, midY + barH / 2f),
                    strokeWidth = 2.5f
                )
            }
        }
    }
}

@Composable
fun AudioMixerChannelColumn(
    channelIndex: Int,
    db: Float,
    isMuted: Boolean,
    isSolo: Boolean,
    onDbChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(52.dp)
    ) {
        Text(
            text = if (db > 0) "+${db.toInt()} dB" else "${db.toInt()} dB",
            color = if (db > 0) VexAccentLight else VexTextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Fader slider + VU Meter side-by-side
        Row(
            modifier = Modifier.height(75.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Slider thumb simulation
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .fillMaxHeight()
                    .background(VexPanelElevated, RoundedCornerShape(3.dp))
                    .border(0.5.dp, VexBorder, RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Vertical slot line
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Color.Black)
                )
                // Fader knob in purple
                val normalizedY = ((6f - db) / 18f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .offset(y = ((normalizedY - 0.5f) * 50).dp)
                        .background(VexAccent, RoundedCornerShape(2.dp))
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // VU Meter LED strip
            Column(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                val levels = 8
                for (l in 0 until levels) {
                    val ledColor = when {
                        l < 2 -> VexMeterRed
                        l < 4 -> VexMeterYellow
                        else -> VexMeterGreen
                    }
                    val isActive = l >= (levels - ((db + 12f) / 18f * levels).toInt())
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .background(if (isActive) ledColor else VexPanelElevated)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Mute / Solo buttons
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (isMuted) VexMeterRed else VexPanelElevated)
                    .clickable { onToggleMute() },
                contentAlignment = Alignment.Center
            ) {
                Text("M", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }

            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (isSolo) VexMeterYellow else VexPanelElevated)
                    .clickable { onToggleSolo() },
                contentAlignment = Alignment.Center
            ) {
                Text("S", color = if (isSolo) Color.Black else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun AudioToolTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(VexPanelElevated, RoundedCornerShape(6.dp))
                .border(0.5.dp, VexBorder, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = VexTextPrimary, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(label, color = VexTextSecondary, fontSize = 9.sp)
    }
}

@Composable
fun AudioMiniButton(
    icon: ImageVector,
    label: String,
    color: Color = VexTextPrimary,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = VexPanelElevated),
        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(VexBorder)),
        shape = RoundedCornerShape(4.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier.height(32.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}
