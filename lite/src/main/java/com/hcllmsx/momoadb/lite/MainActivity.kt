package com.hcllmsx.momoadb.lite

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hcllmsx.momoadb.lite.adb.AdbConnection
import com.hcllmsx.momoadb.lite.adb.AdbCrypto
import java.io.File
import java.util.concurrent.Executors

/**
 * 轻量版主界面：连接一台 Android 4.4 设备，提供设备信息、截图、APK 安装、
 * 文件上传下载、按键模拟、重启与 shell 命令。
 *
 * 所有网络与认证都在单线程池里做，结果回到主线程刷新界面，
 * 因此不需要任何协程或 AndroidX 组件。
 */
class MainActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var apkPathInput: EditText
    private lateinit var remotePathInput: EditText
    private lateinit var localPathInput: EditText
    private lateinit var commandInput: EditText
    private lateinit var outputView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var statusView: TextView
    private lateinit var connectButton: Button

    @Volatile
    private var connection: AdbConnection? = null

    @Volatile
    private var connecting: AdbConnection? = null

    @Volatile
    private var cancelRequested = false
    private var busy = false
    private val logText = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hostInput = findViewById(R.id.host)
        portInput = findViewById(R.id.port)
        apkPathInput = findViewById(R.id.apk_path)
        remotePathInput = findViewById(R.id.remote_path)
        localPathInput = findViewById(R.id.local_path)
        commandInput = findViewById(R.id.command)
        outputView = findViewById(R.id.output)
        logScroll = findViewById(R.id.log_scroll)
        statusView = findViewById(R.id.status)
        connectButton = findViewById(R.id.connect)

        val prefs = getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        hostInput.setText(prefs.getString(KeyHost, ""))
        portInput.setText(prefs.getString(KeyPort, DefaultPort.toString()))

        findViewById<TextView>(R.id.subtitle).text =
            getString(R.string.subtitle) + "  ·  v" + BuildConfig.VERSION_NAME

        connectButton.setOnClickListener { doConnect() }
        findViewById<Button>(R.id.disconnect).setOnClickListener { doDisconnect() }
        findViewById<Button>(R.id.device_info).setOnClickListener { doDeviceInfo() }
        findViewById<Button>(R.id.screenshot).setOnClickListener { doScreenshot() }
        findViewById<Button>(R.id.pick_apk).setOnClickListener { pickApk() }
        findViewById<Button>(R.id.install_apk).setOnClickListener { doInstall() }
        findViewById<Button>(R.id.download_file).setOnClickListener { doDownload() }
        findViewById<Button>(R.id.upload_file).setOnClickListener { doUpload() }
        findViewById<Button>(R.id.key_home).setOnClickListener { doKeyEvent(KeyEventHome) }
        findViewById<Button>(R.id.key_back).setOnClickListener { doKeyEvent(KeyEventBack) }
        findViewById<Button>(R.id.key_power).setOnClickListener { doKeyEvent(KeyEventPower) }
        findViewById<Button>(R.id.key_volume_up).setOnClickListener { doKeyEvent(KeyEventVolumeUp) }
        findViewById<Button>(R.id.key_volume_down).setOnClickListener { doKeyEvent(KeyEventVolumeDown) }
        findViewById<Button>(R.id.reboot).setOnClickListener { doReboot("") }
        findViewById<Button>(R.id.reboot_recovery).setOnClickListener { doReboot("recovery") }
        findViewById<Button>(R.id.clear).setOnClickListener { clearLog() }
        findViewById<Button>(R.id.run).setOnClickListener { doRunCommand() }

        commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                findViewById<Button>(R.id.run).performClick()
                true
            } else {
                false
            }
        }

        // 电视遥控器需要一个可见的起始焦点。
        connectButton.post { if (!connectButton.hasFocus()) connectButton.requestFocus() }

        appendLog("提示：先在电视的开发者选项里打开「网络调试 / ADB 调试」，再填写电视 IP 连接。")
        appendLog("首次连接会在电视上弹出授权框，用遥控器选「允许」即可。")
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
        connecting?.cancel()
        connecting = null
        connection?.close()
        connection = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RequestPickApk || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val cached = copyUriToCache(uri)
        if (cached == null) {
            appendLog("读取所选文件失败，请改用路径输入。")
            return
        }
        apkPathInput.setText(cached.absolutePath)
        appendLog("已选择：${cached.absolutePath}")
    }

    // ---------- 连接 ----------

    private fun doConnect() {
        if (connecting != null) {
            // 授权等待中再点一次即取消，避免在电视前干等 120 秒。
            cancelConnecting()
            return
        }
        if (busy) return
        if (connection?.isConnected == true) {
            appendLog("已经连接，无需重复连接")
            return
        }
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull() ?: DefaultPort
        if (host.isEmpty()) {
            appendLog("请先填写电视的 IP 地址")
            hostInput.requestFocus()
            return
        }

        hideKeyboard()
        cancelRequested = false
        getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(KeyHost, host)
            .putString(KeyPort, port.toString())
            .apply()

        setBusy(true, "正在连接 $host:$port …")
        appendLog("正在连接 $host:$port，授权提示出现时再点一次「连接」可以取消。")
        worker.execute {
            val newConnection = try {
                AdbCrypto.loadOrCreate(filesDir).let { AdbConnection(host, port, it) }
            } catch (error: Throwable) {
                val message = error.message ?: error.javaClass.simpleName
                post {
                    appendLog("初始化密钥失败：$message")
                    setBusy(false, "未连接")
                }
                return@execute
            }
            connecting = newConnection
            try {
                newConnection.connect()
                val model = newConnection.shell("getprop ro.product.model").trim()
                connection = newConnection
                post {
                    appendLog("已连接：${model.ifEmpty { "未知设备" }}（$host:$port）")
                    setBusy(false, "已连接 ${model.ifEmpty { host }}")
                }
            } catch (error: Throwable) {
                runCatching { newConnection.close() }
                val message = if (cancelRequested) {
                    "已取消连接"
                } else {
                    error.message ?: error.javaClass.simpleName
                }
                post {
                    if (cancelRequested) {
                        appendLog("已取消连接")
                    } else {
                        appendLog("连接失败：$message")
                    }
                    setBusy(false, if (cancelRequested) "已取消" else "未连接")
                }
            } finally {
                connecting = null
            }
        }
    }

    private fun cancelConnecting() {
        val pending = connecting ?: return
        cancelRequested = true
        appendLog("正在取消连接…")
        statusView.text = "正在取消…"
        pending.cancel()
    }

    private fun doDisconnect() {
        val active = connection
        if (active == null) {
            appendLog("当前没有连接")
            return
        }
        connection = null
        statusView.text = getString(R.string.status_ready)
        worker.execute { runCatching { active.close() } }
        appendLog("已断开连接")
    }

    // ---------- 功能 ----------

    private fun doDeviceInfo() {
        runCommand(DeviceInfoCommand, "读取设备信息", showCommandLine = false)
    }

    private fun doRunCommand() {
        val command = commandInput.text.toString().trim()
        if (command.isEmpty()) {
            appendLog("请先输入要执行的命令")
            commandInput.requestFocus()
            return
        }
        runCommand(command, "执行命令", showCommandLine = true)
    }

    private fun runCommand(command: String, label: String, showCommandLine: Boolean) {
        withConnection(label) { active ->
            val result = active.shell(command).trimEnd()
            post {
                if (showCommandLine) appendLog("$ $command")
                appendLog(if (result.isEmpty()) "（无输出）" else result)
            }
            null
        }
    }

    private fun doKeyEvent(keyCode: Int) {
        withConnection("发送按键") { active ->
            active.sendKeyEvent(keyCode)
            null
        }
    }

    private fun doReboot(mode: String) {
        withConnection(if (mode.isEmpty()) "重启设备" else "重启到 $mode") { active ->
            active.reboot(mode)
            runCatching { active.close() }
            post {
                connection = null
                appendLog("已发送重启指令，设备重启期间连接会断开。")
            }
            null
        }
    }

    private fun doScreenshot() {
        withConnection("截图") { active ->
            val data = active.screenshot()
            if (data.size < 8 || data[0] != 0x89.toByte() || data[1] != 'P'.code.toByte()) {
                throw IllegalStateException("截图数据无效，可能是设备不支持 screencap")
            }
            val file = File(cacheDir, "screenshot.png")
            file.writeBytes(data)
            val bitmap = decodeScreenshot(file)
            post { showScreenshot(bitmap) }
            "截图已保存：${file.absolutePath}"
        }
    }

    private fun doInstall() {
        val path = apkPathInput.text.toString().trim()
        val file = File(path)
        if (path.isEmpty() || !file.isFile) {
            appendLog("APK 路径无效：$path")
            apkPathInput.requestFocus()
            return
        }
        withConnection("安装 APK") { active ->
            val output = active.installApk(file, progressUpdater())
            if (output.contains("Success", ignoreCase = true)) {
                "安装成功：${file.name}"
            } else {
                "安装失败：${output.ifEmpty { "设备没有返回结果" }}"
            }
        }
    }

    private fun doDownload() {
        val remote = remotePathInput.text.toString().trim()
        if (remote.isEmpty()) {
            appendLog("请填写设备上的文件路径")
            remotePathInput.requestFocus()
            return
        }
        withConnection("下载文件") { active ->
            val target = localTargetFor(remote)
            val bytes = active.pullToFile(remote, target, onProgress = progressUpdater())
            "已下载 ${formatSize(bytes)} 到 ${target.absolutePath}"
        }
    }

    private fun doUpload() {
        val localText = localPathInput.text.toString().trim()
        val remote = remotePathInput.text.toString().trim()
        if (localText.isEmpty()) {
            appendLog("请填写本机文件路径")
            localPathInput.requestFocus()
            return
        }
        val source = File(localText)
        if (!source.isFile) {
            appendLog("本机文件不存在：$localText")
            return
        }
        if (remote.isEmpty()) {
            appendLog("请填写设备上的目标路径")
            remotePathInput.requestFocus()
            return
        }
        val remoteFile = if (remote.endsWith("/")) remote + source.name else remote
        withConnection("上传文件") { active ->
            active.push(remoteFile, source, onProgress = progressUpdater())
            "已上传 ${formatSize(source.length())} 到 $remoteFile"
        }
    }

    // ---------- 截图预览 ----------

    private fun showScreenshot(bitmap: Bitmap?) {
        if (bitmap == null) {
            appendLog("截图解码失败")
            return
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_image)
        dialog.findViewById<ImageView>(R.id.preview).setImageBitmap(bitmap)
        val closeButton = dialog.findViewById<Button>(R.id.close)
        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { bitmap.recycle() }
        dialog.show()
        closeButton.requestFocus()
    }

    private fun decodeScreenshot(file: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > MaxPreviewWidth) sample *= 2
            val options = BitmapFactory.Options()
            options.inSampleSize = sample
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (error: OutOfMemoryError) {
            null
        }
    }

    // ---------- 工具 ----------

    private fun withConnection(label: String, action: (AdbConnection) -> String?) {
        val active = connection
        if (active == null || !active.isConnected) {
            appendLog("请先连接设备")
            connectButton.requestFocus()
            return
        }
        if (busy) {
            appendLog("上一个操作还没结束，请稍候")
            return
        }

        hideKeyboard()
        setBusy(true, "$label…")
        worker.execute {
            try {
                val message = action(active)
                post {
                    if (!message.isNullOrBlank()) appendLog(message)
                    setBusy(false, "完成")
                }
            } catch (error: Throwable) {
                val detail = error.message ?: error.javaClass.simpleName
                val stillConnected = active.isConnected
                post {
                    appendLog("$label 失败：$detail")
                    setBusy(false, "操作失败")
                    if (!stillConnected) {
                        connection = null
                        appendLog("连接已断开")
                    }
                }
            }
        }
    }

    /** 进度只在数值变化时刷新，避免电视上频繁重绘。 */
    private fun progressUpdater(): (Long, Long) -> Unit {
        var lastPercent = -1L
        var lastBytes = -1L
        return { sent, total ->
            if (total > 0) {
                val percent = sent * 100 / total
                if (percent != lastPercent) {
                    lastPercent = percent
                    post { statusView.text = "传输中 $percent%" }
                }
            } else if (sent / (256 * 1024) != lastBytes) {
                // 下载时设备不告诉总大小，按每 256 KB 刷新一次。
                lastBytes = sent / (256 * 1024)
                post { statusView.text = "已接收 ${formatSize(sent)}" }
            }
        }
    }

    private fun localTargetFor(remotePath: String): File {
        val explicit = localPathInput.text.toString().trim()
        if (explicit.isNotEmpty()) return File(explicit)
        val name = remotePath.substringAfterLast('/').ifEmpty { "download.bin" }
        return File(defaultLocalDir(), name)
    }

    private fun defaultLocalDir(): File {
        val base = getExternalFilesDir(null) ?: File(filesDir, "files")
        return File(base, "downloads").apply { mkdirs() }
    }

    private fun pickApk() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        try {
            startActivityForResult(Intent.createChooser(intent, "选择 APK"), RequestPickApk)
        } catch (error: Exception) {
            Toast.makeText(this, "当前系统没有可用的文件选择器，请手动输入路径", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyUriToCache(uri: Uri): File? {
        // 上一次选的 APK 没有保留价值，先清掉，避免占满电视的缓存空间。
        cacheDir.listFiles { file -> file.name.startsWith("picked_") }?.forEach { it.delete() }
        val target = File(cacheDir, "picked_${System.currentTimeMillis()}.apk")
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
            target
        } catch (error: Throwable) {
            null
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes 字节"
    }

    private fun clearLog() {
        logText.setLength(0)
        outputView.text = ""
    }

    private fun setBusy(value: Boolean, message: String) {
        busy = value
        statusView.text = message
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        manager.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun post(action: () -> Unit) {
        mainHandler.post { if (!isFinishing) action() }
    }

    private fun appendLog(text: String) {
        if (logText.length > MaxLogChars) {
            logText.delete(0, logText.length - MaxLogChars)
        }
        logText.append(text).append('\n')
        // 必须转成 String：直接传 StringBuilder 时 TextView 可能认为内容没变而跳过重绘。
        outputView.text = logText.toString()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private companion object {
        const val PrefsName = "momoadb_lite"
        const val KeyHost = "host"
        const val KeyPort = "port"
        const val DefaultPort = 5555
        const val MaxLogChars = 60_000
        const val MaxPreviewWidth = 1280
        const val RequestPickApk = 1001

        const val KeyEventHome = 3
        const val KeyEventBack = 4
        const val KeyEventPower = 26
        const val KeyEventVolumeUp = 24
        const val KeyEventVolumeDown = 25

        /** 4.4 的 mksh 支持 $( ) 命令替换。 */
        const val DeviceInfoCommand =
            "echo 品牌=\$(getprop ro.product.brand);" +
                "echo 型号=\$(getprop ro.product.model);" +
                "echo 系统=\$(getprop ro.build.version.release);" +
                "echo SDK=\$(getprop ro.build.version.sdk);" +
                "echo ABI=\$(getprop ro.product.cpu.abi)"
    }
}
