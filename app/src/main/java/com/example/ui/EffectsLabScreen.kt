package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.*
import com.example.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject

@Composable
private data class EffectPresetEntry(
    val name: String,
    val type: EffectType,
    val intensity: Float,
    val radius: Float,
    val opacity: Float,
    val blend: Float
)

fun EffectsLabScreen(
    viewModel: StudioViewModel,
    onNavigate: (Screen) -> Unit
) {
    val lang = viewModel.settings.collectAsState().value.language
    val context = LocalContext.current
    val presetPreferences = remember(context) { context.getSharedPreferences("vex_effect_presets", 0) }
    val savedPresets = remember { mutableStateListOf<EffectPresetEntry>() }
    var showPresetsDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    LaunchedEffect(presetPreferences) {
        savedPresets.clear()
        val stored = runCatching { JSONArray(presetPreferences.getString("presets", "[]") ?: "[]") }.getOrNull()
        if (stored != null) {
            for (index in 0 until stored.length()) {
                val item = stored.optJSONObject(index) ?: continue
                val type = runCatching { EffectType.valueOf(item.optString("type")) }.getOrNull() ?: continue
                savedPresets.add(EffectPresetEntry(
                    name = item.optString("name", "Preset"), type = type,
                    intensity = item.optDouble("intensity", 1.0).toFloat(),
                    radius = item.optDouble("radius", 0.0).toFloat(),
                    opacity = item.optDouble("opacity", 1.0).toFloat(),
                    blend = item.optDouble("blend", 0.0).toFloat()
                ))
            }
        }
    }

    fun persistPresets() {
        val json = JSONArray()
        savedPresets.forEach { preset ->
            json.put(JSONObject().apply {
                put("name", preset.name); put("type", preset.type.name)
                put("intensity", preset.intensity.toDouble()); put("radius", preset.radius.toDouble())
                put("opacity", preset.opacity.toDouble()); put("blend", preset.blend.toDouble())
            })
        }
        presetPreferences.edit().putString("presets", json.toString()).apply()
    }
    val project = viewModel.currentProject.collectAsState().value
    val selectedClipId = viewModel.selectedClipId.collectAsState().value

    val targetClip = remember(project, selectedClipId) {
        project?.clips?.find { it.id == selectedClipId }
            ?: project?.clips?.firstOrNull { it.trackType == TrackType.VIDEO }
    }

    var selectedCategory by remember { mutableStateOf("Light") }
    var selectedEffectType by remember { mutableStateOf(EffectType.VIOLET_GLOW) }

    var intensity by remember { mutableStateOf(1.00f) }
    var radius by remember { mutableStateOf(0.00f) }
    var opacity by remember { mutableStateOf(1.00f) }
    var blend by remember { mutableStateOf(0.00f) }

    // Before | After toggle
    var isBeforeMode by remember { mutableStateOf(false) }

    val categories = listOf("Cinematic", "Color", "Blur", "Glitch", "Light", "Texture")

    val effectsList = EffectType.values().map { it to it.title }
    val filteredEffects = effectsList.filter { it.first.category.name.equals(selectedCategory, ignoreCase = true) }

    LaunchedEffect(targetClip, selectedEffectType) {
        val current = targetClip?.effects?.firstOrNull { it.type == selectedEffectType }
        if (current != null) {
            intensity = current.intensity; radius = current.radius
            opacity = current.opacity; blend = current.blend
        } else {
            intensity = 1f; radius = 0f; opacity = 1f; blend = 0f
        }
    }

    if (showPresetsDialog) {
        AlertDialog(
            onDismissRequest = { showPresetsDialog = false },
            title = { Text("Effect Presets", color = VexTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = presetName, onValueChange = { presetName = it },
                        label = { Text("Preset name") }, singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = VexTextPrimary, unfocusedTextColor = VexTextPrimary)
                    )
                    Button(
                        onClick = {
                            val name = presetName.trim()
                            if (name.isNotEmpty()) {
                                savedPresets.add(EffectPresetEntry(name, selectedEffectType, intensity, radius, opacity, blend))
                                persistPresets(); presetName = ""
                            }
                        }, enabled = presetName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = VexAccent)
                    ) { Text("Save Current Preset", color = Color.White) }
                    if (savedPresets.isEmpty()) Text("No saved presets yet.", color = VexTextSecondary, fontSize = 12.sp)
                    savedPresets.forEachIndexed { index, preset ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                selectedEffectType = preset.type; intensity = preset.intensity
                                radius = preset.radius; opacity = preset.opacity; blend = preset.blend
                                showPresetsDialog = false
                            }, modifier = Modifier.weight(1f)) {
                                Text("${preset.name} · ${preset.type.title}", color = VexTextPrimary)
                            }
                            IconButton(onClick = { savedPresets.removeAt(index); persistPresets() }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete preset", tint = VexTextSecondary)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPresetsDialog = false }) { Text(Strings.get("close", lang)) } },
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
            IconButton(onClick = { onNavigate(Screen.STUDIO) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = VexTextPrimary
                )
            }
            Text(
                text = "Effects Lab",
                color = VexTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            TextButton(onClick = {
                intensity = 1.0f
                radius = 0.0f
                opacity = 1.0f
                blend = 0.0f
            }) {
                Text(Strings.get("reset", lang), color = VexTextSecondary, fontSize = 13.sp)
            }

            TextButton(onClick = { showPresetsDialog = true }) {
                Text(Strings.get("save_preset", lang), color = VexTextPrimary, fontSize = 13.sp)
            }
        }

        // --- PREVIEW WITH BEFORE | AFTER TOGGLE ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val showGlow = !isBeforeMode && selectedEffectType == EffectType.VIOLET_GLOW
            val clipFile = targetClip?.let { java.io.File(it.thumbnailPath.ifEmpty { it.filePath }) }

            if (clipFile != null && clipFile.exists()) {
                coil.compose.AsyncImage(
                    model = clipFile,
                    contentDescription = "Preview",
                    modifier = Modifier
                        .fillMaxHeight(0.95f)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop,
                    colorFilter = if (showGlow) ColorFilter.tint(VexAccent.copy(alpha = 0.35f * opacity)) else null
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxHeight(0.95f)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF14131A)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = VexAccentLight.copy(alpha = 0.5f), modifier = Modifier.size(36.dp))
                }
            }
        }

        // Before | After Segmented Switch (Matching Screenshot 1)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(VexPanel)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Before",
                color = if (isBeforeMode) VexAccentLight else VexTextSecondary,
                fontSize = 12.sp,
                fontWeight = if (isBeforeMode) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { isBeforeMode = true }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(14.dp)
                    .background(VexBorder)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "After",
                color = if (!isBeforeMode) VexAccentLight else VexTextSecondary,
                fontSize = 12.sp,
                fontWeight = if (!isBeforeMode) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { isBeforeMode = false }
            )
        }

        // Scrollable Content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Category Filter Pills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { cat ->
                    val isSelected = selectedCategory == cat
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) VexPanelElevated else Color.Transparent)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) VexAccentLight else VexBorder,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable { selectedCategory = cat }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = cat,
                            color = if (isSelected) Color.White else VexTextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Effect Cards Grid (2 rows x 4 items)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (filteredEffects.isEmpty()) {
                    Text("No effects in this category yet.", color = VexTextSecondary, fontSize = 12.sp)
                }
                filteredEffects.chunked(4).forEach { rowEffects ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowEffects.forEach { (type, title) ->
                            val isSelected = selectedEffectType == type
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(VexPanel)
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) VexAccent else VexBorder,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .clickable { selectedEffectType = type }
                            ) {
                                Column {
                                    val clipFile = targetClip?.let { java.io.File(it.thumbnailPath.ifEmpty { it.filePath }) }
                                    if (clipFile != null && clipFile.exists()) {
                                        coil.compose.AsyncImage(
                                            model = clipFile,
                                            contentDescription = title,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(44.dp),
                                            contentScale = ContentScale.Crop,
                                            colorFilter = if (type == EffectType.VIOLET_GLOW) ColorFilter.tint(VexAccent.copy(alpha = 0.35f)) else null
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(44.dp)
                                                .background(if (type == EffectType.VIOLET_GLOW) VexAccentDark else VexPanelElevated),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Filter, contentDescription = null, tint = VexAccentLight, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(VexPanelElevated)
                                            .padding(vertical = 4.dp, horizontal = 2.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = title,
                                            color = if (isSelected) VexAccentLight else VexTextPrimary,
                                            fontSize = 9.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- PARAMETER SLIDERS (Matching Screenshot 1) ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(VexPanel, RoundedCornerShape(8.dp))
                    .border(1.dp, VexBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Intensity Slider
                EffectSliderRow(
                    label = "Intensity",
                    value = intensity,
                    valueText = String.format(java.util.Locale.US, "%.2f", intensity),
                    range = 0f..2f,
                    onValueChange = { intensity = it }
                )

                // Radius Slider
                EffectSliderRow(
                    label = "Radius",
                    value = radius,
                    valueText = String.format(java.util.Locale.US, "%.2f", radius),
                    range = 0f..50f,
                    onValueChange = { radius = it }
                )

                // Opacity Slider
                EffectSliderRow(
                    label = "Opacity",
                    value = opacity,
                    valueText = "${(opacity * 100).toInt()}%",
                    range = 0f..1f,
                    onValueChange = { opacity = it }
                )

                // Blend Slider
                EffectSliderRow(
                    label = "Blend",
                    value = blend,
                    valueText = String.format(java.util.Locale.US, "%.2f", blend),
                    range = 0f..1f,
                    onValueChange = { blend = it }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Cancel & Apply Buttons (Matching Screenshot 1)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onNavigate(Screen.STUDIO) },
                    colors = ButtonDefaults.buttonColors(containerColor = VexPanelElevated),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Text(Strings.get("cancel", lang), color = VexTextPrimary, fontSize = 13.sp)
                }

                Button(
                    onClick = {
                        targetClip?.let { clip ->
                            viewModel.applyOrUpdateEffect(
                                clip.id,
                                ClipEffect(
                                    type = selectedEffectType,
                                    intensity = intensity,
                                    radius = radius,
                                    opacity = opacity,
                                    blend = blend,
                                    isEnabled = true
                                )
                            )
                        }
                        onNavigate(Screen.STUDIO)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VexAccent),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .weight(1.3f)
                        .height(44.dp)
                        .testTag("effects_apply_btn")
                ) {
                    Text(
                        Strings.get("apply", lang),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
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
fun EffectSliderRow(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = VexTextPrimary,
            fontSize = 12.sp,
            modifier = Modifier.width(70.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = VexAccent,
                activeTrackColor = VexAccent,
                inactiveTrackColor = VexPanelElevated
            )
        )
        Text(
            text = valueText,
            color = VexTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(42.dp)
        )
    }
}
