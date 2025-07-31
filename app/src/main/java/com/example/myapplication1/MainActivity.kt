package com.example.myapplication1

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.myapplication1.service.FTPService
import com.example.myapplication1.ui.theme.MyApplication1Theme
import java.net.Inet4Address
import java.net.NetworkInterface


class MainActivity : ComponentActivity() {

    private var isServerRunning = false
    private var rootDirectoryUri: Uri? = null

    private var status by mutableStateOf("状态：无")
    private var toggleFtp by mutableStateOf("开始FTP服务")
    private var ipAddress by mutableStateOf("")

    // ActivityResultLauncher 用于获取文件夹选择结果
    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                // 获取持久化权限，以便服务可以在后台访问
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                rootDirectoryUri = uri
                //Toast.makeText(this, "选择文件夹: $uri", Toast.LENGTH_SHORT).show()
                // 选择文件夹后，直接启动服务
                startFtpService()
            } else {
                Toast.makeText(this, "没有选择文件夹", Toast.LENGTH_SHORT).show()
            }
        }
    private val intentPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    // ActivityResultLauncher 用于请求通知权限
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            var notPermissions = it.filter {
                it.value == false
            }.toList().firstOrNull()
            if (notPermissions != null) {
                Toast.makeText(this, "服务的运行必须要有相应的权限", Toast.LENGTH_LONG)
                    .show()
            } else {
                launchFolderPicker()
            }
        }

    // 定义广播接收器
    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val isConnected = intent.getBooleanExtra("isConnected", false)
            updateUi(isConnected)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter("com.example.myapplication1")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(
                receiver,
                filter,
                RECEIVER_NOT_EXPORTED // 或 RECEIVER_EXPORTED
            );
        } else {
            registerReceiver(receiver, filter); // 旧版本兼容
        }

        enableEdgeToEdge()
        setContent {
            MyApplication1Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    FtpServiceView(
                        name = "",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        // 初始时刷新IP
        refreshIpAddress()
    }

    private fun toggleServer() {
        if (isServerRunning) {
            stopFtpService()
        } else {
            checkAndGetPermission()
        }
    }

    private fun checkAndGetPermission() {
        var permissionList = mutableListOf<String>(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                if (!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    Toast.makeText(this, "需要通知权限", Toast.LENGTH_LONG)
                        .show()
                    intentPickerLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    })
                }
            }

            permissionList.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        when {
            getWifiIpAddress() == null -> {
                launchOpenWifiPicker()
            }

//            ContextCompat.checkSelfPermission(
//                this,
//                Manifest.permission.ACCESS_COARSE_LOCATION
//            ) == PackageManager.PERMISSION_GRANTED -> {
//            }

            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) -> {
                Toast.makeText(this, "需要位置权限", Toast.LENGTH_LONG).show()
                intentPickerLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            }

            else -> {
                requestPermissionLauncher.launch(permissionList.toTypedArray())
            }
        }
    }

    private fun launchOpenWifiPicker() {
        intentPickerLauncher.launch(Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    private fun launchFolderPicker() {
        // 启动系统文件夹选择器
        folderPickerLauncher.launch(null)
    }

    private fun startFtpService() {
        val intent = Intent(this, FTPService::class.java).apply {
            action = FTPService.ACTION_START
            putExtra(FTPService.EXTRA_ROOT_URI, rootDirectoryUri.toString())
        }
        ContextCompat.startForegroundService(this, intent)
        //updateUi(true)
    }

    private fun stopFtpService() {
        val intent = Intent(this, FTPService::class.java).apply {
            action = FTPService.ACTION_STOP
        }
        startService(intent) // 只需要startService来发送停止命令
        updateUi(false)
    }

    private fun updateUi(isRunning: Boolean) {
        isServerRunning = isRunning
        if (isRunning) {
            status = "状态：运行在端口 2333"
            toggleFtp = "停止FTP服务"
        } else {
            status = "状态：停止"
            toggleFtp = "开始FTP服务"
            rootDirectoryUri = null // 服务器停止后重置
        }
    }

    private fun refreshIpAddress() {
        val ip = getWifiIpAddress()
        ipAddress = if (ip != null) {
            "IP地址：$ip"
        } else {
            "IP地址：没有连接Wi-Fi"
        }
    }

    private fun getWifiIpAddress(): String? {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            try {
                val networkInterfaces = NetworkInterface.getNetworkInterfaces()
                while (networkInterfaces.hasMoreElements()) {
                    val networkInterface = networkInterfaces.nextElement()
                    if (networkInterface.isUp && !networkInterface.isLoopback) {
                        val inetAddresses = networkInterface.inetAddresses
                        while (inetAddresses.hasMoreElements()) {
                            val inetAddress = inetAddresses.nextElement()
                            if (inetAddress is Inet4Address) {
                                return inetAddress.hostAddress
                            }
                        }
                    }
                }
            } catch (ex: Exception) {
                Toast.makeText(this, "获取IP错误：${ex.message}", Toast.LENGTH_LONG).show()
            }
        }
        return null
    }

    override fun onResume() {
        super.onResume()
        // 当应用返回前台时刷新IP地址
        refreshIpAddress()
    }

    @SuppressLint("UnusedContentLambdaTargetStateParameter")
    @Composable
    fun FtpServiceView(name: String, modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val networkCallback = remember {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    getWifiIpAddress()
                }

                override fun onLost(network: Network) {
                    ipAddress = "WIFI已断开"
                }
            }
        }
        DisposableEffect(Unit) {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            connectivityManager.registerNetworkCallback(request, networkCallback)

            onDispose {
                connectivityManager.unregisterNetworkCallback(networkCallback)
                isServerRunning = false
                stopFtpService()
            }
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "FTP服务",
                modifier = modifier,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = ipAddress,
                fontSize = 18.sp,
                modifier = Modifier.padding(0.dp, 24.dp, 0.dp, 0.dp)
            )
            Text(
                text = status,
                fontSize = 18.sp,
                modifier = Modifier.padding(0.dp, 16.dp, 0.dp, 0.dp)
            )

            Button(
                modifier = Modifier.padding(0.dp, 32.dp, 0.dp, 0.dp),
                onClick = {
                    toggleServer()
                }
            ) {
                Text(toggleFtp)
            }
        }
    }

    @Preview(showBackground = true)
    @Composable
    fun GreetingPreview() {
        MyApplication1Theme {
            FtpServiceView("Android")
        }
    }
}