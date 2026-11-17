package com.hcllmsx.momoadb.lite.adb

/** ADB 协议常量与消息头编解码。 */
internal object AdbProtocol {

    const val A_CNXN = 0x4e584e43 // CNXN
    const val A_AUTH = 0x48545541 // AUTH
    const val A_OPEN = 0x4e45504f // OPEN
    const val A_OKAY = 0x59414b4f // OKAY
    const val A_CLSE = 0x45534c43 // CLSE
    const val A_WRTE = 0x45545257 // WRTE
    const val A_STLS = 0x534c5453 // STLS

    const val A_VERSION = 0x01000000
    const val MAX_PAYLOAD = 4096

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val HEADER_SIZE = 24

    /** 一次收发的最小单位：命令 + 两个参数 + 数据长度 + CRC32 + 反码校验。 */
    class Message(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray,
    )
}
