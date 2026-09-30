package com.nlmthai.telerec.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import com.nlmthai.telerec.ui.CameraScreen

class MainActivity : ComponentActivity() {
    private val model get() = (application as TeleRecApp).model
    private val cameraGranted = mutableStateOf(false)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermission()
        if (cameraGranted.value) CaptureService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A watch press should never find the phone asleep while TeleRec is on screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshPermission()
        // POST_NOTIFICATIONS only exists from Android 13; asking for it earlier is always "denied".
        val wanted = listOfNotNull(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null,
        )
        if (wanted.any { !has(it) }) permissions.launch(wanted.toTypedArray())
        setContent {
            CameraScreen(
                model = model,
                cameraGranted = cameraGranted.value,
                openAppSettings = {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        refreshPermission()
        // Starting (again) while on screen is what lets the service use the camera later.
        if (cameraGranted.value) CaptureService.start(this)
        model.watch.refreshDevices()
    }

    private fun refreshPermission() {
        cameraGranted.value = has(Manifest.permission.CAMERA)
    }

    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
