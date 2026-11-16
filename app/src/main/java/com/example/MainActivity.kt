package com.example

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.ReceiverState
import com.example.data.TvOrientationMode
import com.example.ui.BeamScreenApp
import com.example.ui.theme.BeamScreenTheme
import com.example.ui.viewmodel.BeamScreenViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val viewModel: BeamScreenViewModel = viewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val receiverState by viewModel.receiverState.collectAsStateWithLifecycle()
            val pendingConsent by viewModel.pendingConsentTarget.collectAsStateWithLifecycle()
            val orientationMode by viewModel.tvOrientationMode.collectAsStateWithLifecycle()

            val discoveryPermissions = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.NEARBY_WIFI_DEVICES)
                    add(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }.toTypedArray()

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {
                viewModel.refreshPermissions()
            }

            val scanAfterPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {
                viewModel.refreshPermissions()
                viewModel.startPeerScan()
            }

            val mediaProjectionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                viewModel.onScreenCaptureConsentResult(result.resultCode, result.data)
            }

            LaunchedEffect(pendingConsent) {
                if (pendingConsent != null) {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
                }
            }

            // Keep screen awake when acting as TV monitor or actively streaming
            val shouldKeepAwake = (settings.keepScreenAwake && receiverState !is ReceiverState.Idle) ||
                receiverState is ReceiverState.Streaming
            LaunchedEffect(shouldKeepAwake) {
                if (shouldKeepAwake) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            // Immersive full-screen bars & orientation control during TV mirroring
            val isMirroring = receiverState is ReceiverState.Streaming || receiverState is ReceiverState.Connecting
            LaunchedEffect(isMirroring, orientationMode) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                if (isMirroring) {
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    requestedOrientation = when (orientationMode) {
                        TvOrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        TvOrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        TvOrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }

            BeamScreenTheme(darkTheme = true, dynamicColor = false) {
                BeamScreenApp(
                    viewModel = viewModel,
                    onRequestDiscoveryPermission = {
                        permissionLauncher.launch(discoveryPermissions)
                    },
                    onEnsureCastPermissionsAndScan = {
                        scanAfterPermissionLauncher.launch(discoveryPermissions)
                    }
                )
            }
        }
    }
}
