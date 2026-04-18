package com.wifip2p.wifichat.uii

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.wifip2p.wifichat.ui.theme.WifiChatTheme
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

    @Volatile
    private var fileServerStarted = false

    private var connectedDeviceName: String? = null
    private val isGroupOwnerState = mutableStateOf(false)
    private var peerIpAddress: String? = null

    private val messagesState = mutableStateListOf<ChatMessage>()

    private var socket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var fileServerSocket: ServerSocket? = null
    private lateinit var writer: PrintWriter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        connectedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)

        setContent {
            WifiChatTheme {
                ChatScreen(
                    deviceName = connectedDeviceName ?: "Unknown",
                    isGroupOwner = isGroupOwnerState.value,
                    messages = messagesState,
                    onSendMessage = { sendMessage(it) },
                    onSendFile = { sendFile(it) },
                    onBackClick = { finish() }
                )
            }
        }

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
            isGroupOwnerState.value = true
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
                peerIpAddress = socket!!.inetAddress.hostAddress

                setupStreams(socket!!)
                startReceiver(socket!!)
                startFileReceiver()

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
        peerIpAddress = ip
        Thread {
            try {
                socket = Socket(ip, 8888)

                setupStreams(socket!!)
                startReceiver(socket!!)
                startFileReceiver()

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

    /* ---------------- TEXT RECEIVER ---------------- */

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

    /* ---------------- FILE RECEIVER ---------------- */

    private fun startFileReceiver() {
        if (fileServerStarted) return
        fileServerStarted = true

        Thread {
            try {
                fileServerSocket = ServerSocket(8889)
                while (true) {
                    val fileSocket = fileServerSocket!!.accept()
                    Thread { receiveFile(fileSocket) }.start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun receiveFile(fileSocket: Socket) {
        try {
            val input = DataInputStream(fileSocket.getInputStream())
            val header = input.readUTF()
            val parts = header.split(":")
            if (parts.size < 3) {
                Log.w("CHAT", "Malformed file transfer header: $header")
                runOnUiThread { addSystemMessage("File receive failed: invalid header") }
                return
            }

            // Header format: "<fileName>:<fileSize>:<mimeType>"
            // fileSize is a number and mimeType contains no colons, so splitting from the
            // right correctly handles colons that may appear inside the filename.
            val mimeType = parts.last()
            val fileSize = parts[parts.size - 2].toLong()
            val fileName = parts.dropLast(2).joinToString(":")

            val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: filesDir
            val file = File(downloadsDir, fileName)

            FileOutputStream(file).use { fos ->
                val buffer = ByteArray(8192)
                var remaining = fileSize
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read == -1) break
                    fos.write(buffer, 0, read)
                    remaining -= read
                }
            }
            fileSocket.close()

            val messageType = messageTypeFromMimeType(mimeType)
            runOnUiThread {
                messagesState.add(
                    ChatMessage(
                        text = fileName,
                        isSentByMe = false,
                        messageType = messageType,
                        filePath = file.absolutePath,
                        fileName = fileName,
                        fileSize = fileSize
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /* ---------------- FILE SENDER ---------------- */

    private fun sendFile(uri: Uri) {
        val peerIp = peerIpAddress
        if (peerIp == null) {
            addSystemMessage("Not connected yet")
            return
        }

        var fileName = "file"
        var fileSize = 0L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                cursor.moveToFirst()
                fileName = if (nameIndex >= 0) cursor.getString(nameIndex) ?: "file" else "file"
                fileSize = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
            }
        } catch (e: Exception) {
            addSystemMessage("Could not read file info")
            return
        }

        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val messageType = messageTypeFromMimeType(mimeType)

        messagesState.add(
            ChatMessage(
                text = fileName,
                isSentByMe = true,
                messageType = messageType,
                filePath = uri.toString(),
                fileName = fileName,
                fileSize = fileSize
            )
        )

        Thread {
            try {
                val fileSocket = Socket(peerIp, 8889)
                val output = DataOutputStream(fileSocket.getOutputStream())
                output.writeUTF("$fileName:$fileSize:$mimeType")
                contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(8192)
                    var bytes: Int
                    while (input.read(buffer).also { bytes = it } != -1) {
                        output.write(buffer, 0, bytes)
                    }
                }
                output.flush()
                fileSocket.close()
            } catch (e: Exception) {
                runOnUiThread { addSystemMessage("File send failed: ${e.javaClass.simpleName} - ${e.message ?: "check connection"}") }
            }
        }.start()
    }

    /* ---------------- TEXT SEND ---------------- */

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
            fileServerSocket?.close()

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


/* ---------------- DATA MODELS ---------------- */

enum class MessageType { TEXT, IMAGE, FILE }

fun messageTypeFromMimeType(mimeType: String): MessageType =
    if (mimeType.startsWith("image/")) MessageType.IMAGE else MessageType.FILE

data class ChatMessage(
    val text: String,
    val isSentByMe: Boolean,
    val isSystemMessage: Boolean = false,
    val messageType: MessageType = MessageType.TEXT,
    // Received files: absolute file path on disk. Sent files: content URI string.
    // openFile() supports both.
    val filePath: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun getFormattedTime(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

    fun getFormattedSize(): String {
        val bytes = fileSize ?: return ""
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}


/* ---------------- UI ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    deviceName: String,
    isGroupOwner: Boolean,
    messages: List<ChatMessage>,
    onSendMessage: (String) -> Unit,
    onSendFile: (Uri) -> Unit,
    onBackClick: () -> Unit
) {
    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { onSendFile(it) } }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { onSendFile(it) } }

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
                },
                onImageClick = { imageLauncher.launch("image/*") },
                onFileClick = { fileLauncher.launch("*/*") }
            )
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val context = LocalContext.current
    when {
        message.isSystemMessage -> {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
        message.messageType == MessageType.IMAGE -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = if (message.isSentByMe) Arrangement.End else Arrangement.Start
            ) {
                AsyncImage(
                    model = message.filePath,
                    contentDescription = message.fileName,
                    modifier = Modifier
                        .widthIn(max = 250.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .then(
                            if (message.filePath != null)
                                Modifier.clickable { openFile(context, message.filePath, message.fileName ?: message.text) }
                            else Modifier
                        ),
                    contentScale = ContentScale.Crop
                )
            }
        }
        message.messageType == MessageType.FILE -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = if (message.isSentByMe) Arrangement.End else Arrangement.Start
            ) {
                Card(
                    modifier = Modifier
                        .widthIn(max = 250.dp)
                        .then(
                            if (message.filePath != null)
                                Modifier.clickable { openFile(context, message.filePath, message.fileName ?: message.text) }
                            else Modifier
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (message.isSentByMe) Color(0xFF2196F3) else Color(0xFFE8E8E8)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            tint = if (message.isSentByMe) Color.White else Color(0xFF2196F3)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                message.fileName ?: message.text,
                                color = if (message.isSentByMe) Color.White else Color.Black,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (message.fileSize != null && message.fileSize > 0) {
                                Text(
                                    message.getFormattedSize(),
                                    color = if (message.isSentByMe) Color.White.copy(alpha = 0.7f) else Color.Gray,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
        message.isSentByMe -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Start
            ) {
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
    onSendClick: () -> Unit,
    onImageClick: () -> Unit,
    onFileClick: () -> Unit
) {
    Row(
        modifier = Modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onImageClick) {
            Icon(Icons.Default.Image, contentDescription = "Send image")
        }
        IconButton(onClick = onFileClick) {
            Icon(Icons.Default.AttachFile, contentDescription = "Attach file")
        }
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

private fun openFile(context: Context, filePath: String, fileName: String) {
    try {
        val isContentUri = filePath.startsWith("content://") || filePath.startsWith("file://")
        val uri = if (isContentUri) {
            Uri.parse(filePath)
        } else {
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
                return
            }
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }
        val extension = fileName.substringAfterLast('.', "")
        val mimeTypeFromName = if (extension.isNotEmpty())
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        else null
        val mimeType = context.contentResolver.getType(uri) ?: mimeTypeFromName ?: "*/*"

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, fileName, uri)
        }
        val chooser = Intent.createChooser(intent, "Open with")
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(chooser)
        } else {
            Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Cannot open file: ${e.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
    }
}
