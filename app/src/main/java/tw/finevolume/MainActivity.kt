package tw.finevolume

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var store: ProfileStore
    private lateinit var am: AudioManager

    private lateinit var status: TextView
    private lateinit var serviceSwitch: Switch
    private lateinit var compatNote: TextView
    private lateinit var deviceKind: TextView
    private lateinit var deviceName: TextView
    private lateinit var backToCurrent: TextView
    private lateinit var editingNote: TextView
    private lateinit var pct: TextView
    private lateinit var db: TextView
    private lateinit var tag: TextView
    private lateinit var seek: SeekBar
    private lateinit var presets: LinearLayout
    private lateinit var enabledSwitch: Switch
    private lateinit var limiterSwitch: Switch
    private lateinit var sysVolSwitch: Switch
    private lateinit var deviceList: LinearLayout

    /** 指定編輯某個已記住的裝置／App；null = 跟著目前的裝置與 App */
    private var pinnedDevice: String? = null
    private var pinnedApp: String? = null
    /** 跟著目前裝置時，使用者選了「裝置預設」而不是目前 App */
    private var useDeviceDefault = false
    private var lastSeenApp: String? = null

    private lateinit var accessCard: View
    private lateinit var mediaAccessState: TextView
    private lateinit var usageAccessState: TextView
    private lateinit var mediaAccessBtn: Button
    private lateinit var usageAccessBtn: Button
    private lateinit var targetRow: View
    private lateinit var targetApp: Button
    private lateinit var targetDevice: Button
    private lateinit var appNote: TextView
    private lateinit var resetApp: TextView
    private var binding = false

    private val presetValues = floatArrayOf(0.02f, 0.1f, 0.3f, 1f, 2f, 4f)
    private val presetButtons = mutableListOf<Button>()
    private lateinit var testTone: TestTone
    private lateinit var toneButton: Button
    private lateinit var pickMusic: Button
    private lateinit var toneFile: TextView
    private var toneSession = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = ProfileStore(this)
        am = getSystemService(AUDIO_SERVICE) as AudioManager

        status = findViewById(R.id.status)
        serviceSwitch = findViewById(R.id.serviceSwitch)
        compatNote = findViewById(R.id.compatNote)
        deviceKind = findViewById(R.id.deviceKind)
        deviceName = findViewById(R.id.deviceName)
        backToCurrent = findViewById(R.id.backToCurrent)
        editingNote = findViewById(R.id.editingNote)
        pct = findViewById(R.id.pct)
        db = findViewById(R.id.db)
        tag = findViewById(R.id.tag)
        seek = findViewById(R.id.seek)
        presets = findViewById(R.id.presets)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        limiterSwitch = findViewById(R.id.limiterSwitch)
        sysVolSwitch = findViewById(R.id.sysVolSwitch)
        deviceList = findViewById(R.id.deviceList)
        accessCard = findViewById(R.id.accessCard)
        mediaAccessState = findViewById(R.id.mediaAccessState)
        usageAccessState = findViewById(R.id.usageAccessState)
        mediaAccessBtn = findViewById(R.id.mediaAccessBtn)
        usageAccessBtn = findViewById(R.id.usageAccessBtn)
        targetRow = findViewById(R.id.targetRow)
        targetApp = findViewById(R.id.targetApp)
        targetDevice = findViewById(R.id.targetDevice)
        appNote = findViewById(R.id.appNote)
        resetApp = findViewById(R.id.resetApp)
        mediaAccessBtn.setOnClickListener { runCatching { startActivity(Access.mediaSettingsIntent()) } }
        usageAccessBtn.setOnClickListener { runCatching { startActivity(Access.usageSettingsIntent()) } }
        listOf(mediaAccessBtn, usageAccessBtn).forEach {
            it.backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent))
            it.setTextColor(getColor(R.color.accentInk))
        }
        targetApp.setOnClickListener {
            if (pinnedDevice != null) { if (pinnedApp == null) pinnedApp = VolumeService.currentApp }
            else useDeviceDefault = false
            refresh()
        }
        targetDevice.setOnClickListener {
            if (pinnedDevice != null) pinnedApp = null else useDeviceDefault = true
            refresh()
        }
        resetApp.setOnClickListener {
            val s = selection()
            s.app?.let { store.delete(store.appKey(s.device.key, it)) }
            VolumeService.send(this, VolumeService.ACTION_APPLY)
            refresh()
        }
        testTone = TestTone(this)
        toneButton = findViewById(R.id.toneButton)
        pickMusic = findViewById(R.id.pickMusic)
        toneFile = findViewById(R.id.toneFile)
        toneButton.setOnClickListener { toggleTone() }
        pickMusic.setOnClickListener { pickMusicFile() }
        pickMusic.backgroundTintList = ColorStateList.valueOf(getColor(R.color.panel2))
        pickMusic.setTextColor(getColor(R.color.fg))
        updateToneButton()

        buildPresets()

        serviceSwitch.setOnCheckedChangeListener { _, on ->
            if (binding) return@setOnCheckedChangeListener
            store.serviceOn = on
            if (on) {
                askNotificationPermission()
                VolumeService.start(this)
            } else {
                VolumeService.send(this, VolumeService.ACTION_STOP)
            }
            seek.postDelayed({ refresh() }, 400)
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) editGain { GainMath.posToGain(progress) }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        findViewById<Button>(R.id.down).setOnClickListener { editGain { g -> GainMath.nudge(g, -1) } }
        findViewById<Button>(R.id.up).setOnClickListener { editGain { g -> GainMath.nudge(g, 1) } }
        enabledSwitch.setOnCheckedChangeListener { _, v -> if (!binding) editDevice { it.copy(enabled = v) } }
        limiterSwitch.setOnCheckedChangeListener { _, v -> if (!binding) editDevice { it.copy(limiter = v) } }
        sysVolSwitch.setOnCheckedChangeListener { _, v -> if (!binding) store.rememberSysVolume = v }
        backToCurrent.setOnClickListener { pinnedDevice = null; pinnedApp = null; useDeviceDefault = false; refresh() }

        // 上次是開啟的，打開 App 時順手確保服務在跑
        if (store.serviceOn && !VolumeService.running) runCatching { VolumeService.start(this) }
    }

    override fun onResume() {
        super.onResume()
        VolumeService.listener = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onDestroy() {
        stopTone()
        super.onDestroy()
    }

    private fun pickMusicFile() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
        runCatching { startActivityForResult(pick, REQ_PICK_AUDIO) }
    }

    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_AUDIO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        store.toneUri = uri.toString()
        playTone()
        updateToneButton()
    }

    private fun currentToneUri(): Uri? = store.toneUri?.let { runCatching { Uri.parse(it) }.getOrNull() }

    private fun fileName(uri: Uri?): String? = uri?.let {
        runCatching {
            contentResolver.query(it, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun toggleTone() {
        if (testTone.isPlaying) stopTone() else playTone()
        updateToneButton()
    }

    private fun playTone() {
        closeToneSession()
        // 有選自己的音樂就播它，沒選（或檔案已刪除）就播預設和弦
        val uri = currentToneUri()
        var session = if (uri != null) testTone.start(ToneKind.FILE, uri) else 0
        if (session == 0) {
            if (uri != null) store.toneUri = null
            session = testTone.start(ToneKind.CHORD, null)
        }
        toneSession = session
        // 相容模式下全域效果無效，把效果直接掛到測試音上
        VolumeService.sendSession(this, session, open = true)
    }

    private fun closeToneSession() {
        if (toneSession != 0) VolumeService.sendSession(this, toneSession, open = false)
        toneSession = 0
    }

    private fun stopTone() {
        testTone.stop()
        closeToneSession()
        updateToneButton()
    }

    private fun updateToneButton() {
        val on = testTone.isPlaying
        toneButton.text = getString(if (on) R.string.tone_stop else R.string.tone_play)
        toneButton.backgroundTintList = ColorStateList.valueOf(getColor(if (on) R.color.accent else R.color.panel2))
        toneButton.setTextColor(getColor(if (on) R.color.accentInk else R.color.fg))
        val name = fileName(currentToneUri())
        toneFile.text = name?.let { "測試音：$it" } ?: "測試音：預設和弦（按「自選音樂」換成你的歌）"
    }

    override fun onPause() {
        VolumeService.listener = null
        super.onPause()
    }

    private fun currentDevice(): OutputDevice {
        val key = VolumeService.currentKey
        val name = VolumeService.currentName
        return if (VolumeService.running && key != null && name != null) {
            DeviceResolver.current(am).takeIf { it.key == key } ?: OutputDevice(key, name, "")
        } else DeviceResolver.current(am)
    }

    /** 目前要編輯的「裝置 + App」；app = null 代表編輯裝置預設 */
    private data class Selection(val device: Profile, val app: String?, val appLabel: String?)

    private fun selection(): Selection {
        val liveApp = VolumeService.currentApp
        if (liveApp != lastSeenApp) { lastSeenApp = liveApp; useDeviceDefault = false }
        pinnedDevice?.let { k ->
            val d = store.get(k)
            if (d != null) return Selection(d, pinnedApp, pinnedApp?.let { labelOf(d.key, it) })
        }
        pinnedDevice = null; pinnedApp = null
        val dev = currentDevice()
        val d = store.getOrCreate(dev.key, dev.name)
        val app = if (useDeviceDefault) null else liveApp
        return Selection(d, app, app?.let { labelOf(d.key, it) })
    }

    private fun labelOf(deviceKey: String, pkg: String): String =
        store.getApp(deviceKey, pkg)?.appLabel?.takeIf { it.isNotEmpty() }
            ?: VolumeService.currentAppLabel?.takeIf { pkg == VolumeService.currentApp }
            ?: Access.appLabel(this, pkg)

    /** 調整音量：選到 App 時存到這個 App（第一次會從裝置預設複製一份），否則存到裝置 */
    private fun editGain(change: (Float) -> Float) {
        val s = selection()
        if (s.app != null) {
            val cur = store.getApp(s.device.key, s.app)
                ?: Profile(key = store.appKey(s.device.key, s.app), name = s.device.name, gain = s.device.gain,
                    app = s.app, appLabel = s.appLabel ?: s.app)
            store.save(cur.copy(gain = GainMath.clamp(change(cur.gain)), lastUsed = System.currentTimeMillis()))
        } else {
            store.save(s.device.copy(gain = GainMath.clamp(change(s.device.gain))))
        }
        VolumeService.send(this, VolumeService.ACTION_APPLY)
        refresh()
    }

    /** 啟用／防爆音屬於裝置設定 */
    private fun editDevice(change: (Profile) -> Profile) {
        store.save(change(selection().device))
        VolumeService.send(this, VolumeService.ACTION_APPLY)
        refresh()
    }

    private fun refresh() {
        binding = true
        val running = VolumeService.running
        serviceSwitch.isChecked = running
        status.text = if (running) getString(R.string.status_on) else getString(R.string.status_off)
        compatNote.visibility = if (running && !VolumeService.globalSupported) View.VISIBLE else View.GONE
        refreshAccess()

        val cur = currentDevice()
        val s = selection()
        val d = s.device
        val pinned = pinnedDevice != null
        deviceName.text = d.name
        deviceKind.text = if (d.key != cur.key) "已記住" else cur.kind.ifEmpty { "目前" }
        backToCurrent.visibility = if (pinned) View.VISIBLE else View.GONE
        editingNote.visibility = if (pinned) View.VISIBLE else View.GONE

        // App／裝置預設切換
        val showApp = s.app != null || (!pinned && VolumeService.currentApp != null)
        targetRow.visibility = if (showApp) View.VISIBLE else View.GONE
        val appForButton = s.app ?: VolumeService.currentApp
        val appProfile = store.getApp(d.key, s.app)
        if (showApp && appForButton != null) {
            targetApp.text = s.appLabel ?: labelOf(d.key, appForButton)
            styleToggle(targetApp, s.app != null)
            styleToggle(targetDevice, s.app == null)
        }
        appNote.visibility = if (s.app != null) View.VISIBLE else View.GONE
        resetApp.visibility = if (appProfile != null) View.VISIBLE else View.GONE
        appNote.text = when {
            s.app == null -> ""
            appProfile == null -> getString(R.string.app_inherit, s.appLabel)
            else -> getString(R.string.app_own, s.appLabel)
        }

        val gain = appProfile?.gain ?: d.gain
        pct.text = GainMath.pctText(gain)
        db.text = GainMath.dbText(gain)
        val (label, colorRes) = when {
            !d.enabled -> "未啟用" to R.color.muted
            gain < 0.999f -> "降低" to R.color.cut
            gain > 1.001f -> "放大" to R.color.boost
            else -> "原音" to R.color.muted
        }
        tag.text = label
        tag.setTextColor(getColor(colorRes))
        pct.alpha = if (d.enabled) 1f else 0.4f
        seek.progress = GainMath.gainToPos(gain)
        seek.progressTintList = ColorStateList.valueOf(getColor(if (gain > 1.001f) R.color.boost else R.color.cut))
        enabledSwitch.isChecked = d.enabled
        limiterSwitch.isChecked = d.limiter
        sysVolSwitch.isChecked = store.rememberSysVolume

        presetButtons.forEachIndexed { i, b ->
            val on = abs(presetValues[i] - gain) < 0.0005f
            b.backgroundTintList = ColorStateList.valueOf(getColor(if (on) R.color.accent else R.color.panel2))
            b.setTextColor(getColor(if (on) R.color.accentInk else R.color.fg))
        }

        renderDeviceList(cur.key, appProfile?.key ?: d.key)
        binding = false
    }

    private fun styleToggle(b: Button, on: Boolean) {
        b.backgroundTintList = ColorStateList.valueOf(getColor(if (on) R.color.fg else R.color.panel2))
        b.setTextColor(getColor(if (on) R.color.bg else R.color.fg))
    }

    private fun refreshAccess() {
        val media = Access.hasMediaAccess(this)
        val usage = Access.hasUsageAccess(this)
        accessCard.visibility = if (media && usage) View.GONE else View.VISIBLE
        mediaAccessState.text = if (media) "已開啟" else "未開啟"
        mediaAccessState.setTextColor(getColor(if (media) R.color.cut else R.color.boost))
        mediaAccessBtn.visibility = if (media) View.GONE else View.VISIBLE
        usageAccessState.text = if (usage) "已開啟" else "未開啟"
        usageAccessState.setTextColor(getColor(if (usage) R.color.cut else R.color.boost))
        usageAccessBtn.visibility = if (usage) View.GONE else View.VISIBLE
    }

    private fun buildPresets() {
        presetValues.forEachIndexed { i, v ->
            val b = Button(this).apply {
                text = GainMath.pctText(v)
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                isAllCaps = false
                minWidth = 0; minimumWidth = 0
                setPadding(0, 0, 0, 0)
                stateListAnimator = null
                setOnClickListener { editGain { v } }
            }
            val lp = LinearLayout.LayoutParams(0, dp(44), 1f)
            if (i > 0) lp.marginStart = dp(6)
            presets.addView(b, lp)
            presetButtons += b
        }
    }

    private fun divider() = View(this).apply { setBackgroundColor(getColor(R.color.line)) }

    private fun renderDeviceList(currentKey: String, editing: String) {
        deviceList.removeAllViews()
        val devices = store.devices()
        if (devices.isEmpty()) {
            deviceList.addView(TextView(this).apply {
                text = getString(R.string.no_devices)
                setTextColor(getColor(R.color.muted))
                setPadding(dp(16), dp(14), dp(16), dp(14))
            })
            return
        }
        val liveApp = VolumeService.currentApp
        devices.forEachIndexed { i, d ->
            if (i > 0) deviceList.addView(divider(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            val isCur = d.key == currentKey
            val parts = mutableListOf(
                if (d.enabled) "預設 ${GainMath.pctText(d.gain)}" else "原音",
                if (d.limiter) "防爆音" else "無限幅",
            )
            if (store.rememberSysVolume && d.sysVolume >= 0) parts += "系統音量 ${d.sysVolume}"
            deviceList.addView(listRow(
                title = d.name + if (isCur) "　· 使用中" else "",
                sub = parts.joinToString(" · "),
                indent = 0,
                bold = d.key == editing,
                deletable = !isCur,
                onClick = {
                    if (isCur) { pinnedDevice = null; pinnedApp = null; useDeviceDefault = true }
                    else { pinnedDevice = d.key; pinnedApp = null }
                    refresh()
                },
                onDelete = { store.delete(d.key); if (pinnedDevice == d.key) { pinnedDevice = null; pinnedApp = null } },
            ))
            store.appsFor(d.key).forEach { a ->
                val live = isCur && a.app == liveApp
                deviceList.addView(listRow(
                    title = a.appLabel.ifEmpty { a.app } + if (live) "　· 播放中" else "",
                    sub = "${GainMath.pctText(a.gain)}（${GainMath.dbText(a.gain)}）",
                    indent = 16,
                    bold = a.key == editing,
                    deletable = true,
                    onClick = {
                        if (live) { pinnedDevice = null; pinnedApp = null; useDeviceDefault = false }
                        else { pinnedDevice = d.key; pinnedApp = a.app }
                        refresh()
                    },
                    onDelete = { store.delete(a.key); if (pinnedApp == a.app && pinnedDevice == d.key) pinnedApp = null },
                ))
            }
        }
    }

    private fun listRow(
        title: String, sub: String, indent: Int, bold: Boolean, deletable: Boolean,
        onClick: () -> Unit, onDelete: () -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16 + indent), dp(10), dp(8), dp(10))
            minimumHeight = dp(52)
            isClickable = true
            setBackgroundResource(TypedValue().also {
                theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            }.resourceId)
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = if (indent > 0) "└ $title" else title
            setTextColor(getColor(R.color.fg))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (indent > 0) 14f else 15f)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        })
        texts.addView(TextView(this).apply {
            text = sub
            setTextColor(getColor(R.color.muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            if (indent > 0) setPadding(dp(14), 0, 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (deletable) row.addView(deleteButton(onDelete))
        return row
    }

    /** 兩段式刪除：先按一次變成「確定刪除」，再按一次才刪 */
    private fun deleteButton(onDelete: () -> Unit) = TextView(this).apply {
        text = "刪除"
        setTextColor(getColor(R.color.muted))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        minWidth = dp(64); minHeight = dp(40)
        var armed = false
        setOnClickListener {
            if (!armed) {
                armed = true; text = "確定刪除"; setTextColor(getColor(R.color.boost))
                postDelayed({ if (armed) { armed = false; text = "刪除"; setTextColor(getColor(R.color.muted)) } }, 3000)
            } else {
                onDelete()
                VolumeService.send(this@MainActivity, VolumeService.ACTION_APPLY)
                refresh()
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    companion object {
        private const val REQ_PICK_AUDIO = 2
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
