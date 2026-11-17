package com.hcllmsx.momoadb.lite.adb

import java.io.ByteArrayOutputStream

/**
 * 一条 ADB 数据流，对应一次 OPEN。
 *
 * 写入按 4KB 分片并逐片等待对端 OKAY 做流控；读取每收到一片就回 OKAY。
 * 全部字段只在工作线程访问，不做额外加锁。
 */
class AdbStream internal constructor(
    private val connection: AdbConnection,
    private val serviceName: String,
    val localId: Int,
) {

    private var remoteId = 0
    private var remoteClosed = false
    private var awaitingAck = false
    private val inbound = ByteArrayOutputStream()

    internal fun awaitOpen() {
        while (true) {
            val message = connection.readMessageInternal()
            when (message.command) {
                AdbProtocol.A_OKAY -> if (message.arg1 == localId) {
                    remoteId = message.arg0
                    return
                }
                AdbProtocol.A_CLSE -> if (message.arg1 == localId) {
                    throw AdbException("设备拒绝打开服务：$serviceName")
                }
                else -> Unit
            }
        }
    }

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        var cursor = offset
        val end = offset + length
        while (cursor < end) {
            if (remoteClosed) throw AdbException("设备已关闭数据流")
            val size = minOf(AdbProtocol.MAX_PAYLOAD, end - cursor)
            val chunk = bytes.copyOfRange(cursor, cursor + size)
            awaitingAck = true
            connection.sendMessage(AdbProtocol.A_WRTE, localId, remoteId, chunk)
            waitForAck()
            cursor += size
        }
    }

    /** 读满 buffer 或遇到流结束，返回读取字节数，结束返回 -1。 */
    fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int {
        while (inbound.size() == 0 && !remoteClosed) {
            fillOnce()
        }
        if (inbound.size() == 0) return -1
        val data = inbound.toByteArray()
        val count = minOf(length, data.size)
        System.arraycopy(data, 0, buffer, offset, count)
        inbound.reset()
        if (data.size > count) inbound.write(data, count, data.size - count)
        return count
    }

    /** 读满指定字节数，流提前结束时报错。 */
    fun readExact(size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(buffer, offset, size - offset)
            if (read < 0) throw AdbException("设备提前关闭数据流（已读取 $offset/$size 字节）")
            offset += read
        }
        return buffer
    }

    fun readAll(maxBytes: Long = 96L * 1024 * 1024): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            result.write(buffer, 0, read)
            if (result.size() > maxBytes) {
                throw AdbException("传输内容超过上限（${maxBytes / 1024 / 1024} MB）")
            }
        }
        return result.toByteArray()
    }

    fun close() {
        if (remoteClosed) return
        remoteClosed = true
        runCatching { connection.sendMessage(AdbProtocol.A_CLSE, localId, remoteId, EmptyPayload) }
    }

    private fun waitForAck() {
        while (awaitingAck) {
            val message = connection.readMessageInternal()
            when (message.command) {
                AdbProtocol.A_OKAY -> if (message.arg1 == localId) awaitingAck = false
                AdbProtocol.A_WRTE -> if (message.arg1 == localId) {
                    remoteId = message.arg0
                    inbound.write(message.payload)
                    connection.sendMessage(AdbProtocol.A_OKAY, localId, remoteId, EmptyPayload)
                }
                AdbProtocol.A_CLSE -> if (message.arg1 == localId) {
                    remoteClosed = true
                    awaitingAck = false
                }
                else -> Unit
            }
        }
    }

    private fun fillOnce() {
        val message = connection.readMessageInternal()
        when (message.command) {
            AdbProtocol.A_OKAY -> if (message.arg1 == localId) {
                remoteId = message.arg0
                awaitingAck = false
            }
            AdbProtocol.A_WRTE -> if (message.arg1 == localId) {
                remoteId = message.arg0
                inbound.write(message.payload)
                connection.sendMessage(AdbProtocol.A_OKAY, localId, remoteId, EmptyPayload)
            }
            AdbProtocol.A_CLSE -> if (message.arg1 == localId) {
                remoteClosed = true
                connection.sendMessage(AdbProtocol.A_CLSE, localId, message.arg0, EmptyPayload)
            }
            else -> Unit
        }
    }

    private companion object {
        val EmptyPayload = ByteArray(0)
    }
}
