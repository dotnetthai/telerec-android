package com.nlmthai.telerec.ui

import android.graphics.SurfaceTexture
import android.util.Size
import android.view.TextureView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Shows the camera's long-lived SurfaceTexture. The view only borrows it: when
 * the view goes away it hands the texture back unreleased, so the camera session
 * (and a recording) carries on without a preview.
 */
@Composable
fun CameraPreview(texture: SurfaceTexture, bufferSize: Size?, modifier: Modifier = Modifier) {
    // The buffer is in sensor orientation (landscape); the phone UI is portrait.
    val ratio = bufferSize?.let { it.height.toFloat() / it.width } ?: (9f / 16f)
    AndroidView(
        modifier = modifier.aspectRatio(ratio),
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        if (surface !== texture) setSurfaceTexture(texture)
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

                    /** Keep the camera's texture; release only one the view made itself. */
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = surface !== texture

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
                setSurfaceTexture(texture)
            }
        },
    )
}
