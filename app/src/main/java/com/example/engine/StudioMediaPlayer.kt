package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class PlaybackStatus {
    IDLE,
    LOADING,
    READY,
    PLAYING,
    PAUSED,
    ERROR
}

data class PlayerState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val filePath: String = "",
    val errorMessage: String? = null,
    val isCodecUnsupported: Boolean = false,
    val isReady: Boolean = false
)

/**
 * StudioMediaPlayer provides real-time hardware-accelerated video decoding,
 * preview playback, audio playback, frame-accurate seeking, and codec error handling
 * for VexEdit Studio.
 */
class StudioMediaPlayer(private val context: Context) {
    companion object {
        private const val TAG = "StudioMediaPlayer"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null

    private var currentFilePath: String? = null
    private var isPrepared = false
    private var isPreparing = false
    private var pendingSeekMs: Long? = null
    private var desiredPlaying = false
    private var desiredVolume: Float = 1.0f
    private var isMuted: Boolean = false

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    // Secondary player for background audio music/voice tracks
    private var audioPlayer: MediaPlayer? = null
    private var currentAudioPath: String? = null
    private var isAudioPrepared = false

    private var isSeeking = false
    private var queuedSeekMs: Long? = null

    fun attachSurface(newSurface: Surface?) {
        this.surface = newSurface
        try {
            if (newSurface != null && newSurface.isValid) {
                mediaPlayer?.setSurface(newSurface)
                if (isPrepared && !desiredPlaying) {
                    seekInternal(mediaPlayer!!, currentPositionMs)
                }
            } else {
                mediaPlayer?.setSurface(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching surface to player", e)
        }
    }

    fun bindVideoClip(
        filePath: String,
        startOffsetMs: Long,
        shouldPlay: Boolean,
        volume: Float = 1.0f,
        muted: Boolean = false
    ) {
        desiredVolume = volume
        isMuted = muted
        val effVol = if (muted) 0f else volume.coerceIn(0f, 1f)

        if (filePath.isEmpty()) {
            stopVideo()
            _playerState.value = PlayerState(PlaybackStatus.IDLE)
            return
        }

        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) {
            stopVideo()
            _playerState.value = PlayerState(
                status = PlaybackStatus.ERROR,
                filePath = filePath,
                errorMessage = "Video dosyası bulunamadı veya diskte yok",
                isCodecUnsupported = false
            )
            return
        }

        if (currentFilePath == filePath && mediaPlayer != null) {
            // Already loaded same video file
            try {
                mediaPlayer?.setVolume(effVol, effVol)
            } catch (_: Exception) {}

            if (shouldPlay != desiredPlaying) {
                desiredPlaying = shouldPlay
                if (isPrepared) {
                    if (shouldPlay) {
                        try {
                            mediaPlayer?.start()
                            _playerState.value = PlayerState(PlaybackStatus.PLAYING, filePath, isReady = true)
                        } catch (e: Exception) {
                            Log.e(TAG, "start error", e)
                        }
                    } else {
                        try {
                            mediaPlayer?.pause()
                            _playerState.value = PlayerState(PlaybackStatus.PAUSED, filePath, isReady = true)
                        } catch (e: Exception) {
                            Log.e(TAG, "pause error", e)
                        }
                    }
                }
            }

            if (!shouldPlay) {
                seekVideo(startOffsetMs)
            }
            return
        }

        // New file or first load
        currentFilePath = filePath
        desiredPlaying = shouldPlay
        pendingSeekMs = startOffsetMs.coerceAtLeast(0L)
        _playerState.value = PlayerState(PlaybackStatus.LOADING, filePath)
        initAndPreparePlayer(filePath, effVol)
    }

    private fun initAndPreparePlayer(filePath: String, volume: Float) {
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}

        isPrepared = false
        isPreparing = true

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                if (surface != null && surface!!.isValid) {
                    setSurface(surface)
                }
                setDataSource(filePath)
                setOnPreparedListener { mp ->
                    isPrepared = true
                    isPreparing = false
                    try {
                        if (surface != null && surface!!.isValid) {
                            mp.setSurface(surface)
                        }
                        mp.setVolume(volume, volume)
                        val targetSeek = pendingSeekMs ?: 0L
                        seekInternal(mp, targetSeek)
                        pendingSeekMs = null

                        if (desiredPlaying) {
                            mp.start()
                            _playerState.value = PlayerState(PlaybackStatus.PLAYING, filePath, isReady = true)
                        } else {
                            // Briefly prime the video decoder so the first frame is immediately pushed
                            // to the SurfaceTexture rather than remaining un-rendered black
                            try {
                                mp.start()
                                mp.pause()
                                seekInternal(mp, targetSeek)
                            } catch (_: Exception) {}
                            _playerState.value = PlayerState(PlaybackStatus.READY, filePath, isReady = true)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in onPrepared", e)
                        _playerState.value = PlayerState(
                            status = PlaybackStatus.ERROR,
                            filePath = filePath,
                            errorMessage = "Video hazırlanırken hata oluştu: ${e.localizedMessage ?: "Bilinmeyen hata"}"
                        )
                    }
                }
                setOnSeekCompleteListener {
                    isSeeking = false
                    val next = queuedSeekMs
                    if (next != null) {
                        queuedSeekMs = null
                        seekVideo(next)
                    }
                }
                setOnCompletionListener {
                    if (desiredPlaying) {
                        desiredPlaying = false
                        _playerState.value = PlayerState(PlaybackStatus.PAUSED, filePath, isReady = true)
                    }
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra for $filePath")
                    isPrepared = false
                    isPreparing = false
                    isSeeking = false
                    queuedSeekMs = null
                    val isUnsupported = extra == -1010 || extra == -1007 || what == 200 || extra == -1004
                    val msg = when {
                        extra == -1010 || extra == -1007 -> "Desteklenmeyen video/ses codec'i (Örn: H.265 10-bit / AC-3 Ses)"
                        extra == -1004 -> "Medya I/O okuma hatası"
                        what == MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "Android medya servisi çöktü"
                        else -> "Video decode veya oynatma hatası (Kod: $what, $extra)"
                    }
                    _playerState.value = PlayerState(
                        status = PlaybackStatus.ERROR,
                        filePath = filePath,
                        errorMessage = msg,
                        isCodecUnsupported = isUnsupported
                    )
                    true
                }
                prepareAsync()
            }
            mediaPlayer = player
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPlayer for $filePath", e)
            isPrepared = false
            isPreparing = false
            _playerState.value = PlayerState(
                status = PlaybackStatus.ERROR,
                filePath = filePath,
                errorMessage = "Video açılamadı: ${e.localizedMessage ?: "Desteklenmeyen biçim"}"
            )
        }
    }

    fun play() {
        desiredPlaying = true
        if (isPrepared && mediaPlayer != null) {
            try {
                val effVol = if (isMuted) 0f else desiredVolume.coerceIn(0f, 1f)
                mediaPlayer?.setVolume(effVol, effVol)
                mediaPlayer?.start()
                _playerState.value = _playerState.value.copy(status = PlaybackStatus.PLAYING)
            } catch (e: Exception) {
                Log.e(TAG, "play error", e)
            }
        }
        if (isAudioPrepared) {
            try { audioPlayer?.start() } catch (_: Exception) {}
        }
    }

    fun pause() {
        desiredPlaying = false
        if (isPrepared && mediaPlayer != null) {
            try {
                mediaPlayer?.pause()
                _playerState.value = _playerState.value.copy(status = PlaybackStatus.PAUSED)
            } catch (e: Exception) {
                Log.e(TAG, "pause error", e)
            }
        }
        if (isAudioPrepared) {
            try { audioPlayer?.pause() } catch (_: Exception) {}
        }
    }

    private var lastSeekTimestamp = 0L

    fun seekVideo(offsetMs: Long) {
        val target = offsetMs.coerceAtLeast(0L)
        if (isPrepared && mediaPlayer != null) {
            if (isSeeking) {
                queuedSeekMs = target
                return
            }
            isSeeking = true
            try {
                seekInternal(mediaPlayer!!, target)
            } catch (e: Exception) {
                isSeeking = false
                Log.e(TAG, "seek error", e)
            }
        } else {
            pendingSeekMs = target
        }
    }

    private fun seekInternal(mp: MediaPlayer, targetMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mp.seekTo(targetMs, MediaPlayer.SEEK_CLOSEST)
        } else {
            mp.seekTo(targetMs.toInt())
        }
    }

    fun setVolume(volume: Float, muted: Boolean) {
        desiredVolume = volume
        isMuted = muted
        val effVol = if (muted) 0f else volume.coerceIn(0f, 1f)
        try {
            mediaPlayer?.setVolume(effVol, effVol)
        } catch (_: Exception) {}
    }

    val currentPositionMs: Long
        get() {
            return if (isPrepared && mediaPlayer != null) {
                try {
                    mediaPlayer!!.currentPosition.toLong()
                } catch (_: Exception) {
                    0L
                }
            } else 0L
        }

    val isPlaying: Boolean
        get() {
            return if (isPrepared && mediaPlayer != null) {
                try {
                    mediaPlayer!!.isPlaying
                } catch (_: Exception) {
                    false
                }
            } else false
        }

    val isVideoPrepared: Boolean
        get() = isPrepared

    fun stopVideo() {
        desiredPlaying = false
        isPrepared = false
        isPreparing = false
        currentFilePath = null
        try {
            mediaPlayer?.stop()
            mediaPlayer?.reset()
        } catch (_: Exception) {}
    }

    fun extractFrameBitmap(filePath: String, timeMs: Long): Bitmap? {
        if (filePath.isEmpty()) return null
        val file = File(filePath)
        if (!file.exists()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val timeUs = timeMs.coerceAtLeast(0L) * 1000L
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
                ?: retriever.frameAtTime
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    fun bindAudioTrack(
        audioPath: String,
        startOffsetMs: Long,
        shouldPlay: Boolean,
        volume: Float = 1.0f,
        muted: Boolean = false
    ) {
        if (audioPath.isEmpty() || !File(audioPath).exists()) {
            stopAudio()
            return
        }
        val effVol = if (muted) 0f else volume.coerceIn(0f, 1f)
        if (currentAudioPath == audioPath && audioPlayer != null) {
            try { audioPlayer?.setVolume(effVol, effVol) } catch (_: Exception) {}
            if (shouldPlay && isAudioPrepared) {
                try { audioPlayer?.start() } catch (_: Exception) {}
            } else if (!shouldPlay && isAudioPrepared) {
                try {
                    audioPlayer?.pause()
                    audioPlayer?.seekTo(startOffsetMs.coerceAtLeast(0L).toInt())
                } catch (_: Exception) {}
            }
            return
        }
        currentAudioPath = audioPath
        try {
            audioPlayer?.release()
        } catch (_: Exception) {}
        isAudioPrepared = false
        try {
            val ap = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(audioPath)
                setOnPreparedListener { mp ->
                    isAudioPrepared = true
                    try {
                        mp.setVolume(effVol, effVol)
                        mp.seekTo(startOffsetMs.coerceAtLeast(0L).toInt())
                        if (shouldPlay && desiredPlaying) {
                            mp.start()
                        }
                    } catch (_: Exception) {}
                }
                prepareAsync()
            }
            audioPlayer = ap
        } catch (e: Exception) {
            Log.e(TAG, "AudioPlayer init failed", e)
        }
    }

    fun stopAudio() {
        currentAudioPath = null
        isAudioPrepared = false
        try {
            audioPlayer?.stop()
            audioPlayer?.reset()
        } catch (_: Exception) {}
    }

    fun release() {
        stopVideo()
        stopAudio()
        try {
            mediaPlayer?.release()
            mediaPlayer = null
            audioPlayer?.release()
            audioPlayer = null
        } catch (_: Exception) {}
    }
}
