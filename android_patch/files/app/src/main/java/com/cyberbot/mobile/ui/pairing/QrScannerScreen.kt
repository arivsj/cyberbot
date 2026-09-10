package com.cyberbot.mobile.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cyberbot.mobile.core.model.PairingPayload
import com.cyberbot.mobile.ui.common.ErrorPane
import com.cyberbot.mobile.ui.common.ScreenScaffold
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val ANALYZE_WIDTH = 1280
private const val ANALYZE_HEIGHT = 720

/**
 * Leitor de QR Code do pareamento: camera traseira + CameraX + ZXing (sem Play Services).
 */
@Composable
fun QrScannerRoute(
    onScanned: (PairingPayload) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    val handled = remember { AtomicBoolean(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) error = "Sem permissao de camera nao da para ler o QR Code."
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    BackHandler(onBack = onClose)

    ScreenScaffold(title = "Ler QR Code") { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasPermission) {
                Text(
                    "O app precisa da camera para ler o QR Code do PC.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Permitir camera")
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                ) {
                    CameraPreview(
                        handled = handled,
                        onError = { error = it },
                        onDecoded = { text ->
                            val payload = PairingPayload.parse(text)
                            if (payload != null) {
                                handled.set(true)
                                onScanned(payload)
                            } else {
                                error = "QR Code nao reconhecido. Use o QR gerado no desktop (Configurar P2P)."
                                handled.set(false)
                            }
                        },
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(240.dp)
                            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                    )
                }
                Text(
                    "Aponte para o QR Code do desktop (Configurar P2P > Gerar codigo + QR).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            error?.let { ErrorPane(it) }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("Cancelar")
            }
        }
    }
}

@Composable
private fun CameraPreview(
    handled: AtomicBoolean,
    onDecoded: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                )
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)

                    @Suppress("DEPRECATION")
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(ANALYZE_WIDTH, ANALYZE_HEIGHT))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { proxy ->
                        analyze(proxy, reader, handled, mainHandler, onDecoded)
                    }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (failure: Exception) {
                    onError(failure.message ?: "Falha ao abrir a camera.")
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

private fun analyze(
    proxy: ImageProxy,
    reader: MultiFormatReader,
    handled: AtomicBoolean,
    mainHandler: Handler,
    onDecoded: (String) -> Unit,
) {
    try {
        if (handled.get()) return
        val width = proxy.width
        val height = proxy.height
        val rotation = proxy.imageInfo.rotationDegrees
        val luminance = luminance(proxy) ?: return
        val rotated = rotate(luminance, width, height, rotation)
        val targetWidth = if (rotation % 180 == 0) width else height
        val targetHeight = if (rotation % 180 == 0) height else width
        val source = PlanarYUVLuminanceSource(
            rotated, targetWidth, targetHeight, 0, 0, targetWidth, targetHeight, false,
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val result = runCatching { reader.decodeWithState(bitmap) }.getOrNull()
        // compareAndSet: garante UM único disparo, mesmo que vários frames decodifiquem
        // antes de a tela trocar (senão o app pareava duas vezes, e o 2º par dava "código expirado").
        if (result?.text?.isNotBlank() == true && handled.compareAndSet(false, true)) {
            val text = result.text
            mainHandler.post { onDecoded(text) }
        }
    } catch (_: Throwable) {
    } finally {
        reader.reset()
        proxy.close()
    }
}

private fun luminance(proxy: ImageProxy): ByteArray? {
    val plane = proxy.planes.firstOrNull() ?: return null
    val width = proxy.width
    val height = proxy.height
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val out = ByteArray(width * height)

    if (pixelStride == 1 && rowStride == width) {
        val size = minOf(out.size, buffer.remaining())
        buffer.get(out, 0, size)
        return out
    }

    val row = ByteArray(rowStride)
    for (y in 0 until height) {
        val available = minOf(rowStride, buffer.remaining())
        if (available <= 0) break
        buffer.get(row, 0, available)
        for (x in 0 until width) {
            val index = x * pixelStride
            if (index < available) out[y * width + x] = row[index]
        }
    }
    return out
}

private fun rotate(data: ByteArray, width: Int, height: Int, degrees: Int): ByteArray {
    if (degrees % 360 == 0) return data
    val out = ByteArray(data.size)
    when (degrees) {
        90 -> for (y in 0 until height) for (x in 0 until width) {
            out[x * height + (height - 1 - y)] = data[y * width + x]
        }

        180 -> for (index in data.indices) {
            out[data.size - 1 - index] = data[index]
        }

        270 -> for (y in 0 until height) for (x in 0 until width) {
            out[(width - 1 - x) * height + y] = data[y * width + x]
        }

        else -> return data
    }
    return out
}
