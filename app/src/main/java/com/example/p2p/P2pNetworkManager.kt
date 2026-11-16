package com.example.p2p

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pWfdInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.DiscoveredTvPeer
import com.example.data.PeerTransport
import com.example.data.WifiDirectGroupInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

class P2pNetworkManager(private val context: Context) {

    companion object {
        private const val TAG = "P2pNetworkManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var p2pChannel: WifiP2pManager.Channel? = null

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredTvPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredTvPeer>> = _discoveredPeers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _wifiDirectGroup = MutableStateFlow<WifiDirectGroupInfo?>(null)
    val wifiDirectGroup: StateFlow<WifiDirectGroupInfo?> = _wifiDirectGroup.asStateFlow()

    private val _nsdRegisteredName = MutableStateFlow<String?>(null)
    val nsdRegisteredName: StateFlow<String?> = _nsdRegisteredName.asStateFlow()

    private val _wfdAdvertised = MutableStateFlow(false)
    val wfdAdvertised: StateFlow<Boolean> = _wfdAdvertised.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val peerMap = ConcurrentHashMap<String, DiscoveredTvPeer>()
    private val p2pServiceTxtCache = ConcurrentHashMap<String, Map<String, String>>()
    private val nsdResolveMutex = Mutex()

    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var localP2pServiceInfo: WifiP2pDnsSdServiceInfo? = null
    private var p2pServiceRequest: WifiP2pDnsSdServiceRequest? = null
    private var p2pReceiverRegistered = false
    private var scanTimeoutJob: Job? = null

    @Volatile
    private var pendingConnectionDeferred: CompletableDeferred<Result<String>>? = null

    fun hasDiscoveryPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Returns local IPv4 addresses sorted with Wi-Fi LAN (`wlan`), Hotspot (`ap`/`swlan`),
     * and Wi-Fi Direct (`p2p`) first, excluding cellular carrier and VPN tunnel interfaces
     * when LAN/P2P addresses are present.
     */
    fun getLocalIpAddresses(): List<String> {
        data class RankedIp(val ip: String, val priority: Int)

        val ranked = mutableListOf<RankedIp>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()
                val priority = when {
                    name.startsWith("wlan") || name.startsWith("eth") -> 0
                    name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") -> 1
                    name.startsWith("p2p") -> 2
                    name.startsWith("rmnet") || name.startsWith("ccmni") ||
                        name.startsWith("tun") || name.startsWith("ppp") ||
                        name.startsWith("dummy") || name.startsWith("v4-") -> 99
                    else -> 3
                }
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        addr.hostAddress?.let { ip ->
                            if (!ip.startsWith("127.")) {
                                ranked.add(RankedIp(ip, priority))
                            }
                        }
                    }
                }
            }
        }
        val lanOrP2p = ranked.filter { it.priority < 99 }
        val chosen = lanOrP2p.ifEmpty { ranked }
        return chosen
            .sortedBy { it.priority }
            .map { it.ip }
            .distinct()
    }

    private fun ensureP2pChannel(): WifiP2pManager.Channel? {
        val manager = wifiP2pManager ?: return null
        if (p2pChannel == null) {
            p2pChannel = manager.initialize(context, Looper.getMainLooper()) {
                p2pChannel = null
            }
        }
        registerP2pBroadcastReceiver()
        return p2pChannel
    }

    private val p2pBroadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val manager = wifiP2pManager ?: return
                    val channel = p2pChannel ?: return
                    if (!hasDiscoveryPermission()) return
                    manager.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
                        if (info != null && info.groupFormed) {
                            val ownerIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                            if (info.isGroupOwner) {
                                refreshGroupInfo()
                            } else {
                                pendingConnectionDeferred?.complete(Result.success(ownerIp))
                                pendingConnectionDeferred = null
                            }
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (!hasDiscoveryPermission()) return
                    val manager = wifiP2pManager ?: return
                    val channel = p2pChannel ?: return
                    manager.requestPeers(channel) { peers ->
                        peers?.deviceList?.forEach { device ->
                            maybeAddOrUpdateP2pDevice(device)
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    )
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        _statusMessage.value = "Wi-Fi is disabled. Enable Wi-Fi for P2P and Web Cast."
                    } else if (_statusMessage.value?.contains("disabled") == true) {
                        _statusMessage.value = null
                    }
                }
            }
        }
    }

    private fun maybeAddOrUpdateP2pDevice(device: WifiP2pDevice) {
        val txt = p2pServiceTxtCache[device.deviceAddress]
        // Show devices that either have BeamScreen TXT info OR are available Wi-Fi Direct peers
        if (txt == null && device.status != WifiP2pDevice.AVAILABLE && device.status != WifiP2pDevice.CONNECTED) {
            return
        }
        val tvName = txt?.get("tvName")
            ?: device.deviceName.ifBlank { "Wi-Fi Direct Device" }
        val port = txt?.get("port")?.toIntOrNull() ?: StreamProtocol.DEFAULT_PORT
        val requiresPin = txt?.get("pin") == "1"
        val peer = DiscoveredTvPeer(
            id = "p2p_${device.deviceAddress}",
            name = tvName,
            hostAddress = "192.168.49.1",
            port = port,
            p2pDeviceAddress = device.deviceAddress,
            transport = PeerTransport.WIFI_DIRECT,
            requiresPin = requiresPin
        )
        peerMap[peer.id] = peer
        publishPeers()
    }

    private fun registerP2pBroadcastReceiver() {
        if (p2pReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(p2pBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(p2pBroadcastReceiver, filter)
        }
        p2pReceiverRegistered = true
    }

    @SuppressLint("MissingPermission")
    fun startReceiverBroadcast(
        tvName: String,
        port: Int,
        requirePin: Boolean,
        wfdControlPort: Int = 7236
    ) {
        registerNsdService(tvName, port, requirePin)

        if (!hasDiscoveryPermission()) {
            _statusMessage.value = "Grant Nearby Wi-Fi permission to advertise via Wi-Fi Direct."
            return
        }

        val manager = wifiP2pManager ?: return
        val channel = ensureP2pChannel() ?: return

        configureWfdSink(manager, channel, enabled = true, controlPort = wfdControlPort)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                manager.startListening(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Wi-Fi Direct startListening active")
                    }

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "startListening failed: $reason")
                    }
                })
            }
        }

        manager.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                addLocalP2pService(manager, channel, tvName, port, requirePin)
            }

            override fun onFailure(reason: Int) {
                addLocalP2pService(manager, channel, tvName, port, requirePin)
            }
        })

        manager.requestGroupInfo(channel) { existingGroup ->
            if (existingGroup != null && existingGroup.isGroupOwner) {
                _wifiDirectGroup.value = WifiDirectGroupInfo(
                    isGroupOwner = true,
                    networkSsid = existingGroup.networkName ?: "",
                    passphrase = existingGroup.passphrase ?: "",
                    groupOwnerIp = "192.168.49.1"
                )
            } else {
                manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        refreshGroupInfo()
                    }

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "createGroup failed: $reason")
                    }
                })
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun addLocalP2pService(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        tvName: String,
        port: Int,
        requirePin: Boolean
    ) {
        if (!hasDiscoveryPermission()) return
        val record = mapOf(
            "tvName" to tvName.take(28),
            "port" to port.toString(),
            "pin" to if (requirePin) "1" else "0"
        )
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            StreamProtocol.P2P_SERVICE_INSTANCE,
            StreamProtocol.P2P_SERVICE_TYPE,
            record
        )
        localP2pServiceInfo = serviceInfo
        manager.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct local service registered")
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "addLocalService failed: $reason")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun refreshGroupInfo() {
        if (!hasDiscoveryPermission()) return
        val manager = wifiP2pManager ?: return
        val channel = p2pChannel ?: return
        manager.requestGroupInfo(channel) { group ->
            if (group != null && group.isGroupOwner) {
                _wifiDirectGroup.value = WifiDirectGroupInfo(
                    isGroupOwner = true,
                    networkSsid = group.networkName ?: "",
                    passphrase = group.passphrase ?: "",
                    groupOwnerIp = "192.168.49.1"
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun configureWfdSink(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        enabled: Boolean,
        controlPort: Int
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            _wfdAdvertised.value = false
            return
        }
        runCatching {
            val wfdInfo = WifiP2pWfdInfo().apply {
                isEnabled = enabled
                setDeviceType(WifiP2pWfdInfo.DEVICE_TYPE_PRIMARY_SINK)
                isSessionAvailable = enabled
                setControlPort(controlPort)
                maxThroughput = 50
            }
            manager.setWfdInfo(channel, wfdInfo, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _wfdAdvertised.value = enabled
                    Log.d(TAG, "WFD Sink info set: enabled=$enabled port=$controlPort")
                }

                override fun onFailure(reason: Int) {
                    _wfdAdvertised.value = false
                    Log.w(TAG, "setWfdInfo failed: $reason")
                }
            })
        }.onFailure { err ->
            _wfdAdvertised.value = false
            Log.w(TAG, "WFD Sink unavailable on this ROM: ${err.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopReceiverBroadcast() {
        unregisterNsdService()
        _wifiDirectGroup.value = null
        _wfdAdvertised.value = false

        val manager = wifiP2pManager ?: return
        val channel = p2pChannel ?: return
        if (hasDiscoveryPermission()) {
            configureWfdSink(manager, channel, enabled = false, controlPort = 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { manager.stopListening(channel, null) }
            }
            localP2pServiceInfo?.let { info ->
                manager.removeLocalService(channel, info, null)
            }
            manager.removeGroup(channel, null)
        }
        localP2pServiceInfo = null
    }

    private fun registerNsdService(tvName: String, port: Int, requirePin: Boolean) {
        unregisterNsdService()
        val manager = nsdManager ?: return
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = tvName.take(28)
            serviceType = StreamProtocol.NSD_SERVICE_TYPE
            setPort(port)
            setAttribute("tvName", tvName.take(28))
            setAttribute("pin", if (requirePin) "1" else "0")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                _nsdRegisteredName.value = info.serviceName
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                _nsdRegisteredName.value = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        nsdRegistrationListener = listener
        runCatching {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    private fun unregisterNsdService() {
        val manager = nsdManager ?: return
        nsdRegistrationListener?.let { listener ->
            runCatching { manager.unregisterService(listener) }
        }
        nsdRegistrationListener = null
        _nsdRegisteredName.value = null
    }

    @SuppressLint("MissingPermission")
    fun startPeerDiscovery() {
        stopPeerDiscovery()
        peerMap.clear()
        publishPeers()
        _isScanning.value = true
        _statusMessage.value = null

        startNsdDiscovery()

        if (hasDiscoveryPermission() && wifiP2pManager != null) {
            val manager = wifiP2pManager
            val channel = ensureP2pChannel()
            if (channel != null) {
                manager.setDnsSdResponseListeners(
                    channel,
                    { instanceName, _, srcDevice ->
                        val txt = p2pServiceTxtCache[srcDevice.deviceAddress]
                        val tvName = txt?.get("tvName")
                            ?: srcDevice.deviceName.ifBlank { instanceName }
                        val port = txt?.get("port")?.toIntOrNull() ?: StreamProtocol.DEFAULT_PORT
                        val requiresPin = txt?.get("pin") == "1"
                        val peer = DiscoveredTvPeer(
                            id = "p2p_${srcDevice.deviceAddress}",
                            name = tvName,
                            hostAddress = "192.168.49.1",
                            port = port,
                            p2pDeviceAddress = srcDevice.deviceAddress,
                            transport = PeerTransport.WIFI_DIRECT,
                            requiresPin = requiresPin
                        )
                        peerMap[peer.id] = peer
                        publishPeers()
                    },
                    { _, txtRecordMap, srcDevice ->
                        p2pServiceTxtCache[srcDevice.deviceAddress] = txtRecordMap
                        maybeAddOrUpdateP2pDevice(srcDevice)
                    }
                )

                val req = WifiP2pDnsSdServiceRequest.newInstance()
                p2pServiceRequest = req
                manager.clearServiceRequests(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        manager.addServiceRequest(channel, req, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                manager.discoverServices(channel, object : WifiP2pManager.ActionListener {
                                    override fun onSuccess() {
                                        Log.d(TAG, "Wi-Fi Direct discoverServices started")
                                    }

                                    override fun onFailure(reason: Int) {
                                        Log.w(TAG, "discoverServices failed: $reason")
                                    }
                                })
                            }

                            override fun onFailure(reason: Int) {
                                Log.w(TAG, "addServiceRequest failed: $reason")
                            }
                        })
                    }

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "clearServiceRequests failed: $reason")
                    }
                })

                manager.discoverPeers(channel, null)
            }
        }

        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(18_000L)
            _isScanning.value = false
        }
    }

    private fun startNsdDiscovery() {
        val manager = nsdManager ?: return
        val localIps = getLocalIpAddresses().toSet()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("beamscreen")) return
                scope.launch(Dispatchers.IO) {
                    nsdResolveMutex.withLock {
                        val done = CompletableDeferred<Unit>()
                        @Suppress("DEPRECATION")
                        manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                done.complete(Unit)
                            }

                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                val hostIp = resolved.host?.hostAddress
                                if (hostIp != null && !localIps.contains(hostIp)) {
                                    val tvName = resolved.attributes["tvName"]
                                        ?.let { String(it, Charsets.UTF_8) }
                                        ?.ifBlank { resolved.serviceName }
                                        ?: resolved.serviceName
                                    val requiresPin = resolved.attributes["pin"]
                                        ?.let { String(it, Charsets.UTF_8) == "1" }
                                        ?: false
                                    val peer = DiscoveredTvPeer(
                                        id = "lan_${hostIp}_${resolved.port}",
                                        name = tvName,
                                        hostAddress = hostIp,
                                        port = resolved.port,
                                        transport = PeerTransport.LOCAL_LAN,
                                        requiresPin = requiresPin
                                    )
                                    peerMap[peer.id] = peer
                                    publishPeers()
                                }
                                done.complete(Unit)
                            }
                        })
                        withTimeoutOrNull(3000L) { done.await() }
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                peerMap.entries.removeIf {
                    it.value.transport == PeerTransport.LOCAL_LAN &&
                        it.value.name == serviceInfo.serviceName
                }
                publishPeers()
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsdDiscoveryListener = listener
        runCatching {
            manager.discoverServices(StreamProtocol.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopPeerDiscovery() {
        scanTimeoutJob?.cancel()
        _isScanning.value = false

        nsdDiscoveryListener?.let { listener ->
            runCatching { nsdManager?.stopServiceDiscovery(listener) }
        }
        nsdDiscoveryListener = null

        val manager = wifiP2pManager
        val channel = p2pChannel
        if (manager != null && channel != null && hasDiscoveryPermission()) {
            p2pServiceRequest?.let { req ->
                runCatching { manager.removeServiceRequest(channel, req, null) }
            }
            runCatching { manager.stopPeerDiscovery(channel, null) }
        }
        p2pServiceRequest = null
    }

    @SuppressLint("MissingPermission")
    suspend fun connectWifiDirectPeer(deviceAddress: String): Result<String> {
        if (!hasDiscoveryPermission()) {
            return Result.failure(IllegalStateException("Nearby Wi-Fi permission is required"))
        }
        val manager = wifiP2pManager
            ?: return Result.failure(IllegalStateException("Wi-Fi Direct unavailable on this device"))
        val channel = ensureP2pChannel()
            ?: return Result.failure(IllegalStateException("Wi-Fi Direct channel initialization failed"))

        val deferred = CompletableDeferred<Result<String>>()
        pendingConnectionDeferred = deferred

        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0 // Prefer connecting to the TV screen as Group Owner
        }

        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct connect initiated to $deviceAddress")
            }

            override fun onFailure(reason: Int) {
                deferred.complete(Result.failure(IllegalStateException("Wi-Fi Direct connect error ($reason)")))
                pendingConnectionDeferred = null
            }
        })

        return withTimeoutOrNull(18_000L) {
            deferred.await()
        } ?: run {
            pendingConnectionDeferred = null
            Result.failure(IllegalStateException("Wi-Fi Direct connection timed out"))
        }
    }

    private fun publishPeers() {
        _discoveredPeers.value = peerMap.values
            .sortedWith(compareBy<DiscoveredTvPeer> { it.transport.ordinal }.thenBy { it.name })
    }

    fun release() {
        stopPeerDiscovery()
        stopReceiverBroadcast()
        if (p2pReceiverRegistered) {
            runCatching { context.unregisterReceiver(p2pBroadcastReceiver) }
            p2pReceiverRegistered = false
        }
    }
}
