package com.hcllmsx.momoadb.lite

/**
 * 零依赖的二维码生成器。
 *
 * 轻量版面向 Android 4.4 且不引入任何第三方库，因此按 ISO/IEC 18004 自行实现：
 * 字节模式、纠错等级 M、版本 1~9 自动选择（本应用内容只有「IP:端口」，V2 即够用，
 * 支持到 V9 以防内容变长）。输出的是模块矩阵而不是位图，便于 JVM 单元测试
 * 直接用标准解码器验证，位图渲染由界面层完成。
 */
object QrCode {

    /** 返回 modules[y][x]，true 表示深色模块。不含四周留白（由渲染方补足）。 */
    fun encode(text: String): Array<BooleanArray> {
        val data = text.toByteArray(Charsets.UTF_8)
        // 字节模式在版本 1~9 中长度字段固定 8 位：模式 4 位 + 长度 8 位 + 数据。
        var version = 1
        while (version <= 9 && data.size + 2 > dataCapacityBytes(version)) version++
        require(version <= 9) { "二维码内容过长（${data.size} 字节）" }

        val codewords = buildCodewords(data, version)
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        fun setFunction(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            isFunction[y][x] = true
        }

        drawFunctionPatterns(size, version, ::setFunction)
        drawCodewords(codewords, size, modules, isFunction)

        // 码字排布不带掩码，8 个掩码各自套用后评分，取罚分最小者。
        // 掩码本身不影响可解码性（格式信息里记录了所选掩码），只影响扫码容错率。
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0 until 8) {
            applyMask(mask, size, modules, isFunction)
            drawFormatBits(mask, size, ::setFunction)
            val penalty = penaltyScore(modules)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
            }
            applyMask(mask, size, modules, isFunction) // 还原，供下一个掩码尝试
        }
        applyMask(bestMask, size, modules, isFunction)
        drawFormatBits(bestMask, size, ::setFunction)
        return modules
    }

    // ---------- 数据编码与纠错 ----------

    private fun dataCapacityBytes(version: Int): Int {
        var raw = (16 * version + 128) * version + 64
        val numAlign = version / 7 + 2
        raw -= (25 * numAlign - 10) * numAlign - 55
        if (version >= 7) raw -= 36
        return raw / 8 - eccPerBlock(version) * numBlocks(version)
    }

    private fun eccPerBlock(version: Int): Int = EccPerBlock[version]

    private fun numBlocks(version: Int): Int = EccBlocks[version]

    // 版本 1~9、纠错等级 M 的标准参数表（每块纠错码字数 / 块数）。
    private val EccPerBlock = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 16)
    private val EccBlocks = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 6)

    private fun buildCodewords(data: ByteArray, version: Int): IntArray {
        val capacity = dataCapacityBytes(version)
        val stream = IntArray(capacity)
        var bitPos = 0
        fun appendBits(value: Int, count: Int) {
            for (i in 0 until count) {
                val bit = (value ushr (count - 1 - i)) and 1
                if (bit != 0) stream[bitPos ushr 3] = stream[bitPos ushr 3] or (0x80 ushr (bitPos and 7))
                bitPos++
            }
        }

        appendBits(0x4, 4)               // 字节模式
        appendBits(data.size, 8)         // 长度（版本 1~9 固定 8 位）
        data.forEach { appendBits(it.toInt() and 0xFF, 8) }
        // 终止符最多 4 个 0，随后补齐字节边界。
        appendBits(0, minOf(4, capacity * 8 - bitPos))
        while (bitPos % 8 != 0) {
            appendBits(0, 1)
        }
        var pad = 0
        while (bitPos < capacity * 8) {
            appendBits(if (pad % 2 == 0) 0xEC else 0x11, 8)
            pad++
        }

        // 纠错码字 + 分块交织。
        val numBlocks = numBlocks(version)
        val eccLen = eccPerBlock(version)
        val rawModules = run {
            var raw = (16 * version + 128) * version + 64
            val numAlign = version / 7 + 2
            raw -= (25 * numAlign - 10) * numAlign - 55
            if (version >= 7) raw -= 36
            raw
        }
        val rawWords = rawModules / 8
        val numShortBlocks = numBlocks - rawWords % numBlocks
        val shortBlockLen = rawWords / numBlocks

        // 每块定长（短块多出的一个位置是占位，交织时跳过），便于按行交织。
        val blocks = Array(numBlocks) { IntArray(shortBlockLen + 1) }
        var offset = 0
        for (i in 0 until numBlocks) {
            val dataLen = shortBlockLen - eccLen + (if (i < numShortBlocks) 0 else 1)
            val blockData = stream.copyOfRange(offset, offset + dataLen)
            offset += dataLen
            val remainder = reedSolomonRemainder(blockData, reedSolomonDivisor(eccLen))
            for (j in 0 until dataLen) blocks[i][j] = blockData[j] and 0xFF
            val eccStart = if (i < numShortBlocks) dataLen + 1 else dataLen
            for (j in 0 until eccLen) blocks[i][eccStart + j] = remainder[j] and 0xFF
        }

        val result = IntArray(rawWords)
        var index = 0
        val blockLen = blocks[0].size
        for (row in 0 until blockLen) {
            for (b in 0 until numBlocks) {
                // 短块在数据段末尾有一个占位字节，交织该行时跳过它。
                if (row == shortBlockLen - eccLen && b < numShortBlocks) continue
                result[index++] = blocks[b][row]
            }
        }
        require(index == rawWords) { "交织长度异常：$index / $rawWords" }
        return result
    }

    // ---------- Reed-Solomon（GF(256)，本原多项式 0x11D） ----------

    private fun reedSolomonDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = gfMultiply(result[j], root)
                if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
            }
            root = gfMultiply(root, 0x02)
        }
        return result
    }

    private fun reedSolomonRemainder(data: IntArray, divisor: IntArray): IntArray {
        val result = IntArray(divisor.size)
        for (byte in data) {
            val factor = byte xor result[0]
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (i in divisor.indices) {
                result[i] = result[i] xor gfMultiply(divisor[i], factor)
            }
        }
        return result
    }

    private fun gfMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z and 0xFF
    }

    // ---------- 图形定位与格式信息 ----------

    private fun drawFunctionPatterns(
        size: Int,
        version: Int,
        setFunction: (Int, Int, Boolean) -> Unit,
    ) {
        // 时间线
        for (i in 0 until size) {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }
        // 三个定位图案（含分隔留白）
        drawFinder(3, 3, size, setFunction)
        drawFinder(size - 4, 3, size, setFunction)
        drawFinder(3, size - 4, size, setFunction)
        // 校正图形
        val positions = alignmentPositions(version, size)
        for (ay in positions) {
            for (ax in positions) {
                if (ax == 6 && ay == 6) continue
                if (ax == size - 7 && ay == 6) continue
                if (ax == 6 && ay == size - 7) continue
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        setFunction(ax + dx, ay + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
                    }
                }
            }
        }
        // 固定暗模块
        setFunction(8, size - 8, true)
        // 先画一次占位格式信息，保证码字排布时避开这些位置，最终值在选定掩码后重画。
        drawFormatBits(0, size, setFunction)
        if (version >= 7) drawVersionInfo(version, size, setFunction)
    }

    private fun drawFinder(cx: Int, cy: Int, size: Int, setFunction: (Int, Int, Boolean) -> Unit) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x < 0 || x >= size || y < 0 || y >= size) continue
                val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                setFunction(x, y, dist != 2 && dist != 4)
            }
        }
    }

    private fun alignmentPositions(version: Int, size: Int): IntArray {
        if (version == 1) return IntArray(0)
        val numAlign = version / 7 + 2
        val step = if (version == 32) 26 else (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2
        val result = IntArray(numAlign)
        result[0] = 6
        var pos = size - 7
        for (i in numAlign - 1 downTo 1) {
            result[i] = pos
            pos -= step
        }
        return result
    }

    /** 纠错等级固定为 M，其格式信息基础值为 0。 */
    private fun drawFormatBits(mask: Int, size: Int, setFunction: (Int, Int, Boolean) -> Unit) {
        val data = (0 shl 3) or mask // M 的格式位为 0
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = ((data shl 10) or rem) xor 0x5412
        fun bit(i: Int): Boolean = (bits ushr i) and 1 != 0

        for (i in 0..5) setFunction(8, i, bit(i))
        setFunction(8, 7, bit(6))
        setFunction(8, 8, bit(7))
        setFunction(7, 8, bit(8))
        for (i in 9..14) setFunction(14 - i, 8, bit(i))
        for (i in 0..7) setFunction(size - 1 - i, 8, bit(i))
        for (i in 8..14) setFunction(8, size - 15 + i, bit(i))
        setFunction(size - 8, 8, true)
    }

    private fun drawVersionInfo(version: Int, size: Int, setFunction: (Int, Int, Boolean) -> Unit) {
        var rem = version
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        val bits = (version shl 12) or rem
        fun bit(i: Int): Boolean = (bits ushr i) and 1 != 0
        for (i in 0..17) {
            val value = bit(i)
            val a = size - 11 + i % 3
            val b = i / 3
            setFunction(a, b, value)
            setFunction(b, a, value)
        }
    }

    // ---------- 码字排布与掩码 ----------

    private fun drawCodewords(
        codewords: IntArray,
        size: Int,
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
    ) {
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5 // 让开垂直时间线
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y][x] && i < codewords.size * 8) {
                        modules[y][x] = (codewords[i ushr 3] ushr (7 - (i and 7))) and 1 != 0
                        i++
                    }
                }
            }
            right -= 2
        }
    }

    private fun applyMask(
        mask: Int,
        size: Int,
        modules: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
    ) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (isFunction[y][x]) continue
                if (maskBit(mask, x, y)) modules[y][x] = !modules[y][x]
            }
        }
    }

    private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (x / 3 + y / 2) % 2 == 0
        5 -> x * y % 2 + x * y % 3 == 0
        6 -> (x * y % 2 + x * y % 3) % 2 == 0
        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
    }

    // ---------- 掩码评分 ----------

    private fun penaltyScore(modules: Array<BooleanArray>): Int {
        val size = modules.size
        var penalty = 0
        for (y in 0 until size) penalty += linePenalty(modules[y])
        for (x in 0 until size) {
            val column = BooleanArray(size) { modules[it][x] }
            penalty += linePenalty(column)
        }
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val value = modules[y][x]
                if (value == modules[y][x + 1] && value == modules[y + 1][x] && value == modules[y + 1][x + 1]) {
                    penalty += 3
                }
            }
        }
        var dark = 0
        modules.forEach { row -> row.forEach { if (it) dark++ } }
        val total = size * size
        penalty += Math.abs(dark * 20 - total * 10) / total * 10
        return penalty
    }

    private fun linePenalty(line: BooleanArray): Int {
        var penalty = 0
        // 规则 1：同色连续 5 个以上，每个额外模块记 1 分（起点记 3 分）
        var runStart = 0
        for (i in 1..line.size) {
            if (i == line.size || line[i] != line[runStart]) {
                val len = i - runStart
                if (len >= 5) penalty += len - 2
                runStart = i
            }
        }
        // 规则 3：1:1:3:1:1:4 定位图案比例，出现一次记 40 分
        penalty += finderPatternPenalty(line)
        return penalty
    }

    private fun finderPatternPenalty(line: BooleanArray): Int {
        val runs = ArrayList<Int>()
        var count = 1
        for (i in 1 until line.size) {
            if (line[i] == line[i - 1]) count++ else {
                runs.add(count)
                count = 1
            }
        }
        runs.add(count)
        if (runs.size < 7) return 0

        var penalty = 0
        for (j in 0..runs.size - 7) {
            // 连续 7 段构成 深-浅-深-深-深-浅-深（j 段必须是深色）
            val runDark = if (j % 2 == 0) line[0] else !line[0]
            if (!runDark) continue
            if (runs[j] != 1 || runs[j + 1] != 1 || runs[j + 2] != 1 ||
                runs[j + 3] != 3 || runs[j + 4] != 1 || runs[j + 5] != 1 || runs[j + 6] != 1
            ) {
                continue
            }
            // 图案任意一侧紧邻至少 4 个浅色模块；行 / 列边界按满足处理
            val leftOk = j == 0 || runs[j - 1] >= 4
            val rightOk = j + 7 >= runs.size || runs[j + 7] >= 4
            if (leftOk || rightOk) penalty += 40
        }
        return penalty
    }
}
