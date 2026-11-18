package com.hcllmsx.momoadb.ui.qrcode

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

private const val DefaultAdbPort = 5555

data class DeviceQrUiState(
    val model: String = "",
    val androidVersion: String = "",
    val ips: List<String> = emptyList(),
    val port: Int = DefaultAdbPort,
    val adbOpen: Boolean = false,
    val qrImage: ImageBitmap? = null,
    val refreshing: Boolean = false,
)

/**
 * 本机二维码页：把本机变成被控端时，展示局域网地址与端口供其他设备的
 * 「扫码连接」识别。端口沿用 Android 网络调试的通用端口 5555。
 */
class DeviceQrViewModel : ViewModel() {

    private val state = MutableStateFlow(
        DeviceQrUiState(
            model = Build.MODEL.orEmpty(),
            androidVersion = "Android " + Build.VERSION.RELEASE,
        ),
    )
    val uiState: StateFlow<DeviceQrUiState> = state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        state.value = state.value.copy(refreshing = true)
        viewModelScope.launch(Dispatchers.IO) {
            val ips = localIpAddresses()
            val adbOpen = ips.isNotEmpty() && isPortOpen(state.value.port)
            val qr = ips.firstOrNull()?.let { encodeQr("$it:${state.value.port}") }
            withContext(Dispatchers.Main) {
                state.value = state.value.copy(
                    ips = ips,
                    adbOpen = adbOpen,
                    qrImage = qr,
                    refreshing = false,
                )
            }
        }
    }

    /** 枚举本机所有 IPv4 局域网地址，逐网卡容错。 */
    private fun localIpAddresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                try {
                    if (!networkInterface.isUp || networkInterface.isLoopback) continue
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (address is Inet4Address &&
                            !address.isLoopbackAddress &&
                            !address.isAnyLocalAddress
                        ) {
                            address.hostAddress?.let { result.add(it) }
                        }
                    }
                } catch (_: Throwable) {
                    // 单个网卡属性异常时跳过该网卡。
                }
            }
        } catch (_: Throwable) {
            // 枚举接口失败时按无网络处理。
        }
        return result.distinct()
    }

    /** 探测本机 adbd 是否在监听网络 ADB 端口。 */
    private fun isPortOpen(port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), PortProbeTimeoutMillis)
            true
        }
    } catch (_: Throwable) {
        false
    }

    /** 用 zxing core 生成二维码位图（白底、纠错等级 M）。 */
    private fun encodeQr(text: String, sizePx: Int = QrSizePx): ImageBitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix.get(x, y)) Color.Black.toArgb() else Color.White.toArgb()
            }
        }
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.RGB_565).asImageBitmap()
    }.getOrNull()

    private companion object {
        const val PortProbeTimeoutMillis = 1500
        const val QrSizePx = 640
    }
}
