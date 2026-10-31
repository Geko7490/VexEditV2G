package com.example.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ModuleRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("vex_modules", Context.MODE_PRIVATE)

    private val _modules = MutableStateFlow<List<ModuleItem>>(emptyList())
    val modules: StateFlow<List<ModuleItem>> = _modules.asStateFlow()

    init {
        loadModules()
    }

    private fun loadModules() {
        val initialList = listOf(
            ModuleItem(
                id = "mod_audio_toolkit",
                title = "Audio Toolkit",
                category = ModuleCategory.AUDIO,
                description = "Advanced audio dynamics processing with Multiband Compressor, Noise Gate, Parametric EQ, and Reverb DSP models.",
                version = "v1.2",
                sizeMb = 38.5f,
                status = if (prefs.getBoolean("mod_audio_toolkit_installed", false)) ModuleStatus.INSTALLED else ModuleStatus.UPDATE_AVAILABLE,
                isEnabled = prefs.getBoolean("mod_audio_toolkit_enabled", true),
                unlockedFeatures = listOf("Compressor DSP", "Noise Gate", "Convolution Reverb", "Stereo Limiter")
            ),
            ModuleItem(
                id = "mod_cinematic_effects",
                title = "Cinematic Effects",
                category = ModuleCategory.EFFECTS,
                description = "High-fidelity film emulation including real 35mm grain, anamorphic violet glow, and vintage color grading.",
                version = "v2.0",
                sizeMb = 72.0f,
                status = if (prefs.getBoolean("mod_cinematic_effects_installed", true)) ModuleStatus.INSTALLED else ModuleStatus.AVAILABLE,
                isEnabled = prefs.getBoolean("mod_cinematic_effects_enabled", true),
                unlockedFeatures = listOf("Violet Glow Shaders", "35mm Film Grain", "Anamorphic Flares")
            ),
            ModuleItem(
                id = "mod_color_grading",
                title = "Color Grading",
                category = ModuleCategory.VIDEO,
                description = "Professional color wheels, Lift/Gamma/Gain controls, cinematic teal-orange and cyberpunk palettes.",
                version = "v1.5",
                sizeMb = 55.0f,
                status = if (prefs.getBoolean("mod_color_grading_installed", false)) ModuleStatus.INSTALLED else ModuleStatus.AVAILABLE,
                isEnabled = prefs.getBoolean("mod_color_grading_enabled", true),
                unlockedFeatures = listOf("Warm Tone", "Cool Cyan", "Duotone LUTs", "Contrast Curves")
            ),
            ModuleItem(
                id = "mod_transition_pack",
                title = "Transition Pack",
                category = ModuleCategory.VIDEO,
                description = "Seamless visual cut transitions: dynamic zoom blur, RGB whip pan, flash cut, and VHS glitch transitions.",
                version = "v1.1",
                sizeMb = 29.0f,
                status = if (prefs.getBoolean("mod_transition_pack_installed", false)) ModuleStatus.INSTALLED else ModuleStatus.AVAILABLE,
                isEnabled = prefs.getBoolean("mod_transition_pack_enabled", true),
                unlockedFeatures = listOf("Whip Pan", "Zoom Dissolve", "Glitch Transition")
            ),
            ModuleItem(
                id = "mod_keyframe_tools",
                title = "Keyframe Tools",
                category = ModuleCategory.UTILITIES,
                description = "Bezier curve graph editor, natural spring physics easing, rotational camera shake, and keyframe presets.",
                version = "v2.2",
                sizeMb = 41.0f,
                status = if (prefs.getBoolean("mod_keyframe_tools_installed", true)) ModuleStatus.INSTALLED else ModuleStatus.AVAILABLE,
                isEnabled = prefs.getBoolean("mod_keyframe_tools_enabled", true),
                unlockedFeatures = listOf("Bézier Curve Handles", "Spring Easing", "Camera Shake Presets")
            )
        )
        _modules.value = initialList
    }

    fun installModule(moduleId: String, scope: CoroutineScope) {
        val current = _modules.value.toMutableList()
        val index = current.indexOfFirst { it.id == moduleId }
        if (index == -1) return

        // Simulate real local packaging unpack with responsive progress
        scope.launch(Dispatchers.Default) {
            for (p in 10..100 step 15) {
                val updatedList = _modules.value.toMutableList()
                val idx = updatedList.indexOfFirst { it.id == moduleId }
                if (idx != -1) {
                    updatedList[idx] = updatedList[idx].copy(
                        status = ModuleStatus.DOWNLOADING,
                        downloadProgress = p
                    )
                    _modules.value = updatedList
                }
                delay(120)
            }
            // Mark installed
            prefs.edit().putBoolean("${moduleId}_installed", true).apply()
            val finalList = _modules.value.toMutableList()
            val finalIdx = finalList.indexOfFirst { it.id == moduleId }
            if (finalIdx != -1) {
                finalList[finalIdx] = finalList[finalIdx].copy(
                    status = ModuleStatus.INSTALLED,
                    downloadProgress = 0,
                    isEnabled = true
                )
                _modules.value = finalList
            }
        }
    }

    fun removeModule(moduleId: String) {
        prefs.edit().putBoolean("${moduleId}_installed", false).apply()
        val current = _modules.value.toMutableList()
        val index = current.indexOfFirst { it.id == moduleId }
        if (index != -1) {
            current[index] = current[index].copy(
                status = ModuleStatus.AVAILABLE,
                downloadProgress = 0,
                isEnabled = false
            )
            _modules.value = current
        }
    }

    fun toggleModuleEnabled(moduleId: String) {
        val current = _modules.value.toMutableList()
        val index = current.indexOfFirst { it.id == moduleId }
        if (index != -1) {
            val newState = !current[index].isEnabled
            prefs.edit().putBoolean("${moduleId}_enabled", newState).apply()
            current[index] = current[index].copy(isEnabled = newState)
            _modules.value = current
        }
    }
}
