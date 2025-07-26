package com.example.myapplication1.service

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.myapplication1.MainActivity
import com.example.myapplication1.ftp.SafFileSystemFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.FtpException
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.TransferRatePermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.net.Inet4Address
import java.net.NetworkInterface


class FTPService : Service() {
    private var ftpServer: FtpServer? = null
    private val port = 2333 // 你可以自定义端口号
    private val scope = CoroutineScope(Dispatchers.IO) // 在IO线程中运行服务器

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_ROOT_URI = "EXTRA_ROOT_URI"
        const val NOTIFICATION_CHANNEL_ID = "FtpServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        when (intent?.action) {
            ACTION_START -> {
                val rootUriString = intent.getStringExtra(EXTRA_ROOT_URI)
                if (rootUriString != null) {
                    val rootUri = Uri.parse(rootUriString)
                    startServer(rootUri)
                }
            }

            ACTION_STOP -> {
                stopServer()
            }
        }
        return START_STICKY
//        return START_NOT_STICKY
    }

    //    @SuppressLint("ForegroundServiceType")
    private fun startServer(rootUri: Uri) {
        scope.launch {
            try {

                // 创建携带数据的Intent
                val intent = Intent("com.example.myapplication1")
                intent.putExtra("isConnected", true) // 添加数据
                intent.setPackage("com.example.myapplication1") // 指定目标包名（可选）

                val serverFactory = FtpServerFactory()
                val listenerFactory = ListenerFactory()

                val ipAddress = getIpAddress()
                val notification = createNotification("FTP服务正在运行在 ftp://$ipAddress:$port")

                listenerFactory.port = port

                // 定义一个匿名用户
                val baseUser = BaseUser()
                baseUser.name = "anonymous" // 匿名用户名
                baseUser.password = null // 无密码

                // Android 11+ 需要通过 DocumentFile 访问
                val homeDir = getPathFromUri(applicationContext, rootUri)
                if (homeDir == null) {
                    Log.e("FtpService", "Could not resolve path from URI")
                    //stopForeground(true)
                    startForeground(NOTIFICATION_ID, notification)
                    stopSelf()

                    intent.putExtra("isConnected", false)
                    sendBroadcast(intent)

                    return@launch
                }
                baseUser.homeDirectory = homeDir

                // 授予写入权限
                val authorities = mutableListOf<Authority>()
                authorities.add(WritePermission())
                authorities.add(ConcurrentLoginPermission(0, 0))
                authorities.add(TransferRatePermission(0, 0))

                baseUser.authorities = authorities

                // 将用户添加到服务器
                serverFactory.userManager.save(baseUser)
                serverFactory.addListener("default", listenerFactory.createListener())

                serverFactory.fileSystem = SafFileSystemFactory(this@FTPService,rootUri)

                ftpServer = serverFactory.createServer()
                ftpServer?.start()

                startForeground(NOTIFICATION_ID, notification)

                sendBroadcast(intent)
                Log.d("FtpService", "Server started on ftp://$ipAddress:$port")
            } catch (e: FtpException) {
                Log.e("FtpService", "Failed to start FTP server", e)
                stopServer() // 如果启动失败，清理并停止服务
            } catch (e: Exception) {
                Log.e("FtpService", "Failed to start FTP server", e)
                stopServer() // 如果启动失败，清理并停止服务
            }
        }
    }

    // 在 FtpService.kt 中

    private fun getPathFromUri(context: Context, uri: Uri): String? {
        // 检查Uri的authority是否是外部存储提供者
        if ("com.android.externalstorage.documents" == uri.authority) {
//        if (false) {

            // 【关键修正】: 使用 getTreeDocumentId 来处理文件夹选择器返回的树URI
            val docId = DocumentsContract.getTreeDocumentId(uri)

            val split = docId.split(":").toTypedArray()
            val type = split.getOrNull(0)

            if ("primary".equals(type, ignoreCase = true)) {
                val path = split.getOrNull(1)
                if (path != null) {
                    return "${Environment.getExternalStorageDirectory()}/$path"
                }
            }
            // 这里可以添加对SD卡等其他存储类型的支持 (例如 "1234-5678".equals(type, ...))
        }

        Log.e(
            "FtpService",
            "Could not resolve path from URI: $uri. This URI format is not supported."
        )
        return null
    }

    private fun stopServer() {
        scope.launch {
            ftpServer?.stop()
            ftpServer = null
            stopForeground(true)
            stopSelf()
            Log.d("FtpService", "Server stopped")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "FTP服务消息",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("FTP服务启动")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.star_on) // 替换为你的图标
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun getIpAddress(): String? {
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                // 仅查找Wi-Fi接口
                if (networkInterface.name.contains("wlan", ignoreCase = true)) {
                    val inetAddresses = networkInterface.inetAddresses
                    while (inetAddresses.hasMoreElements()) {
                        val inetAddress = inetAddresses.nextElement()
                        if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                            return inetAddress.hostAddress
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e("IPAddress", "IP address not found", ex)
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}