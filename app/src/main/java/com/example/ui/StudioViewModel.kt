package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import java.io.File
import com.example.engine.ExportConfig
import com.example.engine.ExportEngine
import com.example.engine.ExportResult
import com.example.engine.StudioMediaPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

enum class Screen {
    HOME,
    PROJECTS,
    STUDIO,
    SETTINGS,
    AUDIO_STUDIO,
    EFFECTS_LAB,
    KEYFRAME_EDITOR,
    MODULE_CENTER,
    IMPORT_MEDIA
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    val projectRepo = ProjectRepository(application)
    val moduleRepo = ModuleRepository(application)
    val exportEngine = ExportEngine(application)
    val mediaManager = MediaManager(application)
    val player = StudioMediaPlayer(application)

    private val _currentScreen = MutableStateFlow(Screen.HOME)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _currentProject = MutableStateFlow<Project?>(null)
    val currentProject: StateFlow<Project?> = _currentProject.asStateFlow()

    private val _playheadPositionMs = MutableStateFlow(0L)
    val playheadPositionMs: StateFlow<Long> = _playheadPositionMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _selectedClipId = MutableStateFlow<String?>(null)
    val selectedClipId: StateFlow<String?> = _selectedClipId.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // Effects Lab: Before / After split comparison toggle
    private val _beforeAfterMode = MutableStateFlow(false) // false = After, true = Before
    val beforeAfterMode: StateFlow<Boolean> = _beforeAfterMode.asStateFlow()

    // Undo / Redo stacks
    private val undoStack = mutableListOf<Project>()
    private val redoStack = mutableListOf<Project>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    // Export State
    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _exportProgress = MutableStateFlow(0f)
    val exportProgress: StateFlow<Float> = _exportProgress.asStateFlow()

    private val _exportStage = MutableStateFlow("Hazırlanıyor...")
    val exportStage: StateFlow<String> = _exportStage.asStateFlow()

    private val _exportResult = MutableStateFlow<ExportResult?>(null)
    val exportResult: StateFlow<ExportResult?> = _exportResult.asStateFlow()

    private val _showExportDialog = MutableStateFlow(false)
    val showExportDialog: StateFlow<Boolean> = _showExportDialog.asStateFlow()

    private var playbackJob: Job? = null

    init {
        val initialProjects = projectRepo.projects.value
        if (initialProjects.isNotEmpty()) {
            val proj = sanitizeProject(initialProjects.first())
            _currentProject.value = proj
            _selectedClipId.value = proj.clips.firstOrNull()?.id
        } else {
            _currentProject.value = null
            _selectedClipId.value = null
        }
    }

    private fun sanitizeProject(project: Project): Project {
        var changed = false
        val sanitizedClips = project.clips.map { clip ->
            if (clip.mediaType == MediaType.VIDEO && clip.sourceDurationMs <= 0L && clip.filePath.isNotEmpty()) {
                val dur = mediaManager.queryFileDurationMs(clip.filePath)
                if (dur > 0L) {
                    changed = true
                    clip.copy(sourceDurationMs = dur)
                } else clip
            } else clip
        }
        return if (changed) {
            val updated = project.copy(clips = sanitizedClips)
            projectRepo.saveProject(updated)
            updated
        } else project
    }

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    fun selectProject(project: Project) {
        pausePlayback()
        val sanitized = sanitizeProject(project)
        _currentProject.value = sanitized
        _playheadPositionMs.value = 0L
        _selectedClipId.value = sanitized.clips.firstOrNull()?.id
        undoStack.clear()
        redoStack.clear()
        updateUndoRedoStates()
        _currentScreen.value = Screen.STUDIO
    }

    fun createNewProject(name: String, aspectRatio: AspectRatio) {
        pausePlayback()
        val project = Project(
            title = if (name.isBlank()) "Untitled Project" else name.trim(),
            aspectRatio = aspectRatio,
            durationMs = 0L,
            lastEditedTimestamp = System.currentTimeMillis(),
            clips = emptyList()
        )
        projectRepo.saveProject(project)
        selectProject(project)
    }

    fun selectClip(clipId: String?) {
        _selectedClipId.value = clipId
    }

    fun setPlayheadPosition(posMs: Long) {
        val maxDuration = _currentProject.value?.durationMs ?: 0L
        val target = if (maxDuration > 0L) posMs.coerceIn(0L, maxDuration) else 0L
        _playheadPositionMs.value = target
        syncPlayerToPlayhead(target)
    }

    fun syncPlayerToPlayhead(posMs: Long) {
        val project = _currentProject.value ?: return
        val activeVideoClip = project.clips.filter {
            it.trackType == TrackType.VIDEO && it.mediaType == MediaType.VIDEO
        }.find { posMs >= it.startTimeMs && posMs < (it.startTimeMs + it.durationMs) }
            ?: (if (project.clips.any { it.trackType == TrackType.VIDEO && it.mediaType == MediaType.VIDEO } && posMs <= 0L)
                project.clips.firstOrNull { it.trackType == TrackType.VIDEO && it.mediaType == MediaType.VIDEO }
            else null)

        if (activeVideoClip != null && activeVideoClip.filePath.isNotEmpty()) {
            val offsetMs = (posMs - activeVideoClip.startTimeMs + activeVideoClip.sourceStartMs).coerceAtLeast(0L)
            val isMuted = activeVideoClip.audioProperties.isMuted
            val vol = (activeVideoClip.volume * activeVideoClip.audioProperties.volume).coerceIn(0f, 1f)
            player.bindVideoClip(
                filePath = activeVideoClip.filePath,
                startOffsetMs = offsetMs,
                shouldPlay = _isPlaying.value,
                volume = vol,
                muted = isMuted
            )
        } else {
            player.stopVideo()
        }

        // Background audio track clip sync
        val activeAudioClip = project.clips.filter {
            it.trackType == TrackType.AUDIO_MUSIC || it.trackType == TrackType.AUDIO_SFX
        }.find { posMs >= it.startTimeMs && posMs < (it.startTimeMs + it.durationMs) }

        if (activeAudioClip != null && activeAudioClip.filePath.isNotEmpty()) {
            val audioOffset = (posMs - activeAudioClip.startTimeMs + activeAudioClip.sourceStartMs).coerceAtLeast(0L)
            player.bindAudioTrack(
                audioPath = activeAudioClip.filePath,
                startOffsetMs = audioOffset,
                shouldPlay = _isPlaying.value,
                volume = activeAudioClip.volume,
                muted = activeAudioClip.audioProperties.isMuted
            )
        } else {
            player.stopAudio()
        }
    }

    fun togglePlayback() {
        if (_isPlaying.value) {
            pausePlayback()
        } else {
            val current = _currentProject.value
            if (current != null && current.durationMs > 0L && _playheadPositionMs.value >= current.durationMs) {
                _playheadPositionMs.value = 0L
            }
            startPlayback()
        }
    }

    private fun startPlayback() {
        val current = _currentProject.value ?: return
        if (current.durationMs <= 0L) return

        if (_playheadPositionMs.value >= current.durationMs) {
            _playheadPositionMs.value = 0L
        }

        _isPlaying.value = true
        syncPlayerToPlayhead(_playheadPositionMs.value)
        player.play()

        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            var lastTickTime = System.currentTimeMillis()
            while (isActive && _isPlaying.value) {
                val proj = _currentProject.value ?: break
                val now = System.currentTimeMillis()
                val delta = now - lastTickTime
                lastTickTime = now

                val currentPos = _playheadPositionMs.value
                val newPos = currentPos + delta

                if (newPos >= proj.durationMs) {
                    _playheadPositionMs.value = proj.durationMs
                    pausePlayback()
                    break
                } else {
                    _playheadPositionMs.value = newPos
                    // Check if we crossed into a different video clip
                    val oldClip = proj.clips.filter { it.trackType == TrackType.VIDEO && it.mediaType == MediaType.VIDEO }
                        .find { currentPos >= it.startTimeMs && currentPos < (it.startTimeMs + it.durationMs) }
                    val newClip = proj.clips.filter { it.trackType == TrackType.VIDEO && it.mediaType == MediaType.VIDEO }
                        .find { newPos >= it.startTimeMs && newPos < (it.startTimeMs + it.durationMs) }

                    if (oldClip?.id != newClip?.id) {
                        syncPlayerToPlayhead(newPos)
                    }
                }
                delay(30) // Smooth ~33 FPS UI updates
            }
        }
    }

    fun pausePlayback() {
        _isPlaying.value = false
        playbackJob?.cancel()
        player.pause()
    }

    // --- State mutation with UNDO / REDO ---

    private fun pushUndoState(project: Project) {
        undoStack.add(project)
        if (undoStack.size > 30) undoStack.removeAt(0)
        redoStack.clear()
        updateUndoRedoStates()
    }

    private fun updateUndoRedoStates() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    fun undo() {
        val current = _currentProject.value ?: return
        if (undoStack.isNotEmpty()) {
            val prev = undoStack.removeAt(undoStack.lastIndex)
            redoStack.add(current)
            _currentProject.value = prev
            projectRepo.saveProject(prev)
            updateUndoRedoStates()
        }
    }

    fun redo() {
        val current = _currentProject.value ?: return
        if (redoStack.isNotEmpty()) {
            val next = redoStack.removeAt(redoStack.lastIndex)
            undoStack.add(current)
            _currentProject.value = next
            projectRepo.saveProject(next)
            updateUndoRedoStates()
        }
    }

    // --- Clip Editing Actions ---

    fun reorderTrackClips(trackId: String, fromIndex: Int, toIndex: Int) {
        val project = _currentProject.value ?: return
        val ordered = project.clips.filter { it.trackId == trackId }.sortedBy { it.startTimeMs }
        if (fromIndex !in ordered.indices || toIndex !in ordered.indices || fromIndex == toIndex) return

        // Reorder clip identities while preserving the track's existing timeline slots.
        // This keeps intentional gaps and overlaps instead of silently packing the track.
        pushUndoState(project)
        val reordered = ordered.toMutableList()
        val moved = reordered.removeAt(fromIndex)
        reordered.add(toIndex, moved)
        val slots = ordered.map { it.startTimeMs }
        val replacements = reordered.mapIndexed { index, clip -> clip.copy(startTimeMs = slots[index]) }
        val byId = replacements.associateBy { it.id }
        val allClips = project.clips.map { byId[it.id] ?: it }
        val maxEnd = allClips.maxOfOrNull { it.startTimeMs + it.durationMs } ?: 0L
        val updatedProject = project.copy(clips = allClips, durationMs = maxEnd)
        _currentProject.value = updatedProject
        _selectedClipId.value = moved.id
        projectRepo.saveProject(updatedProject)
    }

    fun moveClipToIndex(clipId: String, targetIndex: Int) {
        val project = _currentProject.value ?: return
        val clip = project.clips.find { it.id == clipId } ?: return
        val trackClips = project.clips.filter { it.trackId == clip.trackId }.sortedBy { it.startTimeMs }
        val currentIndex = trackClips.indexOfFirst { it.id == clipId }
        if (currentIndex != -1 && targetIndex in trackClips.indices) {
            reorderTrackClips(clip.trackId, currentIndex, targetIndex)
        }
    }

    fun moveClipLeft(clipId: String) {
        val project = _currentProject.value ?: return
        val clip = project.clips.find { it.id == clipId } ?: return
        val trackClips = project.clips.filter { it.trackId == clip.trackId }.sortedBy { it.startTimeMs }
        val currentIndex = trackClips.indexOfFirst { it.id == clipId }
        if (currentIndex > 0) {
            reorderTrackClips(clip.trackId, currentIndex, currentIndex - 1)
        }
    }

    fun moveClipRight(clipId: String) {
        val project = _currentProject.value ?: return
        val clip = project.clips.find { it.id == clipId } ?: return
        val trackClips = project.clips.filter { it.trackId == clip.trackId }.sortedBy { it.startTimeMs }
        val currentIndex = trackClips.indexOfFirst { it.id == clipId }
        if (currentIndex >= 0 && currentIndex < trackClips.lastIndex) {
            reorderTrackClips(clip.trackId, currentIndex, currentIndex + 1)
        }
    }

    fun splitClipAtPlayhead() {
        val project = _currentProject.value ?: return
        val playhead = _playheadPositionMs.value
        val clipId = _selectedClipId.value

        val targetClip = if (clipId != null) {
            project.clips.find { it.id == clipId }
        } else {
            project.clips.find { playhead > it.startTimeMs && playhead < (it.startTimeMs + it.durationMs) }
        } ?: return

        if (playhead <= targetClip.startTimeMs + 200L || playhead >= (targetClip.startTimeMs + targetClip.durationMs - 200L)) {
            return
        }

        pushUndoState(project)

        val splitPointInClip = playhead - targetClip.startTimeMs
        val remainingDuration = targetClip.durationMs - splitPointInClip

        val firstPart = targetClip.copy(
            durationMs = splitPointInClip
        )
        val secondPart = targetClip.copy(
            id = UUID.randomUUID().toString(),
            name = "${targetClip.name} (Part 2)",
            startTimeMs = playhead,
            sourceStartMs = targetClip.sourceStartMs + (splitPointInClip * targetClip.speed).toLong(),
            durationMs = remainingDuration
        )

        // Split only the selected clip. Keep every other clip's absolute timeline
        // position so existing gaps and overlaps are not silently destroyed.
        val allClips = project.clips.flatMap { clip ->
            if (clip.id == targetClip.id) listOf(firstPart, secondPart) else listOf(clip)
        }
        val maxEnd = allClips.maxOfOrNull { it.startTimeMs + it.durationMs } ?: 0L

        val updatedProject = project.copy(clips = allClips, durationMs = maxEnd)
        _currentProject.value = updatedProject
        _selectedClipId.value = secondPart.id
        projectRepo.saveProject(updatedProject)
    }

    fun trimClipStart(clipId: String, deltaMs: Long) {
        val project = _currentProject.value ?: return
        val clip = project.clips.find { it.id == clipId } ?: return
        val sourceDuration = if (clip.sourceDurationMs > 0L) clip.sourceDurationMs else {
            mediaManager.queryFileDurationMs(clip.filePath, clip.uriString).takeIf { it > 0L }
                ?: (clip.sourceStartMs + (clip.durationMs * clip.speed).toLong())
        }
        val speed = clip.speed.takeIf { it > 0f } ?: 1f
        val minDelta = maxOf(-(clip.sourceStartMs / speed).toLong(), -clip.startTimeMs)
        val maxDelta = clip.durationMs - 300L
        val delta = deltaMs.coerceIn(minDelta, maxDelta)
        if (delta == 0L) return

        pushUndoState(project)
        val sourceDelta = (delta * speed).toLong()
        val updatedClip = clip.copy(
            startTimeMs = (clip.startTimeMs + delta).coerceAtLeast(0L),
            sourceStartMs = (clip.sourceStartMs + sourceDelta).coerceAtLeast(0L),
            durationMs = (clip.durationMs - delta).coerceAtLeast(300L),
            sourceDurationMs = sourceDuration
        )
        val allClips = project.clips.map { if (it.id == clipId) updatedClip else it }
        val maxEnd = allClips.maxOfOrNull { it.startTimeMs + it.durationMs } ?: 0L
        val updatedProject = project.copy(clips = allClips, durationMs = maxEnd)
        _currentProject.value = updatedProject
        projectRepo.saveProject(updatedProject)
        syncPlayerToPlayhead(_playheadPositionMs.value)
    }

    fun trimClipEnd(clipId: String, deltaMs: Long) {
        val project = _currentProject.value ?: return
        val clip = project.clips.find { it.id == clipId } ?: return
        val sourceDuration = if (clip.sourceDurationMs > 0L) clip.sourceDurationMs else {
            mediaManager.queryFileDurationMs(clip.filePath, clip.uriString).takeIf { it > 0L }
                ?: (clip.sourceStartMs + (clip.durationMs * clip.speed).toLong())
        }
        val speed = clip.speed.takeIf { it > 0f } ?: 1f
        val maxDuration = if (clip.mediaType == MediaType.VIDEO) {
            ((sourceDuration - clip.sourceStartMs).coerceAtLeast(300L) / speed).toLong().coerceAtLeast(300L)
        } else 120_000L
        val targetDuration = (clip.durationMs + deltaMs).coerceIn(300L, maxDuration)
        if (targetDuration == clip.durationMs) return

        pushUndoState(project)
        val updatedClip = clip.copy(durationMs = targetDuration, sourceDurationMs = sourceDuration)
        val allClips = project.clips.map { if (it.id == clipId) updatedClip else it }
        val maxEnd = allClips.maxOfOrNull { it.startTimeMs + it.durationMs } ?: 0L
        val updatedProject = project.copy(clips = allClips, durationMs = maxEnd)
        _currentProject.value = updatedProject
        projectRepo.saveProject(updatedProject)
        syncPlayerToPlayhead(_playheadPositionMs.value)
    }

    fun setClipDuration(clipId: String, durationMs: Long) {
        val project = _currentProject.value ?: return
        val clip = project.clips.find { it.id == clipId } ?: return
        val clamped = durationMs.coerceAtLeast(500L)
        val delta = clamped - clip.durationMs
        trimClipEnd(clipId, delta)
    }

    fun moveClip(clipId: String, newStartTimeMs: Long) {
        val project = _currentProject.value ?: return
        val clip = project.clips.find { it.id == clipId } ?: return
        pushUndoState(project)
        val updated = clip.copy(startTimeMs = newStartTimeMs.coerceAtLeast(0L))
        val updatedClips = project.clips.map { if (it.id == clipId) updated else it }
        val maxEnd = updatedClips.maxOfOrNull { it.startTimeMs + it.durationMs } ?: 0L
        val updatedProject = project.copy(clips = updatedClips, durationMs = maxEnd)
        _currentProject.value = updatedProject
        projectRepo.saveProject(updatedProject)
    }

    fun duplicateClip(clipId: String) {
        val project = _currentProject.value ?: return
        val clip = project.clips.find { it.id == clipId } ?: return
        pushUndoState(project)

        // Insert the duplicate directly after the source clip. Later clips on the same
        // track move by exactly one clip duration; unrelated tracks remain untouched.
        val insertAt = clip.startTimeMs + clip.durationMs
        val copy = clip.copy(
            id = UUID.randomUUID().toString(),
            name = "${clip.name} (Copy)",
            startTimeMs = insertAt,
            isSelected = false
        )
        val updatedClips = project.clips.map { existing ->
            if (existing.trackId == clip.trackId && existing.id != clip.id && existing.startTimeMs >= insertAt) {
                existing.copy(startTimeMs = existing.startTimeMs + clip.durationMs)
            } else existing
        } + copy
        val maxEnd = updatedClips.maxOfOrNull { it.startTimeMs + it.durationMs } ?: 0L
        val updatedProject = project.copy(clips = updatedClips, durationMs = maxEnd)
        _currentProject.value = updatedProject
        _selectedClipId.value = copy.id
        projectRepo.saveProject(updatedProject)
    }

    fun deleteClip(clipId: String) {
        val project = _currentProject.value ?: return
        val clip = project.clips.find { it.id == clipId } ?: return
        pushUndoState(project)

        // Ripple-delete only the removed clip's occupied interval on its own track.
        // Existing gaps before/after that interval and all other tracks are preserved.
        val removedEnd = clip.startTimeMs + clip.durationMs
        val remaining = project.clips.filterNot { it.id == clipId }.map { other ->
            if (other.trackId == clip.trackId && other.startTimeMs >= removedEnd) {
                other.copy(startTimeMs = (other.startTimeMs - clip.durationMs).coerceAtLeast(0L))
            } else other
        }
        val maxEnd = remaining.maxOfOrNull { it.startTimeMs + it.durationMs } ?: 0L
        val firstVisual = remaining.firstOrNull { it.trackType == TrackType.VIDEO }
        val updatedProject = project.copy(
            clips = remaining,
            durationMs = maxEnd,
            thumbnailPath = firstVisual?.let { it.thumbnailPath.ifEmpty { it.filePath } }
        )
        _currentProject.value = updatedProject
        _selectedClipId.value = remaining.firstOrNull { it.trackId == clip.trackId }?.id
            ?: remaining.firstOrNull()?.id
        _playheadPositionMs.value = _playheadPositionMs.value.coerceAtMost(maxEnd)
        projectRepo.saveProject(updatedProject)
        syncPlayerToPlayhead(_playheadPositionMs.value)
    }

    fun stepPlayhead(deltaMs: Long) {
        val current = _playheadPositionMs.value
        val maxDur = _currentProject.value?.durationMs ?: 0L
        val target = (current + deltaMs).coerceIn(0L, maxDur)
        setPlayheadPosition(target)
    }

    fun jumpToPrevCut() {
        val project = _currentProject.value ?: return
        val current = _playheadPositionMs.value
        val cuts = project.clips.flatMap { listOf(it.startTimeMs, it.startTimeMs + it.durationMs) }
            .distinct()
            .sorted()
        val prevCut = cuts.filter { it < current - 100L }.maxOrNull() ?: 0L
        setPlayheadPosition(prevCut)
    }

    fun jumpToNextCut() {
        val project = _currentProject.value ?: return
        val current = _playheadPositionMs.value
        val maxDur = project.durationMs
        val cuts = project.clips.flatMap { listOf(it.startTimeMs, it.startTimeMs + it.durationMs) }
            .distinct()
            .sorted()
        val nextCut = cuts.filter { it > current + 100L }.minOrNull() ?: maxDur
        setPlayheadPosition(nextCut)
    }

    fun setClipSpeed(clipId: String, speed: Float) {
        val project = _currentProject.value ?: return
        val clip = project.clips.find { it.id == clipId } ?: return
        pushUndoState(project)
        val oldSpeed = clip.speed
        val newDuration = (clip.durationMs * (oldSpeed / speed)).toLong().coerceAtLeast(500L)
        val updated = clip.copy(speed = speed, durationMs = newDuration)
        val updatedClips = project.clips.map { if (it.id == clipId) updated else it }
        val updatedProject = project.copy(clips = updatedClips)
        _currentProject.value = updatedProject
        projectRepo.saveProject(updatedProject)
    }

    fun setClipVolume(clipId: String, volume: Float) {
        val project = _currentProject.value ?: return
        val clip = project.clips.find { it.id == clipId } ?: return
        val updated = clip.copy(
            volume = volume,
            audioProperties = clip.audioProperties.copy(volume = volume)
        )
        val updatedClips = project.clips.map { if (it.id == clipId) updated else it }
        val updatedProject = project.copy(clips = updatedClips)
        _currentProject.value = updatedProject
        projectRepo.saveProject(updatedProject)
    }

    fun updateClipTransform(clipId: String, transform: ClipTransform) {
        val project = _currentProject.value ?: return
        val clip = project.clips.find { it.id == clipId } ?: return
        val updated = clip.copy(transform = transform)
        val updatedClips = project.clips.map { if (it.id == clipId) updated else it }
        val updatedProject = project.copy(clips = updatedClips)
        _currentProject.value = updatedProject
        projectRepo.saveProject(updatedProject)
    }

    // --- Keyframe Management ---

    fun addOrUpdateKeyframe(clipId: String, param: KeyframeParam, value: Float, curve: InterpolationType = InterpolationType.EASE_IN_OUT) {
        val project = _currentProject.value ?: return
        val clip = project.clips.find { it.id == clipId } ?: return
        val currentTime = _playheadPositionMs.value - clip.startTimeMs
        if (currentTime < 0 || currentTime > clip.durationMs) return

        pushUndoState(project)
        val existingIndex = clip.keyframes.indexOfFirst { it.param == param && Math.abs(it.timeMs - currentTime) < 300L }
        val newKeyframes = clip.keyframes.toMutableList()
        val kf = Keyframe(
            timeMs = currentTime,
            param = param,
            value = value,
            curve = curve
        )
        if (existingIndex != -1) {
            newKeyframes[existingIndex] = kf
        } else {
            newKeyframes.add(kf)
            newKeyframes.sortBy { it.timeMs }
        }

        val updated = clip.copy(keyframes = newKeyframes)
        val updatedClips = project.clips.map { if (it.id == clipId) updated else it }
        val updatedProject = project.copy(clips = updatedClips)
        _currentProject.value = updatedProject
        projectRepo.saveProject(updatedProject)
    }

    fun removeKeyframe(clipId: String, keyframeId: String) {
        val project = _currentProject.value ?: return
        val clip = project.clips.find { it.id == clipId } ?: return
        pushUndoState(project)
        val updatedKeyframes = clip.keyframes.filterNot { it.id == keyframeId }
        val updated = clip.copy(keyframes = updatedKeyframes)
        val updatedClips = project.clips.map { if (it.id == clipId) updated else it }
        val updatedProject = project.copy(clips = updatedClips)
        _currentProject.value = updatedProject
        projectRepo.saveProject(updatedProject)
    }

    // Evaluates interpolated keyframe value at current playhead
    fun getInterpolatedValue(clip: MediaClip, param: KeyframeParam): Float {
        val paramKeyframes = clip.keyframes.filter { it.param == param }.sortedBy { it.timeMs }
        if (paramKeyframes.isEmpty()) {
            return when (param) {
                KeyframeParam.POSITION_X -> clip.transform.posX
                KeyframeParam.POSITION_Y -> clip.transform.posY
                KeyframeParam.SCALE -> clip.transform.scale
                KeyframeParam.ROTATION -> clip.transform.rotation
                KeyframeParam.OPACITY -> clip.transform.opacity
            }
        }

        val relativeTime = _playheadPositionMs.value - clip.startTimeMs
        if (relativeTime <= paramKeyframes.first().timeMs) {
            return paramKeyframes.first().value
        }
        if (relativeTime >= paramKeyframes.last().timeMs) {
            return paramKeyframes.last().value
        }

        // Find boundary keyframes
        for (i in 0 until paramKeyframes.size - 1) {
            val kf1 = paramKeyframes[i]
            val kf2 = paramKeyframes[i + 1]
            if (relativeTime in kf1.timeMs..kf2.timeMs) {
                val span = (kf2.timeMs - kf1.timeMs).toFloat().coerceAtLeast(1f)
                val t = (relativeTime - kf1.timeMs) / span
                val easedT = when (kf1.curve) {
                    InterpolationType.LINEAR -> t
                    InterpolationType.EASE_IN -> t * t
                    InterpolationType.EASE_OUT -> t * (2 - t)
                    InterpolationType.EASE_IN_OUT -> if (t < 0.5f) 2 * t * t else -1 + (4 - 2 * t) * t
                }
                return kf1.value + (kf2.value - kf1.value) * easedT
            }
        }

        return paramKeyframes.first().value
    }

    // --- Effects Management ---

    fun applyOrUpdateEffect(clipId: String, effect: ClipEffect) {
        val project = _currentProject.value ?: return
        val clip = project.clips.find { it.id == clipId } ?: return
        pushUndoState(project)

        val currentEffects = clip.effects.toMutableList()
        val index = currentEffects.indexOfFirst { it.type == effect.type }
        if (index != -1) {
            currentEffects[index] = effect
        } else {
            currentEffects.add(effect)
        }

        val updated = clip.copy(effects = currentEffects)
        val updatedClips = project.clips.map { if (it.id == clipId) updated else it }
        val updatedProject = project.copy(clips = updatedClips)
        _currentProject.value = updatedProject
        projectRepo.saveProject(updatedProject)
    }

    fun removeEffect(clipId: String, effectType: EffectType) {
        val project = _currentProject.value ?: return
        val clip = project.clips.find { it.id == clipId } ?: return
        pushUndoState(project)

        val updated = clip.copy(effects = clip.effects.filterNot { it.type == effectType })
        val updatedClips = project.clips.map { if (it.id == clipId) updated else it }
        val updatedProject = project.copy(clips = updatedClips)
        _currentProject.value = updatedProject
        projectRepo.saveProject(updatedProject)
    }

    fun toggleBeforeAfter() {
        _beforeAfterMode.value = !_beforeAfterMode.value
    }

    // --- Audio Studio Mixer Actions ---

    fun updateAudioProperties(clipId: String, props: AudioProperties) {
        val project = _currentProject.value ?: return
        val clip = project.clips.find { it.id == clipId } ?: return
        val updated = clip.copy(audioProperties = props, volume = props.volume)
        val updatedClips = project.clips.map { if (it.id == clipId) updated else it }
        val updatedProject = project.copy(clips = updatedClips)
        _currentProject.value = updatedProject
        projectRepo.saveProject(updatedProject)
    }

    fun setTrackFader(trackId: String, db: Float) {
        val project = _currentProject.value ?: return
        val updatedTracks = project.tracks.map {
            if (it.id == trackId) it.copy(faderDb = db) else it
        }
        val updatedProject = project.copy(tracks = updatedTracks)
        _currentProject.value = updatedProject
        projectRepo.saveProject(updatedProject)
    }

    fun toggleTrackMute(trackId: String) {
        val project = _currentProject.value ?: return
        val updatedTracks = project.tracks.map {
            if (it.id == trackId) it.copy(isMuted = !it.isMuted) else it
        }
        val updatedProject = project.copy(tracks = updatedTracks)
        _currentProject.value = updatedProject
        projectRepo.saveProject(updatedProject)
    }

    fun toggleTrackSolo(trackId: String) {
        val project = _currentProject.value ?: return
        val updatedTracks = project.tracks.map {
            if (it.id == trackId) it.copy(isSolo = !it.isSolo) else it
        }
        val updatedProject = project.copy(tracks = updatedTracks)
        _currentProject.value = updatedProject
        projectRepo.saveProject(updatedProject)
    }

    // --- Media Import ---

    fun addImportedMediaToTimeline(items: List<ImportedMediaItem>) {
        var project = _currentProject.value
        if (project == null) {
            // Auto create project if none exists
            project = Project(
                title = "My Project",
                aspectRatio = AspectRatio.RATIO_9_16,
                durationMs = 0L,
                clips = emptyList()
            )
            projectRepo.saveProject(project)
            _currentProject.value = project
        }

        pushUndoState(project)
        val updatedClips = project.clips.toMutableList()

        items.forEach { item ->
            val trackType = when (item.mediaType) {
                MediaType.VIDEO -> TrackType.VIDEO
                MediaType.AUDIO -> TrackType.AUDIO_MUSIC
                MediaType.PHOTO -> TrackType.VIDEO
            }
            val trackId = when (trackType) {
                TrackType.VIDEO -> "track_video_1"
                TrackType.AUDIO_MUSIC -> "track_audio_1"
                else -> "track_text_1"
            }

            // Find current end time of this track
            val trackClips = updatedClips.filter { it.trackId == trackId }
            val nextStart = if (trackClips.isNotEmpty()) {
                trackClips.maxOf { it.startTimeMs + it.durationMs }
            } else 0L

            val clip = MediaClip(
                id = UUID.randomUUID().toString(),
                name = item.name,
                filePath = item.filePath,
                uriString = item.uriString,
                thumbnailPath = item.thumbnailPath,
                mediaType = item.mediaType,
                trackId = trackId,
                trackType = trackType,
                startTimeMs = nextStart,
                durationMs = item.durationMs,
                sourceDurationMs = item.durationMs
            )
            updatedClips.add(clip)
        }

        val maxEnd = updatedClips.maxOfOrNull { it.startTimeMs + it.durationMs } ?: 0L
        val firstVisual = updatedClips.firstOrNull { it.trackType == TrackType.VIDEO }
        val thumbPath = firstVisual?.let { it.thumbnailPath.ifEmpty { it.filePath } } ?: project.thumbnailPath

        val updatedProject = project.copy(
            clips = updatedClips,
            durationMs = maxEnd,
            thumbnailPath = thumbPath
        )
        _currentProject.value = updatedProject
        _selectedClipId.value = updatedClips.lastOrNull()?.id
        projectRepo.saveProject(updatedProject)
        navigateTo(Screen.STUDIO)
    }

    fun addImportedClips(clips: List<MediaClip>) {
        val project = _currentProject.value ?: return
        pushUndoState(project)
        val updatedClips = project.clips.toMutableList()

        clips.forEach { newClip ->
            val trackClips = updatedClips.filter { it.trackId == newClip.trackId }
            val nextStart = if (trackClips.isNotEmpty()) {
                trackClips.maxOf { it.startTimeMs + it.durationMs }
            } else 0L

            updatedClips.add(newClip.copy(startTimeMs = nextStart))
        }

        val maxEnd = updatedClips.maxOfOrNull { it.startTimeMs + it.durationMs } ?: 0L
        val updatedProject = project.copy(
            clips = updatedClips,
            durationMs = maxEnd
        )
        _currentProject.value = updatedProject
        _selectedClipId.value = clips.lastOrNull()?.id
        projectRepo.saveProject(updatedProject)
        navigateTo(Screen.STUDIO)
    }

    // --- Export Video ---

    fun openExportDialog() {
        _exportResult.value = null
        _showExportDialog.value = true
    }

    fun closeExportDialog() {
        _showExportDialog.value = false
    }

    fun startExport(config: ExportConfig) {
        val project = _currentProject.value ?: return
        _isExporting.value = true
        _exportProgress.value = 0f
        _exportStage.value = "Hazırlanıyor..."
        _exportResult.value = null

        viewModelScope.launch {
            val result = exportEngine.exportVideo(project, config) { progress, stage ->
                _exportProgress.value = progress
                _exportStage.value = stage
            }
            _isExporting.value = false
            _exportResult.value = result
        }
    }

    // --- Settings & Recovery ---

    fun setLanguage(lang: AppLanguage) {
        _settings.value = _settings.value.copy(language = lang)
    }

    fun setPerformanceMode(mode: PerformanceMode) {
        _settings.value = _settings.value.copy(performanceMode = mode)
    }

    fun clearCache() {
        _settings.value = _settings.value.copy(cacheSizeMb = 0L)
    }

    fun restoreAutoSave() {
        val recovered = projectRepo.restoreAutoSave()
        if (recovered != null) {
            selectProject(recovered)
        }
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}
