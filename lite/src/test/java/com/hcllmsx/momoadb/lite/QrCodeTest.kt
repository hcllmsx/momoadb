package com.hcllmsx.momoadb.lite

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用标准解码器（zxing core，仅测试期依赖）验证 QrCode 生成的矩阵可被读取。
 * 覆盖典型 IP:端口（V2）与更长内容（触发多块交织、校正图形、版本信息）。
 */
class QrCodeTest {

    @Test
    fun typicalEndpointIsDecodable() = assertDecodes("192.168.1.86:5555")

    @Test
    fun multiBlockContentIsDecodable() = assertDecodes("momoadb://connect?host=192.168.100.100&port=5555")

    @Test
    fun highVersionContentIsDecodable() = assertDecodes("momoadb://connect?host=" + "a".repeat(100) + "&port=5555")

    private fun assertDecodes(text: String) {
        val matrix = QrCode.encode(text)
        val quiet = 4
        val scale = 4
        val dim = (matrix.size + quiet * 2) * scale
        val pixels = IntArray(dim * dim) { 0xFFFFFF }
        for (y in matrix.indices) {
            for (x in matrix.indices) {
                if (!matrix[y][x]) continue
                val px = (x + quiet) * scale
                val py = (y + quiet) * scale
                for (dy in 0 until scale) {
                    val base = (py + dy) * dim + px
                    java.util.Arrays.fill(pixels, base, base + scale, 0x000000)
                }
            }
        }
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(dim, dim, pixels))))
        assertEquals(text, result.text)
    }
}
