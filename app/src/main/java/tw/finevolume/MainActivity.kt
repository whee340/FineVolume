package tw.finevolume

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.media.AudioManager
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

    /** 正在編輯的裝置；null = 跟著目前輸出裝置 */
    private var editingKey: String? = null
    private var binding = false

    private val presetValues = floatArrayOf(0.02f, 0.1f, 0.3f, 1f, 2f, 4f)
    private val presetButtons = mutableListOf<Button>()

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
                if (fromUser) edit { it.copy(gain = GainMath.clamp(GainMath.posToGain(progress))) }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        findViewById<Button>(R.id.down).setOnClickListener { edit { it.copy(gain = GainMath.nudge(it.gain, -1)) } }
        findViewById<Button>(R.id.up).setOnClickListener { edit { it.copy(gain = GainMath.nudge(it.gain, 1)) } }
        enabledSwitch.setOnCheckedChangeListener { _, v -> if (!binding) edit { it.copy(enabled = v) } }
        limiterSwitch.setOnCheckedChangeListener { _, v -> if (!binding) edit { it.copy(limiter = v) } }
        sysVolSwitch.setOnCheckedChangeListener { _, v -> if (!binding) store.rememberSysVolume = v }
        backToCurrent.setOnClickListener { editingKey = null; refresh() }

        // 上次是開啟的，打開 App 時順手確保服務在跑
        if (store.serviceOn && !VolumeService.running) runCatching { VolumeService.start(this) }
    }

    override fun onResume() {
        super.onResume()
        VolumeService.listener = { runOnUiThread { refresh() } }
        refresh()
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

    private fun targetProfile(): Profile {
        editingKey?.let { k -> store.get(k)?.let { return it } }
        editingKey = null
        val dev = currentDevice()
        return store.getOrCreate(dev.key, dev.name)
    }

    private fun edit(change: (Profile) -> Profile) {
        store.save(change(targetProfile()))
        VolumeService.send(this, VolumeService.ACTION_APPLY)
        refresh()
    }

    private fun refresh() {
        binding = true
        val running = VolumeService.running
        serviceSwitch.isChecked = running
        status.text = if (running) getString(R.string.status_on) else getString(R.string.status_off)
        compatNote.visibility = if (running && !VolumeService.globalSupported) View.VISIBLE else View.GONE

        val cur = currentDevice()
        val p = targetProfile()
        val editingOther = p.key != cur.key
        deviceName.text = p.name
        deviceKind.text = if (editingOther) "已記住" else cur.kind.ifEmpty { "目前" }
        backToCurrent.visibility = if (editingOther) View.VISIBLE else View.GONE
        editingNote.visibility = if (editingOther) View.VISIBLE else View.GONE

        pct.text = GainMath.pctText(p.gain)
        db.text = GainMath.dbText(p.gain)
        val (label, colorRes) = when {
            !p.enabled -> "未啟用" to R.color.muted
            p.gain < 0.999f -> "降低" to R.color.cut
            p.gain > 1.001f -> "放大" to R.color.boost
            else -> "原音" to R.color.muted
        }
        tag.text = label
        tag.setTextColor(getColor(colorRes))
        pct.alpha = if (p.enabled) 1f else 0.4f
        seek.progress = GainMath.gainToPos(p.gain)
        seek.progressTintList = ColorStateList.valueOf(getColor(if (p.gain > 1.001f) R.color.boost else R.color.cut))
        enabledSwitch.isChecked = p.enabled
        limiterSwitch.isChecked = p.limiter
        sysVolSwitch.isChecked = store.rememberSysVolume

        presetButtons.forEachIndexed { i, b ->
            val on = abs(presetValues[i] - p.gain) < 0.0005f
            b.backgroundTintList = ColorStateList.valueOf(getColor(if (on) R.color.accent else R.color.panel2))
            b.setTextColor(getColor(if (on) R.color.accentInk else R.color.fg))
        }

        renderDeviceList(cur.key, p.key)
        binding = false
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
                setOnClickListener { edit { it.copy(gain = v) } }
            }
            val lp = LinearLayout.LayoutParams(0, dp(44), 1f)
            if (i > 0) lp.marginStart = dp(6)
            presets.addView(b, lp)
            presetButtons += b
        }
    }

    private fun renderDeviceList(currentKey: String, editing: String) {
        deviceList.removeAllViews()
        val all = store.all()
        if (all.isEmpty()) {
            deviceList.addView(TextView(this).apply {
                text = getString(R.string.no_devices)
                setTextColor(getColor(R.color.muted))
                setPadding(dp(16), dp(14), dp(16), dp(14))
            })
            return
        }
        all.forEachIndexed { i, p ->
            if (i > 0) deviceList.addView(View(this).apply { setBackgroundColor(getColor(R.color.line)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(8), dp(10))
                minimumHeight = dp(56)
                isClickable = true
                setBackgroundResource(TypedValue().also {
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                }.resourceId)
                setOnClickListener { editingKey = if (p.key == currentKey) null else p.key; refresh() }
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = p.name + if (p.key == currentKey) "　· 使用中" else ""
                setTextColor(getColor(R.color.fg))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                if (p.key == editing) setTypeface(typeface, Typeface.BOLD)
            })
            texts.addView(TextView(this).apply {
                val parts = mutableListOf(
                    if (p.enabled) "${GainMath.pctText(p.gain)}（${GainMath.dbText(p.gain)}）" else "原音",
                    if (p.limiter) "防爆音" else "無限幅",
                )
                if (store.rememberSysVolume && p.sysVolume >= 0) parts += "系統音量 ${p.sysVolume}"
                text = parts.joinToString(" · ")
                setTextColor(getColor(R.color.muted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (p.key != currentKey) {
                row.addView(DeleteButton(p))
            }
            deviceList.addView(row)
        }
    }

    /** 兩段式刪除：先按一次變成「確定刪除」，再按一次才刪 */
    private fun DeleteButton(p: Profile) = TextView(this).apply {
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
                store.delete(p.key)
                if (editingKey == p.key) editingKey = null
                refresh()
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
