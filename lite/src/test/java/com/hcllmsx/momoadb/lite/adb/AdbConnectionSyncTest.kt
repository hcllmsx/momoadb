package com.hcllmsx.momoadb.lite.adb

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import kotlin.concurrent.thread

/**
 * 用一个本地假 adbd 验证 sync 传输链路：
 * 握手、打开 sync 流、RECV/SEND 分帧、二进制内容完整性、失败消息。
 *
 * 真实设备上 adbd 的行为来自 AOSP 4.4 的 file_sync_service.c，
 * 这里按同一协议实现服务端，重点盯住客户端的分帧与边界处理。
 */
class AdbConnectionSyncTest {

    private lateinit var server: FakeAdbd
    private lateinit var connection: AdbConnection

    @Before
    fun setUp() {
        server = FakeAdbd()
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        connection = AdbConnection(
            host = "127.0.0.1",
            port = server.port,
            crypto = AdbCrypto(keyPair.private as RSAPrivateCrtKey),
        )
        connection.connect(timeoutMs = 5_000)
    }

    @After
    fun tearDown() {
        runCatching { connection.close() }
        server.close()
        server.checkNoFailure()
    }

    @Test(timeout = 15_000)
    fun pullKeepsBinaryContent() {
        val content = binaryPayload(70_003)
        server.remoteFiles["/sdcard/test.bin"] = content

        assertArrayEquals(content, connection.pull("/sdcard/test.bin"))
    }

    @Test(timeout = 15_000)
    fun pullMissingFileReportsDeviceReason() {
        val error = runCatching { connection.pull("/sdcard/nope.bin") }.exceptionOrNull()

        assertTrue("应抛出 AdbException，实际：$error", error is AdbException)
        assertTrue("错误信息应包含设备原因：${error?.message}", error?.message?.contains("No such file") == true)
    }

    @Test(timeout = 20_000)
    fun pushWritesBinaryContentAndReportsProgress() {
        val content = binaryPayload(130_001)
        val local = File.createTempFile("momoadb-push", ".bin")
        local.writeBytes(content)
        val progress = mutableListOf<Long>()
        try {
            connection.push("/sdcard/upload.bin", local) { sent, _ -> progress.add(sent) }
        } finally {
            local.delete()
        }

        assertArrayEquals(content, server.uploadedFiles["/sdcard/upload.bin"])
        assertEquals(content.size.toLong(), progress.last())
        assertTrue("进度应递增：$progress", progress == progress.sorted())
    }

    @Test(timeout = 15_000)
    fun pushToDirectoryAssignedPathKeepsModeSuffix() {
        val local = File.createTempFile("momoadb-push-mode", ".bin")
        local.writeBytes(byteArrayOf(1, 2, 3))
        try {
            connection.push("/sdcard/with,comma.bin", local)
        } finally {
            local.delete()
        }

        // 设备端按最后一个逗号拆权限，文件名里的逗号必须保留。
        assertArrayEquals(byteArrayOf(1, 2, 3), server.uploadedFiles["/sdcard/with,comma.bin"])
    }

    @Test(timeout = 15_000)
    fun pullToFileStreamsContent() {
        val content = binaryPayload(200_000)
        server.remoteFiles["/sdcard/big.bin"] = content
        val target = File.createTempFile("momoadb-pull", ".bin")
        try {
            val bytes = connection.pullToFile("/sdcard/big.bin", target)
            assertEquals(content.size.toLong(), bytes)
            assertArrayEquals(content, target.readBytes())
        } finally {
            target.delete()
        }
    }

    private fun binaryPayload(size: Int): ByteArray =
        ByteArray(size) { index -> (index * 31 + 7).toByte() }

    /** 只实现本测试需要的 adbd 与 sync 协议。 */
    private class FakeAdbd : Closeable {

        private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int = serverSocket.localPort

        val remoteFiles = ConcurrentHashMap<String, ByteArray>()
        val uploadedFiles = ConcurrentHashMap<String, ByteArray>()

        private val failure = AtomicReference<Throwable?>()
        private val stopped = CountDownLatch(1)

        @Volatile
        private var clientSocket: Socket? = null

        @Volatile
        private var closing = false

        init {
            thread(isDaemon = true, name = "fake-adbd") { serve() }
        }

        override fun close() {
            closing = true
            runCatching { clientSocket?.close() }
            runCatching { serverSocket.close() }
            stopped.await(3, TimeUnit.SECONDS)
        }

        fun checkNoFailure() {
            failure.get()?.let { throw AssertionError("假 adbd 异常：${it.message}", it) }
        }

        private fun serve() {
            try {
                serverSocket.accept().use { socket ->
                    clientSocket = socket
                    socket.soTimeout = 10_000
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    val state = ConnectionState(input, output)
                    state.run()
                }
            } catch (error: Throwable) {
                // 测试收尾主动关闭连接时，socket 异常属于预期。
                if (!closing) failure.set(error)
            } finally {
                stopped.countDown()
            }
        }

        private inner class ConnectionState(
            private val input: DataInputStream,
            private val output: DataOutputStream,
        ) {
            private var streamLocalId = 0
            private var sync: SyncSession? = null

            fun run() {
                val hello = readMessage() ?: return
                if (hello.command != AdbProtocol.A_CNXN) throw IOException("客户端未发送 CNXN")
                sendMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.MAX_PAYLOAD, "device::\u0000".toByteArray())

                while (true) {
                    val message = readMessage() ?: return
                    when (message.command) {
                        AdbProtocol.A_OPEN -> {
                            streamLocalId = message.arg0
                            sendMessage(AdbProtocol.A_OKAY, ServerStreamId, streamLocalId, ByteArray(0))
                            val service = String(message.payload, Charsets.UTF_8).trimEnd('\u0000')
                            if (service == "sync:") {
                                sync = SyncSession(::sendStream, remoteFiles, uploadedFiles)
                            }
                        }
                        AdbProtocol.A_WRTE -> {
                            // 先回 ACK，避免客户端卡在写入的流控里。
                            sendMessage(AdbProtocol.A_OKAY, ServerStreamId, streamLocalId, ByteArray(0))
                            sync?.feed(message.payload)
                        }
                        AdbProtocol.A_CLSE -> {
                            sendMessage(AdbProtocol.A_CLSE, ServerStreamId, streamLocalId, ByteArray(0))
                        }
                        AdbProtocol.A_OKAY -> Unit
                    }
                }
            }

            private fun readMessage(): AdbProtocol.Message? {
                return try {
                    val command = input.readLeInt()
                    val arg0 = input.readLeInt()
                    val arg1 = input.readLeInt()
                    val length = input.readLeInt()
                    input.readLeInt() // checksum
                    val magic = input.readLeInt()
                    if (magic != command.inv()) throw IOException("消息头反码不匹配")
                    if (length < 0 || length > AdbProtocol.MAX_PAYLOAD) throw IOException("消息长度异常：$length")
                    val payload = ByteArray(length)
                    if (length > 0) input.readFully(payload)
                    AdbProtocol.Message(command, arg0, arg1, payload)
                } catch (end: EOFException) {
                    null
                } catch (reset: java.net.SocketException) {
                    null
                }
            }

            private fun sendMessage(command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
                val checksum = CRC32().apply { update(payload) }.value.toInt()
                output.writeLeInt(command)
                output.writeLeInt(arg0)
                output.writeLeInt(arg1)
                output.writeLeInt(payload.size)
                output.writeLeInt(checksum)
                output.writeLeInt(command.inv())
                if (payload.isNotEmpty()) output.write(payload)
                output.flush()
            }

            /** 按 4 KB 分片发送 sync 字节流，模拟真实 adbd 的 ADB 负载上限。 */
            private fun sendStream(bytes: ByteArray) {
                var offset = 0
                while (offset < bytes.size) {
                    val size = minOf(AdbProtocol.MAX_PAYLOAD, bytes.size - offset)
                    sendMessage(
                        AdbProtocol.A_WRTE,
                        ServerStreamId,
                        streamLocalId,
                        bytes.copyOfRange(offset, offset + size),
                    )
                    offset += size
                }
            }
        }
    }

    /** 逐字节累积的 sync 服务端状态机，处理请求头、路径、DATA 与 DONE。 */
    private class SyncSession(
        private val sendStream: (ByteArray) -> Unit,
        private val remoteFiles: MutableMap<String, ByteArray>,
        private val uploadedFiles: MutableMap<String, ByteArray>,
    ) {
        private var buffer = ByteArray(0)
        private var state = State.RequestHeader
        private var requestId = 0
        private var needed = 0
        private var path = ""
        private var upload = ByteArrayOutputStream()

        fun feed(chunk: ByteArray) {
            buffer += chunk
            process()
        }

        private fun process() {
            var consumed = 0
            while (true) {
                when (state) {
                    State.RequestHeader -> {
                        if (buffer.size - consumed < 8) break
                        requestId = readLeInt(buffer, consumed)
                        needed = readLeInt(buffer, consumed + 4)
                        consumed += 8
                        if (needed < 0 || needed > 1024) throw IOException("非法路径长度：$needed")
                        state = State.RequestPath
                    }
                    State.RequestPath -> {
                        if (buffer.size - consumed < needed) break
                        path = String(buffer, consumed, needed, Charsets.UTF_8)
                        consumed += needed
                        handleRequest()
                    }
                    State.UploadHeader -> {
                        if (buffer.size - consumed < 8) break
                        val id = readLeInt(buffer, consumed)
                        val size = readLeInt(buffer, consumed + 4)
                        consumed += 8
                        if (id == SyncIdData) {
                            if (size < 0 || size > SyncDataMax) throw IOException("非法数据长度：$size")
                            needed = size
                            state = State.UploadData
                        } else if (id == SyncIdDone) {
                            uploadedFiles[path] = upload.toByteArray()
                            sendStream(syncHeader(SyncIdOkay, 0))
                            state = State.RequestHeader
                        } else {
                            sendStream(syncHeader(SyncIdFail, 0))
                            state = State.RequestHeader
                        }
                    }
                    State.UploadData -> {
                        if (buffer.size - consumed < needed) break
                        upload.write(buffer, consumed, needed)
                        consumed += needed
                        state = State.UploadHeader
                    }
                }
            }
            if (consumed > 0) buffer = buffer.copyOfRange(consumed, buffer.size)
        }

        private fun handleRequest() {
            when (requestId) {
                SyncIdRecv -> {
                    val content = remoteFiles[path]
                    if (content == null) {
                        sendFail("No such file or directory")
                    } else {
                        var offset = 0
                        while (offset < content.size) {
                            val size = minOf(SyncDataMax, content.size - offset)
                            sendStream(syncHeader(SyncIdData, size))
                            sendStream(content.copyOfRange(offset, offset + size))
                            offset += size
                        }
                        sendStream(syncHeader(SyncIdDone, 0))
                    }
                    state = State.RequestHeader
                }
                SyncIdSend -> {
                    // 路径后面附加了 ,mode，按最后一个逗号拆掉。
                    val comma = path.lastIndexOf(',')
                    path = if (comma >= 0) path.substring(0, comma) else path
                    upload = ByteArrayOutputStream()
                    state = State.UploadHeader
                }
                else -> {
                    sendFail("unknown command")
                    state = State.RequestHeader
                }
            }
        }

        private fun sendFail(reason: String) {
            val message = reason.toByteArray(Charsets.UTF_8)
            sendStream(syncHeader(SyncIdFail, message.size))
            sendStream(message)
        }

        private fun syncHeader(id: Int, value: Int): ByteArray {
            val header = ByteArray(8)
            writeLeInt(header, 0, id)
            writeLeInt(header, 4, value)
            return header
        }

        private enum class State { RequestHeader, RequestPath, UploadHeader, UploadData }
    }

    private companion object {
        const val ServerStreamId = 4096
        const val SyncDataMax = 64 * 1024

        const val SyncIdSend = 0x444E4553
        const val SyncIdRecv = 0x52454356
        const val SyncIdData = 0x41544144
        const val SyncIdDone = 0x454E4F44
        const val SyncIdOkay = 0x59414B4F
        const val SyncIdFail = 0x4C494146
    }
}

private fun readLeInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

private fun writeLeInt(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value and 0xFF).toByte()
    bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

private fun DataInputStream.readLeInt(): Int =
    readUnsignedByte() or
        (readUnsignedByte() shl 8) or
        (readUnsignedByte() shl 16) or
        (readUnsignedByte() shl 24)

private fun DataOutputStream.writeLeInt(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 24) and 0xFF)
}
