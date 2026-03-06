package com.wifip2p.wifichat.Network



import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.wifip2p.wifichat.uii.ChatActivity
import com.wifip2p.wifichat.uii.MainActivity

/**
 * WiFiDirectBroadcastReceiver
 *
 * WHAT IS THIS?
 * Listens for WiFi P2P events from the Android system
 * When something happens (device found, connected, etc.), this catches it
 *
 * WHY NEEDED?
 * WiFi P2P operations are asynchronous - they happen in the background
 * The system tells us about events through broadcasts
 * This receiver catches those broadcasts and reacts accordingly
 *
 * WORKS WITH COMPOSE:
 * Even though we're using Compose for UI, we still need this receiver
 * because WiFi P2P is a system-level feature, not a UI component
 */
class WiFiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val activity: MainActivity
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {

            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                handleWifiP2pStateChanged(intent)
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                handlePeersChanged()
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                handleConnectionChanged(intent)
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                handleThisDeviceChanged()
            }
        }
    }

    /* ---------------- WIFI STATE ---------------- */

    private fun handleWifiP2pStateChanged(intent: Intent) {
        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)

        when (state) {
            WifiP2pManager.WIFI_P2P_STATE_ENABLED -> {
                Toast.makeText(activity, "WiFi P2P enabled", Toast.LENGTH_SHORT).show()
            }
            WifiP2pManager.WIFI_P2P_STATE_DISABLED -> {
                Toast.makeText(
                    activity,
                    "Please enable WiFi",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /* ---------------- PEERS ---------------- */

    private fun handlePeersChanged() {
        if (ActivityCompat.checkSelfPermission(
                activity,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        manager.requestPeers(channel) { peerList ->
            activity.updateDeviceList(peerList.deviceList)
        }
    }

    /* ---------------- CONNECTION ---------------- */

    private fun handleConnectionChanged(intent: Intent) {

        val networkInfo =
            intent.getParcelableExtra<NetworkInfo>(
                WifiP2pManager.EXTRA_NETWORK_INFO
            )

        if (networkInfo?.isConnected == true) {

            // 🔒 VERY IMPORTANT GUARD
            if (MainActivity.chatOpened) return
            MainActivity.chatOpened = true


            val chatIntent = Intent(activity, ChatActivity::class.java)
            activity.startActivity(chatIntent)

        } else {
            // Connection lost
            MainActivity.chatOpened = false
        }
    }

    /* ---------------- DEVICE INFO ---------------- */

    private fun handleThisDeviceChanged() {
        // optional
    }
}
