package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class MediaManager(private val context: Context) {

    private val mediaDir = File(context.filesDir, "imported_media").apply { if (!exists()) mkdirs() }
    private val thumbDir = File(context.filesDir, "thumbnails").apply { if (!exists()) mkdirs() }
    private val libraryFile = File(context.filesDir, "vex_imported_media.json")

    private val _importedMedia = MutableStateFlow<List<ImportedMediaItem>>(emptyList())
    val importedMedia: StateFlow<List<ImportedMediaItem>> = _importedMedia.asStateFlow()

    init {
        loadLibrary()
    }

    private fun loadLibrary() {
        if (!libraryFile.exists()) {
            _importedMedia.value = emptyList()
            seedDefaultMediaIfEmpty()
            return
        }
        try {
            val jsonString = libraryFile.readText()
            val array = JSONArray(jsonString)
            val list = mutableListOf<ImportedMediaItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val item = ImportedMediaItem(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    filePath = obj.getString("filePath"),
                    uriString = obj.optString("uriString", ""),
                    thumbnailPath = obj.optString("thumbnailPath", ""),
                    mediaType = MediaType.valueOf(obj.getString("mediaType")),
                    durationMs = obj.optLong("durationMs", 5000L),
                    sizeBytes = obj.optLong("sizeBytes", 0L),
                    dateAdded = obj.optLong("dateAdded", System.currentTimeMillis()),
                    resolution = obj.optString("resolution", "")
                )
                // Only keep if file actually still exists on device
                if (File(item.filePath).exists()) {
                    list.add(item)
                }
            }
            _importedMedia.value = list
            if (list.isEmpty()) {
                seedDefaultMediaIfEmpty()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _importedMedia.value = emptyList()
        }
    }

    private fun seedDefaultMediaIfEmpty() {
        if (_importedMedia.value.isNotEmpty()) return
        try {
            val sampleResId = context.resources.getIdentifier("sample_car_night", "drawable", context.packageName)
            if (sampleResId != 0) {
                val sampleFile = File(mediaDir, "sample_night_highway.jpg")
                if (!sampleFile.exists()) {
                    val bmp = BitmapFactory.decodeResource(context.resources, sampleResId)
                    if (bmp != null) {
                        FileOutputStream(sampleFile).use { fos ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                        }
                    }
                }
                if (sampleFile.exists() && sampleFile.length() > 0) {
                    val item = ImportedMediaItem(
                        id = "sample_media_car",
                        name = "Night_Highway_Shot.jpg",
                        filePath = sampleFile.absolutePath,
                        uriString = "",
                        thumbnailPath = sampleFile.absolutePath,
                        mediaType = MediaType.PHOTO,
                        durationMs = 5000L,
                        sizeBytes = sampleFile.length(),
                        dateAdded = System.currentTimeMillis(),
                        resolution = "1920x1080"
                    )
                    val list = listOf(item)
                    _importedMedia.value = list
                    persistLibrary(list)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun persistLibrary(list: List<ImportedMediaItem>) {
        try {
            val array = JSONArray()
            list.forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("filePath", item.filePath)
                    put("uriString", item.uriString)
                    put("thumbnailPath", item.thumbnailPath)
                    put("mediaType", item.mediaType.name)
                    put("durationMs", item.durationMs)
                    put("sizeBytes", item.sizeBytes)
                    put("dateAdded", item.dateAdded)
                    put("resolution", item.resolution)
                }
                array.put(obj)
            }
            libraryFile.writeText(array.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun importMediaUris(uris: List<Uri>, forcedType: MediaType? = null): List<ImportedMediaItem> = withContext(Dispatchers.IO) {
        val newItems = mutableListOf<ImportedMediaItem>()

        for (uri in uris) {
            try {
                val rawName = queryDisplayName(uri) ?: "Media_${System.currentTimeMillis()}"
                val mimeType = context.contentResolver.getType(uri) ?: ""
                val type = forcedType ?: determineMediaType(rawName, mimeType)

                val ext = getExtension(rawName, type)
                val localFile = File(mediaDir, "media_${UUID.randomUUID()}.$ext")

                // Copy stream to app storage
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(localFile).use { output ->
                        input.copyTo(output)
                    }
                }

                if (!localFile.exists() || localFile.length() == 0L) continue

                // Verify actual file content using MediaMetadataRetriever
                var resolvedType = type
                val metaRetriever = MediaMetadataRetriever()
                var hasVideoTrack = false
                var hasAudioTrack = false
                try {
                    metaRetriever.setDataSource(localFile.absolutePath)
                    hasVideoTrack = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                    hasAudioTrack = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                    if (hasVideoTrack) {
                        resolvedType = MediaType.VIDEO
                    } else if (hasAudioTrack && forcedType == null && resolvedType != MediaType.PHOTO) {
                        resolvedType = MediaType.AUDIO
                    }
                } catch (_: Exception) {}

                // If detected as video but stored with non-video extension (e.g. .jpg or .bin), rename to .mp4
                // so Android's hardware decoder and MediaPlayer can open it without format rejection
                var finalFile = localFile
                if (resolvedType == MediaType.VIDEO && !localFile.name.lowercase().endsWith(".mp4") &&
                    !localFile.name.lowercase().endsWith(".mov") && !localFile.name.lowercase().endsWith(".mkv") &&
                    !localFile.name.lowercase().endsWith(".webm")
                ) {
                    val correctVideoFile = File(mediaDir, "${localFile.nameWithoutExtension}.mp4")
                    if (localFile.renameTo(correctVideoFile)) {
                        finalFile = correctVideoFile
                        try {
                            metaRetriever.setDataSource(finalFile.absolutePath)
                        } catch (_: Exception) {}
                    }
                }

                var durationMs = 5000L
                var resolution = ""
                var thumbPath = ""

                if (resolvedType == MediaType.VIDEO) {
                    try {
                        val durStr = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val parsedDur = durStr?.toLongOrNull() ?: 0L
                        durationMs = if (parsedDur > 0L) parsedDur else 5000L
                        val w = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "1080"
                        val h = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "1920"
                        resolution = "${w}x${h}"

                        val frameBmp = metaRetriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: metaRetriever.getFrameAtTime(minOf(1_000_000L, durationMs * 500L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: metaRetriever.frameAtTime
                        if (frameBmp != null) {
                            val thumbFile = File(thumbDir, "thumb_${finalFile.nameWithoutExtension}.jpg")
                            FileOutputStream(thumbFile).use { fos ->
                                frameBmp.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                            }
                            thumbPath = thumbFile.absolutePath
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        try { metaRetriever.release() } catch (ignored: Exception) {}
                    }
                } else if (type == MediaType.PHOTO) {
                    durationMs = 4000L // Default duration for photo clips in timeline
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(localFile.absolutePath, opts)
                    resolution = "${opts.outWidth}x${opts.outHeight}"

                    // Generate compact thumbnail
                    val thumbOpts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    val photoBmp = BitmapFactory.decodeFile(localFile.absolutePath, thumbOpts)
                    if (photoBmp != null) {
                        val thumbFile = File(thumbDir, "thumb_${localFile.nameWithoutExtension}.jpg")
                        FileOutputStream(thumbFile).use { fos ->
                            photoBmp.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                        }
                        thumbPath = thumbFile.absolutePath
                    }
                } else if (type == MediaType.AUDIO) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(localFile.absolutePath)
                        val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        durationMs = durStr?.toLongOrNull() ?: 15000L
                        resolution = "Audio"
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        try { retriever.release() } catch (ignored: Exception) {}
                    }
                }

                val item = ImportedMediaItem(
                    name = rawName,
                    filePath = finalFile.absolutePath,
                    uriString = uri.toString(),
                    thumbnailPath = thumbPath.ifEmpty { finalFile.absolutePath },
                    mediaType = resolvedType,
                    durationMs = durationMs,
                    sizeBytes = finalFile.length(),
                    resolution = resolution
                )
                newItems.add(item)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val updated = newItems + _importedMedia.value
        _importedMedia.value = updated
        persistLibrary(updated)

        return@withContext newItems
    }

    fun deleteMediaItem(itemId: String) {
        val current = _importedMedia.value.toMutableList()
        val item = current.find { it.id == itemId }
        if (item != null) {
            try { File(item.filePath).delete() } catch (e: Exception) {}
            try { if (item.thumbnailPath.isNotEmpty()) File(item.thumbnailPath).delete() } catch (e: Exception) {}
            current.remove(item)
            _importedMedia.value = current
            persistLibrary(current)
        }
    }

    fun queryFileDurationMs(filePath: String, uriString: String = ""): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            if (filePath.isNotEmpty() && File(filePath).exists()) {
                retriever.setDataSource(filePath)
            } else if (uriString.isNotEmpty()) {
                retriever.setDataSource(context, Uri.parse(uriString))
            } else {
                return 0L
            }
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            dur?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            return cursor.getString(nameIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return uri.lastPathSegment
    }

    private fun determineMediaType(name: String, mime: String): MediaType {
        val lowerMime = mime.lowercase()
        val lowerName = name.lowercase()
        return when {
            lowerMime.startsWith("video") || lowerName.endsWith(".mp4") || lowerName.endsWith(".mov") || lowerName.endsWith(".mkv") || lowerName.endsWith(".webm") -> MediaType.VIDEO
            lowerMime.startsWith("audio") || lowerName.endsWith(".mp3") || lowerName.endsWith(".wav") || lowerName.endsWith(".m4a") || lowerName.endsWith(".aac") || lowerName.endsWith(".ogg") -> MediaType.AUDIO
            else -> MediaType.PHOTO
        }
    }

    private fun getExtension(name: String, type: MediaType): String {
        val dot = name.lastIndexOf('.')
        if (dot != -1 && dot < name.length - 1) {
            return name.substring(dot + 1)
        }
        return when (type) {
            MediaType.VIDEO -> "mp4"
            MediaType.PHOTO -> "jpg"
            MediaType.AUDIO -> "mp3"
        }
    }
}
