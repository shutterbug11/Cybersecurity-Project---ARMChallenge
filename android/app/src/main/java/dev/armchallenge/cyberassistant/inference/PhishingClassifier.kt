package dev.armchallenge.cyberassistant.inference

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.armchallenge.cyberassistant.core.ThreatFinding
import java.nio.FloatBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import org.json.JSONObject
import org.tartarus.snowball.ext.EnglishStemmer

class PhishingClassifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val assetDir = "phishing-v1"
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private val manifest by lazy {
        JSONObject(context.assets.open("$assetDir/manifest.json").bufferedReader().use { it.readText() })
    }
    private val vocabulary: Map<String, Int> by lazy {
        val bytes = context.assets.open("$assetDir/vocabulary.txt").use { it.readBytes() }
        require(sha256(bytes) == manifest.getJSONObject("artifacts").getString("vocabulary.txt")) {
            "Phishing vocabulary integrity check failed"
        }
        bytes.inputStream().bufferedReader().useLines { lines ->
            lines.withIndex().associate { it.value to it.index }
        }.also { require(it.size == manifest.getInt("feature_count")) }
    }
    private val session: OrtSession by lazy {
        val model = context.filesDir.resolve("phishing_lr.onnx")
        val expected = manifest.getJSONObject("artifacts").getString("phishing_lr.onnx")
        if (!model.exists() || sha256(model.readBytes()) != expected) {
            context.assets.open("$assetDir/phishing_lr.onnx").use { input ->
                Files.copy(input, model.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        require(sha256(model.readBytes()) == expected) { "Phishing model integrity check failed" }
        environment.createSession(model.absolutePath, OrtSession.SessionOptions())
    }

    fun classify(raw: String): ThreatFinding {
        val url = raw.trim()
        require(url.length in 4..4096) { "Enter a URL between 4 and 4096 characters" }
        val uri = try { java.net.URI(url) } catch (_: Exception) { null }
        require(uri != null && uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()) {
            "Enter a complete http:// or https:// URL"
        }
        val tokens = Regex("[A-Za-z]+").findAll(url).map { match ->
            val stemmer = EnglishStemmer()
            stemmer.current = match.value.lowercase(Locale.ROOT)
            stemmer.stem()
            stemmer.current
        }.filter { it.length >= 2 }.toList()
        val features = FloatArray(vocabulary.size)
        for (token in tokens) vocabulary[token]?.let { features[it] += 1f }
        val label = OnnxTensor.createTensor(environment, FloatBuffer.wrap(features), longArrayOf(1, features.size.toLong())).use { tensor ->
            session.run(mapOf("X" to tensor)).use { results ->
                (results[0].value as Array<*>)[0] as String
            }
        }
        val indicators = buildList {
            if (uri.scheme.equals("http", ignoreCase = true)) add("unencrypted_http")
            if (uri.host?.startsWith("xn--") == true) add("punycode_host")
            if (url.contains('@')) add("at_sign")
            if (url.length > 150) add("long_url")
        }
        return ThreatFinding(
            category = "Phishing URL",
            label = label,
            modelId = "phishing-lr",
            modelVersion = "1",
            indicators = indicators,
            limitation = "This is a model prediction from URL text. It does not prove the site is safe or malicious. The model score is not calibrated.",
            safeActions = if (label == "bad") listOf(
                "Do not open this link or enter passwords.",
                "Use the organization's official app or type its known address yourself.",
                "If you entered credentials, change them using the official site and enable two-factor authentication."
            ) else listOf(
                "Check the sender and destination before opening the link.",
                "Avoid entering sensitive details if you did not expect this link."
            )
        )
    }

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { "%02x".format(it) }
}
