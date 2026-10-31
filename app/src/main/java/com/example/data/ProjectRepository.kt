package com.example.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ProjectRepository(private val context: Context) {

    private val projectsFile = File(context.filesDir, "vex_projects.json")
    private val autoSaveFile = File(context.filesDir, "vex_autosave.json")
    private val database = Room.databaseBuilder(context.applicationContext, VexProjectDatabase::class.java, "vex_projects.db").build()
    private val projectDao = database.projectDao()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceMutex = Mutex()

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    init {
        loadProjects()
    }

    private fun loadProjects() {
        ioScope.launch {
            persistenceMutex.withLock {
                try {
                    val roomProjects = projectDao.getAll()
                    val loaded = if (roomProjects.isNotEmpty()) {
                        roomProjects.mapNotNull { row ->
                            try { deserializeProject(JSONObject(row.payload)) } catch (_: Exception) { null }
                        }
                    } else if (projectsFile.exists()) {
                        // One-time migration from the previous JSON-only project store.
                        deserializeProjects(projectsFile.readText())
                    } else emptyList()

                    // Preserve projects created while the asynchronous initial load was running.
                    val inMemory = _projects.value
                    val inMemoryIds = inMemory.map { it.id }.toSet()
                    val merged = (loaded.filterNot { it.id in inMemoryIds } + inMemory)
                        .sortedByDescending { it.lastEditedTimestamp }
                    val cleanList = merged.filterNot {
                        it.id in listOf("proj_midnight", "proj_drill", "proj_summer", "proj_cinematic")
                    }
                    _projects.value = cleanList
                    if (roomProjects.isEmpty() || cleanList != loaded) {
                        writePersistedProjects(cleanList)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    _projects.value = try {
                        if (projectsFile.exists()) deserializeProjects(projectsFile.readText()) else emptyList()
                    } catch (_: Exception) { emptyList() }
                }
            }
        }
    }

    private fun persistProjects(list: List<Project>) {
        ioScope.launch {
            persistenceMutex.withLock {
                writePersistedProjects(list)
            }
        }
    }

    private fun writePersistedProjects(list: List<Project>) {
        try {
            val rows = list.map { project ->
                ProjectEntity(
                    id = project.id,
                    title = project.title,
                    payload = serializeProject(project).toString(),
                    updatedAt = project.lastEditedTimestamp
                )
            }
            database.runInTransaction {
                projectDao.deleteAll()
                if (rows.isNotEmpty()) projectDao.upsertAll(rows)
            }
            // Keep a recovery snapshot while Room remains the primary store.
            projectsFile.writeText(serializeProjects(list))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveProject(project: Project) {
        val current = _projects.value.toMutableList()
        val index = current.indexOfFirst { it.id == project.id }

        // Automatically update project duration and thumbnail based on clips
        val maxDuration = project.clips.maxOfOrNull { it.startTimeMs + it.durationMs } ?: 0L
        val firstVisualClip = project.clips.firstOrNull { it.trackType == TrackType.VIDEO || it.trackType == TrackType.TEXT }
        val thumbPath = firstVisualClip?.let { it.thumbnailPath.ifEmpty { it.filePath } } ?: project.thumbnailPath

        val updated = project.copy(
            durationMs = maxDuration,
            thumbnailPath = thumbPath,
            lastEditedTimestamp = System.currentTimeMillis()
        )

        if (index != -1) {
            current[index] = updated
        } else {
            current.add(0, updated)
        }
        _projects.value = current
        persistProjects(current)

        // Persist auto-save off the main thread with the same ordered I/O queue.
        ioScope.launch {
            persistenceMutex.withLock {
                try { autoSaveFile.writeText(serializeProject(updated).toString()) }
                catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    fun deleteProject(projectId: String) {
        val current = _projects.value.filterNot { it.id == projectId }
        _projects.value = current
        persistProjects(current)
    }

    fun duplicateProject(projectId: String): Project? {
        val original = _projects.value.find { it.id == projectId } ?: return null
        val copy = original.copy(
            id = UUID.randomUUID().toString(),
            title = "${original.title} (Copy)",
            lastEditedTimestamp = System.currentTimeMillis(),
            isFavorite = false
        )
        val current = _projects.value.toMutableList()
        current.add(0, copy)
        _projects.value = current
        persistProjects(current)
        return copy
    }

    fun renameProject(projectId: String, newName: String) {
        val current = _projects.value.toMutableList()
        val index = current.indexOfFirst { it.id == projectId }
        if (index != -1) {
            current[index] = current[index].copy(
                title = newName.trim(),
                lastEditedTimestamp = System.currentTimeMillis()
            )
            _projects.value = current
            persistProjects(current)
        }
    }

    fun toggleFavorite(projectId: String) {
        val current = _projects.value.toMutableList()
        val index = current.indexOfFirst { it.id == projectId }
        if (index != -1) {
            current[index] = current[index].copy(
                isFavorite = !current[index].isFavorite,
                lastEditedTimestamp = System.currentTimeMillis()
            )
            _projects.value = current
            persistProjects(current)
        }
    }

    fun restoreAutoSave(): Project? {
        if (!autoSaveFile.exists()) return null
        return try {
            val json = JSONObject(autoSaveFile.readText())
            deserializeProject(json)
        } catch (e: Exception) {
            null
        }
    }

    fun getProject(id: String): Project? {
        return _projects.value.find { it.id == id }
    }

    private fun serializeProjects(list: List<Project>): String {
        val array = JSONArray()
        list.forEach { project ->
            array.put(serializeProject(project))
        }
        return array.toString()
    }

    private fun serializeProject(project: Project): JSONObject {
        val obj = JSONObject()
        obj.put("id", project.id)
        obj.put("title", project.title)
        obj.put("aspectRatio", project.aspectRatio.name)
        obj.put("durationMs", project.durationMs)
        obj.put("lastEditedTimestamp", project.lastEditedTimestamp)
        obj.put("isFavorite", project.isFavorite)
        obj.put("thumbnailUri", project.thumbnailUri ?: "")
        obj.put("thumbnailPath", project.thumbnailPath ?: "")

        val tracksArray = JSONArray()
        project.tracks.forEach { track ->
            tracksArray.put(JSONObject().apply {
                put("id", track.id)
                put("title", track.title)
                put("subtitle", track.subtitle)
                put("type", track.type.name)
                put("isMuted", track.isMuted)
                put("isVisible", track.isVisible)
                put("isSolo", track.isSolo)
                put("faderDb", track.faderDb.toDouble())
            })
        }
        obj.put("tracks", tracksArray)

        val clipsArray = JSONArray()
        project.clips.forEach { clip ->
            val c = JSONObject()
            c.put("id", clip.id)
            c.put("name", clip.name)
            c.put("uriString", clip.uriString)
            c.put("filePath", clip.filePath)
            c.put("thumbnailPath", clip.thumbnailPath)
            c.put("mediaType", clip.mediaType.name)
            c.put("trackId", clip.trackId)
            c.put("trackType", clip.trackType.name)
            c.put("startTimeMs", clip.startTimeMs)
            c.put("sourceStartMs", clip.sourceStartMs)
            c.put("durationMs", clip.durationMs)
            c.put("sourceDurationMs", clip.sourceDurationMs)
            c.put("speed", clip.speed.toDouble())
            c.put("volume", clip.volume.toDouble())

            val tr = JSONObject()
            tr.put("posX", clip.transform.posX.toDouble())
            tr.put("posY", clip.transform.posY.toDouble())
            tr.put("scale", clip.transform.scale.toDouble())
            tr.put("rotation", clip.transform.rotation.toDouble())
            tr.put("opacity", clip.transform.opacity.toDouble())
            c.put("transform", tr)

            val effArray = JSONArray()
            clip.effects.forEach { eff ->
                val eo = JSONObject()
                eo.put("type", eff.type.name)
                eo.put("intensity", eff.intensity.toDouble())
                eo.put("radius", eff.radius.toDouble())
                eo.put("opacity", eff.opacity.toDouble())
                eo.put("blend", eff.blend.toDouble())
                effArray.put(eo)
            }
            c.put("effects", effArray)

            val kfArray = JSONArray()
            clip.keyframes.forEach { kf ->
                val ko = JSONObject()
                ko.put("timeMs", kf.timeMs)
                ko.put("param", kf.param.name)
                ko.put("value", kf.value.toDouble())
                ko.put("curve", kf.curve.name)
                kfArray.put(ko)
            }
            c.put("keyframes", kfArray)

            // Audio Properties
            val ap = JSONObject()
            ap.put("volume", clip.audioProperties.volume.toDouble())
            ap.put("isMuted", clip.audioProperties.isMuted)
            ap.put("isSolo", clip.audioProperties.isSolo)
            ap.put("fadeInMs", clip.audioProperties.fadeInMs)
            ap.put("fadeOutMs", clip.audioProperties.fadeOutMs)
            ap.put("faderDb", clip.audioProperties.faderDb.toDouble())
            ap.put("eqPreset", clip.audioProperties.eqPreset)
            ap.put("noiseGateEnabled", clip.audioProperties.noiseGateEnabled)
            ap.put("compressorEnabled", clip.audioProperties.compressorEnabled)
            ap.put("reverbLevel", clip.audioProperties.reverbLevel.toDouble())
            ap.put("delayLevel", clip.audioProperties.delayLevel.toDouble())
            ap.put("limiterEnabled", clip.audioProperties.limiterEnabled)
            c.put("audioProperties", ap)

            // Waveform points
            val wfArray = JSONArray()
            clip.waveformPoints.forEach { wfArray.put(it.toDouble()) }
            c.put("waveformPoints", wfArray)

            clipsArray.put(c)
        }
        obj.put("clips", clipsArray)
        return obj
    }

    private fun deserializeProjects(jsonString: String): List<Project> {
        val result = mutableListOf<Project>()
        val array = JSONArray(jsonString)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result.add(deserializeProject(obj))
        }
        return result
    }

    private fun deserializeProject(obj: JSONObject): Project {
        val id = obj.optString("id", UUID.randomUUID().toString())
        val title = obj.optString("title", "Untitled Project")
        val aspectName = obj.optString("aspectRatio", AspectRatio.RATIO_9_16.name)
        val aspect = try { AspectRatio.valueOf(aspectName) } catch (e: Exception) { AspectRatio.RATIO_9_16 }
        val durationMs = obj.optLong("durationMs", 0L)
        val lastEdited = obj.optLong("lastEditedTimestamp", System.currentTimeMillis())
        val isFavorite = obj.optBoolean("isFavorite", false)
        val thumbUri = obj.optString("thumbnailUri").takeIf { it.isNotEmpty() }
        val thumbPath = obj.optString("thumbnailPath").takeIf { it.isNotEmpty() }

        val tracks = mutableListOf<Track>()
        val tracksArray = obj.optJSONArray("tracks")
        if (tracksArray != null) {
            for (index in 0 until tracksArray.length()) {
                val item = tracksArray.optJSONObject(index) ?: continue
                val type = try { TrackType.valueOf(item.optString("type")) } catch (_: Exception) { continue }
                tracks.add(
                    Track(
                        id = item.optString("id", "track_${index}"),
                        title = item.optString("title", "Track"),
                        subtitle = item.optString("subtitle", ""),
                        type = type,
                        isMuted = item.optBoolean("isMuted", false),
                        isVisible = item.optBoolean("isVisible", true),
                        isSolo = item.optBoolean("isSolo", false),
                        faderDb = item.optDouble("faderDb", 0.0).toFloat()
                    )
                )
            }
        }
        val projectTracks = tracks.ifEmpty { Project.defaultTracks() }

        val clips = mutableListOf<MediaClip>()
        val clipsArray = obj.optJSONArray("clips") ?: JSONArray()
        for (i in 0 until clipsArray.length()) {
            val c = clipsArray.getJSONObject(i)
            val mediaType = try { MediaType.valueOf(c.optString("mediaType")) } catch (e: Exception) { MediaType.VIDEO }
            val trackType = try { TrackType.valueOf(c.optString("trackType")) } catch (e: Exception) { TrackType.VIDEO }

            val trObj = c.optJSONObject("transform")
            val transform = if (trObj != null) {
                ClipTransform(
                    posX = trObj.optDouble("posX", 0.0).toFloat(),
                    posY = trObj.optDouble("posY", 0.0).toFloat(),
                    scale = trObj.optDouble("scale", 100.0).toFloat(),
                    rotation = trObj.optDouble("rotation", 0.0).toFloat(),
                    opacity = trObj.optDouble("opacity", 100.0).toFloat()
                )
            } else ClipTransform()

            val effectsList = mutableListOf<ClipEffect>()
            val effArray = c.optJSONArray("effects")
            if (effArray != null) {
                for (j in 0 until effArray.length()) {
                    val eo = effArray.getJSONObject(j)
                    val effType = try { EffectType.valueOf(eo.getString("type")) } catch (e: Exception) { EffectType.VIOLET_GLOW }
                    effectsList.add(
                        ClipEffect(
                            type = effType,
                            intensity = eo.optDouble("intensity", 1.0).toFloat(),
                            radius = eo.optDouble("radius", 0.0).toFloat(),
                            opacity = eo.optDouble("opacity", 1.0).toFloat(),
                            blend = eo.optDouble("blend", 0.0).toFloat()
                        )
                    )
                }
            }

            val keyframesList = mutableListOf<Keyframe>()
            val kfArray = c.optJSONArray("keyframes")
            if (kfArray != null) {
                for (k in 0 until kfArray.length()) {
                    val ko = kfArray.getJSONObject(k)
                    val param = try { KeyframeParam.valueOf(ko.getString("param")) } catch (e: Exception) { KeyframeParam.POSITION_X }
                    val curve = try { InterpolationType.valueOf(ko.optString("curve")) } catch (e: Exception) { InterpolationType.EASE_IN_OUT }
                    keyframesList.add(
                        Keyframe(
                            timeMs = ko.optLong("timeMs", 0L),
                            param = param,
                            value = ko.optDouble("value", 0.0).toFloat(),
                            curve = curve
                        )
                    )
                }
            }

            val apObj = c.optJSONObject("audioProperties")
            val audioProps = if (apObj != null) {
                AudioProperties(
                    volume = apObj.optDouble("volume", 1.0).toFloat(),
                    isMuted = apObj.optBoolean("isMuted", false),
                    isSolo = apObj.optBoolean("isSolo", false),
                    fadeInMs = apObj.optLong("fadeInMs", 0L),
                    fadeOutMs = apObj.optLong("fadeOutMs", 0L),
                    faderDb = apObj.optDouble("faderDb", 0.0).toFloat(),
                    eqPreset = apObj.optString("eqPreset", "Flat"),
                    noiseGateEnabled = apObj.optBoolean("noiseGateEnabled", false),
                    compressorEnabled = apObj.optBoolean("compressorEnabled", false),
                    reverbLevel = apObj.optDouble("reverbLevel", 0.0).toFloat(),
                    delayLevel = apObj.optDouble("delayLevel", 0.0).toFloat(),
                    limiterEnabled = apObj.optBoolean("limiterEnabled", false)
                )
            } else AudioProperties()

            val wfPoints = mutableListOf<Float>()
            val wfArray = c.optJSONArray("waveformPoints")
            if (wfArray != null) {
                for (w in 0 until wfArray.length()) {
                    wfPoints.add(wfArray.getDouble(w).toFloat())
                }
            }

            clips.add(
                MediaClip(
                    id = c.optString("id", UUID.randomUUID().toString()),
                    name = c.optString("name", "Clip"),
                    uriString = c.optString("uriString", ""),
                    filePath = c.optString("filePath", ""),
                    thumbnailPath = c.optString("thumbnailPath", ""),
                    resourceId = null,
                    mediaType = mediaType,
                    trackId = c.optString("trackId", "track_video_1"),
                    trackType = trackType,
                    startTimeMs = c.optLong("startTimeMs", 0L),
                    sourceStartMs = c.optLong("sourceStartMs", 0L),
                    durationMs = c.optLong("durationMs", 5000L),
                    sourceDurationMs = c.optLong("sourceDurationMs", if (mediaType == MediaType.VIDEO) c.optLong("durationMs", 5000L) else 0L),
                    speed = c.optDouble("speed", 1.0).toFloat(),
                    volume = c.optDouble("volume", 1.0).toFloat(),
                    audioProperties = audioProps,
                    transform = transform,
                    effects = effectsList,
                    keyframes = keyframesList,
                    waveformPoints = wfPoints
                )
            )
        }

        return Project(
            id = id,
            title = title,
            aspectRatio = aspect,
            durationMs = durationMs,
            lastEditedTimestamp = lastEdited,
            isFavorite = isFavorite,
            thumbnailUri = thumbUri,
            thumbnailPath = thumbPath,
            tracks = projectTracks,
            clips = clips
        )
    }
}
