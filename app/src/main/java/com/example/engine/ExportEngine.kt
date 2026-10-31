package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.roundToInt

data class ExportConfig(
    val resolution: String = "1080p", // "480p", "720p", "1080p", "2K", "4K"
    val aspectRatio: AspectRatio = AspectRatio.RATIO_9_16,
    val bitrateBps: Int = 10_000_000,
    val fps: Int = 30, // 15, 24, 25, 30, 60, 120
    val includeAudio: Boolean = true
)

data class ExportResult(
    val success: Boolean,
    val outputFile: File?,
    val savedPathDisplay: String = "",
    val fileSizeFormatted: String = "",
    val totalFrames: Int = 0,
    val durationFormatted: String = "",
    val fpsUsed: Int = 30,
    val resolutionUsed: String = "1080p",
    val errorMessage: String? = null
)

private class MuxerTrackState {
    var trackIndex: Int = -1
    var isStarted: Boolean = false
    var samplesWritten: Int = 0
}

class ExportEngine(private val context: Context) {

    companion object {
        private const val TAG = "VexExportEngine"
    }

    suspend fun exportVideo(
        project: Project,
        config: ExportConfig,
        onProgress: (progress: Float, stage: String) -> Unit
    ): ExportResult = withContext(Dispatchers.Default) {
        if (project.clips.isEmpty()) {
            return@withContext ExportResult(
                success = false,
                outputFile = null,
                fileSizeFormatted = "0 MB",
                errorMessage = "Projeye henüz medya eklenmedi. Lütfen timeline'a en az bir fotoğraf veya video ekleyin."
            )
        }

        onProgress(0.02f, "Hazırlanıyor...")

        // Compute actual dimensions aligned to 16 for hardware codec compatibility
        val (width, height) = getDimensions(config.resolution, project.aspectRatio)
        val fps = config.fps.coerceIn(15, 120)
        val totalDurationMs = project.clips.maxOfOrNull { it.startTimeMs + it.durationMs }?.coerceAtLeast(500L) ?: 1000L
        val totalFrames = ((totalDurationMs / 1000.0) * fps).roundToInt().coerceAtLeast(1)
        val effectiveBitrate = calculateBitrate(config.resolution, fps)

        // Temporary file in private app cache
        val tempDir = File(context.cacheDir, "export_tmp").apply { if (!exists()) mkdirs() }
        val tempFile = File(tempDir, "temp_render_${System.currentTimeMillis()}.mp4")

        try {
            var encodedSuccess = false

            // Step 1: Check if hardware surface encoding is supported on this device
            if (canUseSurfaceEncoder(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                try {
                    encodedSuccess = encodeVideoWithSurface(
                        outputFile = tempFile,
                        width = width,
                        height = height,
                        fps = fps,
                        bitrate = effectiveBitrate,
                        totalFrames = totalFrames,
                        project = project,
                        onProgress = onProgress
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Surface-based encoding failed: ${e.message}. Falling back to byte buffer encoder.")
                    encodedSuccess = false
                }
            }

            // Step 2: Use ByteBuffer encoder (robust across both emulators and all real devices)
            if (!encodedSuccess || !tempFile.exists() || tempFile.length() == 0L) {
                try { tempFile.delete() } catch (ignored: Exception) {}
                encodedSuccess = encodeVideoWithByteBuffer(
                    outputFile = tempFile,
                    width = width,
                    height = height,
                    fps = fps,
                    bitrate = effectiveBitrate,
                    totalFrames = totalFrames,
                    project = project,
                    onProgress = onProgress
                )
            }

            if (!encodedSuccess || !tempFile.exists() || tempFile.length() == 0L) {
                try { tempFile.delete() } catch (ignored: Exception) {}
                return@withContext ExportResult(
                    success = false,
                    outputFile = null,
                    fileSizeFormatted = "0 MB",
                    errorMessage = "Video kodlama işlemi cihaz donanımı tarafından tamamlanamadı. Lütfen daha düşük bir çözünürlük veya FPS deneyin."
                )
            }

            var fileToSave = tempFile
            var muxedFile: File? = null
            if (config.includeAudio) {
                onProgress(0.92f, "Ses izi işleniyor ve birleştiriliyor...")
                try {
                    muxedFile = muxAudioIntoVideo(
                        context = context,
                        videoOnlyFile = tempFile,
                        project = project,
                        totalDurationMs = totalDurationMs
                    )
                    if (muxedFile != null && muxedFile.exists() && muxedFile.length() > 0L) {
                        fileToSave = muxedFile
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Audio muxing fallback warning: ${e.message}")
                }
            }

            onProgress(0.96f, "Galeriye kaydediliyor...")

            // Step 3: Save to Device MediaStore (Movies/VexEdit Studio) and index into Gallery
            val (savedFile, displayPath) = saveVideoToGallery(
                context = context,
                tempVideoFile = fileToSave,
                projectTitle = project.title
            )

            // Cleanup temp files
            try { tempFile.delete() } catch (ignored: Exception) {}
            try { muxedFile?.delete() } catch (ignored: Exception) {}

            if (savedFile.exists() && savedFile.length() > 0L) {
                val sizeMb = String.format(java.util.Locale.US, "%.1f MB", savedFile.length() / (1024f * 1024f))
                val durationSec = totalDurationMs / 1000f
                val durFormatted = String.format(java.util.Locale.US, "%.1f sn", durationSec)

                onProgress(1.0f, "Tamamlandı")

                ExportResult(
                    success = true,
                    outputFile = savedFile,
                    savedPathDisplay = displayPath,
                    fileSizeFormatted = sizeMb,
                    totalFrames = totalFrames,
                    durationFormatted = durFormatted,
                    fpsUsed = fps,
                    resolutionUsed = config.resolution
                )
            } else {
                ExportResult(
                    success = false,
                    outputFile = null,
                    fileSizeFormatted = "0 MB",
                    errorMessage = "Video dosyası galeriye kaydedilirken depolama hatası oluştu."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export exception", e)
            try { tempFile.delete() } catch (ignored: Exception) {}
            ExportResult(
                success = false,
                outputFile = null,
                fileSizeFormatted = "0 MB",
                errorMessage = e.localizedMessage ?: "Bilinmeyen bir dışa aktarma hatası oluştu"
            )
        }
    }

    private fun canUseSurfaceEncoder(mimeType: String): Boolean {
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.equals(mimeType, ignoreCase = true)) {
                        val name = info.name.lowercase()
                        // Software-only codecs (like c2.android or OMX.google) lack hardware surface component interfaces
                        if (name.startsWith("c2.android.") || name.startsWith("omx.google.")) {
                            continue
                        }
                        val caps = info.getCapabilitiesForType(type)
                        if (caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return false
        }
        return false
    }

    private fun getDimensions(resolution: String, aspectRatio: AspectRatio): Pair<Int, Int> {
        val shortSide = when (resolution) {
            "480p" -> 480
            "720p" -> 720
            "1080p" -> 1080
            "2K" -> 1440
            "4K" -> 2160
            else -> 720
        }

        val rawPair = when (aspectRatio) {
            AspectRatio.RATIO_9_16 -> Pair(shortSide, (shortSide * (16f / 9f)).toInt())
            AspectRatio.RATIO_16_9 -> Pair((shortSide * (16f / 9f)).toInt(), shortSide)
            AspectRatio.RATIO_1_1 -> Pair(shortSide, shortSide)
            AspectRatio.RATIO_4_5 -> Pair(shortSide, (shortSide * (5f / 4f)).toInt())
            AspectRatio.RATIO_21_9 -> Pair((shortSide * (21f / 9f)).toInt(), shortSide)
        }

        // Align dimensions to 16 for H.264 macroblock requirements
        val alignedW = ((rawPair.first + 15) / 16) * 16
        val alignedH = ((rawPair.second + 15) / 16) * 16
        return Pair(alignedW, alignedH)
    }

    private fun calculateBitrate(resolution: String, fps: Int): Int {
        val base = when (resolution) {
            "480p" -> 2_500_000
            "720p" -> 5_000_000
            "1080p" -> 10_000_000
            "2K" -> 18_000_000
            "4K" -> 35_000_000
            else -> 6_000_000
        }
        return if (fps >= 60) (base * 1.5).toInt() else base
    }

    // --- PIPELINE 1: HARDWARE SURFACE ENCODING (EGL14 + GLES20) ---

    private fun encodeVideoWithSurface(
        outputFile: File,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        totalFrames: Int,
        project: Project,
        onProgress: (Float, String) -> Unit
    ): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var eglHelper: EglSurfaceHelper? = null
        val muxerState = MuxerTrackState()

        val photoBitmapCache = mutableMapOf<String, Bitmap>()
        val videoRetrievers = mutableMapOf<String, MediaMetadataRetriever>()
        val lastVideoFrameCache = mutableMapOf<String, Bitmap>()
        val reusableFrameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val renderCanvas = Canvas(reusableFrameBitmap)
        var lastProgressTime = 0L

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()
            codec.start()

            eglHelper = EglSurfaceHelper(inputSurface, width, height)
            eglHelper.makeCurrent()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()

            for (frame in 0 until totalFrames) {
                val currentMs = (frame * 1000L) / fps
                val ptsNs = (frame * 1_000_000_000L) / fps

                renderFrameAtTime(
                    canvas = renderCanvas,
                    width = width,
                    height = height,
                    currentMs = currentMs,
                    project = project,
                    photoCache = photoBitmapCache,
                    videoRetrievers = videoRetrievers,
                    lastVideoFrames = lastVideoFrameCache
                )

                eglHelper.drawBitmap(reusableFrameBitmap)
                eglHelper.setPresentationTime(ptsNs)
                eglHelper.swapBuffers()

                drainEncoder(codec, muxer, bufferInfo, false, muxerState)

                val now = System.currentTimeMillis()
                if (now - lastProgressTime >= 80L || frame == totalFrames - 1) {
                    lastProgressTime = now
                    val progress = 0.05f + (0.85f * (frame + 1) / totalFrames)
                    onProgress(progress, "Kareler işleniyor: ${frame + 1} / $totalFrames")
                }
            }

            codec.signalEndOfInputStream()
            drainEncoder(codec, muxer, bufferInfo, true, muxerState)

            return muxerState.isStarted && muxerState.samplesWritten > 0 && outputFile.exists() && outputFile.length() > 0L
        } finally {
            try { eglHelper?.release() } catch (ignored: Exception) {}
            try { codec?.stop(); codec?.release() } catch (ignored: Exception) {}
            if (muxer != null) {
                try {
                    if (muxerState.isStarted && muxerState.samplesWritten > 0) {
                        muxer.stop()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Muxer stop warning: ${e.message}")
                } finally {
                    try { muxer.release() } catch (ignored: Exception) {}
                }
            }
            try { reusableFrameBitmap.recycle() } catch (ignored: Exception) {}
            photoBitmapCache.values.forEach { try { it.recycle() } catch (ignored: Exception) {} }
            videoRetrievers.values.forEach { try { it.release() } catch (ignored: Exception) {} }
            lastVideoFrameCache.values.forEach { try { it.recycle() } catch (ignored: Exception) {} }
        }
    }

    // --- PIPELINE 2: BYTEBUFFER ENCODING (NV12 / YUV420 Fallback) ---

    private fun encodeVideoWithByteBuffer(
        outputFile: File,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        totalFrames: Int,
        project: Project,
        onProgress: (Float, String) -> Unit
    ): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        val muxerState = MuxerTrackState()

        val photoBitmapCache = mutableMapOf<String, Bitmap>()
        val videoRetrievers = mutableMapOf<String, MediaMetadataRetriever>()
        val lastVideoFrameCache = mutableMapOf<String, Bitmap>()
        val reusableFrameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val renderCanvas = Canvas(reusableFrameBitmap)
        val argbPixels = IntArray(width * height)
        val yuvBuffer = ByteArray(width * height * 3 / 2)
        var lastProgressTime = 0L

        try {
            val (colorFormat, isSemiPlanar) = selectColorFormat(MediaFormat.MIMETYPE_VIDEO_AVC)

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()

            for (frame in 0 until totalFrames) {
                val currentMs = (frame * 1000L) / fps
                val ptsUs = (frame * 1_000_000L) / fps

                renderFrameAtTime(
                    canvas = renderCanvas,
                    width = width,
                    height = height,
                    currentMs = currentMs,
                    project = project,
                    photoCache = photoBitmapCache,
                    videoRetrievers = videoRetrievers,
                    lastVideoFrames = lastVideoFrameCache
                )

                reusableFrameBitmap.getPixels(argbPixels, 0, width, 0, 0, width, height)
                encodeYUV420(yuvBuffer, argbPixels, width, height, isSemiPlanar)

                var inputIndex = -1
                var tries = 0
                while (inputIndex < 0 && tries < 30) {
                    inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex < 0) {
                        drainEncoder(codec, muxer, bufferInfo, false, muxerState)
                        tries++
                    }
                }

                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(yuvBuffer)
                        codec.queueInputBuffer(inputIndex, 0, yuvBuffer.size, ptsUs, 0)
                    }
                }

                drainEncoder(codec, muxer, bufferInfo, false, muxerState)

                val now = System.currentTimeMillis()
                if (now - lastProgressTime >= 80L || frame == totalFrames - 1) {
                    lastProgressTime = now
                    val progress = 0.05f + (0.85f * (frame + 1) / totalFrames)
                    onProgress(progress, "Kareler işleniyor: ${frame + 1} / $totalFrames")
                }
            }

            // Signal EOS
            var eosQueued = false
            var eosTries = 0
            while (!eosQueued && eosTries < 30) {
                val eosIndex = codec.dequeueInputBuffer(10_000)
                if (eosIndex >= 0) {
                    val finalPtsUs = (totalFrames * 1_000_000L) / fps
                    codec.queueInputBuffer(eosIndex, 0, 0, finalPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                } else {
                    drainEncoder(codec, muxer, bufferInfo, false, muxerState)
                    eosTries++
                }
            }

            drainEncoder(codec, muxer, bufferInfo, true, muxerState)

            return muxerState.isStarted && muxerState.samplesWritten > 0 && outputFile.exists() && outputFile.length() > 0L
        } finally {
            try { codec?.stop(); codec?.release() } catch (ignored: Exception) {}
            if (muxer != null) {
                try {
                    if (muxerState.isStarted && muxerState.samplesWritten > 0) {
                        muxer.stop()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Muxer stop warning: ${e.message}")
                } finally {
                    try { muxer.release() } catch (ignored: Exception) {}
                }
            }
            try { reusableFrameBitmap.recycle() } catch (ignored: Exception) {}
            photoBitmapCache.values.forEach { try { it.recycle() } catch (ignored: Exception) {} }
            videoRetrievers.values.forEach { try { it.release() } catch (ignored: Exception) {} }
            lastVideoFrameCache.values.forEach { try { it.recycle() } catch (ignored: Exception) {} }
        }
    }

    private fun selectColorFormat(mimeType: String): Pair<Int, Boolean> {
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in codecList.codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.equals(mimeType, ignoreCase = true)) {
                        val capabilities = info.getCapabilitiesForType(type)
                        if (capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)) {
                            return Pair(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar, true)
                        }
                        if (capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)) {
                            return Pair(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible, true)
                        }
                        if (capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)) {
                            return Pair(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar, false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Color format detection warning: ${e.message}")
        }
        return Pair(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar, true)
    }

    private fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer?,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        state: MuxerTrackState
    ) {
        val maxLoops = if (endOfStream) 60 else 10
        var loops = 0

        while (loops < maxLoops) {
            val status = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
                loops++
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxer != null && !state.isStarted) {
                    val newFormat = codec.outputFormat
                    state.trackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    state.isStarted = true
                }
            } else if (status >= 0) {
                val encodedData = codec.getOutputBuffer(status)
                if (encodedData != null && muxer != null) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size != 0 && state.isStarted && state.trackIndex >= 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        try {
                            muxer.writeSampleData(state.trackIndex, encodedData, bufferInfo)
                            state.samplesWritten++
                        } catch (e: Exception) {
                            Log.w(TAG, "Muxer writeSampleData warning: ${e.message}")
                        }
                    }

                    codec.releaseOutputBuffer(status, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                } else {
                    codec.releaseOutputBuffer(status, false)
                }
            }
        }
    }

    // --- FRAME COMPOSITION & DRAWING ---

    private fun renderFrameAtTime(
        canvas: Canvas,
        width: Int,
        height: Int,
        currentMs: Long,
        project: Project,
        photoCache: MutableMap<String, Bitmap>,
        videoRetrievers: MutableMap<String, MediaMetadataRetriever>,
        lastVideoFrames: MutableMap<String, Bitmap>
    ) {
        canvas.drawColor(Color.parseColor("#0C0C10"))

        val trackOrder = project.tracks.mapIndexed { index, track -> track.id to index }.toMap()
        val visualClips = project.clips.filter {
            it.trackType == TrackType.VIDEO || it.mediaType == MediaType.PHOTO || it.mediaType == MediaType.VIDEO
        }.sortedBy { trackOrder[it.trackId] ?: Int.MAX_VALUE }
        val activeClips = visualClips.filter {
            currentMs >= it.startTimeMs && currentMs < (it.startTimeMs + it.durationMs)
        }

        // Composite every active visual layer in track order instead of exporting only
        // the first matching clip. Higher tracks are drawn later and appear above lower tracks.
        activeClips.forEach { activeClip ->
            var clipBitmap: Bitmap? = null

            if (activeClip.mediaType == MediaType.PHOTO) {
                val path = activeClip.filePath.ifEmpty { activeClip.thumbnailPath }
                if (path.isNotEmpty() && File(path).exists()) {
                    clipBitmap = photoCache.getOrPut(path) {
                        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                        BitmapFactory.decodeFile(path, opts) ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                    }
                } else if (activeClip.uriString.isNotEmpty()) {
                    clipBitmap = photoCache.getOrPut(activeClip.uriString) {
                        try {
                            context.contentResolver.openInputStream(Uri.parse(activeClip.uriString))?.use {
                                BitmapFactory.decodeStream(it)
                            } ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                        } catch (_: Exception) {
                            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                        }
                    }
                }
            } else if (activeClip.mediaType == MediaType.VIDEO) {
                val path = activeClip.filePath.ifEmpty { activeClip.uriString }
                if (path.isNotEmpty()) {
                    val retriever = videoRetrievers.getOrPut(path) {
                        MediaMetadataRetriever().apply {
                            try {
                                if (File(path).exists()) {
                                    setDataSource(path)
                                } else if (activeClip.uriString.isNotEmpty()) {
                                    setDataSource(context, Uri.parse(activeClip.uriString))
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to setDataSource for $path: ${e.message}")
                            }
                        }
                    }

                    val elapsedTimelineMs = (currentMs - activeClip.startTimeMs).coerceAtLeast(0L)
                    val effectiveSpeed = if (activeClip.speed > 0f) activeClip.speed else 1.0f
                    val elapsedSourceMs = (elapsedTimelineMs * effectiveSpeed).toLong()
                    val videoOffsetUs = ((activeClip.sourceStartMs + elapsedSourceMs) * 1000L).coerceAtLeast(0L)

                    clipBitmap = try {
                        retriever.getFrameAtTime(videoOffsetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: retriever.getFrameAtTime(videoOffsetUs, MediaMetadataRetriever.OPTION_CLOSEST)
                            ?: retriever.getFrameAtTime(videoOffsetUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
                            ?: retriever.getFrameAtTime(videoOffsetUs)
                            ?: retriever.frameAtTime
                    } catch (e: Exception) {
                        null
                    }

                    // Fallback 1: Previous successfully extracted frame from this video
                    if (clipBitmap == null) {
                        clipBitmap = lastVideoFrames[activeClip.id]
                    }

                    // Fallback 2: Stored thumbnail for the video
                    if (clipBitmap == null && activeClip.thumbnailPath.isNotEmpty() && File(activeClip.thumbnailPath).exists()) {
                        clipBitmap = photoCache.getOrPut(activeClip.thumbnailPath) {
                            BitmapFactory.decodeFile(activeClip.thumbnailPath) ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                        }
                    }

                    if (clipBitmap != null && !clipBitmap.isRecycled) {
                        lastVideoFrames[activeClip.id] = clipBitmap
                    }
                }
            }

            if (clipBitmap != null && !clipBitmap.isRecycled) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

                paint.colorFilter = buildEffectColorFilter(activeClip.effects)

                val posX = getInterpolatedValue(activeClip, KeyframeParam.POSITION_X, currentMs)
                val posY = getInterpolatedValue(activeClip, KeyframeParam.POSITION_Y, currentMs)
                val scale = getInterpolatedValue(activeClip, KeyframeParam.SCALE, currentMs)
                val rot = getInterpolatedValue(activeClip, KeyframeParam.ROTATION, currentMs)
                val opacity = (getInterpolatedValue(activeClip, KeyframeParam.OPACITY, currentMs) / 100f).coerceIn(0f, 1f)
                paint.alpha = (opacity * 255).toInt()

                val scaleMode = Math.max(width.toFloat() / clipBitmap.width, height.toFloat() / clipBitmap.height)
                val dstW = clipBitmap.width * scaleMode
                val dstH = clipBitmap.height * scaleMode
                val left = (width - dstW) / 2f
                val top = (height - dstH) / 2f

                canvas.save()
                canvas.translate(posX, posY)
                canvas.scale(scale / 100f, scale / 100f, width / 2f, height / 2f)
                canvas.rotate(rot, width / 2f, height / 2f)
                canvas.drawBitmap(clipBitmap, null, RectF(left, top, left + dstW, top + dstH), paint)
                canvas.restore()
        }
        }

        // Draw every active text layer in its track order.
        val textClips = project.clips.filter { it.trackType == TrackType.TEXT }
            .filter { currentMs >= it.startTimeMs && currentMs < (it.startTimeMs + it.durationMs) }
            .sortedBy { trackOrder[it.trackId] ?: Int.MAX_VALUE }
        textClips.forEachIndexed { index, activeText ->
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = width * 0.07f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
                setShadowLayer(14f, 0f, 4f, Color.parseColor("#A855F7"))
            }
            canvas.drawText(activeText.name, width / 2f, height * (0.85f - index * 0.09f), textPaint)
        }
    }

    /**
     * Shared export-side color grading for enabled clip effects. Multiple compatible
     * grading effects are composed into one matrix so they no longer overwrite each other.
     */
    private fun buildEffectColorFilter(effects: List<ClipEffect>): ColorMatrixColorFilter? {
        val matrix = ColorMatrix()
        var changed = false
        effects.filter { it.isEnabled }.forEach { effect ->
            val amount = (effect.intensity * effect.opacity).coerceIn(0f, 2f)
            when (effect.type) {
                EffectType.BRIGHTNESS -> {
                    val offset = (amount - 1f) * 48f
                    matrix.postConcat(ColorMatrix(floatArrayOf(
                        1f,0f,0f,0f,offset, 0f,1f,0f,0f,offset,
                        0f,0f,1f,0f,offset, 0f,0f,0f,1f,0f
                    )))
                    changed = true
                }
                EffectType.CONTRAST -> {
                    val contrast = 1f + (amount * 0.35f)
                    val offset = 128f * (1f - contrast)
                    matrix.postConcat(ColorMatrix(floatArrayOf(
                        contrast,0f,0f,0f,offset, 0f,contrast,0f,0f,offset,
                        0f,0f,contrast,0f,offset, 0f,0f,0f,1f,0f
                    )))
                    changed = true
                }
                EffectType.WARMTH -> {
                    matrix.postConcat(ColorMatrix(floatArrayOf(
                        1f + .08f*amount,0f,0f,0f,8f*amount,
                        0f,1f + .025f*amount,0f,0f,2f*amount,
                        0f,0f,1f - .10f*amount,0f,0f,
                        0f,0f,0f,1f,0f
                    )))
                    changed = true
                }
                EffectType.COOL_CYAN -> {
                    matrix.postConcat(ColorMatrix(floatArrayOf(
                        1f - .10f*amount,0f,0f,0f,0f,
                        0f,1f + .025f*amount,0f,0f,2f*amount,
                        0f,0f,1f + .10f*amount,0f,8f*amount,
                        0f,0f,0f,1f,0f
                    )))
                    changed = true
                }
                EffectType.VINTAGE -> {
                    matrix.postConcat(ColorMatrix(floatArrayOf(
                        1f,0f,0f,0f,8f*amount, 0f,.94f,0f,0f,4f*amount,
                        0f,0f,.82f,0f,0f, 0f,0f,0f,1f,0f
                    )))
                    changed = true
                }
                EffectType.VIOLET_GLOW -> {
                    matrix.postConcat(ColorMatrix(floatArrayOf(
                        1f,0f,0f,0f,5f*amount, 0f,.92f,0f,0f,0f,
                        0f,0f,1.08f,0f,8f*amount, 0f,0f,0f,1f,0f
                    )))
                    changed = true
                }
                else -> Unit
            }
        }
        return if (changed) ColorMatrixColorFilter(matrix) else null
    }

    private fun getInterpolatedValue(clip: MediaClip, param: KeyframeParam, currentMs: Long): Float {
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
        val relativeTime = currentMs - clip.startTimeMs
        if (relativeTime <= paramKeyframes.first().timeMs) return paramKeyframes.first().value
        if (relativeTime >= paramKeyframes.last().timeMs) return paramKeyframes.last().value
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

    // --- YUV420 (NV12 / I420) COLOR CONVERSION ---

    private fun encodeYUV420(
        yuv: ByteArray,
        argb: IntArray,
        width: Int,
        height: Int,
        isSemiPlanar: Boolean
    ) {
        val frameSize = width * height
        var yIndex = 0
        var uIndex = frameSize
        var vIndex = frameSize + (frameSize / 4)

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[j * width + i]
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    if (isSemiPlanar) {
                        yuv[uIndex++] = u.coerceIn(0, 255).toByte()
                        yuv[uIndex++] = v.coerceIn(0, 255).toByte()
                    } else {
                        yuv[uIndex++] = u.coerceIn(0, 255).toByte()
                        yuv[vIndex++] = v.coerceIn(0, 255).toByte()
                    }
                }
            }
        }
    }

    // --- AUDIO EXTRACTION & MUXING ---

    private fun muxAudioIntoVideo(
        context: Context,
        videoOnlyFile: File,
        project: Project,
        totalDurationMs: Long
    ): File? {
        val audioSources = project.clips.filter {
            (it.trackType == TrackType.AUDIO_MUSIC || it.mediaType == MediaType.AUDIO) ||
            (it.mediaType == MediaType.VIDEO && !it.audioProperties.isMuted && it.volume > 0.01f)
        }.sortedBy { it.startTimeMs }

        if (audioSources.isEmpty()) return null

        val outputFile = File(context.cacheDir, "temp_muxed_${System.currentTimeMillis()}.mp4")
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            // 1. Prepare video extractor
            videoExtractor = MediaExtractor().apply { setDataSource(videoOnlyFile.absolutePath) }
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }
            if (videoTrackIndex < 0 || videoFormat == null) return null

            // 2. Find primary audio clip or first valid audio track
            var selectedAudioClip: MediaClip? = null
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (clip in audioSources) {
                val extractor = MediaExtractor()
                try {
                    val path = clip.filePath
                    if (path.isNotEmpty() && File(path).exists()) {
                        extractor.setDataSource(path)
                    } else if (clip.uriString.isNotEmpty()) {
                        extractor.setDataSource(context, Uri.parse(clip.uriString), null)
                    } else {
                        extractor.release()
                        continue
                    }
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("audio/")) {
                            selectedAudioClip = clip
                            audioTrackIndex = i
                            audioFormat = format
                            audioExtractor = extractor
                            break
                        }
                    }
                    if (audioExtractor != null) break
                } catch (e: Exception) {
                    try { extractor.release() } catch (_: Exception) {}
                }
            }

            if (audioExtractor == null || audioFormat == null || selectedAudioClip == null) {
                return null
            }

            // 3. Initialize MediaMuxer with both tracks
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            // 4. Copy video samples from videoOnlyFile
            videoExtractor.selectTrack(videoTrackIndex)
            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                videoExtractor.advance()
            }

            // 5. Copy audio samples from selectedAudioClip
            audioExtractor.selectTrack(audioTrackIndex)
            val clip = selectedAudioClip
            val sourceStartUs = (clip.sourceStartMs * 1000L).coerceAtLeast(0L)
            audioExtractor.seekTo(sourceStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val maxDurationUs = (totalDurationMs * 1000L)
            var baseAudioPtsUs = -1L

            while (true) {
                bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                val rawPts = audioExtractor.sampleTime
                if (baseAudioPtsUs < 0L) {
                    baseAudioPtsUs = rawPts
                }
                val relativePtsUs = (rawPts - baseAudioPtsUs) + (clip.startTimeMs * 1000L)
                if (relativePtsUs > maxDurationUs) break

                bufferInfo.presentationTimeUs = relativePtsUs.coerceAtLeast(0L)
                bufferInfo.flags = audioExtractor.sampleFlags
                try {
                    muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                } catch (e: Exception) {
                    Log.w(TAG, "Audio sample write warning: ${e.message}")
                }
                audioExtractor.advance()
            }

            return outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Audio muxing error", e)
            try { outputFile.delete() } catch (_: Exception) {}
            return null
        } finally {
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
            if (muxer != null) {
                try {
                    muxer.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Muxer stop warning: ${e.message}")
                }
                try {
                    muxer.release()
                } catch (_: Exception) {}
            }
        }
    }

    // --- MEDIASTORE & GALLERY SAVING ---

    private fun saveVideoToGallery(
        context: Context,
        tempVideoFile: File,
        projectTitle: String
    ): Pair<File, String> {
        val cleanTitle = projectTitle.replace("[^a-zA-Z0-9_]".toRegex(), "_").ifEmpty { "VexEdit" }
        val fileName = "${cleanTitle}_${System.currentTimeMillis()}.mp4"
        val displayPath = "Movies/VexEdit Studio/$fileName"

        val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "VexEdit Studio")
        if (!moviesDir.exists()) moviesDir.mkdirs()
        val publicFile = File(moviesDir, fileName)

        // Android 10+ (API 29+) Scoped MediaStore
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VexEdit Studio")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri: Uri? = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        tempVideoFile.inputStream().use { inp ->
                            inp.copyTo(out)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore insert warning: ${e.message}")
            }
        }

        // Direct public directory file copy as companion
        try {
            tempVideoFile.copyTo(publicFile, overwrite = true)
        } catch (e: Exception) {
            Log.w(TAG, "Direct public file write warning: ${e.message}")
        }

        // Trigger MediaScannerConnection to index file into Gallery
        val targetPath = if (publicFile.exists() && publicFile.length() > 0) publicFile.absolutePath else tempVideoFile.absolutePath
        MediaScannerConnection.scanFile(context, arrayOf(targetPath), arrayOf("video/mp4")) { path, uri ->
            Log.i(TAG, "MediaScanner indexed: $path -> $uri")
        }

        val finalFile = if (publicFile.exists() && publicFile.length() > 0) publicFile else tempVideoFile
        return Pair(finalFile, displayPath)
    }
}

// --- EGL14 & OPENGL ES 2.0 SURFACE HELPER ---

private class EglSurfaceHelper(
    private val surface: Surface,
    private val width: Int,
    private val height: Int
) {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var textureId = 0
    private var aPositionHandle = 0
    private var aTexCoordHandle = 0
    private var uTextureHandle = 0

    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    init {
        val vertexCoords = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        )
        val texCoords = floatArrayOf(
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
        )

        vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(vertexCoords)
                position(0)
            }

        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply {
                put(texCoords)
                position(0)
            }

        initEgl()
        initGl()
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("EGL initialize failed")
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, configs.size, numConfigs, 0)
        val config = configs[0] ?: throw RuntimeException("Unable to find suitable EGLConfig")

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, surfaceAttribs, 0)
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("EGL create window surface failed")
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("EGL make current failed in initEgl")
        }
    }

    private fun initGl() {
        val vertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        val fragmentShaderCode = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }

        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTextureHandle = GLES20.glGetUniformLocation(program, "uTexture")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }

    fun makeCurrent() {
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        GLES20.glViewport(0, 0, width, height)
    }

    fun drawBitmap(bitmap: Bitmap) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glUniform1i(uTextureHandle, 0)

        GLES20.glEnableVertexAttribArray(aPositionHandle)
        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

        GLES20.glEnableVertexAttribArray(aTexCoordHandle)
        GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTexCoordHandle)
    }

    fun setPresentationTime(nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }

    fun swapBuffers() {
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
        }
        surface.release()
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }
}
