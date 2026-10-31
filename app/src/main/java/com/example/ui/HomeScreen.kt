package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.data.Project
import com.example.data.Strings
import com.example.ui.theme.*
import java.io.File

@Composable
fun HomeScreen(
    viewModel: StudioViewModel,
    onNavigate: (Screen) -> Unit
) {
    val lang = viewModel.settings.collectAsState().value.language
    val projects = viewModel.projectRepo.projects.collectAsState().value
    var showNewProjectDialog by remember { mutableStateOf(false) }

    NewProjectDialog(
        isOpen = showNewProjectDialog,
        onDismiss = { showNewProjectDialog = false },
        onCreate = { name, ratio ->
            viewModel.createNewProject(name, ratio)
        },
        lang = lang
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VexBackground)
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(VexPanelElevated)
                        .border(1.dp, VexAccentLight.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_vexedit_logo),
                        contentDescription = "VexEdit Studio Logo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "VexEdit Studio",
                        color = VexTextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "V1.1 Professional",
                        color = VexAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            IconButton(
                onClick = { onNavigate(Screen.SETTINGS) },
                modifier = Modifier.testTag("home_settings_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = Strings.get("nav_settings", lang),
                    tint = VexTextSecondary
                )
            }
        }

        // Main Content Scrollable
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Big New Project & Import Media Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // New Project Main Button
                Button(
                    onClick = { showNewProjectDialog = true },
                    modifier = Modifier
                        .weight(1.3f)
                        .height(56.dp)
                        .testTag("home_new_project_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = VexAccent),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = Strings.get("new_project", lang),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Import Media Shortcut Button
                OutlinedButton(
                    onClick = { onNavigate(Screen.IMPORT_MEDIA) },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .testTag("home_import_media_btn"),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = VexPanel),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(VexBorder)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileUpload,
                        contentDescription = null,
                        tint = VexAccentLight,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = Strings.get("import_media", lang),
                        color = VexTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Recent Projects Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = Strings.get("recent_projects", lang),
                    color = VexTextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { onNavigate(Screen.PROJECTS) }) {
                    Text(
                        text = Strings.get("tab_all", lang),
                        color = VexAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Horizontal Recent Projects List
            if (projects.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(VexPanel)
                        .border(1.dp, VexBorder, RoundedCornerShape(8.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.VideoLibrary,
                            contentDescription = null,
                            tint = VexTextMuted,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = Strings.get("no_projects_yet", lang),
                            color = VexTextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(projects.take(6)) { project ->
                        RecentProjectCard(
                            project = project,
                            onClick = { viewModel.selectProject(project) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 2x2 Quick Tools Grid
            Text(
                text = Strings.get("quick_tools", lang),
                color = VexTextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ToolShortcutCard(
                        title = Strings.get("tool_audio_studio", lang),
                        subtitle = "Multi-track & mixer",
                        icon = Icons.Default.GraphicEq,
                        accentColor = VexWaveformMusic,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(Screen.AUDIO_STUDIO) }
                    )
                    ToolShortcutCard(
                        title = Strings.get("tool_effects_lab", lang),
                        subtitle = "VFX & color filters",
                        icon = Icons.Default.AutoAwesome,
                        accentColor = VexAccent,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(Screen.EFFECTS_LAB) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ToolShortcutCard(
                        title = Strings.get("tool_keyframe_editor", lang),
                        subtitle = "Bézier curves & motion",
                        icon = Icons.Default.Timeline,
                        accentColor = VexWaveformVoice,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(Screen.KEYFRAME_EDITOR) }
                    )
                    ToolShortcutCard(
                        title = Strings.get("tool_module_center", lang),
                        subtitle = "Packs & extensible tools",
                        icon = Icons.Default.Extension,
                        accentColor = VexWaveformSfx,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(Screen.MODULE_CENTER) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }

        // Bottom Navigation
        VexBottomBar(
            currentScreen = Screen.HOME,
            onNavigate = onNavigate,
            lang = lang
        )
    }
}

@Composable
fun RecentProjectCard(
    project: Project,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = VexPanel),
        modifier = Modifier
            .width(150.dp)
            .clickable { onClick() }
            .border(1.dp, VexBorder, RoundedCornerShape(8.dp))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(95.dp)
                    .background(Color(0xFF14131A)),
                contentAlignment = Alignment.Center
            ) {
                val thumbFile = project.thumbnailPath?.let { File(it) }
                if (thumbFile != null && thumbFile.exists()) {
                    coil.compose.AsyncImage(
                        model = thumbFile,
                        contentDescription = project.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MovieCreation,
                        contentDescription = null,
                        tint = VexAccentLight.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                }
                // Aspect Ratio Tag
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = project.aspectRatio.label,
                        color = VexAccentLight,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Duration tag
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = formatDurationMinutesSeconds(project.durationMs),
                        color = Color.White,
                        fontSize = 10.sp
                    )
                }
            }

            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = project.title,
                    color = VexTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${project.clips.size} clips",
                    color = VexTextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun ToolShortcutCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = VexPanel),
        modifier = modifier
            .height(84.dp)
            .clickable { onClick() }
            .border(1.dp, VexBorder, RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accentColor.copy(alpha = 0.15f))
                    .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = VexTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = VexTextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
