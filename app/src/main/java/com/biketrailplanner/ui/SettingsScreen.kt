package com.biketrailplanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.biketrailplanner.BuildConfig
import com.biketrailplanner.data.AiFailure
import com.biketrailplanner.data.AiKeyStore
import com.biketrailplanner.data.AiReply
import com.biketrailplanner.data.DeepSeekClient
import com.biketrailplanner.data.DeepSeekHttpCall
import com.biketrailplanner.data.EncryptedAiKeyStore
import com.biketrailplanner.ui.theme.GradeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** docs/UI.md section 5: everything technical lives here, one row each. */
@Composable
fun SettingsScreen(
    state: PlanViewModel.UiState,
    onAllowFallback: (Boolean) -> Unit,
    onRefreshTrailData: () -> Unit,
    onSaveCurrentSpotAsHome: () -> Unit,
    onForgetSavedHome: () -> Unit,
    /** D36: opens the system's Notification-access screen. */
    onOpenNotificationSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dot = when {
        state.result?.usedFallback == true -> GradeColors.d
        state.serverReachable == true -> GradeColors.b
        else -> GradeColors.notMeasured
    }
    val connection = when {
        state.result?.usedFallback == true -> "Using the public router"
        state.serverReachable == true -> "Connected to your server"
        else -> "Not checked yet"
    }
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Done") }
        }
        Spacer(Modifier.height(12.dp))

        SettingRow("Server address", state.serverUrl)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            ConnectionDot(dot)
            Spacer(Modifier.width(8.dp))
            Text(connection, style = MaterialTheme.typography.bodyMedium)
        }

        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Use the public router if my server is offline", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "On by default. When your Mac cannot be reached the ride is planned by " +
                        "valhalla1.openstreetmap.de, a third-party volunteer server, and the badge shows it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.allowFallback, onCheckedChange = onAllowFallback)
        }

        HorizontalDivider()
        // docs/LOCATION-CONTROL.md B2/B3: the saved home is NOT where rides start. Say both facts.
        SettingRow("Saved home", state.homeLabel)
        Text(
            RiderText.PlanningOrigin.SAVED_HOME_EXPLANATION,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onSaveCurrentSpotAsHome, enabled = state.planningOrigin != null) {
            Text("Save this spot as home")
        }
        if (state.savedHomeIsUserSet) {
            TextButton(onClick = onForgetSavedHome) { Text("Forget this saved home") }
        }
        state.saveHomeMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        HorizontalDivider()
        // D27: the rider's own DeepSeek key, typed here and nowhere else (docs/AI-KEY.md).
        Text("AI route planning", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        AiPlanningSection()

        HorizontalDivider()
        // D36 (docs/VELOTRIC-LINK.md): the end-of-navigation watcher and the two grants it needs.
        Text("End of ride", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(
            when {
                !state.notificationAccessGranted ->
                    "Off. Allow notification access and the app can tell when you stop navigating in " +
                        "Google Maps, then ask whether to stop your Velotric ride."
                !state.notificationsEnabled ->
                    "Notification access is on, but the app is not allowed to send notifications, so " +
                        "the reminder cannot appear."
                state.rideWatchArmed ->
                    "Watching. When you stop navigating in Google Maps, a notification will offer to " +
                        "open Velotric so you can end the ride."
                else ->
                    "On. The next ride you hand to Google Maps will be watched until you stop " +
                        "navigating."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.notificationAccessGranted) {
            TextButton(onClick = onOpenNotificationSettings) { Text("Allow notification access") }
        }

        HorizontalDivider()
        TextButton(onClick = onRefreshTrailData, enabled = !state.refreshing) { Text("Re-check trail data") }
        state.trailRefresh?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("About", style = MaterialTheme.typography.titleMedium)
        Text(
            "© OpenStreetMap contributors (ODbL)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Data sources: OpenStreetMap (ODbL), WSDOT, Pierce County, PSRC", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // The one row that answers "did the build I just made actually land on this phone?". Read from
        // the INSTALLED package, so it cannot be faked by a stale process: if the install time is not
        // the time of the last deploy, the phone is still running the old APK.
        buildStamp()?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RiderText.dataFlags(state.dataFlags).forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
    }
}

/**
 * The "AI route planning" section (D27, docs/AI-KEY.md). It owns its own small state because the key
 * is not part of the planning state: it lives in encrypted preferences, and it is read back masked.
 *
 * Everything here is rider language: no HTTP codes, no exception text, no model jargon beyond the
 * model name DeepSeek itself uses.
 */
@Composable
private fun AiPlanningSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { EncryptedAiKeyStore(context) }
    var savedKey by remember { mutableStateOf(store.deepSeekKey) }
    var model by remember { mutableStateOf(store.model) }
    var enteredKey by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    if (!store.available) {
        Text(
            AiSettingsText.STORAGE_UNAVAILABLE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text(
        if (savedKey.isBlank()) AiSettingsText.NO_KEY_LINE else AiSettingsText.KEY_SET_LINE,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = enteredKey,
        onValueChange = { enteredKey = it },
        label = { Text("DeepSeek key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    if (savedKey.isNotBlank()) {
        Text(
            "Saved key: " + AiKeyStore.maskKey(savedKey),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                if (enteredKey.isNotBlank()) store.deepSeekKey = enteredKey
                store.model = model
                savedKey = store.deepSeekKey
                model = store.model
                enteredKey = ""
                message = if (savedKey.isBlank()) AiSettingsText.NOTHING_TO_SAVE else AiSettingsText.SAVED
            },
            enabled = enteredKey.isNotBlank() || model.trim() != store.model,
        ) { Text("Save") }
        TextButton(onClick = {
            store.clearKey()
            savedKey = ""
            enteredKey = ""
            message = AiSettingsText.CLEARED
        }) { Text("Clear") }
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = {
                testing = true
                message = null
                scope.launch {
                    // The composition's scope runs on the main thread; the call itself belongs on IO.
                    val reply = withContext(Dispatchers.IO) {
                        DeepSeekClient(DeepSeekHttpCall(savedKey, model)).ping()
                    }
                    testing = false
                    message = AiSettingsText.testResult(reply)
                }
            },
            enabled = savedKey.isNotBlank() && !testing,
        ) { Text(if (testing) "Testing\u2026" else "Test key") }
    }

    OutlinedTextField(
        value = model,
        onValueChange = { model = it },
        label = { Text("Model") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        AiSettingsText.MODEL_HINT,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        AiSettingsText.PRIVACY,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * What the AI section says, in rider language only (docs/AI-PLANNING.md section 2 for the privacy
 * line, section 7 for the degradation wording). Kept here, next to the controls it explains.
 */
object AiSettingsText {

    const val STORAGE_UNAVAILABLE: String =
        "This phone will not let the app keep a key safely, so the AI stays off. The app plans " +
            "without it, using the trails and entrances it finds itself."

    /** Ladder step 1: with no key the rider is told plainly that the app plans without AI. */
    const val NO_KEY_LINE: String =
        "No key is set, so the app plans without AI: it tries the trails and entrances it finds " +
            "itself, best first."

    const val KEY_SET_LINE: String =
        "A key is set. When a new ride is planned, the AI is asked once which of the trails the app " +
            "already found to try first. The same ride is never planned twice."

    /** docs/AI-PLANNING.md section 2: names are sent, coordinates are not. */
    const val PRIVACY: String =
        "What the AI sees: the names of the trails and quiet streets near you, and how far you want " +
            "to ride. Your position is never sent. The AI only suggests which trail to try; the route " +
            "and the safety check are still done on your own server. Each new plan is one request on " +
            "your own DeepSeek account, so it costs whatever DeepSeek charges you."

    const val MODEL_HINT: String =
        "The model name, as DeepSeek writes it. Nothing else needs changing."

    const val SAVED: String = "Saved. The app will use the AI on the next new ride."

    const val NOTHING_TO_SAVE: String = "Type your key above, then tap Save."

    const val CLEARED: String = "Cleared. The app plans without AI now."

    /** One short sentence about a test call: never a stack trace, never a status code. */
    fun testResult(reply: AiReply): String = when (reply) {
        is AiReply.Ok -> "That key works. The AI can suggest routes now."
        is AiReply.Failed -> when (reply.reason) {
            AiFailure.NO_KEY -> "There is no key saved yet. Type one above and tap Save."
            AiFailure.TIMEOUT -> "The AI did not answer in time. Check your connection and try again."
            AiFailure.TRANSPORT -> "The AI could not be reached. Check your connection and try again."
            AiFailure.UNPARSEABLE -> "The AI answered, but not in a way this app could use. Try again later."
        }
    }
}

/**
 * "App version 1 (0.1.0) · installed 20 Sep 2026 17:17", straight from the package manager.
 *
 * Read from the installed package rather than compiled in, for two reasons: a compiled-in timestamp
 * would invalidate Gradle's build cache on every build, and - the point - this number changes when an
 * APK is actually INSTALLED, which is precisely the question being asked.
 */
@Composable
private fun buildStamp(): String? {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember {
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val installed = java.text.SimpleDateFormat("d MMM yyyy HH:mm", java.util.Locale.US)
                .format(java.util.Date(info.lastUpdateTime))
            "App version " + (info.versionName ?: "?") + " (" + info.versionCode + ") \u00b7 installed " + installed
        }.getOrNull()
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
