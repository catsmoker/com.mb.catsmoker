package com.catsmoker.app.system.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.catsmoker.app.features.about.AboutRoute
import com.catsmoker.app.features.settings.SettingsRoute
import com.catsmoker.app.features.logs.LogsRoute
import com.catsmoker.app.features.main.MainRoute
import com.catsmoker.app.features.editgamefiles.EditGameFilesRoute
import com.catsmoker.app.features.gamingtools.GamingToolsRoute
import com.catsmoker.app.features.permissions.PermissionRoute
import com.catsmoker.app.features.spoofdevice.AppAssignmentScreen
import com.catsmoker.app.features.spoofdevice.DiagnosticsScreen
import com.catsmoker.app.features.spoofdevice.ProfileEditorScreen
import com.catsmoker.app.features.spoofdevice.ProfilesListScreen
import com.catsmoker.app.features.spoofdevice.SafeModeScreen
import com.catsmoker.app.features.spoofdevice.SpoofDeviceViewModel
import com.catsmoker.app.features.spoofdevice.SpoofRoute

@Composable
fun AppNavHost(navController: NavHostController, startDestination: String) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None }
    ) {
        composable(Routes.MAIN) {
            MainRoute(onNavigate = { navController.navigate(it) })
        }
        composable(Routes.PERMISSION) {
            PermissionRoute(
                onDone = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.PERMISSION) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.GAMING_TOOLS) {
            GamingToolsRoute(
                onNavigate = { navController.navigate(it) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.EDIT_GAME_FILES) {
            EditGameFilesRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.SPOOF_DEVICE) {
            SpoofRoute(
                onNavigate = { navController.navigate(it) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SPOOF_PROFILES) {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.SPOOF_DEVICE) }
            val viewModel: SpoofDeviceViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()
            ProfilesListScreen(
                uiState = uiState,
                onNavigateToEditor = { navController.navigate(Routes.SPOOF_EDITOR.replace("{profileId}", it)) },
                onCreateProfile = { viewModel.createProfile(it) },
                onDeleteProfile = { viewModel.deleteProfile(it) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SPOOF_EDITOR) { backStackEntry ->
            val profileId = backStackEntry.arguments?.getString("profileId") ?: return@composable
            val parentEntry = remember(backStackEntry) { navController.getBackStackEntry(Routes.SPOOF_DEVICE) }
            val viewModel: SpoofDeviceViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()
            ProfileEditorScreen(
                profileId = profileId,
                uiState = uiState,
                presets = viewModel.repository.getPresets(),
                onSave = { id, name, profile ->
                    viewModel.updateProfile(id, name, profile)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SPOOF_APPS) {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.SPOOF_DEVICE) }
            val viewModel: SpoofDeviceViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()
            AppAssignmentScreen(
                uiState = uiState,
                onLoadApps = { viewModel.loadApps() },
                onAssignProfile = { pkg, id -> viewModel.assignProfile(pkg, id) },
                onAssignRateCandidate = { pkg, id, hz -> viewModel.assignRateCandidate(pkg, id, hz) },
                onRemoveRateCandidate = { pkg, id, hz -> viewModel.removeRateCandidate(pkg, id, hz) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SPOOF_SAFE_MODE) {
            val parentEntry = remember(it) { navController.getBackStackEntry(Routes.SPOOF_DEVICE) }
            val viewModel: SpoofDeviceViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()
            SafeModeScreen(
                uiState = uiState,
                onLoadApps = { viewModel.loadApps() },
                onToggleSafeMode = { pkg, enabled -> viewModel.toggleSafeMode(pkg, enabled) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SPOOF_DIAGNOSTICS) {
            DiagnosticsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.ABOUT) {
            AboutRoute(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                onBack = { navController.popBackStack() },
                onOpenPermissions = { navController.navigate(Routes.PERMISSION) },
                onOpenLogs = { navController.navigate(Routes.LOGS) }
            )
        }
        composable(Routes.LOGS) {
            LogsRoute(onBack = { navController.popBackStack() })
        }
    }
}
