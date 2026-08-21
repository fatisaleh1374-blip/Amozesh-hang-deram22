package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.AppScreen
import com.example.ui.HandpanViewModel
import com.example.ui.components.CustomSamplerDialog
import com.example.ui.components.OnboardingDialog
import com.example.ui.screens.ExerciseLibraryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MetronomeScreen
import com.example.ui.screens.PatternEditorScreen
import com.example.ui.screens.PracticeScreen
import com.example.ui.screens.RhythmTrainerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.CharcoalDark
import com.example.ui.theme.HandpanGold
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val viewModel: HandpanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val appState by viewModel.appUiState.collectAsStateWithLifecycle()

            MyApplicationTheme(darkTheme = appState.darkTheme) {
                // Persian RTL Direction Provider
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Scaffold(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                        bottomBar = {
                            // Show Navigation Bar on primary root screens
                            val isRootScreen = appState.currentScreen in listOf(
                                AppScreen.HOME,
                                AppScreen.EXERCISE_LIBRARY,
                                AppScreen.METRONOME,
                                AppScreen.SETTINGS
                            )
                            if (isRootScreen) {
                                AppBottomNavBar(
                                    currentScreen = appState.currentScreen,
                                    onNavigate = { viewModel.navigateTo(it) }
                                )
                            }
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            // Handle system back navigation
                            BackHandler(enabled = appState.currentScreen != AppScreen.HOME) {
                                when (appState.currentScreen) {
                                    AppScreen.PRACTICE -> viewModel.navigateTo(AppScreen.EXERCISE_LIBRARY)
                                    AppScreen.PATTERN_EDITOR -> viewModel.navigateTo(AppScreen.EXERCISE_LIBRARY)
                                    else -> viewModel.navigateTo(AppScreen.HOME)
                                }
                            }

                            when (appState.currentScreen) {
                                AppScreen.HOME -> HomeScreen(
                                    viewModel = viewModel,
                                    onNavigate = { viewModel.navigateTo(it) },
                                    onStartPractice = { pattern -> viewModel.startPractice(pattern) }
                                )
                                AppScreen.EXERCISE_LIBRARY -> ExerciseLibraryScreen(
                                    viewModel = viewModel,
                                    onBack = { viewModel.navigateTo(AppScreen.HOME) },
                                    onNavigate = { viewModel.navigateTo(it) },
                                    onStartPractice = { pattern -> viewModel.startPractice(pattern) }
                                )
                                AppScreen.PRACTICE -> PracticeScreen(
                                    viewModel = viewModel,
                                    onBack = { viewModel.navigateTo(AppScreen.EXERCISE_LIBRARY) }
                                )
                                AppScreen.METRONOME -> MetronomeScreen(
                                    viewModel = viewModel,
                                    onBack = { viewModel.navigateTo(AppScreen.HOME) }
                                )
                                AppScreen.RHYTHM_TRAINER -> RhythmTrainerScreen(
                                    viewModel = viewModel,
                                    onBack = { viewModel.navigateTo(AppScreen.HOME) }
                                )
                                AppScreen.PATTERN_EDITOR -> PatternEditorScreen(
                                    viewModel = viewModel,
                                    onBack = { viewModel.navigateTo(AppScreen.EXERCISE_LIBRARY) }
                                )
                                AppScreen.SETTINGS -> SettingsScreen(
                                    viewModel = viewModel,
                                    onBack = { viewModel.navigateTo(AppScreen.HOME) }
                                )
                            }

                            // Custom Acoustic Sampler Studio Dialog
                            if (appState.showSamplerDialog) {
                                CustomSamplerDialog(
                                    viewModel = viewModel,
                                    onDismiss = { viewModel.dismissSamplerDialog() }
                                )
                            }

                            // Onboarding Modal Dialog
                            if (appState.showOnboarding) {
                                OnboardingDialog(
                                    onDismiss = { viewModel.dismissOnboarding() }
                                )
                            }

                            // Handpan Anatomy & Techniques Guide Modal Dialog
                            if (appState.showGuideDialog) {
                                com.example.ui.components.HandpanGuideDialog(
                                    onDismiss = { viewModel.dismissGuideDialog() }
                                )
                            }

                            // Scale & Tuning Selector Dialog
                            if (appState.showScaleDialog) {
                                com.example.ui.components.ScaleSelectorDialog(
                                    viewModel = viewModel,
                                    onDismiss = { viewModel.dismissScaleDialog() }
                                )
                            }

                            // Backing Ambient Soundscapes Dialog
                            if (appState.showBackingTracksDialog) {
                                com.example.ui.components.BackingTracksDialog(
                                    viewModel = viewModel,
                                    onDismiss = { viewModel.dismissBackingTracksDialog() }
                                )
                            }

                            // Performance Recorder & Looper Studio Dialog
                            if (appState.showRecorderDialog) {
                                com.example.ui.components.PerformanceRecorderDialog(
                                    viewModel = viewModel,
                                    onDismiss = { viewModel.dismissRecorderDialog() }
                                )
                            }

                            // Interactive Masterclass Lesson Studio Dialog
                            if (appState.showLessonStudioDialog) {
                                com.example.ui.components.InteractiveLessonStudioDialog(
                                    viewModel = viewModel,
                                    onStartPractice = { pattern ->
                                        viewModel.startPractice(pattern)
                                    },
                                    onDismiss = { viewModel.dismissLessonStudioDialog() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppBottomNavBar(
    currentScreen: AppScreen,
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier
            .testTag("app_bottom_nav_bar"),
        containerColor = CharcoalDark,
        tonalElevation = 8.dp
    ) {
        val items = listOf(
            Triple(AppScreen.HOME, "خانه", Icons.Filled.Home to Icons.Outlined.Home),
            Triple(AppScreen.EXERCISE_LIBRARY, "تمرین‌ها", Icons.AutoMirrored.Filled.MenuBook to Icons.AutoMirrored.Outlined.MenuBook),
            Triple(AppScreen.METRONOME, "مترونوم", Icons.Filled.Timer to Icons.Outlined.Timer),
            Triple(AppScreen.SETTINGS, "تنظیمات", Icons.Filled.Settings to Icons.Outlined.Settings)
        )

        items.forEach { (screen, title, icons) ->
            val isSelected = (screen == currentScreen)
            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigate(screen) },
                icon = {
                    Icon(
                        imageVector = if (isSelected) icons.first else icons.second,
                        contentDescription = title
                    )
                },
                label = {
                    Text(
                        text = title,
                        fontSize = 11.sp
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.Black,
                    selectedTextColor = HandpanGold,
                    indicatorColor = HandpanGold,
                    unselectedIconColor = Color.LightGray,
                    unselectedTextColor = Color.Gray
                ),
                modifier = Modifier.testTag("nav_item_${screen.name.lowercase()}")
            )
        }
    }
}
