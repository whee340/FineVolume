package tw.finevolume

import android.content.Context
import org.json.JSONObject

/** 每個輸出裝置一份設定 */
data class Profile(
    val key: String,
    val name: String,
    val gain: Float = 1f,
    val enabled: Boolean = true,
    val limiter: Boolean = true,
    /** 記住的系統媒體音量格數，-1 = 尚未記錄 */
    val sysVolume: Int = -1,
    val lastUsed: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("name", name).put("gain", gain.toDouble())
        .put("enabled", enabled).put("limiter", limiter)
        .put("sysVolume", sysVolume).put("lastUsed", lastUsed)

    companion object {
        fun fromJson(o: JSONObject) = Profile(
            key = o.getString("key"),
            name = o.optString("name", o.getString("key")),
            gain = o.optDouble("gain", 1.0).toFloat(),
            enabled = o.optBoolean("enabled", true),
            limiter = o.optBoolean("limiter", true),
            sysVolume = o.optInt("sysVolume", -1),
            lastUsed = o.optLong("lastUsed", 0L),
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
        prefs.edit().remove("p:$key").apply()
    }

    fun all(): List<Profile> = prefs.all.entries
        .filter { it.key.startsWith("p:") }
        .mapNotNull { e -> (e.value as? String)?.let { runCatching { Profile.fromJson(JSONObject(it)) }.getOrNull() } }
        .sortedByDescending { it.lastUsed }

    var serviceOn: Boolean
        get() = prefs.getBoolean("serviceOn", false)
        set(v) { prefs.edit().putBoolean("serviceOn", v).apply() }

    var rememberSysVolume: Boolean
        get() = prefs.getBoolean("rememberSysVolume", true)
        set(v) { prefs.edit().putBoolean("rememberSysVolume", v).apply() }
}
