package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.*
import com.example.ui.theme.VexBackground
import com.example.ui.theme.VexEditTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VexEditTheme {
                val viewModel: StudioViewModel = viewModel()
                val currentScreen by viewModel.currentScreen.collectAsState()

                // Hardware / System Back handling
                BackHandler(enabled = currentScreen != Screen.HOME) {
                    when (currentScreen) {
                        Screen.AUDIO_STUDIO,
                        Screen.EFFECTS_LAB,
                        Screen.KEYFRAME_EDITOR,
                        Screen.MODULE_CENTER,
                        Screen.IMPORT_MEDIA -> viewModel.navigateTo(Screen.STUDIO)
                        Screen.STUDIO,
                        Screen.PROJECTS,
                        Screen.SETTINGS -> viewModel.navigateTo(Screen.HOME)
                        Screen.HOME -> { /* Exit app */ }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = VexBackground
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        when (currentScreen) {
                            Screen.HOME -> HomeScreen(
                                viewModel = viewModel,
                                onNavigate = { viewModel.navigateTo(it) }
                            )
                            Screen.PROJECTS -> ProjectsScreen(
                                viewModel = viewModel,
                                onNavigate = { viewModel.navigateTo(it) }
                            )
                            Screen.STUDIO -> StudioScreen(
                                viewModel = viewModel,
                                onNavigate = { viewModel.navigateTo(it) }
                            )
                            Screen.SETTINGS -> SettingsScreen(
                                viewModel = viewModel,
                                onNavigate = { viewModel.navigateTo(it) }
                            )
                            Screen.AUDIO_STUDIO -> AudioStudioScreen(
                                viewModel = viewModel,
                                onNavigate = { viewModel.navigateTo(it) }
                            )
                            Screen.EFFECTS_LAB -> EffectsLabScreen(
                                viewModel = viewModel,
                                onNavigate = { viewModel.navigateTo(it) }
                            )
                            Screen.KEYFRAME_EDITOR -> KeyframeEditorScreen(
                                viewModel = viewModel,
                                onNavigate = { viewModel.navigateTo(it) }
                            )
                            Screen.MODULE_CENTER -> ModuleCenterScreen(
                                viewModel = viewModel,
                                onNavigate = { viewModel.navigateTo(it) }
                            )
                            Screen.IMPORT_MEDIA -> ImportMediaScreen(
                                viewModel = viewModel,
                                onNavigate = { viewModel.navigateTo(it) }
                            )
                        }
                    }
                }
            }
        }
    }
}
