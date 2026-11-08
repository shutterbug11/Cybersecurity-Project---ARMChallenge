package dev.armchallenge.cyberassistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.armchallenge.cyberassistant.core.ScanState
import dev.armchallenge.cyberassistant.data.ScanEvent
import dev.armchallenge.cyberassistant.data.ScanEventDao
import dev.armchallenge.cyberassistant.inference.PhishingClassifier
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val classifier: PhishingClassifier,
    private val dao: ScanEventDao,
) : ViewModel() {
    private val mutableState = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = mutableState

    fun scanUrl(url: String) {
        if (mutableState.value is ScanState.Loading) return
        viewModelScope.launch {
            mutableState.value = ScanState.Loading
            try {
                val finding = withContext(Dispatchers.Default) { classifier.classify(url) }
                dao.insert(ScanEvent(
                    type = "url", timestamp = System.currentTimeMillis(),
                    resultLabel = finding.label, modelId = finding.modelId,
                    modelVersion = finding.modelVersion,
                    indicatorCodes = finding.indicators.joinToString(","), status = "complete"
                ))
                mutableState.value = ScanState.Complete(finding)
            } catch (error: Exception) {
                mutableState.value = ScanState.Error(error.message ?: "The scan could not be completed")
            }
        }
    }
}
