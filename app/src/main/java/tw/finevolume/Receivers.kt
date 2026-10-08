package tw.finevolume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

/** 開機或 App 更新後，若先前是開啟狀態就自動啟動 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ProfileStore(context).serviceOn) {
            runCatching { VolumeService.start(context) }
        }
    }
}

/** 播放器宣告音效工作階段（相容模式用） */
class SessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!VolumeService.running) return
        val action = when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> VolumeService.ACTION_SESSION_OPEN
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> VolumeService.ACTION_SESSION_CLOSE
            else -> return
        }
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
        runCatching {
            context.startService(
                Intent(context, VolumeService::class.java)
                    .setAction(action)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
            )
        }
    }
}
