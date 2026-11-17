package com.hcllmsx.momoadb.lite.adb

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.zip.CRC32

/**
 * 一个最简 ADB 客户端连接：完成握手、RSA 认证，并提供 shell / 推送 / 拉取 / 安装 / 截图 / 重启。
 *
 * 面向 Android 4.4：文件传输统一走 `sync:` 服务（RECV / SEND），
 * 因为旧版 adbd 的 `shell:` 命令会经过 PTY，换行转换会破坏二进制内容。
 */
class AdbConnection(
    private val host: String,
    private val port: Int,
    private val crypto: AdbCrypto,
) {

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var input: DataInputStream? = null

    @Volatile
    private var output: DataOutputStream? = null
    private var nextLocalId = 1

    @Volatile
    var isConnected: Boolean = false
        private set

    fun connect(timeoutMs: Int = 15000) {
        if (isConnected) return
        val newSocket = Socket()
        newSocket.tcpNoDelay = true
        try {
            newSocket.connect(InetSocketAddress(host, port), timeoutMs)
            newSocket.soTimeout = timeoutMs
            socket = newSocket
            input = DataInputStream(BufferedInputStream(newSocket.getInputStream()))
            output = DataOutputStream(BufferedOutputStream(newSocket.getOutputStream()))

            sendMessage(
                AdbProtocol.A_CNXN,
                AdbProtocol.A_VERSION,
                AdbProtocol.MAX_PAYLOAD,
                "host::".toByteArray(Charsets.UTF_8) + 0,
            )
            handshake()
            isConnected = true
            newSocket.soTimeout = 0
        } catch (e: Throwable) {
            closeQuietly()
            throw asAdbException(e, "连接 $host:$port 失败")
        }
    }

    /**
     * 立即中断当前连接或正在进行中的连接。
     *
     * 只关闭 socket、不等待读写线程退出，供界面线程在授权等待期间取消使用。
     */
    fun cancel() {
        isConnected = false
        runCatching { socket?.close() }
    }

    /** 执行一条 shell 命令，返回合并后的 stdout + stderr。 */
    fun shell(command: String, timeoutMs: Int = 20000): String {
        ensureConnected()
        socket?.soTimeout = timeoutMs

        val localId = nextLocalId++
        sendMessage(AdbProtocol.A_OPEN, localId, 0, ("shell:" + command).toByteArray(Charsets.UTF_8) + 0)

        val buffer = ByteArrayOutputStream()
        var remoteId = 0
        try {
            while (true) {
                val message = readMessageInternal()
                when (message.command) {
                    AdbProtocol.A_OKAY -> if (message.arg1 == localId) remoteId = message.arg0
                    AdbProtocol.A_WRTE -> if (message.arg1 == localId) {
                        remoteId = message.arg0
                        buffer.write(message.payload)
                        sendMessage(AdbProtocol.A_OKAY, localId, remoteId, EmptyPayload)
                    }
                    AdbProtocol.A_CLSE -> if (message.arg1 == localId) {
                        sendMessage(AdbProtocol.A_CLSE, localId, message.arg0, EmptyPayload)
                        break
                    }
                    else -> Unit
                }
            }
        } catch (e: SocketTimeoutException) {
            closeQuietly()
            throw AdbException("命令执行超时（${timeoutMs / 1000} 秒）：$command")
        } catch (e: EOFException) {
            closeQuietly()
            throw AdbException("与设备的连接已断开")
        } catch (e: Throwable) {
            closeQuietly()
            throw asAdbException(e, "命令执行失败：$command")
        }

        socket?.soTimeout = 0
        // 4.4 的 shell 走 PTY，换行会变成 CRLF，展示前统一掉。
        return buffer.toString("UTF-8").replace("\r\n", "\n")
    }

    /** 读取设备上的文件内容（适合截图等小文件）。 */
    fun pull(remotePath: String, timeoutMs: Int = 60_000): ByteArray {
        val result = ByteArrayOutputStream()
        receive(remotePath, timeoutMs) { data, size ->
            if (result.size() + size > MaxPullMemoryBytes) {
                throw AdbException("文件超过 ${MaxPullMemoryBytes / 1024 / 1024} MB，请改用下载到本机")
            }
            result.write(data, 0, size)
        }
        return result.toByteArray()
    }

    /** 把设备文件直接写到本地文件，适合大文件下载。 */
    fun pullToFile(
        remotePath: String,
        target: File,
        timeoutMs: Int = 300_000,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): Long {
        target.parentFile?.mkdirs()
        var received = 0L
        try {
            FileOutputStream(target).use { fileOutput ->
                receive(remotePath, timeoutMs) { data, size ->
                    fileOutput.write(data, 0, size)
                    received += size
                    onProgress(received, -1)
                }
            }
        } catch (error: Throwable) {
            runCatching { target.delete() }
            throw error
        }
        return received
    }

    /** 把本地文件写入设备路径，失败时抛出包含设备端原因的异常。 */
    fun push(
        remotePath: String,
        source: File,
        timeoutMs: Int = 120_000,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        if (!source.isFile) throw AdbException("本机文件不存在：${source.absolutePath}")
        if (remotePath.isEmpty()) throw AdbException("设备路径不能为空")
        val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
        // sync 协议把权限附在路径后面，由设备端按最后一个逗号拆分。
        val request = pathBytes + ModeSuffix
        if (request.size > MaxSyncPathBytes) throw AdbException("设备路径过长（最多 1024 字节）")

        ensureConnected()
        socket?.soTimeout = timeoutMs
        val stream = openService("sync:", timeoutMs)
        val total = source.length()
        var sent = 0L
        try {
            stream.write(syncHeader(SyncIdSend, request.size))
            stream.write(request)

            source.inputStream().use { fileInput ->
                val chunk = ByteArray(SyncDataMax)
                while (true) {
                    val read = fileInput.read(chunk)
                    if (read <= 0) break
                    stream.write(syncHeader(SyncIdData, read))
                    stream.write(chunk, 0, read)
                    sent += read
                    onProgress(sent, total)
                }
            }

            // DONE 携带文件的修改时间，设备端顺手设置。
            stream.write(syncHeader(SyncIdDone, (System.currentTimeMillis() / 1000L).toInt()))
            val (id, size) = readSyncHeader(stream)
            when (id) {
                SyncIdOkay -> Unit
                SyncIdFail -> throw AdbException("写入失败：${readSyncMessage(stream, size)}")
                else -> throw AdbException("写入失败：设备返回未知结果")
            }
        } finally {
            runCatching { stream.close() }
            socket?.soTimeout = 0
        }
    }

    /**
     * 安装本地 APK：先推到 /data/local/tmp，再用 pm install。
     * 返回 pm 的输出，调用方据此判断成功与否。
     */
    fun installApk(
        apk: File,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): String {
        val remote = "/data/local/tmp/momoadb_install.apk"
        try {
            push(remote, apk, onProgress = onProgress)
            return shell("pm install -r -t ${shellQuote(remote)}", timeoutMs = 180_000).trim()
        } finally {
            runCatching { shell("rm -f ${shellQuote(remote)}", timeoutMs = 15_000) }
        }
    }

    /** 截图并返回 PNG 字节。 */
    fun screenshot(timeoutMs: Int = 60_000): ByteArray {
        val remote = "/data/local/tmp/momoadb_screenshot.png"
        val command = "screencap -p ${shellQuote(remote)}"
        try {
            val output = shell(command, timeoutMs).trim()
            if (output.isNotEmpty()) {
                throw AdbException("截图失败：$output")
            }
            return pull(remote, timeoutMs)
        } finally {
            runCatching { shell("rm -f ${shellQuote(remote)}", timeoutMs = 15_000) }
        }
    }

    /** 发送按键事件，例如 3=主页、4=返回、26=电源、24/25=音量。 */
    fun sendKeyEvent(keyCode: Int) {
        shell("input keyevent $keyCode", timeoutMs = 15_000)
    }

    /** 重启设备，mode 为空表示正常重启，可传 recovery / bootloader。 */
    fun reboot(mode: String = "") {
        ensureConnected()
        socket?.soTimeout = 5_000
        val localId = nextLocalId++
        sendMessage(
            AdbProtocol.A_OPEN,
            localId,
            0,
            ("reboot:" + mode).toByteArray(Charsets.UTF_8) + 0,
        )
        // 设备通常会直接重启、不再回应，这里的异常与超时都视为正常。
        runCatching {
            while (true) {
                val message = readMessageInternal()
                if (message.command == AdbProtocol.A_CLSE && message.arg1 == localId) return
            }
        }
    }

    fun close() {
        closeQuietly()
    }

    internal fun readMessageInternal(): AdbProtocol.Message {
        val stream = input ?: throw AdbException("尚未连接设备")
        val command = stream.readLeInt()
        val arg0 = stream.readLeInt()
        val arg1 = stream.readLeInt()
        val length = stream.readLeInt()
        val checksum = stream.readLeInt()
        val magic = stream.readLeInt()

        if (magic != command.inv()) throw AdbException("ADB 消息头校验失败，连接可能已损坏")
        if (length < 0 || length > MaxMessageLength) throw AdbException("ADB 消息长度异常：$length")

        val payload = ByteArray(length)
        if (length > 0) stream.readFully(payload)

        // 部分实现用 0 表示「未计算 CRC」。
        if (checksum != 0) {
            val actual = CRC32().apply { update(payload) }.value.toInt()
            if (actual != checksum) throw AdbException("ADB 消息内容校验失败")
        }
        return AdbProtocol.Message(command, arg0, arg1, payload)
    }

    internal fun sendMessage(command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val stream = output ?: throw AdbException("尚未连接设备")
        if (payload.size > AdbProtocol.MAX_PAYLOAD) throw AdbException("ADB 单次数据过大")
        val checksum = CRC32().apply { update(payload) }.value.toInt()
        stream.writeLeInt(command)
        stream.writeLeInt(arg0)
        stream.writeLeInt(arg1)
        stream.writeLeInt(payload.size)
        stream.writeLeInt(checksum)
        stream.writeLeInt(command.inv())
        if (payload.isNotEmpty()) stream.write(payload)
        stream.flush()
    }

    private fun openService(service: String, timeoutMs: Int): AdbStream {
        ensureConnected()
        socket?.soTimeout = timeoutMs
        val localId = nextLocalId++
        val stream = AdbStream(this, service, localId)
        sendMessage(AdbProtocol.A_OPEN, localId, 0, service.toByteArray(Charsets.UTF_8) + 0)
        stream.awaitOpen()
        return stream
    }

    /** 打开 sync 流并发送 RECV，把收到的数据交给 onData。 */
    private fun receive(remotePath: String, timeoutMs: Int, onData: (ByteArray, Int) -> Unit) {
        ensureConnected()
        if (remotePath.isEmpty()) throw AdbException("设备路径不能为空")
        val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
        if (pathBytes.size > MaxSyncPathBytes) throw AdbException("设备路径过长（最多 1024 字节）")

        socket?.soTimeout = timeoutMs
        val stream = openService("sync:", timeoutMs)
        try {
            stream.write(syncHeader(SyncIdRecv, pathBytes.size))
            stream.write(pathBytes)

            while (true) {
                val (id, size) = readSyncHeader(stream)
                when (id) {
                    SyncIdData -> {
                        if (size < 0 || size > SyncDataMax) {
                            throw AdbException("设备返回了异常的数据长度：$size")
                        }
                        onData(stream.readExact(size), size)
                    }
                    SyncIdDone -> return
                    SyncIdFail -> throw AdbException("读取失败：${readSyncMessage(stream, size)}")
                    else -> throw AdbException("读取失败：设备返回未知结果")
                }
            }
        } finally {
            runCatching { stream.close() }
            socket?.soTimeout = 0
        }
    }

    private fun readSyncHeader(stream: AdbStream): Pair<Int, Int> {
        val header = stream.readExact(8)
        return readLeInt(header, 0) to readLeInt(header, 4)
    }

    private fun readSyncMessage(stream: AdbStream, size: Int): String {
        if (size <= 0) return "设备未提供原因"
        if (size > SyncDataMax) throw AdbException("设备返回的错误信息过长：$size")
        return String(stream.readExact(size), Charsets.UTF_8).trim().ifEmpty { "设备未提供原因" }
    }

    private fun syncHeader(id: Int, value: Int): ByteArray {
        val header = ByteArray(8)
        writeLeInt(header, 0, id)
        writeLeInt(header, 4, value)
        return header
    }

    private fun handshake() {
        var signatureSent = false
        var publicKeySent = false
        while (true) {
            val message = readMessageInternal()
            when (message.command) {
                AdbProtocol.A_CNXN -> return
                AdbProtocol.A_AUTH -> {
                    if (message.arg0 != AdbProtocol.AUTH_TOKEN) {
                        throw AdbException("设备返回了未知的认证请求（${message.arg0}）")
                    }
                    when {
                        !signatureSent -> {
                            sendMessage(AdbProtocol.A_AUTH, AdbProtocol.AUTH_SIGNATURE, 0, crypto.sign(message.payload))
                            signatureSent = true
                        }
                        !publicKeySent -> {
                            // 提交公钥后设备会弹出授权提示，给用户留出用遥控器确认的时间。
                            socket?.soTimeout = AuthorizeTimeoutMs
                            sendMessage(AdbProtocol.A_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, crypto.publicKeyPayload())
                            publicKeySent = true
                        }
                        else -> throw AdbException("电视未授权本机调试，请在电视上选择「允许」后重试")
                    }
                }
                else -> throw AdbException("设备返回了未知的握手消息 0x${Integer.toHexString(message.command)}")
            }
        }
    }

    private fun ensureConnected() {
        if (!isConnected) throw AdbException("尚未连接设备")
    }

    private fun closeQuietly() {
        isConnected = false
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    private fun asAdbException(error: Throwable, prefix: String): AdbException {
        if (error is AdbException) return error
        if (error is SocketTimeoutException) {
            return AdbException("$prefix：等待超时，请确认地址与端口正确、设备已开启网络调试", error)
        }
        val detail = error.message ?: error.javaClass.simpleName
        return AdbException("$prefix：$detail", error)
    }

    private fun DataInputStream.readLeInt(): Int {
        val b0 = readUnsignedByte()
        val b1 = readUnsignedByte()
        val b2 = readUnsignedByte()
        val b3 = readUnsignedByte()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun DataOutputStream.writeLeInt(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private companion object {
        val EmptyPayload = ByteArray(0)
        const val MaxMessageLength = 1024 * 1024
        const val AuthorizeTimeoutMs = 120_000
        const val MaxSyncPathBytes = 1024
        const val SyncDataMax = 64 * 1024
        const val MaxPullMemoryBytes = 64L * 1024 * 1024

        // sync 请求 ID 按小端字节序解释，例如 0x444E4553 写出即为 "SEND"。
        const val SyncIdSend = 0x444E4553
        const val SyncIdRecv = 0x52454356
        const val SyncIdData = 0x41544144
        const val SyncIdDone = 0x454E4F44
        const val SyncIdOkay = 0x59414B4F
        const val SyncIdFail = 0x4C494146

        /** 0100644：普通文件，rw-r--r--，设备端会把用户权限同步到组和其他人。 */
        val ModeSuffix = ",33188".toByteArray(Charsets.US_ASCII)

        /** 用单引号包裹路径，内部的单引号按 shell 规则转义。 */
        fun shellQuote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

        fun readLeInt(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)

        fun writeLeInt(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }
    }
}
