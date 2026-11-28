package com.example.bpm_player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.bpm_player.databinding.ActivitySettingsBinding
import com.example.bpm_player.stem.StemSeparator
import com.google.android.material.snackbar.Snackbar

/** Configurações: por enquanto, onde salvar os elementos separados de cada música. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@registerForActivityResult
        try {
            // permissão permanente: a pasta continua acessível depois de reiniciar o aparelho
            contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            StemSeparator.storageTree(this)?.let { releaseOld(it, keep = tree) }
            StemSeparator.setStorageTree(this, tree)
        } catch (e: SecurityException) {
            Snackbar.make(binding.root, R.string.settings_folder_error, Snackbar.LENGTH_LONG).show()
        }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.settingsToolbar.setNavigationOnClickListener { finish() }
        binding.chooseStemsFolder.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            pickFolder.launch(StemSeparator.storageTree(this))
        }
        binding.useAppStorage.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            StemSeparator.storageTree(this)?.let { releaseOld(it, keep = null) }
            StemSeparator.setStorageTree(this, null)
            render()
        }
        render()
    }

    private fun releaseOld(old: Uri, keep: Uri?) {
        if (old == keep) return
        try {
            contentResolver.releasePersistableUriPermission(
                old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) { }
    }

    private fun render() {
        val tree = StemSeparator.storageTree(this)
        binding.stemsLocation.text = if (tree == null) {
            getString(R.string.settings_stems_internal)
        } else {
            // "primary:Music/BeatTrack" → "Music/BeatTrack": legível sem expor o URI
            val path = DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "/" }
            getString(R.string.settings_stems_folder, path)
        }
        binding.useAppStorage.visibility = if (tree == null) View.GONE else View.VISIBLE
    }
}
