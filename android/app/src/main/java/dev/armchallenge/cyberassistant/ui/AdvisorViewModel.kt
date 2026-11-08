package dev.armchallenge.cyberassistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.armchallenge.cyberassistant.core.ThreatFinding
import dev.armchallenge.cyberassistant.data.ScanEventDao
import dev.armchallenge.cyberassistant.inference.LocalLlamaAdvisor
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AdvisorViewModel @Inject constructor(
    private val advisor: LocalLlamaAdvisor,
    private val dao: ScanEventDao,
) : ViewModel() {
    private val mutableState = MutableStateFlow<AdvisorState>(AdvisorState.Idle)
    val state: StateFlow<AdvisorState> = mutableState

    fun explainLatest() {
        if (mutableState.value is AdvisorState.Loading) return
        viewModelScope.launch {
            mutableState.value = AdvisorState.Loading
            try {
                val event = dao.latest() ?: error("Scan a URL first so there is a result to explain")
                require(event.type == "url" && event.modelId == "phishing-lr") {
                    "This scan type is not supported by the advisor yet"
                }
                val actions = if (event.resultLabel == "bad") listOf(
                    "Do not open the link or enter passwords.",
                    "Use the organization's official app or type its known address yourself.",
                    "If you entered credentials, change them on the official site and enable two-factor authentication."
                ) else listOf(
                    "Check the sender and destination before opening the link.",
                    "Avoid entering sensitive details if you did not expect this link."
                )
                val finding = ThreatFinding(
                    category = "Phishing URL", label = event.resultLabel,
                    modelId = event.modelId, modelVersion = event.modelVersion,
                    indicators = event.indicatorCodes.split(',').filter(String::isNotBlank),
                    limitation = "URL text classifier prediction; confidence is not calibrated and the destination was not opened.",
                    safeActions = actions,
                )
                val explanation = advisor.explain(finding)
                mutableState.value = AdvisorState.Complete(explanation, actions)
            } catch (error: Exception) {
                mutableState.value = AdvisorState.Error(error.message ?: "Local advisor failed")
            }
        }
    }
}

sealed interface AdvisorState {
    data object Idle : AdvisorState
    data object Loading : AdvisorState
    data class Complete(val explanation: String, val safeActions: List<String>) : AdvisorState
    data class Error(val message: String) : AdvisorState
}
