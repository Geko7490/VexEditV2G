package com.example.data

import com.example.R
import java.util.UUID

enum class AspectRatio(val label: String, val ratio: Float) {
    RATIO_9_16("9:16", 9f / 16f),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_1_1("1:1", 1f),
    RATIO_4_5("4:5", 4f / 5f),
    RATIO_21_9("21:9", 21f / 9f)
}

enum class TrackType(val label: String) {
    VIDEO("Video"),
    AUDIO_MUSIC("Audio"),
    AUDIO_VOICE("Audio"),
    AUDIO_SFX("Audio"),
    VFX("Effects"),
    TEXT("Overlays")
}

enum class MediaType {
    VIDEO,
    PHOTO,
    AUDIO
}

enum class InterpolationType(val label: String) {
    LINEAR("Linear"),
    EASE_IN("Ease In"),
    EASE_OUT("Ease Out"),
    EASE_IN_OUT("Ease In-Out")
}

enum class KeyframeParam(val label: String, val unit: String, val min: Float, val max: Float, val defaultVal: Float) {
    POSITION_X("Position X", "", -100f, 100f, 0f),
    POSITION_Y("Position Y", "", -100f, 100f, 0f),
    SCALE("Scale", "%", 10f, 300f, 100f),
    ROTATION("Rotation", "°", -180f, 180f, 0f),
    OPACITY("Opacity", "%", 0f, 100f, 100f)
}

data class Keyframe(
    val id: String = UUID.randomUUID().toString(),
    val timeMs: Long,
    val param: KeyframeParam,
    val value: Float,
    val curve: InterpolationType = InterpolationType.EASE_IN_OUT
)

enum class EffectCategory {
    ALL,
    CINEMATIC,
    COLOR,
    BLUR,
    GLITCH,
    LIGHT,
    DISTORTION,
    TEXTURE
}

enum class EffectType(val title: String, val category: EffectCategory) {
    VIOLET_GLOW("Violet Glow", EffectCategory.LIGHT),
    FILM_GRAIN("Film Grain", EffectCategory.TEXTURE),
    RGB_SPLIT("RGB Split", EffectCategory.GLITCH),
    MOTION_BLUR("Motion Blur", EffectCategory.BLUR),
    CHROMATIC_ABERRATION("Chromatic Aberration", EffectCategory.GLITCH),
    VHS("VHS", EffectCategory.CINEMATIC),
    VINTAGE("Vintage", EffectCategory.CINEMATIC),
    CONTRAST("Contrast Boost", EffectCategory.COLOR),
    BRIGHTNESS("Brightness", EffectCategory.COLOR),
    WARMTH("Warm Tone", EffectCategory.COLOR),
    COOL_CYAN("Cool Cyan", EffectCategory.COLOR)
}

data class ClipEffect(
    val id: String = UUID.randomUUID().toString(),
    val type: EffectType,
    val intensity: Float = 1.0f, // 0.0 .. 2.0
    val radius: Float = 0.0f,    // 0.0 .. 50.0
    val opacity: Float = 1.0f,   // 0.0 .. 1.0
    val blend: Float = 0.0f,     // 0.0 .. 1.0
    val isEnabled: Boolean = true
)

data class ClipTransform(
    val posX: Float = 0f,
    val posY: Float = 0f,
    val scale: Float = 100f,
    val rotation: Float = 0f,
    val opacity: Float = 100f
)

data class AudioProperties(
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val isSolo: Boolean = false,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val faderDb: Float = 0.0f,
    val eqPreset: String = "Flat",
    val noiseGateEnabled: Boolean = false,
    val compressorEnabled: Boolean = false,
    val reverbLevel: Float = 0f,
    val delayLevel: Float = 0f,
    val limiterEnabled: Boolean = false
)

data class MediaClip(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val uriString: String = "",
    val filePath: String = "",
    val thumbnailPath: String = "",
    val resourceId: Int? = null,
    val mediaType: MediaType = MediaType.VIDEO,
    val trackId: String,
    val trackType: TrackType,
    val startTimeMs: Long = 0L,
    val sourceStartMs: Long = 0L,
    val durationMs: Long = 5000L,
    val sourceDurationMs: Long = 0L,
    val speed: Float = 1.0f,
    val volume: Float = 1.0f,
    val audioProperties: AudioProperties = AudioProperties(),
    val transform: ClipTransform = ClipTransform(),
    val keyframes: List<Keyframe> = emptyList(),
    val effects: List<ClipEffect> = emptyList(),
    val isSelected: Boolean = false,
    val waveformPoints: List<Float> = emptyList() // Normalized audio samples (0.0 .. 1.0)
)

data class Track(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val type: TrackType,
    val isMuted: Boolean = false,
    val isVisible: Boolean = true,
    val isSolo: Boolean = false,
    val faderDb: Float = 0.0f // -12dB .. +6dB
)

data class Project(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val aspectRatio: AspectRatio = AspectRatio.RATIO_9_16,
    val durationMs: Long = 0L,
    val lastEditedTimestamp: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val thumbnailUri: String? = null,
    val thumbnailPath: String? = null,
    val tracks: List<Track> = defaultTracks(),
    val clips: List<MediaClip> = emptyList()
) {
    companion object {
        fun defaultTracks(): List<Track> = listOf(
            Track("track_video_1", "Video 1", "Video", TrackType.VIDEO),
            Track("track_audio_1", "Music", "Audio", TrackType.AUDIO_MUSIC, faderDb = 0.0f),
            Track("track_audio_voice", "Voice", "Audio", TrackType.AUDIO_VOICE, faderDb = 0.0f),
            Track("track_audio_2", "Sound FX", "Audio", TrackType.AUDIO_SFX, faderDb = 0.0f),
            Track("track_vfx_1", "VFX", "Effects", TrackType.VFX),
            Track("track_text_1", "Text", "Overlays", TrackType.TEXT)
        )
    }
}

data class ImportedMediaItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val filePath: String,
    val uriString: String,
    val thumbnailPath: String = "",
    val mediaType: MediaType,
    val durationMs: Long = 5000L,
    val sizeBytes: Long = 0L,
    val dateAdded: Long = System.currentTimeMillis(),
    val resolution: String = ""
)

enum class ModuleCategory {
    ALL,
    VIDEO,
    AUDIO,
    EFFECTS,
    UTILITIES
}

enum class ModuleStatus {
    AVAILABLE,
    DOWNLOADING,
    INSTALLED,
    UPDATE_AVAILABLE
}

data class ModuleItem(
    val id: String,
    val title: String,
    val category: ModuleCategory,
    val description: String,
    val version: String,
    val sizeMb: Float,
    val status: ModuleStatus = ModuleStatus.AVAILABLE,
    val downloadProgress: Int = 0,
    val isEnabled: Boolean = true,
    val unlockedFeatures: List<String> = emptyList()
)

enum class PerformanceMode {
    LOW,
    MEDIUM,
    HIGH
}

data class AppSettings(
    val language: AppLanguage = AppLanguage.TR,
    val theme: String = "Vex Dark",
    val performanceMode: PerformanceMode = PerformanceMode.HIGH,
    val previewResolution: String = "720p",
    val targetFps: Int = 30,
    val autoSaveEnabled: Boolean = true,
    val cacheSizeMb: Long = 142L
)
