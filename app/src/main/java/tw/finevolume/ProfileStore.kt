package tw.finevolume

import android.content.Context
import org.json.JSONObject

/**
 * 一份音量設定。
 * - 裝置設定：key = 裝置 key，app 為空。記細調音量、啟用、防爆音、系統音量。
 * - App 設定：key = 裝置 key + APP_SEP + 套件名稱。只記細調音量，其他沿用裝置設定。
 */
data class Profile(
    val key: String,
    val name: String,
    val gain: Float = 1f,
    val enabled: Boolean = true,
    val limiter: Boolean = true,
    /** 記住的系統媒體音量格數，-1 = 尚未記錄 */
    val sysVolume: Int = -1,
    val lastUsed: Long = 0L,
    /** App 設定才有：套件名稱與顯示名稱 */
    val app: String = "",
    val appLabel: String = "",
) {
    val isApp get() = app.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("name", name).put("gain", gain.toDouble())
        .put("enabled", enabled).put("limiter", limiter)
        .put("sysVolume", sysVolume).put("lastUsed", lastUsed)
        .put("app", app).put("appLabel", appLabel)

    companion object {
        fun fromJson(o: JSONObject) = Profile(
            key = o.getString("key"),
            name = o.optString("name", o.getString("key")),
            gain = o.optDouble("gain", 1.0).toFloat(),
            enabled = o.optBoolean("enabled", true),
            limiter = o.optBoolean("limiter", true),
            sysVolume = o.optInt("sysVolume", -1),
            lastUsed = o.optLong("lastUsed", 0L),
            app = o.optString("app", ""),
            appLabel = o.optString("appLabel", ""),
        )
    }
}

class ProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    fun get(key: String): Profile? =
        prefs.getString("p:$key", null)?.let { runCatching { Profile.fromJson(JSONObject(it)) }.getOrNull() }

    /** 取得裝置設定；第一次遇到的裝置自動建立一份 100% 的設定 */
    fun getOrCreate(key: String, name: String): Profile {
        val existing = get(key)
        if (existing != null) return existing
        val p = Profile(key = key, name = name, lastUsed = System.currentTimeMillis())
        save(p)
        return p
    }

    fun save(p: Profile) {
        prefs.edit().putString("p:${p.key}", p.toJson().toString()).apply()
    }

    fun delete(key: String) {
        val e = prefs.edit().remove("p:$key")
        // 刪除裝置時，連同它底下的 App 設定一起刪
        if (!key.contains(APP_SEP)) {
            prefs.all.keys.filter { it.startsWith("p:$key$APP_SEP") }.forEach { e.remove(it) }
        }
        e.apply()
    }

    fun appKey(deviceKey: String, pkg: String) = "$deviceKey$APP_SEP$pkg"

    fun getApp(deviceKey: String, pkg: String?): Profile? = pkg?.let { get(appKey(deviceKey, it)) }

    /** 這個裝置 + App 實際要用的音量：有 App 設定就用它，沒有就用裝置預設 */
    fun effectiveGain(device: Profile, pkg: String?): Float = getApp(device.key, pkg)?.gain ?: device.gain

    fun devices(): List<Profile> = all().filter { !it.isApp }

    fun appsFor(deviceKey: String): List<Profile> =
        all().filter { it.isApp && it.key.startsWith("$deviceKey$APP_SEP") }.sortedBy { it.appLabel }

    fun all(): List<Profile> = prefs.all.entries
        .filter { it.key.startsWith("p:") }
        .mapNotNull { e -> (e.value as? String)?.let { runCatching { Profile.fromJson(JSONObject(it)) }.getOrNull() } }
        .sortedByDescending { it.lastUsed }

    companion object {
        const val APP_SEP = "##app:"
    }

    var serviceOn: Boolean
        get() = prefs.getBoolean("serviceOn", false)
        set(v) { prefs.edit().putBoolean("serviceOn", v).apply() }

    var toneKind: String?
        get() = prefs.getString("toneKind", null)
        set(v) { prefs.edit().putString("toneKind", v).apply() }

    var toneUri: String?
        get() = prefs.getString("toneUri", null)
        set(v) { prefs.edit().putString("toneUri", v).apply() }

    var rememberSysVolume: Boolean
        get() = prefs.getBoolean("rememberSysVolume", true)
        set(v) { prefs.edit().putBoolean("rememberSysVolume", v).apply() }
}
