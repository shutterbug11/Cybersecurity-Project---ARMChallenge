package dev.armchallenge.cyberassistant.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.armchallenge.cyberassistant.inference.LocalLlamaAdvisor
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ModelViewModel @Inject constructor(private val advisor: LocalLlamaAdvisor) : ViewModel() {
    private val mutableStatus = MutableStateFlow("Checking local model files…")
    val status: StateFlow<String> = mutableStatus
    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        mutableStatus.value = if (advisor.isProvisioned()) "Verified Llama pack installed" else "Llama pack not installed or incomplete"
    }

    fun importFile(uri: Uri, tokenizer: Boolean) {
        if (mutableBusy.value) return
        viewModelScope.launch {
            mutableBusy.value = true
            mutableStatus.value = "Copying and verifying ${if (tokenizer) "tokenizer" else "model"} locally…"
            try {
                advisor.importFile(uri, tokenizer)
                refresh()
            } catch (error: Exception) {
                mutableStatus.value = error.message ?: "Import failed"
            } finally {
                mutableBusy.value = false
            }
        }
    }
}
