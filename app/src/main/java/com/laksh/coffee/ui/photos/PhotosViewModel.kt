package com.laksh.coffee.ui.photos

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.laksh.coffee.app
import com.laksh.coffee.data.db.entity.PhotoEntity
import com.laksh.coffee.data.photos.AddPhotoResult
import com.laksh.coffee.data.photos.CameraTarget
import com.laksh.coffee.data.photos.PhotoOwner
import com.laksh.coffee.data.photos.PhotoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PhotosUi(
    val photos: List<PhotoEntity> = emptyList(),
    /** False until Room's first answer, so "no photos" is never shown for a brew that has some. */
    val loaded: Boolean = false,
    /** Photos being read and re-saved right now; the strip shows a placeholder for each. */
    val adding: Int = 0,
    /** One line about the last thing that did not work, or null. */
    val message: String? = null,
    val limit: Int = 0,
) {
    val full: Boolean get() = photos.size + adding >= limit
}

/** The photos of one brew or one bag: what the strip shows and every action on them. */
class PhotosViewModel(
    private val repo: PhotoRepository,
    val owner: PhotoOwner,
) : ViewModel() {

    private val adding = MutableStateFlow(0)
    private val message = MutableStateFlow<String?>(null)
    private val limit = PhotoRepository.limit(owner)

    val state: StateFlow<PhotosUi> = combine(
        // A read failure shows as an empty strip; it must not take the screen down.
        repo.observe(owner).catch { emit(emptyList()) },
        adding,
        message,
    ) { photos, busy, msg ->
        PhotosUi(photos = photos, loaded = true, adding = busy, message = msg, limit = limit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PhotosUi(limit = limit))

    private val fullMessage: String = when (owner) {
        is PhotoOwner.Brew -> "That's the most for one brew."
        is PhotoOwner.Bean -> "That's the most for one bag."
    }

    /** True when another photo may be added; otherwise says why not. */
    fun canAdd(): Boolean {
        val full = state.value.loaded && state.value.full
        if (full) message.value = fullMessage
        return !full
    }

    fun newCameraTarget(): CameraTarget? = repo.newCameraTarget().also {
        if (it == null) message.value = "The camera couldn't be opened."
    }

    fun noCameraApp() {
        message.value = "No camera app on this phone."
    }

    fun noPicker() {
        message.value = "No photo picker on this phone."
    }

    fun addFromLibrary(uri: Uri, kind: String) = add { repo.addFromLibrary(uri, kind, owner) }

    fun addFromCamera(name: String, kind: String) = add { repo.addFromCamera(name, kind, owner) }

    fun discardCamera(name: String) {
        viewModelScope.launch { repo.discardCamera(name) }
    }

    private fun add(block: suspend () -> AddPhotoResult) {
        message.value = null
        adding.update { it + 1 }
        viewModelScope.launch {
            try {
                when (block()) {
                    is AddPhotoResult.Added -> Unit
                    AddPhotoResult.Full -> message.value = fullMessage
                    AddPhotoResult.Unreadable -> message.value = "That photo couldn't be read."
                }
            } finally {
                adding.update { (it - 1).coerceAtLeast(0) }
            }
        }
    }

    fun setKind(photo: PhotoEntity, kind: String) {
        viewModelScope.launch { repo.setKind(photo.uid, kind) }
    }

    fun setCaption(photo: PhotoEntity, caption: String) {
        viewModelScope.launch { repo.setCaption(photo.uid, caption) }
    }

    fun setSendToAi(photo: PhotoEntity, send: Boolean) {
        viewModelScope.launch { repo.setSendToAi(photo.uid, send) }
    }

    fun delete(photo: PhotoEntity) {
        viewModelScope.launch {
            repo.delete(photo)
            ThumbnailCache.evict(photo.uid)
        }
    }

    fun clearMessage() {
        message.value = null
    }

    companion object {
        fun key(owner: PhotoOwner): String = when (owner) {
            is PhotoOwner.Brew -> "photos-brew-${owner.brewLogId}"
            is PhotoOwner.Bean -> "photos-bean-${owner.beanId}"
        }

        fun factory(owner: PhotoOwner): ViewModelProvider.Factory = viewModelFactory {
            initializer { PhotosViewModel(this.app.photos, owner) }
        }
    }
}
