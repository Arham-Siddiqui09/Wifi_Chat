package com.wifip2p.wifichat.uii

import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.wifip2p.wifichat.ui.theme.WifiChatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*



class ChatActivity : ComponentActivity(),
    WifiP2pManager.ConnectionInfoListener {

    @Volatile
    private var connectionStarted = false

    @Volatile
    private var receiverStarted = false

    private var connectedDeviceName: String? = null

    private val messagesState = mutableStateListOf<ChatMessage>()

    private var socket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private lateinit var writer: PrintWriter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        connectedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)

        setContent {
            WifiChatTheme {
                ChatScreen(
                    deviceName = connectedDeviceName ?: "Unknown",
                    isGroupOwner = false,
                    messages = messagesState,
                    onSendMessage = { sendMessage(it) },
                    onBackClick = { finish() }
                )
            }
        }

        // 🔥 THIS IS THE MOST IMPORTANT LINE
        MainActivity.sharedManager.requestConnectionInfo(
            MainActivity.sharedChannel,
            this
        )
    }

    /* ---------- WIFI P2P CALLBACK ---------- */

    override fun onConnectionInfoAvailable(info: WifiP2pInfo) {

        if (connectionStarted) return
        connectionStarted = true

        if (info.isGroupOwner) {
            addSystemMessage("You are host")
            startServer()
        } else {
            addSystemMessage("Connecting to host")
            startClient(info.groupOwnerAddress.hostAddress)
        }
    }

    /* ---------------- SERVER ---------------- */

    private fun startServer() {
        Thread {
            try {
                serverSocket = ServerSocket(8888)
                socket = serverSocket!!.accept()

                setupStreams(socket!!)
                startReceiver(socket!!)

                runOnUiThread {
                    addSystemMessage("Client connected")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    /* ---------------- CLIENT ---------------- */

    private fun startClient(ip: String) {
        Thread {
            try {
                socket = Socket(ip, 8888)

                setupStreams(socket!!)
                startReceiver(socket!!)

                runOnUiThread {
                    addSystemMessage("Connected to host")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    /* ---------------- STREAM SETUP ---------------- */

    private fun setupStreams(socket: Socket) {
        writer = PrintWriter(socket.getOutputStream(), true)
    }

    /* ---------------- RECEIVER ---------------- */

    private fun startReceiver(socket: Socket) {

        if (receiverStarted) return
        receiverStarted = true

        Thread {
            try {
                val reader = BufferedReader(
                    InputStreamReader(socket.getInputStream())
                )

                while (true) {
                    val msg = reader.readLine() ?: break

                    runOnUiThread {
                        messagesState.add(
                            ChatMessage(msg, isSentByMe = false)
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    /* ---------------- SEND ---------------- */

    private fun sendMessage(text: String) {

        if (!::writer.isInitialized) {
            addSystemMessage("Not connected yet")
            return
        }

        messagesState.add(
            ChatMessage(text, isSentByMe = true)
        )

        Thread {
            try {
                writer.println(text)
            } catch (e: Exception) {
                runOnUiThread {
                    addSystemMessage("Send failed")
                }
            }
        }.start()
    }

    private fun addSystemMessage(text: String) {
        messagesState.add(
            ChatMessage(text, isSentByMe = false, isSystemMessage = true)
        )
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            socket?.close()
            serverSocket?.close()

            // 🔥 REMOVE P2P GROUP (VERY IMPORTANT)
            MainActivity.sharedManager.removeGroup(
                MainActivity.sharedChannel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d("CHAT", "Group removed")
                    }

                    override fun onFailure(reason: Int) {
                        Log.d("CHAT", "Group remove failed: $reason")
                    }
                }
            )

            MainActivity.chatOpened = false

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    companion object {
        const val EXTRA_DEVICE_NAME = "device_name"
    }
}


/* ---------------- UI (UNCHANGED) ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    deviceName: String,
    isGroupOwner: Boolean,
    messages: List<ChatMessage>,
    onSendMessage: (String) -> Unit,
    onBackClick: () -> Unit
) {
    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(deviceName)
                        Text(
                            if (isGroupOwner) "Connected (Host)" else "Connected",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFF5F5F5))
                .imePadding()
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(8.dp)
            ) {
                items(messages) {
                    MessageBubble(it)
                }
            }

            MessageInput(
                messageText = messageText,
                onMessageChange = { messageText = it },
                onSendClick = {
                    if (messageText.isNotBlank()) {
                        onSendMessage(messageText)
                        messageText = ""
                    }
                }
            )
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    when {
        message.isSystemMessage -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(message.text)
            }
        }
        message.isSentByMe -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    message.text,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color(0xFF2196F3), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                )
            }
        }
        else -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Text(
                    message.text,
                    modifier = Modifier
                        .background(Color(0xFFE8E8E8), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                )
            }
        }
    }
}

@Composable
fun MessageInput(
    messageText: String,
    onMessageChange: (String) -> Unit,
    onSendClick: () -> Unit
) {
    Row(
        modifier = Modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = messageText,
            onValueChange = onMessageChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type a message...") }
        )
        IconButton(onClick = onSendClick, enabled = messageText.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, null)
        }
    }
}

data class ChatMessage(
    val text: String,
    val isSentByMe: Boolean,
    val isSystemMessage: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun getFormattedTime(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
