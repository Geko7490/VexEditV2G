package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class PlaybackStatus { IDLE, LOADING, READY, PLAYING, PAUSED, ERROR }

data class PlayerState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val filePath: String = "",
    val errorMessage: String? = null,
    val isCodecUnsupported: Boolean = false,
    val isReady: Boolean = false
)

/**
 * Media3-backed preview player. The public methods intentionally retain the old
 * StudioMediaPlayer API so the editor UI/ViewModel can migrate without a rewrite.
 * Audio track playback remains isolated until the timeline mixer is upgraded.
 */
class StudioMediaPlayer(context: Context) {
    companion object { private const val TAG = "StudioMediaPlayer" }

    private val appContext = context.applicationContext
    private val videoPlayer: ExoPlayer = ExoPlayer.Builder(appContext).build()
    private var surface: Surface? = null
    private var currentFilePath: String? = null
    private var desiredPlaying = false
    private var desiredVolume = 1f
    private var isMuted = false
    private var pendingSeekMs: Long = 0L
    private var isPrepared = false

    private var audioPlayer: MediaPlayer? = null
    private var currentAudioPath: String? = null
    private var isAudioPrepared = false

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    init {
        videoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        isPrepared = false
                        _playerState.value = PlayerState(PlaybackStatus.LOADING, currentFilePath.orEmpty())
                    }
                    Player.STATE_READY -> {
                        isPrepared = true
                        if (pendingSeekMs > 0L) {
                            videoPlayer.seekTo(pendingSeekMs)
                            pendingSeekMs = 0L
                        }
                        if (desiredPlaying) {
                            videoPlayer.play()
                            _playerState.value = PlayerState(PlaybackStatus.PLAYING, currentFilePath.orEmpty(), isReady = true)
                        } else {
                            _playerState.value = PlayerState(PlaybackStatus.READY, currentFilePath.orEmpty(), isReady = true)
                        }
                    }
                    Player.STATE_ENDED -> {
                        desiredPlaying = false
                        _playerState.value = PlayerState(PlaybackStatus.PAUSED, currentFilePath.orEmpty(), isReady = true)
                    }
                    Player.STATE_IDLE -> isPrepared = false
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isPrepared = false
                val unsupported = error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                Log.e(TAG, "Media3 playback error", error)
                _playerState.value = PlayerState(
                    status = PlaybackStatus.ERROR,
                    filePath = currentFilePath.orEmpty(),
                    errorMessage = error.localizedMessage ?: "Medya oynatılamadı. Codec veya dosya biçimi desteklenmiyor olabilir.",
                    isCodecUnsupported = unsupported
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPrepared) {
                    _playerState.value = _playerState.value.copy(
                        status = if (isPlaying) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
                        isReady = true
                    )
                }
            }
        })
    }

    fun attachSurface(newSurface: Surface?) {
        surface = newSurface
        try {
            if (newSurface != null && newSurface.isValid) videoPlayer.setVideoSurface(newSurface)
            else videoPlayer.clearVideoSurface()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to attach preview surface", e)
        }
    }

    fun bindVideoClip(
        filePath: String,
        startOffsetMs: Long,
        shouldPlay: Boolean,
        volume: Float = 1f,
        muted: Boolean = false
    ) {
        desiredPlaying = shouldPlay
        desiredVolume = volume.coerceIn(0f, 1f)
        isMuted = muted
        videoPlayer.volume = if (muted) 0f else desiredVolume

        if (filePath.isBlank()) {
            stopVideo()
            return
        }
        val file = File(filePath)
        if (!file.exists() || file.length() <= 0L) {
            stopVideo()
            _playerState.value = PlayerState(
                status = PlaybackStatus.ERROR,
                filePath = filePath,
                errorMessage = "Video dosyası bulunamadı veya boş."
            )
            return
        }

        if (currentFilePath != filePath) {
            currentFilePath = filePath
            pendingSeekMs = startOffsetMs.coerceAtLeast(0L)
            isPrepared = false
            _playerState.value = PlayerState(PlaybackStatus.LOADING, filePath)
            videoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            videoPlayer.prepare()
            if (shouldPlay) videoPlayer.play() else videoPlayer.pause()
        } else {
            if (isPrepared && !shouldPlay) videoPlayer.seekTo(startOffsetMs.coerceAtLeast(0L))
            if (shouldPlay) videoPlayer.play() else videoPlayer.pause()
        }
    }

    fun play() {
        desiredPlaying = true
        if (isPrepared) videoPlayer.play()
        if (isAudioPrepared) try { audioPlayer?.start() } catch (_: Exception) {}
    }

    fun pause() {
        desiredPlaying = false
        videoPlayer.pause()
        if (isAudioPrepared) try { audioPlayer?.pause() } catch (_: Exception) {}
    }

    fun seekVideo(offsetMs: Long) {
        val target = offsetMs.coerceAtLeast(0L)
        if (isPrepared) videoPlayer.seekTo(target) else pendingSeekMs = target
    }

    fun setVolume(volume: Float, muted: Boolean) {
        desiredVolume = volume.coerceIn(0f, 1f)
        isMuted = muted
        videoPlayer.volume = if (muted) 0f else desiredVolume
    }

    val currentPositionMs: Long
        get() = if (isPrepared) videoPlayer.currentPosition.coerceAtLeast(0L) else 0L

    val isPlaying: Boolean get() = videoPlayer.isPlaying
    val isVideoPrepared: Boolean get() = isPrepared

    fun stopVideo() {
        desiredPlaying = false
        isPrepared = false
        currentFilePath = null
        pendingSeekMs = 0L
        try {
            videoPlayer.pause()
            videoPlayer.stop()
            videoPlayer.clearMediaItems()
        } catch (e: Exception) {
            Log.w(TAG, "Video stop failed", e)
        }
        _playerState.value = PlayerState(PlaybackStatus.IDLE)
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
        volume: Float = 1f,
        muted: Boolean = false
    ) {
        if (audioPath.isEmpty() || !File(audioPath).exists()) {
            stopAudio()
            return
        }
        val effectiveVolume = if (muted) 0f else volume.coerceIn(0f, 1f)
        if (currentAudioPath == audioPath && audioPlayer != null) {
            try {
                audioPlayer?.setVolume(effectiveVolume, effectiveVolume)
                if (shouldPlay && isAudioPrepared && audioPlayer?.isPlaying != true) audioPlayer?.start()
                else if (!shouldPlay && isAudioPrepared) {
                    audioPlayer?.pause()
                    audioPlayer?.seekTo(startOffsetMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                }
            } catch (_: Exception) {}
            return
        }

        currentAudioPath = audioPath
        try { audioPlayer?.release() } catch (_: Exception) {}
        isAudioPrepared = false
        try {
            audioPlayer = MediaPlayer().apply {
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
                        mp.setVolume(effectiveVolume, effectiveVolume)
                        mp.seekTo(startOffsetMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        if (shouldPlay && desiredPlaying) mp.start()
                    } catch (_: Exception) {}
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Audio playback error: $what/$extra")
                    isAudioPrepared = false
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioPlayer init failed", e)
            isAudioPrepared = false
        }
    }

    fun stopAudio() {
        currentAudioPath = null
        isAudioPrepared = false
        try { audioPlayer?.stop(); audioPlayer?.reset() } catch (_: Exception) {}
    }

    fun release() {
        stopAudio()
        try { audioPlayer?.release() } catch (_: Exception) {}
        audioPlayer = null
        try { videoPlayer.release() } catch (_: Exception) {}
    }
}
