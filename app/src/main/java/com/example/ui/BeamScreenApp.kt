package com.example.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.data.ReceiverState
import com.example.ui.screens.CastSenderScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TvReceiverScreen
import com.example.ui.viewmodel.BeamScreenViewModel

const val ROUTE_TV_SCREEN = "tv_screen"
const val ROUTE_CAST_PHONE = "cast_phone"
const val ROUTE_SETTINGS = "settings"

private data class NavItem(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val BeamNavItems = listOf(
    NavItem(
        route = ROUTE_TV_SCREEN,
        labelRes = R.string.nav_tv_screen,
        selectedIcon = Icons.Rounded.Tv,
        unselectedIcon = Icons.Outlined.Tv,
        testTag = "nav_tab_tv_screen"
    ),
    NavItem(
        route = ROUTE_CAST_PHONE,
        labelRes = R.string.nav_cast_phone,
        selectedIcon = Icons.Rounded.Cast,
        unselectedIcon = Icons.Outlined.Cast,
        testTag = "nav_tab_cast_phone"
    ),
    NavItem(
        route = ROUTE_SETTINGS,
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Rounded.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_tab_settings"
    )
)

@Composable
fun BeamScreenApp(
    viewModel: BeamScreenViewModel,
    onRequestDiscoveryPermission: () -> Unit,
    onEnsureCastPermissionsAndScan: () -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val receiverState by viewModel.receiverState.collectAsStateWithLifecycle()
    val senderState by viewModel.senderState.collectAsStateWithLifecycle()
    val discoveredPeers by viewModel.discoveredPeers.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val p2pStatusMessage by viewModel.p2pStatusMessage.collectAsStateWithLifecycle()
    val hasDiscoveryPermission by viewModel.hasDiscoveryPermission.collectAsStateWithLifecycle()
    val aspectMode by viewModel.aspectMode.collectAsStateWithLifecycle()
    val orientationMode by viewModel.tvOrientationMode.collectAsStateWithLifecycle()

    val isFullScreenTv = (receiverState is ReceiverState.Streaming || receiverState is ReceiverState.Connecting) &&
        currentDestination?.route == ROUTE_TV_SCREEN

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!useNavigationRail && !isFullScreenTv) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        BeamNavItems.forEach { item ->
                            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = stringResource(item.labelRes)
                                    )
                                },
                                label = { Text(stringResource(item.labelRes)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                ),
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail && !isFullScreenTv) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        BeamNavItems.forEach { item ->
                            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = stringResource(item.labelRes)
                                    )
                                },
                                label = { Text(stringResource(item.labelRes)) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                ),
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_TV_SCREEN,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(ROUTE_TV_SCREEN) {
                        TvReceiverScreen(
                            receiverState = receiverState,
                            settings = settings,
                            hasDiscoveryPermission = hasDiscoveryPermission,
                            aspectMode = aspectMode,
                            orientationMode = orientationMode,
                            onRequestDiscoveryPermission = onRequestDiscoveryPermission,
                            onStartReceiver = viewModel::startTvReceiver,
                            onStopReceiver = viewModel::stopTvReceiver,
                            onRefreshNetwork = viewModel::refreshNetworkEndpoints,
                            onAttachSurface = viewModel::attachTvSurface,
                            onToggleAspectMode = viewModel::toggleAspectMode,
                            onCycleOrientation = viewModel::cycleTvOrientation,
                            onDisconnectSender = viewModel::disconnectActiveSender
                        )
                    }

                    composable(ROUTE_CAST_PHONE) {
                        CastSenderScreen(
                            senderState = senderState,
                            discoveredPeers = discoveredPeers,
                            isScanning = isScanning,
                            p2pStatusMessage = p2pStatusMessage,
                            settings = settings,
                            hasDiscoveryPermission = hasDiscoveryPermission,
                            onRequestDiscoveryPermission = onRequestDiscoveryPermission,
                            onScanPeers = onEnsureCastPermissionsAndScan,
                            onSelectQuality = viewModel::updateQualityPreset,
                            onConnectPeer = viewModel::prepareCastToPeer,
                            onConnectDirectIp = viewModel::prepareCastToDirectIp,
                            onTogglePrivacyPause = viewModel::togglePrivacyPause,
                            onStopCasting = viewModel::stopCasting,
                            onDismissError = viewModel::clearSenderError
                        )
                    }

                    composable(ROUTE_SETTINGS) {
                        SettingsScreen(
                            settings = settings,
                            onUpdateTvName = viewModel::updateTvDisplayName,
                            onUpdateQualityPreset = viewModel::updateQualityPreset,
                            onUpdateRequirePin = viewModel::updateRequirePin,
                            onRegeneratePin = viewModel::regeneratePin,
                            onUpdateKeepScreenAwake = viewModel::updateKeepScreenAwake,
                            onUpdateLowLatencyMode = viewModel::updateLowLatencyMode,
                            onUpdateAutoStartReceiver = viewModel::updateAutoStartReceiver
                        )
                    }
                }
            }
        }
    }
}
