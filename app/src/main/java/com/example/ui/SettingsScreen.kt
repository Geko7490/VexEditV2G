package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.*
import com.example.ui.theme.*

@Composable
fun SettingsScreen(
    viewModel: StudioViewModel,
    onNavigate: (Screen) -> Unit
) {
    val settings = viewModel.settings.collectAsState().value
    val lang = settings.language

    var showClearCacheDialog by remember { mutableStateOf(false) }
    var cacheClearedToast by remember { mutableStateOf(false) }
    var recoverySuccessToast by remember { mutableStateOf(false) }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text(Strings.get("clear_cache", lang), color = VexTextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to clear temporary video and audio rendering cache?", color = VexTextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearCache()
                        showClearCacheDialog = false
                        cacheClearedToast = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VexAccent)
                ) {
                    Text(Strings.get("confirm", lang), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text(Strings.get("cancel", lang), color = VexTextSecondary)
                }
            },
            containerColor = VexPanel
        )
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
            IconButton(onClick = { onNavigate(Screen.HOME) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = VexTextPrimary
                )
            }
            Text(
                text = Strings.get("nav_settings", lang),
                color = VexTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // --- SCROLLABLE SETTINGS CONTENT ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Success Toasts
            if (cacheClearedToast) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = VexPanelElevated),
                    modifier = Modifier.fillMaxWidth().border(1.dp, VexMeterGreen, RoundedCornerShape(8.dp))
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = VexMeterGreen)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(Strings.get("cache_cleared", lang), color = VexTextPrimary, fontSize = 13.sp)
                    }
                }
            }

            if (recoverySuccessToast) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = VexPanelElevated),
                    modifier = Modifier.fillMaxWidth().border(1.dp, VexAccent, RoundedCornerShape(8.dp))
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Restore, contentDescription = null, tint = VexAccent)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Auto-Save project successfully restored!", color = VexTextPrimary, fontSize = 13.sp)
                    }
                }
            }

            // 1. GENERAL (Language & Theme)
            SettingsSectionHeader(title = Strings.get("general", lang))

            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = VexPanel),
                modifier = Modifier.fillMaxWidth().border(1.dp, VexBorder, RoundedCornerShape(8.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Language Switcher
                    Text(text = Strings.get("language", lang), color = VexTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AppLanguage.values().forEach { l ->
                            val isSel = lang == l
                            Button(
                                onClick = { viewModel.setLanguage(l) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSel) VexAccent else VexPanelElevated
                                ),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f).testTag("lang_${l.code}")
                            ) {
                                Text(l.displayName, color = if (isSel) Color.White else VexTextSecondary, fontSize = 12.sp)
                            }
                        }
                    }

                    HorizontalDivider(color = VexBorder, thickness = 0.5.dp)

                    // Theme
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(Strings.get("theme", lang), color = VexTextPrimary, fontSize = 13.sp)
                        Text("Vex Dark (#101014)", color = VexAccentLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 2. PERFORMANCE (Low, Medium, High)
            SettingsSectionHeader(title = Strings.get("performance", lang))

            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = VexPanel),
                modifier = Modifier.fillMaxWidth().border(1.dp, VexBorder, RoundedCornerShape(8.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(
                        Triple(PerformanceMode.LOW, Strings.get("perf_low", lang), "Optimized for older devices & battery saving (360p preview)"),
                        Triple(PerformanceMode.MEDIUM, Strings.get("perf_med", lang), "Balanced quality & timeline smoothness (720p preview)"),
                        Triple(PerformanceMode.HIGH, Strings.get("perf_high", lang), "Real-time effects shaders & 60 FPS playback (1080p preview)")
                    ).forEach { (mode, title, desc) ->
                        val isSel = settings.performanceMode == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) VexPanelElevated else Color.Transparent)
                                .clickable { viewModel.setPerformanceMode(mode) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSel,
                                onClick = { viewModel.setPerformanceMode(mode) },
                                colors = RadioButtonDefaults.colors(selectedColor = VexAccent)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(title, color = if (isSel) VexAccentLight else VexTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(desc, color = VexTextSecondary, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }

            // 3. STORAGE & CACHE
            SettingsSectionHeader(title = Strings.get("storage", lang))

            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = VexPanel),
                modifier = Modifier.fillMaxWidth().border(1.dp, VexBorder, RoundedCornerShape(8.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Cache Usage", color = VexTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Render waveforms, downscaled previews", color = VexTextSecondary, fontSize = 10.sp)
                        }
                        Text("${settings.cacheSizeMb} MB", color = VexTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { showClearCacheDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = VexPanelElevated),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(VexBorder)),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth().testTag("clear_cache_btn")
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = VexTextSecondary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(Strings.get("clear_cache", lang), color = VexTextPrimary, fontSize = 12.sp)
                    }
                }
            }

            // 4. PROJECT RECOVERY
            SettingsSectionHeader(title = Strings.get("project_recovery", lang))

            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = VexPanel),
                modifier = Modifier.fillMaxWidth().border(1.dp, VexBorder, RoundedCornerShape(8.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(Strings.get("auto_save", lang), color = VexTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Continuous snapshot backup every edit", color = VexTextSecondary, fontSize = 10.sp)
                        }
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = VexMeterGreen, modifier = Modifier.size(20.dp))
                    }

                    Button(
                        onClick = {
                            viewModel.restoreAutoSave()
                            recoverySuccessToast = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = VexPanelElevated),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth().testTag("restore_autosave_btn")
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, tint = VexAccentLight, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(Strings.get("recover_last", lang), color = VexTextPrimary, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        // Bottom Navigation
        VexBottomBar(
            currentScreen = Screen.SETTINGS,
            onNavigate = onNavigate,
            lang = lang
        )
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = VexTextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )
}
