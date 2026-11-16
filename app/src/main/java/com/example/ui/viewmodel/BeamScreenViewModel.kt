package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cast.CastSessionBus
import com.example.cast.ScreenCastForegroundService
import com.example.data.AspectScaleMode
import com.example.data.BeamSettings
import com.example.data.DiscoveredTvPeer
import com.example.data.PeerTransport
import com.example.data.QualityPreset
import com.example.data.ReceiverState
import com.example.data.SenderState
import com.example.data.SettingsDataStore
import com.example.data.TvOrientationMode
import com.example.p2p.P2pNetworkManager
import com.example.p2p.StreamProtocol
import com.example.receiver.TvReceiverEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PendingCastTarget(
    val name: String,
    val host: String,
    val port: Int,
    val pin: String,
    val transportLabel: String
)

class BeamScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val settingsStore = SettingsDataStore(appContext)
    private val p2pManager = P2pNetworkManager(appContext)
    private val receiverEngine = TvReceiverEngine(appContext)

    val settings: StateFlow<BeamSettings> = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BeamSettings(
            tvDisplayName = "${Build.MODEL.take(18).ifBlank { "Android" }} TV",
            qualityPreset = QualityPreset.BALANCED_720P_60,
            requirePin = false,
            pairingPin = "4829",
            keepScreenAwake = true,
            lowLatencyMode = true,
            autoStartReceiver = true
        )
    )

    val receiverState: StateFlow<ReceiverState> = receiverEngine.receiverState
    val senderState: StateFlow<SenderState> = CastSessionBus.senderState
    val discoveredPeers: StateFlow<List<DiscoveredTvPeer>> = p2pManager.discoveredPeers
    val isScanning: StateFlow<Boolean> = p2pManager.isScanning
    val p2pStatusMessage: StateFlow<String?> = p2pManager.statusMessage

    private val _hasDiscoveryPermission = MutableStateFlow(p2pManager.hasDiscoveryPermission())
    val hasDiscoveryPermission: StateFlow<Boolean> = _hasDiscoveryPermission.asStateFlow()

    private val _aspectMode = MutableStateFlow(AspectScaleMode.FIT)
    val aspectMode: StateFlow<AspectScaleMode> = _aspectMode.asStateFlow()

    private val _tvOrientationMode = MutableStateFlow(TvOrientationMode.AUTO)
    val tvOrientationMode: StateFlow<TvOrientationMode> = _tvOrientationMode.asStateFlow()

    private val _pendingConsentTarget = MutableStateFlow<PendingCastTarget?>(null)
    val pendingConsentTarget: StateFlow<PendingCastTarget?> = _pendingConsentTarget.asStateFlow()

    private var receiverStartedOnce = false

    init {
        viewModelScope.launch {
            combine(
                p2pManager.wifiDirectGroup,
                p2pManager.nsdRegisteredName,
                p2pManager.wfdAdvertised
            ) { group, nsdName, wfdAdv ->
                Triple(group, nsdName, wfdAdv)
            }.collect { (group, nsdName, wfdAdv) ->
                receiverEngine.updateStandbyMetadata(
                    localIps = p2pManager.getLocalIpAddresses(),
                    p2pGroup = group,
                    nsdName = nsdName,
                    wfdAdvertised = wfdAdv
                )
            }
        }

        viewModelScope.launch {
            val initial = settingsStore.settingsFlow.first()
            if (initial.autoStartReceiver && !receiverStartedOnce) {
                receiverStartedOnce = true
                startTvReceiver()
            }
        }
    }

    fun refreshPermissions() {
        val granted = p2pManager.hasDiscoveryPermission()
        val changed = granted != _hasDiscoveryPermission.value
        _hasDiscoveryPermission.value = granted
        if (changed && granted && receiverState.value !is ReceiverState.Idle) {
            // Re-advertise Wi-Fi Direct now that permission is granted
            val current = settings.value
            val port = (receiverState.value as? ReceiverState.Standby)?.listenPort ?: StreamProtocol.DEFAULT_PORT
            p2pManager.startReceiverBroadcast(
                current.tvDisplayName,
                port,
                current.requirePin,
                receiverEngine.wfdSinkPort
            )
        }
    }

    fun refreshNetworkEndpoints() {
        receiverEngine.updateStandbyMetadata(
            localIps = p2pManager.getLocalIpAddresses(),
            p2pGroup = p2pManager.wifiDirectGroup.value,
            nsdName = p2pManager.nsdRegisteredName.value,
            wfdAdvertised = p2pManager.wfdAdvertised.value
        )
    }

    fun startTvReceiver() {
        val current = settings.value
        val pin = if (current.requirePin) current.pairingPin else null
        val port = receiverEngine.start(current.tvDisplayName, pin)
        receiverEngine.updateStandbyMetadata(
            localIps = p2pManager.getLocalIpAddresses(),
            p2pGroup = p2pManager.wifiDirectGroup.value,
            nsdName = p2pManager.nsdRegisteredName.value,
            wfdAdvertised = p2pManager.wfdAdvertised.value
        )
        p2pManager.startReceiverBroadcast(
            current.tvDisplayName,
            port,
            current.requirePin,
            receiverEngine.wfdSinkPort
        )
    }

    fun stopTvReceiver() {
        p2pManager.stopReceiverBroadcast()
        receiverEngine.stop()
    }

    fun attachTvSurface(surface: Surface?) {
        receiverEngine.attachVideoSurface(surface)
    }

    fun disconnectActiveSender() {
        receiverEngine.disconnectActiveSender()
    }

    fun toggleAspectMode() {
        _aspectMode.value = when (_aspectMode.value) {
            AspectScaleMode.FIT -> AspectScaleMode.FILL
            AspectScaleMode.FILL -> AspectScaleMode.FIT
        }
    }

    fun cycleTvOrientation() {
        _tvOrientationMode.value = when (_tvOrientationMode.value) {
            TvOrientationMode.AUTO -> TvOrientationMode.LANDSCAPE
            TvOrientationMode.LANDSCAPE -> TvOrientationMode.PORTRAIT
            TvOrientationMode.PORTRAIT -> TvOrientationMode.AUTO
        }
    }

    fun startPeerScan() {
        refreshPermissions()
        p2pManager.startPeerDiscovery()
    }

    fun stopPeerScan() {
        p2pManager.stopPeerDiscovery()
    }

    fun prepareCastToPeer(peer: DiscoveredTvPeer, pin: String) {
        viewModelScope.launch {
            CastSessionBus.updateState(SenderState.Connecting(peer.name))
            val resolvedHostResult = if (peer.transport == PeerTransport.WIFI_DIRECT && peer.p2pDeviceAddress != null) {
                p2pManager.connectWifiDirectPeer(peer.p2pDeviceAddress)
            } else {
                Result.success(peer.hostAddress)
            }

            resolvedHostResult.onSuccess { hostIp ->
                val target = PendingCastTarget(
                    name = peer.name,
                    host = hostIp,
                    port = peer.port,
                    pin = pin.trim(),
                    transportLabel = peer.transport.label
                )
                _pendingConsentTarget.value = target
                CastSessionBus.updateState(SenderState.AwaitingConsent(peer.name))
            }.onFailure { err ->
                CastSessionBus.updateState(
                    SenderState.Error(err.message ?: "Could not establish P2P link to ${peer.name}")
                )
            }
        }
    }

    fun prepareCastToDirectIp(hostIp: String, port: Int, pin: String) {
        val cleanIp = hostIp.trim()
        if (cleanIp.isBlank()) {
            CastSessionBus.updateState(SenderState.Error("Enter a valid receiver IP address"))
            return
        }
        val target = PendingCastTarget(
            name = cleanIp,
            host = cleanIp,
            port = port,
            pin = pin.trim(),
            transportLabel = PeerTransport.DIRECT_IP.label
        )
        _pendingConsentTarget.value = target
        CastSessionBus.updateState(SenderState.AwaitingConsent(cleanIp))
    }

    fun onScreenCaptureConsentResult(resultCode: Int, resultData: Intent?) {
        val target = _pendingConsentTarget.value
        _pendingConsentTarget.value = null
        if (target == null || resultData == null) {
            CastSessionBus.updateState(SenderState.Idle)
            return
        }

        val currentSettings = settings.value
        val serviceIntent = Intent(appContext, ScreenCastForegroundService::class.java).apply {
            action = ScreenCastForegroundService.ACTION_START_CAST
            putExtra(ScreenCastForegroundService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCastForegroundService.EXTRA_RESULT_DATA, resultData)
            putExtra(ScreenCastForegroundService.EXTRA_HOST, target.host)
            putExtra(ScreenCastForegroundService.EXTRA_PORT, target.port)
            putExtra(ScreenCastForegroundService.EXTRA_TARGET_NAME, target.name)
            putExtra(ScreenCastForegroundService.EXTRA_SENDER_NAME, Build.MODEL)
            putExtra(ScreenCastForegroundService.EXTRA_PIN, target.pin)
            putExtra(ScreenCastForegroundService.EXTRA_PRESET_ID, currentSettings.qualityPreset.id)
            putExtra(ScreenCastForegroundService.EXTRA_LOW_LATENCY, currentSettings.lowLatencyMode)
            putExtra(ScreenCastForegroundService.EXTRA_TRANSPORT_LABEL, target.transportLabel)
        }
        ContextCompat.startForegroundService(appContext, serviceIntent)
    }

    fun togglePrivacyPause() {
        val intent = Intent(appContext, ScreenCastForegroundService::class.java).apply {
            action = ScreenCastForegroundService.ACTION_TOGGLE_PRIVACY
        }
        appContext.startService(intent)
    }

    fun stopCasting() {
        val intent = Intent(appContext, ScreenCastForegroundService::class.java).apply {
            action = ScreenCastForegroundService.ACTION_STOP_CAST
        }
        appContext.startService(intent)
        CastSessionBus.updateState(SenderState.Idle)
    }

    fun clearSenderError() {
        if (CastSessionBus.senderState.value is SenderState.Error) {
            CastSessionBus.updateState(SenderState.Idle)
        }
    }

    // Settings updates
    fun updateTvDisplayName(name: String) {
        viewModelScope.launch {
            settingsStore.setTvDisplayName(name)
            val current = settings.value.copy(tvDisplayName = name.trim().take(28).ifBlank { name })
            receiverEngine.updateConfig(
                current.tvDisplayName,
                if (current.requirePin) current.pairingPin else null
            )
            if (receiverState.value !is ReceiverState.Idle) {
                val port = (receiverState.value as? ReceiverState.Standby)?.listenPort ?: StreamProtocol.DEFAULT_PORT
                p2pManager.startReceiverBroadcast(current.tvDisplayName, port, current.requirePin)
            }
        }
    }

    fun updateQualityPreset(preset: QualityPreset) {
        viewModelScope.launch { settingsStore.setQualityPreset(preset) }
    }

    fun updateRequirePin(requirePin: Boolean) {
        viewModelScope.launch {
            settingsStore.setRequirePin(requirePin)
            val updated = settingsStore.settingsFlow.first()
            receiverEngine.updateConfig(
                updated.tvDisplayName,
                if (updated.requirePin) updated.pairingPin else null
            )
            if (receiverState.value !is ReceiverState.Idle) {
                val port = (receiverState.value as? ReceiverState.Standby)?.listenPort ?: StreamProtocol.DEFAULT_PORT
                p2pManager.startReceiverBroadcast(updated.tvDisplayName, port, updated.requirePin)
            }
        }
    }

    fun regeneratePin() {
        viewModelScope.launch {
            val newPin = settingsStore.regeneratePin()
            val updated = settingsStore.settingsFlow.first()
            receiverEngine.updateConfig(
                updated.tvDisplayName,
                if (updated.requirePin) newPin else null
            )
        }
    }

    fun updateKeepScreenAwake(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setKeepScreenAwake(enabled) }
    }

    fun updateLowLatencyMode(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setLowLatencyMode(enabled) }
    }

    fun updateAutoStartReceiver(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutoStartReceiver(enabled) }
    }

    override fun onCleared() {
        p2pManager.release()
        receiverEngine.stop()
        super.onCleared()
    }
}
