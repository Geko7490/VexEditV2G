package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.*
import com.example.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ModuleCenterScreen(
    viewModel: StudioViewModel,
    onNavigate: (Screen) -> Unit
) {
    val lang = viewModel.settings.collectAsState().value.language
    val modules = viewModel.moduleRepo.modules.collectAsState().value
    val coroutineScope = rememberCoroutineScope()

    var selectedCategory by remember { mutableStateOf(ModuleCategory.ALL) }
    var filterMode by remember { mutableStateOf("All Modules") } // "All Modules", "Installed", "Available"
    var selectedModuleForDetails by remember { mutableStateOf<ModuleItem?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    // Module Details Dialog
    if (selectedModuleForDetails != null) {
        val mod = selectedModuleForDetails!!
        Dialog(onDismissRequest = { selectedModuleForDetails = null }) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = VexPanel),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .border(1.dp, VexBorder, RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = mod.title,
                            color = VexTextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { selectedModuleForDetails = null }) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = VexTextSecondary)
                        }
                    }

                    Text(
                        text = "${mod.version} · ${mod.sizeMb} MB · ${mod.category.name}",
                        color = VexAccentLight,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = mod.description,
                        color = VexTextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Unlocked Studio Features:",
                        color = VexTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    mod.unlockedFeatures.forEach { feat ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("• ", color = VexAccent, fontSize = 14.sp)
                            Text(feat, color = VexTextPrimary, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Enable / Disable switch if installed
                    if (mod.status == ModuleStatus.INSTALLED) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (mod.isEnabled) Strings.get("enabled", lang) else Strings.get("disabled", lang),
                                color = VexTextPrimary,
                                fontSize = 13.sp
                            )
                            Switch(
                                checked = mod.isEnabled,
                                onCheckedChange = {
                                    viewModel.moduleRepo.toggleModuleEnabled(mod.id)
                                    selectedModuleForDetails = selectedModuleForDetails?.copy(isEnabled = !mod.isEnabled)
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = VexAccent)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { selectedModuleForDetails = null },
                        colors = ButtonDefaults.buttonColors(containerColor = VexPanelElevated),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(Strings.get("close", lang), color = VexTextPrimary)
                    }
                }
            }
        }
    }

    // Filter list
    val filteredModules = remember(modules, selectedCategory, filterMode, searchQuery) {
        modules.filter { mod ->
            val matchCat = selectedCategory == ModuleCategory.ALL || mod.category == selectedCategory
            val matchFilter = when (filterMode) {
                "Installed" -> mod.status == ModuleStatus.INSTALLED
                "Available" -> mod.status == ModuleStatus.AVAILABLE
                else -> true
            }
            val matchSearch = searchQuery.isBlank() || mod.title.contains(searchQuery.trim(), ignoreCase = true)
            matchCat && matchFilter && matchSearch
        }
    }

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
            IconButton(onClick = { onNavigate(Screen.STUDIO) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = VexTextPrimary
                )
            }

            Text(
                text = Strings.get("module_center", lang),
                color = VexTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = { isSearchActive = !isSearchActive }) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = if (isSearchActive) VexAccent else VexTextSecondary
                )
            }
        }

        // Search field if active
        if (isSearchActive) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search modules...", color = VexTextSecondary, fontSize = 12.sp) },
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
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .height(48.dp)
            )
        }

        // Category Tabs: All, Video, Audio, Effects, Utilities
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            listOf(
                Pair(ModuleCategory.ALL, "All"),
                Pair(ModuleCategory.VIDEO, "Video"),
                Pair(ModuleCategory.AUDIO, "Audio"),
                Pair(ModuleCategory.EFFECTS, "Effects"),
                Pair(ModuleCategory.UTILITIES, "Utilities")
            ).forEach { (cat, label) ->
                val isSel = selectedCategory == cat
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { selectedCategory = cat }
                ) {
                    Text(
                        text = label,
                        color = if (isSel) VexAccentLight else VexTextSecondary,
                        fontSize = 14.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(28.dp)
                            .height(2.dp)
                            .background(if (isSel) VexAccent else Color.Transparent)
                    )
                }
            }
        }

        HorizontalDivider(color = VexBorder, thickness = 0.5.dp)

        // --- FEATURED BANNER (Matching Screenshot 4) ---
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = VexPanel),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .border(1.dp, VexBorder, RoundedCornerShape(8.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Purple geometric box
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(VexAccent.copy(alpha = 0.25f))
                        .border(1.dp, VexAccentLight, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesomeMotion,
                        contentDescription = null,
                        tint = VexAccentLight,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cinematic Effects Pack",
                        color = VexTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Enhance your film creations",
                        color = VexTextSecondary,
                        fontSize = 12.sp
                    )
                }

                Button(
                    onClick = {
                        viewModel.moduleRepo.installModule("mod_cinematic_effects", coroutineScope)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VexAccent),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("Install", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Filter By row: "All Modules", "Installed", "Available"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Filter By: ", color = VexTextSecondary, fontSize = 12.sp)
            Spacer(modifier = Modifier.width(6.dp))

            listOf("All Modules", "Installed", "Available").forEach { filterName ->
                val isSel = filterMode == filterName
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSel) VexPanelElevated else Color.Transparent)
                        .border(1.dp, if (isSel) VexBorder else Color.Transparent, RoundedCornerShape(4.dp))
                        .clickable { filterMode = filterName }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = filterName,
                        color = if (isSel) VexTextPrimary else VexTextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // --- MODULES LIST (Matching Screenshot 4) ---
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredModules, key = { it.id }) { mod ->
                ModuleCardRow(
                    module = mod,
                    onDetails = { selectedModuleForDetails = mod },
                    onAction = {
                        when (mod.status) {
                            ModuleStatus.AVAILABLE -> viewModel.moduleRepo.installModule(mod.id, coroutineScope)
                            ModuleStatus.INSTALLED -> viewModel.moduleRepo.removeModule(mod.id)
                            ModuleStatus.UPDATE_AVAILABLE -> viewModel.moduleRepo.installModule(mod.id, coroutineScope)
                            ModuleStatus.DOWNLOADING -> viewModel.moduleRepo.removeModule(mod.id)
                        }
                    }
                )
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
fun ModuleCardRow(
    module: ModuleItem,
    onDetails: () -> Unit,
    onAction: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = VexPanel),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onDetails() }
            .border(1.dp, VexBorder, RoundedCornerShape(8.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(VexPanelElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (module.category) {
                            ModuleCategory.AUDIO -> Icons.Default.GraphicEq
                            ModuleCategory.EFFECTS -> Icons.Default.AutoAwesome
                            ModuleCategory.VIDEO -> Icons.Default.Movie
                            else -> Icons.Default.Extension
                        },
                        contentDescription = null,
                        tint = VexAccentLight,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = module.title,
                        color = VexTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${module.version} | ${module.sizeMb} MB",
                        color = VexTextSecondary,
                        fontSize = 11.sp
                    )
                }

                // Action Button / Status Badge
                when (module.status) {
                    ModuleStatus.INSTALLED -> {
                        OutlinedButton(
                            onClick = onAction,
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = VexPanelElevated),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(VexBorder)),
                            shape = RoundedCornerShape(4.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("Remove", color = VexTextSecondary, fontSize = 11.sp)
                        }
                    }
                    ModuleStatus.AVAILABLE -> {
                        Button(
                            onClick = onAction,
                            colors = ButtonDefaults.buttonColors(containerColor = VexPanelElevated),
                            shape = RoundedCornerShape(4.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("Install", color = VexTextPrimary, fontSize = 11.sp)
                        }
                    }
                    ModuleStatus.UPDATE_AVAILABLE -> {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Update Available", color = VexTextSecondary, fontSize = 9.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Button(
                                onClick = onAction,
                                colors = ButtonDefaults.buttonColors(containerColor = VexPanelElevated),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("Update", color = VexTextPrimary, fontSize = 11.sp)
                            }
                        }
                    }
                    ModuleStatus.DOWNLOADING -> {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Downloading... ${module.downloadProgress}%", color = VexTextSecondary, fontSize = 9.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            OutlinedButton(
                                onClick = onAction,
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = VexPanelElevated),
                                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(VexBorder)),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Cancel", color = VexMeterRed, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }

            // Downloading Progress Bar
            if (module.status == ModuleStatus.DOWNLOADING) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { module.downloadProgress / 100f },
                    color = VexAccent,
                    trackColor = VexPanelElevated,
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                )
            }
        }
    }
}
