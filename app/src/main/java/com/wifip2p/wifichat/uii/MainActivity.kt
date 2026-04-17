package com.wifip2p.wifichat.uii

import android.Manifest
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.wifip2p.wifichat.Network.WiFiDirectBroadcastReceiver
import com.wifip2p.wifichat.ui.theme.WifiChatTheme

/**
 * MainActivity - Device Discovery Screen (Jetpack Compose)
 *
 * WHAT THIS DOES:
 * - Shows a list of nearby WiFi P2P devices
 * - User can scan for devices using FAB button
 * - Tap on a device to connect and start chatting
 *
 * WHY COMPOSE:
 * - Less code than XML (no separate layout files!)
 * - Reactive UI (state changes automatically update UI)
 * - Modern Android development standard
 * - Easy to read and maintain
 */
class MainActivity : ComponentActivity() {


    companion object {
        @Volatile
        var chatOpened = false

        lateinit var sharedManager: WifiP2pManager
        lateinit var sharedChannel: WifiP2pManager.Channel
    }

    // WiFi P2P components
    private lateinit var wifiP2pManager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var receiver: BroadcastReceiver

    // State for UI - Compose will automatically update UI when these change
    private var deviceListState = mutableStateOf<List<WifiP2pDevice>>(emptyList())
    private var isScanningState = mutableStateOf(false)

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "Permissions granted! You can now scan.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permissions required for WiFi P2P", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize WiFi P2P
        initializeWifiP2p()

        // Request permissions
        requestPermissions()

        // Set Compose UI
        setContent {
            WifiChatTheme {
                DeviceDiscoveryScreen(
                    devices = deviceListState.value,
                    isScanning = isScanningState.value,
                    onScanClick = { discoverPeers() },
                    onDeviceClick = { device -> connectToDevice(device) }
                )
            }
        }
    }

    /**
     * Initialize WiFi P2P Manager
     * This sets up the WiFi Direct framework
     */
    private fun initializeWifiP2p() {
        wifiP2pManager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        channel = wifiP2pManager.initialize(this, mainLooper, null)

        // 🔥 SHARE WITH ChatActivity
        sharedManager = wifiP2pManager
        sharedChannel = channel

        receiver = WiFiDirectBroadcastReceiver(wifiP2pManager, channel, this)
    }


    /**
     * Request necessary permissions for WiFi P2P
     * Different permissions for different Android versions
     */
    private fun requestPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        // Android 13+ needs NEARBY_WIFI_DEVICES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            // Android 13+ media permissions for file sharing
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            // Android 12 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        // All versions need location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissionsNeeded.isNotEmpty()) {
            permissionLauncher.launch(permissionsNeeded.toTypedArray())
        }
    }

    /**
     * Start discovering nearby WiFi P2P devices
     * Like pressing "scan" on Bluetooth
     */
    private fun discoverPeers() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Location permission needed", Toast.LENGTH_SHORT).show()
            return
        }

        isScanningState.value = true

        wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Toast.makeText(this@MainActivity, "Scanning for devices...", Toast.LENGTH_SHORT).show()
            }

            override fun onFailure(reason: Int) {
                isScanningState.value = false
                val message = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "WiFi P2P not supported"
                    WifiP2pManager.BUSY -> "System busy, try again"
                    WifiP2pManager.ERROR -> "Error occurred"
                    else -> "Failed to start discovery"
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        })
    }

    /**
     * Connect to selected device
     * This is where we'll implement WiFi P2P connection
     */
    private fun connectToDevice(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }

        wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { /* Connected! */ }
            override fun onFailure(reason: Int) { /* Handle error */ }
        })
    }
    /**
     * Update device list - called by BroadcastReceiver
     * When new devices are found, this updates the UI
     */
    fun updateDeviceList(devices: Collection<WifiP2pDevice>) {
        deviceListState.value = devices.toList()
        isScanningState.value = false
    }





    override fun onResume() {
        super.onResume()
        MainActivity.sharedManager.removeGroup(
            MainActivity.sharedChannel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            }
        )


        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        registerReceiver(receiver, intentFilter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }
}

/**
 * COMPOSABLE FUNCTION - Device Discovery Screen
 *
 * This is the UI for the main screen
 * Compose functions describe what the UI should look like
 * When state changes, Compose automatically rebuilds the UI
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDiscoveryScreen(
    devices: List<WifiP2pDevice>,
    isScanning: Boolean,
    onScanClick: () -> Unit,
    onDeviceClick: (WifiP2pDevice) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi Chat") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onScanClick,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Scan for devices"
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Info card at top
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Nearby Devices",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap the scan button to discover devices. Make sure WiFi is enabled.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Scanning indicator
            if (isScanning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scanning...", style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Device list
            if (devices.isEmpty() && !isScanning) {
                // Empty state
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp)
                ) {
                    items(devices) { device ->
                        DeviceItem(
                            device = device,
                            onClick = { onDeviceClick(device) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * COMPOSABLE - Individual Device Item
 * Shows one device in the list
 */
@Composable
fun DeviceItem(
    device: WifiP2pDevice,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Device icon
            Text(
                text = "📱",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Device info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.deviceName.ifEmpty { "Unknown Device" },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = when (device.status) {
                        WifiP2pDevice.AVAILABLE -> "Available - Tap to connect"
                        WifiP2pDevice.INVITED -> "Invitation sent..."
                        WifiP2pDevice.CONNECTED -> "Connected ✓"
                        WifiP2pDevice.FAILED -> "Connection failed"
                        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
                        else -> "Unknown status"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Arrow indicator
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * COMPOSABLE - Empty State
 * Shown when no devices are found
 */
@Composable
fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "📱",
            style = MaterialTheme.typography.displayLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No devices found",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Tap the scan button to discover nearby devices",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
