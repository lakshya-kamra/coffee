package com.laksh.coffee.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laksh.coffee.app
import com.laksh.coffee.data.backup.BackupExporter
import com.laksh.coffee.data.backup.BackupImporter
import com.laksh.coffee.data.backup.ImportOptions
import com.laksh.coffee.data.backup.ImportState
import com.laksh.coffee.domain.backup.Counts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ExportState {
    data object Idle : ExportState
    data object Saving : ExportState
    data class Saved(val counts: Counts, val photosWithData: Int, val photosLeftOut: Int = 0) : ExportState
    data object Failed : ExportState
}

data class BackupUi(
    val includePhotos: Boolean = true,
    /** Null until the first estimate is in. */
    val estimate: BackupExporter.Estimate? = null,
    val export: ExportState = ExportState.Idle,
)

/**
 * Gear › App › YOUR DATA. Saving and restoring run in the app's scope, not this ViewModel's,
 * so a backup being written or an import under way finishes even if the screen is left.
 */
class BackupViewModel(
    private val exporter: BackupExporter,
    private val importer: BackupImporter,
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val mutableUi = MutableStateFlow(BackupUi())
    val ui: StateFlow<BackupUi> = mutableUi.asStateFlow()

    val import: StateFlow<ImportState> = importer.state

    init {
        refreshEstimate()
    }

    /** Re-read the sizes: called when the section comes on screen and after an import. */
    fun refreshEstimate() {
        viewModelScope.launch {
            val estimate = try {
                exporter.estimate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (estimate != null) mutableUi.update { it.copy(estimate = estimate) }
        }
    }

    fun setIncludePhotos(on: Boolean) = mutableUi.update { it.copy(includePhotos = on) }

    /** Writes the backup into the document the person just created. */
    fun save(uri: Uri) {
        if (mutableUi.value.export == ExportState.Saving) return
        val includePhotos = mutableUi.value.includePhotos
        mutableUi.update { it.copy(export = ExportState.Saving) }
        appScope.launch {
            val next = try {
                val written = exporter.writeTo(uri, includePhotos = includePhotos)
                ExportState.Saved(written.counts, written.photosWithData, written.photosLeftOut)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ExportState.Failed
            }
            mutableUi.update { it.copy(export = next) }
        }
    }

    fun open(uri: Uri) = importer.open(uri)

    fun confirm(options: ImportOptions) = importer.confirm(options)

    fun dismissImport() {
        importer.dismiss()
        refreshEstimate()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { BackupViewModel(this.app.backupExporter, this.app.backupImporter, this.app.appScope) }
        }
    }
}
