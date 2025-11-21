package com.snakesan.neonflux

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            FluxMobileUI()
        }
    }

    @Composable
    fun FluxMobileUI() {
        val context = LocalContext.current
        var hasPermission by remember { mutableStateOf(checkBluetoothPermission(context)) }

        // Permission Launcher
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { isGranted ->
                hasPermission = isGranted
                if (isGranted) {
                    startFluxService()
                }
            }
        )

        // Try to start immediately if we already have permission
        LaunchedEffect(Unit) {
            if (hasPermission) {
                startFluxService()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("NEON FLUX MOBILE", color = Color.White, fontSize = 24.sp)
                Spacer(modifier = Modifier.height(20.dp))

                if (hasPermission) {
                    Text("Service Running (Background Safe)", color = Color.Green)
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = { startFluxService() }) {
                        Text("FORCE RESTART")
                    }
                } else {
                    Text("Permission Required", color = Color.Red)
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                    }) {
                        Text("ALLOW CONNECTION")
                    }
                }
            }
        }
    }

    private fun checkBluetoothPermission(context: android.content.Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required on older Android
        }
    }

    private fun startFluxService() {
        val intent = Intent(this, FluxService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}