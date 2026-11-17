package com.hcllmsx.momoadb.lite.adb

import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * ADB 认证用的 RSA-2048 密钥对。
 *
 * 设备端只认 Android 自定义的公钥格式（不是 X.509），共 524 字节、小端：
 * 模长字数(4) + n0inv(4) + 模数(256) + R²(256) + 指数(4)。
 * 首次连接会把公钥发给设备，用户在电视上点「允许」后才会放行。
 */
class AdbCrypto(private val privateKey: PrivateKey) {

    private val crt = privateKey as RSAPrivateCrtKey

    /** 用私钥对设备下发的 20 字节随机 token 做 SHA1-RSA 签名。 */
    fun sign(token: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(privateKey)
        signature.update(token)
        return signature.sign()
    }

    /** AUTH RSAPUBLICKEY 的负载：Android 公钥二进制结构的 Base64 + 结尾 '\0'。 */
    fun publicKeyPayload(): ByteArray {
        val text = Base64.encodeToString(encodeAndroidPublicKey(), Base64.NO_WRAP)
        return (text + "\u0000").toByteArray(Charsets.US_ASCII)
    }

    private fun encodeAndroidPublicKey(): ByteArray {
        val modulus = crt.modulus
        val buffer = ByteArray(EncodedSize)
        // 模长按 32 位字计：2048 / 32 = 64
        putLeWord(buffer, 0, (ModulusSize * 8 / 32).toLong())
        putLeWord(buffer, 4, n0inv(modulus))
        writeLeFixed(modulus, buffer, 8, ModulusSize)
        writeLeFixed(montgomeryRr(modulus), buffer, 8 + ModulusSize, ModulusSize)
        putLeWord(buffer, 8 + ModulusSize * 2, crt.publicExponent.toLong())
        return buffer
    }

    /** n0inv = -1 / n[0] mod 2³²，与 AOSP 的 android_pubkey_encode 一致。 */
    private fun n0inv(modulus: BigInteger): Long {
        val r32 = BigInteger.ONE.shiftLeft(32)
        val inverse = modulus.mod(r32).modInverse(r32)
        return r32.subtract(inverse).toLong() and 0xFFFFFFFFL
    }

    /** rr = (2^2048)² mod n，即蒙哥马利参数 R²。 */
    private fun montgomeryRr(modulus: BigInteger): BigInteger {
        val r = BigInteger.ONE.shiftLeft(ModulusSize * 8)
        return r.multiply(r).mod(modulus)
    }

    private fun putLeWord(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    /** BigInteger 是大端，这里按字节倒序写入，高位不足补 0。 */
    private fun writeLeFixed(value: BigInteger, buffer: ByteArray, offset: Int, size: Int) {
        val bigEndian = value.toByteArray()
        for (i in 0 until size) {
            val sourceIndex = bigEndian.size - 1 - i
            buffer[offset + i] = if (sourceIndex >= 0) bigEndian[sourceIndex] else 0
        }
    }

    companion object {
        private const val KeyFileName = "adbkey.pk8"
        private const val KeySizeBits = 2048
        private const val ModulusSize = 256
        private const val EncodedSize = 8 + ModulusSize * 2 + 4

        /** 读取已有密钥，不存在则生成一份并落盘。 */
        fun loadOrCreate(dir: File): AdbCrypto {
            val keyFile = File(dir, KeyFileName)
            val existing = runCatching { readPrivateKey(keyFile) }.getOrNull()
            if (existing != null) return AdbCrypto(existing)

            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(KeySizeBits)
            val pair = generator.generateKeyPair()
            keyFile.writeBytes(pair.private.encoded)
            return AdbCrypto(pair.private)
        }

        private fun readPrivateKey(file: File): PrivateKey? {
            if (!file.exists() || file.length() == 0L) return null
            val factory = KeyFactory.getInstance("RSA")
            return factory.generatePrivate(PKCS8EncodedKeySpec(file.readBytes()))
        }
    }
}
