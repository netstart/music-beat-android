package com.example.bpm_player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Permite testar a reprodução de qualquer música via adb shell:
 *   adb shell am broadcast -a com.example.bpm_player.TEST_PLAY \
 *     --es uri "content://media/external/audio/media/1000003624"
 *
 * Útil para disparar a reprodução de músicas longas sem precisar tocar na tela.
 */
class TestPlayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.getStringExtra("uri")
        if (uri == null) {
            Log.e("BPM_TEST", "TEST_PLAY sem URI")
            return
        }

        Log.i("BPM_TEST", "TEST_PLAY: uri=$uri")

        // Envia a URI para a MainActivity via FLAG_ACTIVITY_NEW_TASK + FLAG_ACTIVITY_SINGLE_TOP.
        // Se a activity já estiver rodando, onNewIntent() é chamado.
        val activityIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(uri)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(activityIntent)
    }
}
