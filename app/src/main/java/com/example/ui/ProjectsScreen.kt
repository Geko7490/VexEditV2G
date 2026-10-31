package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProjectsScreen(
    viewModel: StudioViewModel,
    onNavigate: (Screen) -> Unit
) {
    val lang = viewModel.settings.collectAsState().value.language
    val allProjects = viewModel.projectRepo.projects.collectAsState().value

    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) } // 0: All, 1: Recent, 2: Favorites
    var sortMode by remember { mutableStateOf("Last Edited") } // Last Edited, Name, Duration
    var showSortMenu by remember { mutableStateOf(false) }

    var showNewProjectDialog by remember { mutableStateOf(false) }
    var projectToDelete by remember { mutableStateOf<Project?>(null) }
    var projectToRename by remember { mutableStateOf<Project?>(null) }
    var renameText by remember { mutableStateOf("") }

    NewProjectDialog(
        isOpen = showNewProjectDialog,
        onDismiss = { showNewProjectDialog = false },
        onCreate = { name, ratio -> viewModel.createNewProject(name, ratio) },
        lang = lang
    )

    // Delete Confirmation Dialog
    if (projectToDelete != null) {
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            title = { Text(Strings.get("delete", lang), color = VexTextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text(Strings.get("delete_project_prompt", lang), color = VexTextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        projectToDelete?.let { viewModel.projectRepo.deleteProject(it.id) }
                        projectToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VexMeterRed)
                ) {
                    Text(Strings.get("delete", lang), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { projectToDelete = null }) {
                    Text(Strings.get("cancel", lang), color = VexTextSecondary)
                }
            },
            containerColor = VexPanel
        )
    }

    // Rename Dialog
    if (projectToRename != null) {
        AlertDialog(
            onDismissRequest = { projectToRename = null },
            title = { Text(Strings.get("rename", lang), color = VexTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = VexTextPrimary,
                        unfocusedTextColor = VexTextPrimary,
                        focusedBorderColor = VexAccent,
                        unfocusedBorderColor = VexBorder,
                        focusedContainerColor = VexBackground,
                        unfocusedContainerColor = VexBackground
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        projectToRename?.let { viewModel.projectRepo.renameProject(it.id, renameText) }
                        projectToRename = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VexAccent)
                ) {
                    Text(Strings.get("save", lang), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { projectToRename = null }) {
                    Text(Strings.get("cancel", lang), color = VexTextSecondary)
                }
            },
            containerColor = VexPanel
        )
    }

    // Filter & Sort
    val filteredProjects = remember(allProjects, searchQuery, selectedTab, sortMode) {
        var list = allProjects
        if (searchQuery.isNotBlank()) {
            list = list.filter { it.title.contains(searchQuery.trim(), ignoreCase = true) }
        }
        list = when (selectedTab) {
            1 -> list.sortedByDescending { it.lastEditedTimestamp }
            2 -> list.filter { it.isFavorite }
            else -> list
        }
        when (sortMode) {
            "Name" -> list.sortedBy { it.title.lowercase(Locale.ROOT) }
            "Duration" -> list.sortedByDescending { it.durationMs }
            else -> list.sortedByDescending { it.lastEditedTimestamp }
        }
    }

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
            Text(
                text = "VexEdit Studio",
                color = VexTextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Button(
                onClick = { showNewProjectDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = VexAccent),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.testTag("projects_new_project_btn")
            ) {
                Text(
                    text = Strings.get("new_project", lang),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Search Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(Strings.get("search_projects", lang), color = VexTextSecondary, fontSize = 13.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = VexTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = null, tint = VexTextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = VexTextPrimary,
                    unfocusedTextColor = VexTextPrimary,
                    focusedBorderColor = VexAccent,
                    unfocusedBorderColor = VexBorder,
                    focusedContainerColor = VexPanel,
                    unfocusedContainerColor = VexPanel
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("search_projects_input")
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Tabs: All | Recent | Favorites
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val tabs = listOf(
                Strings.get("tab_all", lang),
                Strings.get("tab_recent", lang),
                Strings.get("tab_favorites", lang)
            )
            tabs.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { selectedTab = index }
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        text = title,
                        color = if (isSelected) VexTextPrimary else VexTextSecondary,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(2.dp)
                            .background(if (isSelected) VexAccent else Color.Transparent)
                    )
                }
            }
        }

        HorizontalDivider(color = VexBorder, thickness = 0.5.dp)

        // Sort Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Text(
                text = "${Strings.get("sort_by", lang)} ",
                color = VexTextSecondary,
                fontSize = 12.sp
            )
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { showSortMenu = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = sortMode,
                        color = VexTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = VexTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false },
                    modifier = Modifier.background(VexPanel)
                ) {
                    listOf("Last Edited", "Name", "Duration").forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item, color = VexTextPrimary) },
                            onClick = {
                                sortMode = item
                                showSortMenu = false
                            }
                        )
                    }
                }
            }
        }

        // Two Column Projects Grid or Empty State
        if (filteredProjects.isEmpty()) {
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
                        imageVector = Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = VexAccentLight,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = Strings.get("no_projects_yet", lang),
                        color = VexTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = Strings.get("create_first_project", lang),
                        color = VexTextSecondary,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = { showNewProjectDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = VexAccent),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("empty_create_project_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(Strings.get("new_project", lang), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(filteredProjects, key = { it.id }) { project ->
                    ProjectGridCard(
                        project = project,
                        onClick = { viewModel.selectProject(project) },
                        onRename = {
                            renameText = project.title
                            projectToRename = project
                        },
                        onDuplicate = { viewModel.projectRepo.duplicateProject(project.id) },
                        onToggleFavorite = { viewModel.projectRepo.toggleFavorite(project.id) },
                        onDelete = { projectToDelete = project },
                        lang = lang
                    )
                }
            }
        }

        // Bottom Navigation
        VexBottomBar(
            currentScreen = Screen.PROJECTS,
            onNavigate = onNavigate,
            lang = lang
        )
    }
}

@Composable
fun ProjectGridCard(
    project: Project,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    lang: com.example.data.AppLanguage
) {
    var showMenu by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM dd", Locale.US) }
    val formattedDate = remember(project.lastEditedTimestamp) {
        dateFormat.format(Date(project.lastEditedTimestamp))
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = VexPanel),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .border(1.dp, VexBorder, RoundedCornerShape(8.dp))
            .testTag("project_card_${project.id}")
    ) {
        Column {
            // Thumbnail container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .background(Color(0xFF14131A)),
                contentAlignment = Alignment.Center
            ) {
                val thumbFile = project.thumbnailPath?.let { java.io.File(it) }
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
                        modifier = Modifier.size(36.dp)
                    )
                }

                // 3-dots Menu Button
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(VexPanelElevated)
                    ) {
                        DropdownMenuItem(
                            text = { Text(Strings.get("open", lang), color = VexTextPrimary) },
                            onClick = {
                                showMenu = false
                                onClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(Strings.get("rename", lang), color = VexTextPrimary) },
                            onClick = {
                                showMenu = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(Strings.get("duplicate", lang), color = VexTextPrimary) },
                            onClick = {
                                showMenu = false
                                onDuplicate()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (project.isFavorite) "Unfavorite" else Strings.get("tab_favorites", lang),
                                    color = VexTextPrimary
                                )
                            },
                            onClick = {
                                showMenu = false
                                onToggleFavorite()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(Strings.get("delete", lang), color = VexMeterRed) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            // Info Section
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = project.title,
                    color = VexTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = project.aspectRatio.label,
                        color = VexTextSecondary,
                        fontSize = 11.sp
                    )
                    Text(
                        text = formattedDate,
                        color = VexTextSecondary,
                        fontSize = 11.sp
                    )
                    Text(
                        text = formatDurationMinutesSeconds(project.durationMs),
                        color = VexTextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
