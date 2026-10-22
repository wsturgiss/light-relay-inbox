package com.thelightphone.sdk.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.ExifInterface
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * A photo taken with [LightPhotoCapture]: an upright JPEG plus the exposure settings it was taken
 * with, where the camera reported them.
 */
class LightCapturedPhoto(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val exposureTimeSeconds: Double?,
    val iso: Int?,
    val aperture: Double?,
)

/**
 * Full-screen camera: live back-camera preview, top-bar back, and a shutter button in the bottom
 * bar. Calls [onCaptured] once per press of the shutter.
 *
 * The JPEG is rotated upright for a portrait phone. [maxLongEdgePx] downscales it; null keeps the
 * camera's full resolution.
 *
 * Host apps must declare [Manifest.permission.CAMERA].
 */
@Composable
fun LightPhotoCapture(
    onCaptured: (LightCapturedPhoto) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Take Photo",
    maxLongEdgePx: Int? = null,
    checkCameraPermission: suspend () -> Result<Boolean>,
    launchCameraPermissionRequest: suspend () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = LightThemeTokens.colors
    var launchedPermissionRequest by remember { mutableStateOf(false) }
    var uiState by remember { mutableStateOf(LightPhotoUiState.Loading) }
    var isCapturing by remember { mutableStateOf(false) }
    val onCapturedState = rememberUpdatedState(onCaptured)
    val onBackState = rememberUpdatedState(onBack)

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val permissionCheck = checkCameraPermission()
            if (permissionCheck.isFailure) {
                uiState = LightPhotoUiState.PermissionError
            } else if (permissionCheck.getOrNull() == false) {
                if (!launchedPermissionRequest) {
                    launchCameraPermissionRequest()
                    launchedPermissionRequest = true
                } else {
                    uiState = LightPhotoUiState.PermissionDenied
                }
            } else {
                uiState = LightPhotoUiState.Active
            }
        }
    }

    val cameraController = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        }
    }
    val processingExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    DisposableEffect(cameraController) {
        onDispose {
            cameraController.unbind()
            processingExecutor.shutdown()
        }
    }

    fun capture() {
        if (isCapturing || uiState != LightPhotoUiState.Active) return
        isCapturing = true
        cameraController.takePicture(
            processingExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val photo = runCatching { image.use { toCapturedPhoto(it, maxLongEdgePx) } }.getOrNull()
                    mainExecutor.execute {
                        isCapturing = false
                        if (photo != null) onCapturedState.value(photo)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    mainExecutor.execute { isCapturing = false }
                }
            },
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = { onBackState.value() },
                ),
                center = LightTopBarCenter.Text(title),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when (uiState) {
                    LightPhotoUiState.Active -> PhotoCameraPreview(
                        cameraController = cameraController,
                        lifecycleOwner = lifecycleOwner,
                        modifier = Modifier.fillMaxSize(),
                    )

                    LightPhotoUiState.Loading -> CircularProgressIndicator()

                    else -> LightText(
                        text = if (uiState == LightPhotoUiState.PermissionDenied) {
                            "Camera permission is required to take photos."
                        } else {
                            "Error: unable to request camera permission!"
                        },
                        variant = LightTextVariant.Copy,
                        align = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp()),
                    )
                }
            }

            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.CAMERA,
                        onClick = if (uiState == LightPhotoUiState.Active && !isCapturing) ::capture else null,
                        contentDescription = "Take photo",
                    ),
                ),
            )
        }
    }
}

@Composable
private fun PhotoCameraPreview(
    cameraController: LifecycleCameraController,
    lifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                controller = cameraController
                addOnAttachStateChangeListener(
                    object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: View) {
                            runCatching { cameraController.bindToLifecycle(lifecycleOwner) }
                        }

                        override fun onViewDetachedFromWindow(view: View) {
                            cameraController.unbind()
                        }
                    },
                )
            }
        },
    )
}

private fun toCapturedPhoto(image: ImageProxy, maxLongEdgePx: Int?): LightCapturedPhoto {
    check(image.format == ImageFormat.JPEG) { "Unexpected capture format ${image.format}" }
    val buffer = image.planes[0].buffer
    val original = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val rotation = image.imageInfo.rotationDegrees

    val exif = ExifInterface(ByteArrayInputStream(original))
    val exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0).takeIf { it > 0 }
    val iso = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, -1).takeIf { it > 0 }
    val aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0).takeIf { it > 0 }

    val longEdge = maxOf(image.width, image.height)
    val scale = maxLongEdgePx?.let { minOf(1f, it.toFloat() / longEdge) } ?: 1f
    if (rotation == 0 && scale == 1f) {
        return LightCapturedPhoto(original, image.width, image.height, exposureTime, iso, aperture)
    }

    val decoded = BitmapFactory.decodeByteArray(original, 0, original.size)
        ?: error("Camera returned an undecodable image")
    val matrix = Matrix().apply {
        postScale(scale, scale)
        postRotate(rotation.toFloat())
    }
    val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    val jpeg = ByteArrayOutputStream().use { out ->
        upright.compress(Bitmap.CompressFormat.JPEG, 95, out)
        out.toByteArray()
    }
    val result = LightCapturedPhoto(jpeg, upright.width, upright.height, exposureTime, iso, aperture)
    if (upright !== decoded) upright.recycle()
    decoded.recycle()
    return result
}

private enum class LightPhotoUiState {
    Loading, PermissionError, PermissionDenied, Active
}
