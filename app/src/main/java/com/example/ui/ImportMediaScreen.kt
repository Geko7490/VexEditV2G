package com.example.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.*
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ImportMediaScreen(
    viewModel: StudioViewModel,
    onNavigate: (Screen) -> Unit
) {
    val lang = viewModel.settings.collectAsState().value.language
    val importedMediaList by viewModel.mediaManager.importedMedia.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) } // 0: Videos, 1: Photos, 2: Audio, 3: Files
    val selectedItemIds = remember { mutableStateListOf<String>() }
    var isImporting by remember { mutableStateOf(false) }

    // Android Photo & Video Picker launcher (Standard zero-permission PhotoPicker!)
    val photoVideoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            isImporting = true
            coroutineScope.launch {
                val newItems = viewModel.mediaManager.importMediaUris(uris)
                newItems.forEach { selectedItemIds.add(it.id) }
                isImporting = false
            }
        }
    }

    // Android Document/Audio picker
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            isImporting = true
            coroutineScope.launch {
                val newItems = viewModel.mediaManager.importMediaUris(uris, forcedType = MediaType.AUDIO)
                newItems.forEach { selectedItemIds.add(it.id) }
                isImporting = false
            }
        }
    }

    // Generic files picker
    val filesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            isImporting = true
            coroutineScope.launch {
                val newItems = viewModel.mediaManager.importMediaUris(uris)
                newItems.forEach { selectedItemIds.add(it.id) }
                isImporting = false
            }
        }
    }

    // Filter items according to tab
    val filteredMedia = remember(importedMediaList, selectedTab) {
        when (selectedTab) {
            0 -> importedMediaList.filter { it.mediaType == MediaType.VIDEO }
            1 -> importedMediaList.filter { it.mediaType == MediaType.PHOTO }
            2 -> importedMediaList.filter { it.mediaType == MediaType.AUDIO }
            else -> importedMediaList
        }
    }

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
                text = Strings.get("import_media", lang),
                color = VexTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            // Direct pick button
            Button(
                onClick = {
                    when (selectedTab) {
                        2 -> audioPickerLauncher.launch("audio/*")
                        3 -> filesPickerLauncher.launch("*/*")
                        else -> photoVideoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = VexAccent),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(34.dp).testTag("browse_device_btn")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(Strings.get("device_picker", lang), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Tabs: Videos | Photos | Audio | Files
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val tabs = listOf(
                Strings.get("tab_videos", lang),
                Strings.get("tab_photos", lang),
                Strings.get("tab_audio", lang),
                Strings.get("tab_files", lang)
            )
            tabs.forEachIndexed { index, tabTitle ->
                val isSelected = selectedTab == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) VexPanelElevated else Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) VexBorder else Color.Transparent,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .clickable { selectedTab = index }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tabTitle,
                        color = if (isSelected) Color.White else VexTextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        // Loading indicator during device copy & thumbnail extraction
        if (isImporting) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = VexAccent,
                trackColor = VexPanelElevated
            )
        }

        // Media Grid or Clean Empty State
        if (filteredMedia.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = when (selectedTab) {
                            0 -> Icons.Default.Videocam
                            1 -> Icons.Default.Image
                            2 -> Icons.Default.Audiotrack
                            else -> Icons.Default.FolderOpen
                        },
                        contentDescription = null,
                        tint = VexAccentLight,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = Strings.get("no_imported_media_yet", lang),
                        color = VexTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = Strings.get("tap_picker_to_import", lang),
                        color = VexTextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            when (selectedTab) {
                                2 -> audioPickerLauncher.launch("audio/*")
                                3 -> filesPickerLauncher.launch("*/*")
                                else -> photoVideoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = VexPanelElevated),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(VexAccent)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("empty_pick_media_btn")
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = VexAccentLight)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (selectedTab) {
                                2 -> Strings.get("device_audio_picker", lang)
                                else -> Strings.get("device_picker", lang)
                            },
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(filteredMedia, key = { it.id }) { item ->
                    val isSelected = selectedItemIds.contains(item.id)

                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(VexPanel)
                            .border(
                                width = if (isSelected) 2.5.dp else 0.5.dp,
                                color = if (isSelected) VexAccent else VexBorder,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                if (isSelected) selectedItemIds.remove(item.id) else selectedItemIds.add(item.id)
                            }
                            .testTag("media_item_${item.id}")
                    ) {
                        if (item.mediaType == MediaType.AUDIO) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(VexPanelElevated)
                                    .padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = VexWaveformMusic,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = item.name,
                                    color = VexTextPrimary,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatDurationMinutesSeconds(item.durationMs),
                                    color = VexTextSecondary,
                                    fontSize = 9.sp
                                )
                            }
                        } else {
                            val previewFile = File(item.thumbnailPath.ifEmpty { item.filePath })
                            if (previewFile.exists()) {
                                AsyncImage(
                                    model = previewFile,
                                    contentDescription = item.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(VexPanelElevated),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.BrokenImage, contentDescription = null, tint = VexTextMuted)
                                }
                            }

                            if (item.mediaType == MediaType.VIDEO) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(28.dp)
                                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // Bottom duration info
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.65f))
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(formatDurationMinutesSeconds(item.durationMs), color = Color.White, fontSize = 9.sp)
                                Text(if (item.mediaType == MediaType.VIDEO) "Video" else "Photo", color = VexAccentLight, fontSize = 9.sp)
                            }
                        }

                        // Purple checkmark badge
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(VexAccent),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Action Bar
        Surface(
            color = VexPanel,
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 0.5.dp, color = VexBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedItemIds.size} ${Strings.get("items_selected", lang)}",
                        color = VexTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            selectedItemIds.clear()
                            selectedItemIds.addAll(filteredMedia.map { it.id })
                        },
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = VexPanelElevated),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(VexBorder)),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(Strings.get("select_all", lang), color = VexTextPrimary, fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = { selectedItemIds.clear() },
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = VexPanelElevated),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(VexBorder)),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1.1f)
                    ) {
                        Text(Strings.get("clear_selection", lang), color = VexTextPrimary, fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            when (selectedTab) {
                                2 -> audioPickerLauncher.launch("audio/*")
                                3 -> filesPickerLauncher.launch("*/*")
                                else -> photoVideoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = VexPanelElevated),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(VexBorder)),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(0.9f)
                    ) {
                        Text("+ Cihaz", color = VexTextPrimary, fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Purple "Add to Timeline" button
                Button(
                    onClick = {
                        val selectedItems = importedMediaList.filter { selectedItemIds.contains(it.id) }
                        viewModel.addImportedMediaToTimeline(selectedItems)
                    },
                    enabled = selectedItemIds.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VexAccent,
                        disabledContainerColor = VexPanelElevated
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("add_to_timeline_btn")
                ) {
                    Text(
                        text = Strings.get("add_to_timeline", lang),
                        color = if (selectedItemIds.isNotEmpty()) Color.White else VexTextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
