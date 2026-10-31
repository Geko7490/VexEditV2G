package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb
import android.util.Log
import android.view.Surface
import android.net.Uri
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class PlaybackStatus { IDLE, LOADING, READY, PLAYING, PAUSED, ERROR }

data class AudioTrackPlayback(
    val id: String,
    val filePath: String,
    val startOffsetMs: Long,
    val volume: Float,
    val muted: Boolean,
    val speed: Float = 1f,
    val shouldPlay: Boolean,
    val eqPreset: String = "Flat",
    val reverbLevel: Float = 0f
)

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
 * Active music, voice and SFX clips are synchronized as independent preview players.
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

    private val audioPlayers = mutableMapOf<String, MediaPlayer>()
    private val audioPaths = mutableMapOf<String, String>()
    private val equalizers = mutableMapOf<String, Equalizer>()
    private val reverbs = mutableMapOf<String, PresetReverb>()

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
            if (!isPrepared && !shouldPlay) pendingSeekMs = startOffsetMs.coerceAtLeast(0L)
            if (isPrepared && !shouldPlay) videoPlayer.seekTo(startOffsetMs.coerceAtLeast(0L))
            if (shouldPlay) videoPlayer.play() else videoPlayer.pause()
        }
    }

    fun play() {
        desiredPlaying = true
        if (isPrepared) videoPlayer.play()
        audioPlayers.values.forEach { mp -> try { if (!mp.isPlaying) mp.start() } catch (_: Exception) {} }
    }

    fun pause() {
        desiredPlaying = false
        videoPlayer.pause()
        audioPlayers.values.forEach { mp -> try { if (mp.isPlaying) mp.pause() } catch (_: Exception) {} }
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
    ) = bindAudioTracks(
        listOf(AudioTrackPlayback("legacy", audioPath, startOffsetMs, volume, muted, shouldPlay = shouldPlay))
    )

    /** Synchronizes every audio clip active at the current timeline position. */
    fun bindAudioTracks(tracks: List<AudioTrackPlayback>) {
        val validTracks = tracks.filter { it.filePath.isNotBlank() && File(it.filePath).isFile }
        val activeIds = validTracks.map { it.id }.toSet()
        (audioPlayers.keys - activeIds).forEach { id ->
            releasePreviewEffects(id)
            try { audioPlayers.remove(id)?.release() } catch (_: Exception) {}
            audioPaths.remove(id)
        }

        validTracks.forEach { track ->
            val targetVolume = if (track.muted) 0f else track.volume.coerceIn(0f, 1f)
            val existing = audioPlayers[track.id]
            if (existing != null && audioPaths[track.id] == track.filePath) {
                try {
                    existing.setVolume(targetVolume, targetVolume)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        existing.playbackParams = existing.playbackParams.setSpeed(track.speed.coerceIn(0.25f, 4f))
                    }
                    applyPreviewEffects(track.id, existing.audioSessionId, track.eqPreset, track.reverbLevel)
                    val drift = kotlin.math.abs(existing.currentPosition.toLong() - track.startOffsetMs)
                    if (!track.shouldPlay && drift > 180L) existing.seekTo(track.startOffsetMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    if (track.shouldPlay && !existing.isPlaying) existing.start()
                    if (!track.shouldPlay && existing.isPlaying) existing.pause()
                } catch (e: Exception) {
                    Log.w(TAG, "Audio sync failed for ${track.id}", e)
                }
            } else {
                releasePreviewEffects(track.id)
                try { existing?.release() } catch (_: Exception) {}
                audioPaths[track.id] = track.filePath
                val player = MediaPlayer()
                audioPlayers[track.id] = player
                try {
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    player.setDataSource(track.filePath)
                    player.setOnPreparedListener { mp ->
                        try {
                            mp.setVolume(targetVolume, targetVolume)
                            applyPreviewEffects(track.id, mp.audioSessionId, track.eqPreset, track.reverbLevel)
                            mp.seekTo(track.startOffsetMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                mp.playbackParams = mp.playbackParams.setSpeed(track.speed.coerceIn(0.25f, 4f))
                            }
                            if (track.shouldPlay && desiredPlaying) mp.start()
                        } catch (e: Exception) {
                            Log.w(TAG, "Audio track preparation failed for ${track.id}", e)
                        }
                    }
                    player.setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "Audio track ${track.id} error: $what/$extra")
                        audioPlayers.remove(track.id)
                        audioPaths.remove(track.id)
                        true
                    }
                    player.prepareAsync()
                } catch (e: Exception) {
                    Log.e(TAG, "Audio track initialization failed for ${track.id}", e)
                    audioPlayers.remove(track.id)
                    audioPaths.remove(track.id)
                    try { player.release() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun applyPreviewEffects(id: String, sessionId: Int, eqPreset: String, reverbLevel: Float) {
        try {
            val equalizer = equalizers[id] ?: Equalizer(0, sessionId).also { equalizers[id] = it }
            if (!equalizer.hasControl()) return
            val requested = eqPreset.trim().lowercase()
            var matched: Short? = null
            for (index in 0 until equalizer.numberOfPresets.toInt()) {
                val presetIndex = index.toShort()
                if (equalizer.getPresetName(presetIndex).toString().lowercase() == requested) {
                    matched = presetIndex
                    break
                }
            }
            if (matched != null) {
                equalizer.usePreset(matched)
            } else {
                val flatIndex = (0 until equalizer.numberOfPresets.toInt())
                    .map { it.toShort() }
                    .firstOrNull { equalizer.getPresetName(it).toString().equals("Flat", ignoreCase = true) }
                if (flatIndex != null) equalizer.usePreset(flatIndex)
            }
            equalizer.enabled = !eqPreset.equals("Flat", ignoreCase = true)
        } catch (e: Exception) {
            Log.w(TAG, "Equalizer preview unavailable for $id", e)
        }

        try {
            val level = reverbLevel.coerceIn(0f, 1f)
            val reverb = reverbs[id] ?: PresetReverb(0, sessionId).also { reverbs[id] = it }
            if (level <= 0.01f) {
                reverb.enabled = false
            } else {
                reverb.preset = when {
                    level >= 0.66f -> PresetReverb.PRESET_LARGEHALL
                    level >= 0.33f -> PresetReverb.PRESET_MEDIUMROOM
                    else -> PresetReverb.PRESET_SMALLROOM
                }
                reverb.enabled = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reverb preview unavailable for $id", e)
        }
    }

    private fun releasePreviewEffects(id: String) {
        try { equalizers.remove(id)?.release() } catch (_: Exception) {}
        try { reverbs.remove(id)?.release() } catch (_: Exception) {}
    }

    fun stopAudio() {
        (equalizers.keys + reverbs.keys).toSet().forEach { releasePreviewEffects(it) }
        audioPlayers.values.forEach { player -> try { player.release() } catch (_: Exception) {} }
        audioPlayers.clear()
        audioPaths.clear()
    }

    fun release() {
        stopAudio()
        try { videoPlayer.release() } catch (_: Exception) {}
    }
}
