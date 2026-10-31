package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.*
import com.example.ui.theme.*

@Composable
fun KeyframeEditorScreen(
    viewModel: StudioViewModel,
    onNavigate: (Screen) -> Unit
) {
    val lang = viewModel.settings.collectAsState().value.language
    val project = viewModel.currentProject.collectAsState().value
    val playheadMs = viewModel.playheadPositionMs.collectAsState().value
    val selectedClipId = viewModel.selectedClipId.collectAsState().value

    val targetClip = remember(project, selectedClipId) {
        project?.clips?.find { it.id == selectedClipId }
            ?: project?.clips?.firstOrNull { it.trackType == TrackType.VIDEO }
    }

    var posX by remember(targetClip) { mutableStateOf(targetClip?.transform?.posX ?: 0f) }
    var posY by remember(targetClip) { mutableStateOf(targetClip?.transform?.posY ?: 0f) }
    var scaleVal by remember(targetClip) { mutableStateOf(targetClip?.transform?.scale ?: 100f) }
    var rotVal by remember(targetClip) { mutableStateOf(targetClip?.transform?.rotation ?: 0f) }
    var opacityVal by remember(targetClip) { mutableStateOf(targetClip?.transform?.opacity ?: 100f) }

    var selectedCurve by remember { mutableStateOf(InterpolationType.EASE_IN_OUT) }
    var selectedKeyframeId by remember(targetClip?.id) { mutableStateOf<String?>(null) }
    val selectedKeyframe = targetClip?.keyframes?.firstOrNull { it.id == selectedKeyframeId }
    var activeBottomTab by remember { mutableStateOf("Curves") } // Transform, Motion, Opacity, Curves

    LaunchedEffect(targetClip, playheadMs) {
        targetClip?.let { clip ->
            posX = viewModel.getInterpolatedValue(clip, KeyframeParam.POSITION_X)
            posY = viewModel.getInterpolatedValue(clip, KeyframeParam.POSITION_Y)
            scaleVal = viewModel.getInterpolatedValue(clip, KeyframeParam.SCALE)
            rotVal = viewModel.getInterpolatedValue(clip, KeyframeParam.ROTATION)
            opacityVal = viewModel.getInterpolatedValue(clip, KeyframeParam.OPACITY)
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
                text = "Keyframe Editor",
                color = VexTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = { viewModel.undo() }) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = VexTextPrimary)
            }

            TextButton(onClick = {
                targetClip?.let { clip ->
                    viewModel.updateClipTransform(
                        clip.id,
                        ClipTransform(posX, posY, scaleVal, rotVal, opacityVal)
                    )
                }
                onNavigate(Screen.STUDIO)
            }) {
                Text(Strings.get("save", lang), color = VexAccentLight, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }

        // --- UPPER SECTION: PREVIEW (LEFT) + PARAMETERS & CURVE GRAPH (RIGHT) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Left: Interactive Preview with bounding box
            Box(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val clipFile = targetClip?.let { java.io.File(it.thumbnailPath.ifEmpty { it.filePath }) }
                if (clipFile != null && clipFile.exists()) {
                    coil.compose.AsyncImage(
                        model = clipFile,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .offset(x = posX.dp, y = posY.dp)
                            .scale((scaleVal / 100f).coerceIn(0.1f, 3f))
                            .rotate(rotVal)
                            .alpha((opacityVal / 100f).coerceIn(0f, 1f)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF14131A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MovieCreation, contentDescription = null, tint = VexAccentLight.copy(alpha = 0.5f), modifier = Modifier.size(36.dp))
                    }
                }

                // Draggable interactive "TITLES" bounding box matching Screenshot 7
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(x = posX.dp, y = posY.dp)
                        .scale((scaleVal / 100f).coerceIn(0.1f, 3f))
                        .rotate(rotVal)
                        .border(1.5.dp, VexAccent, RoundedCornerShape(2.dp))
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                posX = (posX + dragAmount.x * 0.5f).coerceIn(-100f, 100f)
                                posY = (posY + dragAmount.y * 0.5f).coerceIn(-100f, 100f)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Corner handle squares
                    Box(modifier = Modifier.size(6.dp).background(VexAccent).align(Alignment.TopStart))
                    Box(modifier = Modifier.size(6.dp).background(VexAccent).align(Alignment.TopEnd))
                    Box(modifier = Modifier.size(6.dp).background(VexAccent).align(Alignment.BottomStart))
                    Box(modifier = Modifier.size(6.dp).background(VexAccent).align(Alignment.BottomEnd))
                }
            }

            // Right: Keyframe Parameters + Bézier Curve Graph
            Column(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (activeBottomTab == "Transform" || activeBottomTab == "Motion") {
                KeyframeParamRow(
                    label = "Position X", valueText = String.format(java.util.Locale.US, "%.1f", posX),
                    value = posX, valueRange = -100f..100f, onValueChange = { posX = it }
                ) { targetClip?.let { viewModel.addOrUpdateKeyframe(it.id, KeyframeParam.POSITION_X, posX, selectedCurve) } }
                KeyframeParamRow(
                    label = "Position Y", valueText = String.format(java.util.Locale.US, "%.1f", posY),
                    value = posY, valueRange = -100f..100f, onValueChange = { posY = it }
                ) { targetClip?.let { viewModel.addOrUpdateKeyframe(it.id, KeyframeParam.POSITION_Y, posY, selectedCurve) } }
                if (activeBottomTab == "Transform") {
                KeyframeParamRow(
                    label = "Scale", valueText = "${scaleVal.toInt()}%", value = scaleVal,
                    valueRange = 10f..300f, onValueChange = { scaleVal = it }
                ) { targetClip?.let { viewModel.addOrUpdateKeyframe(it.id, KeyframeParam.SCALE, scaleVal, selectedCurve) } }
                }
                KeyframeParamRow(
                    label = "Rotation", valueText = "${rotVal.toInt()}°", value = rotVal,
                    valueRange = -180f..180f, onValueChange = { rotVal = it }
                ) { targetClip?.let { viewModel.addOrUpdateKeyframe(it.id, KeyframeParam.ROTATION, rotVal, selectedCurve) } }
                }
                if (activeBottomTab == "Opacity") {
                KeyframeParamRow(
                    label = "Opacity", valueText = "${opacityVal.toInt()}%", value = opacityVal,
                    valueRange = 0f..100f, onValueChange = { opacityVal = it }
                ) { targetClip?.let { viewModel.addOrUpdateKeyframe(it.id, KeyframeParam.OPACITY, opacityVal, selectedCurve) } }
                }

                if (activeBottomTab == "Curves") {
                Spacer(modifier = Modifier.height(4.dp))

                // Bézier Curve Graph Card
                Card(
                    shape = RoundedCornerShape(6.dp),
                    colors = CardDefaults.cardColors(containerColor = VexPanel),
                    modifier = Modifier.fillMaxWidth().border(0.5.dp, VexBorder, RoundedCornerShape(6.dp))
                ) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        // Curve Canvas
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .background(VexBackground, RoundedCornerShape(4.dp))
                                .padding(4.dp)
                        ) {
                            // Grid lines
                            val gridColor = Color(0xFF232230)
                            drawLine(gridColor, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1f)
                            drawLine(gridColor, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1f)

                            // Bézier S-curve
                            val path = Path().apply {
                                moveTo(4f, size.height - 4f)
                                cubicTo(
                                    size.width * 0.4f, size.height - 4f,
                                    size.width * 0.6f, 4f,
                                    size.width - 4f, 4f
                                )
                            }
                            drawPath(path, color = VexAccent, style = Stroke(width = 3f, cap = StrokeCap.Round))
                            // Handle points
                            drawCircle(Color.White, radius = 4f, center = Offset(size.width * 0.4f, size.height * 0.7f))
                            drawCircle(Color.White, radius = 4f, center = Offset(size.width * 0.6f, size.height * 0.3f))
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Curve Easing Presets
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf(
                                Pair(InterpolationType.LINEAR, "Linear"),
                                Pair(InterpolationType.EASE_OUT, "Ease Out"),
                                Pair(InterpolationType.EASE_IN, "Ease In"),
                                Pair(InterpolationType.EASE_IN_OUT, "Ease In-Out")
                            ).chunked(2).forEach { colPair ->
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    colPair.forEach { (type, label) ->
                                        val isSel = selectedCurve == type
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(if (isSel) VexAccent else VexPanelElevated)
                                                .clickable { selectedCurve = type }
                                                .padding(vertical = 3.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(label, color = if (isSel) Color.White else VexTextSecondary, fontSize = 9.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Timecode ruler
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .background(VexPanelElevated)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("00:00", "00:05", "00:10", "00:15", "00:20").forEach { tc ->
                Text(tc, color = if (tc == "00:10") VexAccentLight else VexTextSecondary, fontSize = 10.sp)
            }
        }

        // --- KEYFRAME TIMELINE TRACKS (Video 1, Transform, Opacity) ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(VexBackground)
                .border(0.5.dp, VexBorder)
        ) {
            // Video 1 thumbnail track
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .border(0.5.dp, VexBorder),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Video 1", color = VexTextSecondary, fontSize = 10.sp, modifier = Modifier.width(70.dp).padding(start = 6.dp))
                val clipFile = targetClip?.let { java.io.File(it.thumbnailPath.ifEmpty { it.filePath }) }
                Row(modifier = Modifier.weight(1f).fillMaxHeight().border(1.dp, VexAccentLight)) {
                    if (clipFile != null && clipFile.exists()) {
                        coil.compose.AsyncImage(
                            model = clipFile,
                            contentDescription = null,
                            modifier = Modifier.fillMaxHeight().width(50.dp),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Image(
                            painter = painterResource(id = R.drawable.sample_car_night),
                            contentDescription = null,
                            modifier = Modifier.fillMaxHeight().width(50.dp),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }

            val totalDur = targetClip?.durationMs?.coerceAtLeast(1000L) ?: 5000L
            val transformKeyframes = targetClip?.keyframes?.filter {
                it.param in listOf(KeyframeParam.POSITION_X, KeyframeParam.POSITION_Y, KeyframeParam.SCALE, KeyframeParam.ROTATION)
            } ?: emptyList()
            val opacityKeyframes = targetClip?.keyframes?.filter { it.param == KeyframeParam.OPACITY } ?: emptyList()
            val transformOffsets = transformKeyframes.map { (it.timeMs.toFloat() / totalDur).coerceIn(0.01f, 0.99f) }
            val opacityOffsets = opacityKeyframes.map { (it.timeMs.toFloat() / totalDur).coerceIn(0.01f, 0.99f) }

            KeyframeTrackRow(
                label = "Transform", keyframeOffsets = transformOffsets,
                onKeyframeClick = { index ->
                    targetClip?.let {
                        val keyframe = transformKeyframes.getOrNull(index) ?: return@let
                        selectedKeyframeId = keyframe.id
                        viewModel.setPlayheadPosition(it.startTimeMs + keyframe.timeMs)
                    }
                }
            )
            KeyframeTrackRow(
                label = "Opacity", keyframeOffsets = opacityOffsets,
                onKeyframeClick = { index ->
                    targetClip?.let {
                        val keyframe = opacityKeyframes.getOrNull(index) ?: return@let
                        selectedKeyframeId = keyframe.id
                        viewModel.setPlayheadPosition(it.startTimeMs + keyframe.timeMs)
                    }
                }
            )
        }

        // --- KEYFRAME CONTROLS BAR: [ < Prev Keyframe ] [ ◆+ Add Keyframe ] [ > Next Keyframe ] ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(VexPanel)
                .border(0.5.dp, VexBorder),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val clip = targetClip
                    val keyframe = selectedKeyframe
                    if (clip != null && keyframe != null) {
                        viewModel.removeKeyframe(clip.id, keyframe.id)
                        selectedKeyframeId = null
                    }
                },
                enabled = selectedKeyframe != null
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete selected keyframe",
                    tint = if (selectedKeyframe != null) VexMeterRed else VexTextMuted
                )
            }

            TextButton(
                onClick = { targetClip?.let { viewModel.jumpToPreviousKeyframe(it.id) } }
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = VexTextPrimary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(Strings.get("prev_keyframe", lang), color = VexTextPrimary, fontSize = 11.sp)
            }

            Button(
                onClick = {
                    targetClip?.let {
                        viewModel.addOrUpdateKeyframes(
                            it.id,
                            mapOf(
                                KeyframeParam.POSITION_X to posX,
                                KeyframeParam.POSITION_Y to posY,
                                KeyframeParam.SCALE to scaleVal,
                                KeyframeParam.ROTATION to rotVal,
                                KeyframeParam.OPACITY to opacityVal
                            ),
                            selectedCurve
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = VexPanelElevated),
                shape = RoundedCornerShape(4.dp),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(VexBorder)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("◆+", color = VexAccentLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(6.dp))
                Text(Strings.get("add_keyframe", lang), color = Color.White, fontSize = 11.sp)
            }

            TextButton(
                onClick = { targetClip?.let { viewModel.jumpToNextKeyframe(it.id) } }
            ) {
                Text(Strings.get("next_keyframe", lang), color = VexTextPrimary, fontSize = 11.sp)
                Spacer(modifier = Modifier.width(4.dp))
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = VexTextPrimary, modifier = Modifier.size(16.dp))
            }
        }

        // Bottom Tabs: Transform, Motion, Opacity, Curves
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(VexBackground)
                .border(0.5.dp, VexBorder),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                Pair("Transform", Icons.Default.CropRotate),
                Pair("Motion", Icons.Default.DirectionsRun),
                Pair("Opacity", Icons.Default.Tonality),
                Pair("Curves", Icons.Default.Gesture)
            ).forEach { (tabName, icon) ->
                val isSel = activeBottomTab == tabName
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { activeBottomTab = tabName }
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = tabName,
                        tint = if (isSel) VexAccentLight else VexTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = tabName,
                        color = if (isSel) VexAccentLight else VexTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                    )
                }
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
fun KeyframeParamRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onToggleDiamond: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = VexTextPrimary, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(valueText, color = VexTextSecondary, fontSize = 11.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier.size(18.dp).clickable { onToggleDiamond() },
                    contentAlignment = Alignment.Center
                ) { Text("◆", color = VexAccentLight, fontSize = 13.sp) }
            }
        }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth().height(22.dp)
        )
    }
}

@Composable
fun KeyframeTrackRow(
    label: String,
    keyframeOffsets: List<Float>,
    onKeyframeClick: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .border(0.5.dp, VexBorder),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = VexTextSecondary, fontSize = 10.sp, modifier = Modifier.width(70.dp).padding(start = 6.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterStart
        ) {
            // Horizontal line
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(VexAccent.copy(alpha = 0.5f)))

            // Diamonds on track
            keyframeOffsets.forEachIndexed { index, fraction ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .wrapContentWidth(Alignment.End)
                        .clickable { onKeyframeClick(index) }
                ) {
                    Text("◆", color = VexAccentLight, fontSize = 12.sp)
                }
            }
        }
    }
}
