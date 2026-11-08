package dev.armchallenge.cyberassistant.core

data class ThreatFinding(
    val category: String,
    val label: String,
    val modelId: String,
    val modelVersion: String,
    val indicators: List<String>,
    val limitation: String,
    val safeActions: List<String>,
)

sealed interface ScanState {
    data object Idle : ScanState
    data object Loading : ScanState
    data class Complete(val finding: ThreatFinding) : ScanState
    data class Error(val message: String) : ScanState
}
