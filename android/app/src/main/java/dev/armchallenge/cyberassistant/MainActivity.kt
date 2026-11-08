package dev.armchallenge.cyberassistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.armchallenge.cyberassistant.core.ScanState
import dev.armchallenge.cyberassistant.ui.HistoryViewModel
import dev.armchallenge.cyberassistant.ui.ScanViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppNavigation(shared)
            }
        }
    }
}

private val routes = listOf(
    "home", "url", "sms", "network", "malware", "details", "advisor",
    "history", "settings", "models", "permissions", "onboarding", "splash"
)

@Composable
private fun AppNavigation(sharedText: String?) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "splash") {
        composable("splash") {
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                val onboarded = context.getSharedPreferences("settings", 0).getBoolean("onboarded", false)
                val target = if (!onboarded) "onboarding" else if (sharedText != null && sharedText.startsWith("http")) "url" else if (sharedText != null) "sms" else "home"
                nav.navigate(target) { popUpTo("splash") { inclusive = true } }
            }
            Page("Initializing", "Checking local resources…", nav)
        }
        composable("onboarding") {
            val context = LocalContext.current
            Page("Welcome", "Scans and advice run on your device. Only supported inputs can be checked, and predictions are not guarantees.", nav) {
                Button(onClick = {
                    context.getSharedPreferences("settings", 0).edit().putBoolean("onboarded", true).apply()
                    nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                }) { Text("Continue") }
            }
        }
        composable("home") { Home(nav) }
        composable("url") { UrlScanner(nav, sharedText?.takeIf { it.startsWith("http") }.orEmpty()) }
        composable("sms") { Page("SMS scanner", "The original scaler has been reconstructed and the saved classifier matched Python ONNX outputs. Android tokenization and feature parity remain unverified, so classification is unavailable. No SMS permission is requested.", nav) {
            OutlinedTextField(value = sharedText?.takeUnless { it.startsWith("http") }.orEmpty(), onValueChange = {}, readOnly = true, label = { Text("Shared text") }, modifier = Modifier.fillMaxWidth())
        } }
        composable("network") { Page("Network monitor", "VPN flow extraction and the DDoS model are not validated. Monitoring is off; no network traffic is captured.", nav) }
        composable("malware") { Page("Malware scanner", "A Windows PE classifier has been exported, but Android PE feature extraction is not validated. The CNN expects specially prepared images. Device malware scanning is unavailable.", nav) }
        composable("details") { Page("Threat details", "Open a completed scan to see its evidence and safe actions. No scan is selected.", nav) }
        composable("advisor") { Page("AI security advisor", "The required local Llama model has not been provisioned or validated. Rule-based guidance remains available in scan results.", nav) }
        composable("history") { HistoryScreen(nav) }
        composable("settings") { Page("Settings", "Local history can be deleted from the History screen. No telemetry or cloud inference is enabled.", nav) {
            LinkButton("Model management", nav, "models")
            LinkButton("Permissions", nav, "permissions")
        } }
        composable("models") { Page("Offline AI status", "Phishing LR: bundled ONNX candidate. Llama, SMS, DDoS, and malware models: unavailable or not validated for Android.", nav) }
        composable("permissions") { Page("Permissions", "Manual URL scanning needs no special permission. SMS inbox access and VPN monitoring are not enabled.", nav) }
    }
}

@Composable
private fun Page(title: String, description: String, nav: NavHostController, content: @Composable () -> Unit = {}) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(description)
            content()
            if (title != "Welcome") TextButton(onClick = { nav.navigate("home") }) { Text("Home") }
        }
    }
}

@Composable
private fun LinkButton(label: String, nav: NavHostController, route: String) {
    Button(onClick = { nav.navigate(route) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun Home(nav: NavHostController) {
    Page("Security dashboard", "Offline processing • Manual scans only. No whole-device security score or continuous protection is claimed.", nav) {
        LinkButton("Scan a URL", nav, "url")
        LinkButton("Check an SMS", nav, "sms")
        LinkButton("Network monitor status", nav, "network")
        LinkButton("Malware scanner status", nav, "malware")
        LinkButton("Threat history", nav, "history")
        LinkButton("AI advisor status", nav, "advisor")
        LinkButton("Settings", nav, "settings")
    }
}

@Composable
private fun UrlScanner(nav: NavHostController, initialUrl: String) {
    val vm: ScanViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    var input by rememberSaveable { mutableStateOf(initialUrl) }
    Page("URL scanner", "The URL text is analyzed locally. This app never opens the link. A prediction is not proof of safety or harm.", nav) {
        OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("http:// or https:// URL") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Button(onClick = { vm.scanUrl(input) }, enabled = state !is ScanState.Loading) { Text("Analyze URL") }
        when (val result = state) {
            ScanState.Idle -> Text("Enter a complete link to scan.")
            ScanState.Loading -> CircularProgressIndicator()
            is ScanState.Error -> Text(result.message, color = MaterialTheme.colorScheme.error)
            is ScanState.Complete -> {
                val finding = result.finding
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (finding.label == "bad") "Potential phishing link" else "No phishing pattern detected", style = MaterialTheme.typography.titleLarge)
                        Text(finding.limitation)
                        if (finding.indicators.isNotEmpty()) Text("Independent URL indicators: ${finding.indicators.joinToString()}")
                        Text("Suggested actions", fontWeight = FontWeight.Bold)
                        finding.safeActions.forEach { Text("• $it") }
                        Text("Model: ${finding.modelId} v${finding.modelVersion}")
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(nav: NavHostController) {
    val vm: HistoryViewModel = hiltViewModel()
    val events by vm.events.collectAsState(initial = emptyList())
    Page("Threat history", "Only result labels and indicator codes are saved. Raw URLs and messages are not retained.", nav) {
        if (events.isEmpty()) Text("No scans saved yet.")
        else {
            Button(onClick = vm::deleteAll) { Text("Delete all history") }
            events.forEach { event ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${event.type.uppercase()}: ${event.resultLabel}")
                        Text("Model ${event.modelId} v${event.modelVersion}")
                        Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(event.timestamp)))
                        TextButton(onClick = { vm.delete(event.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}
