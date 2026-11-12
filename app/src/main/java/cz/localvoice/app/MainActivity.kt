package cz.localvoice.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var repository: ModelRepository
    private var config by mutableStateOf(SpeakConfig())
    private var installedIds by mutableStateOf<Set<String>>(emptySet())
    private var screen by mutableStateOf(Screen.WELCOME)
    private var introductionStep by mutableIntStateOf(0)
    private var microphoneGranted by mutableStateOf(false)
    private var accessibilityEnabled by mutableStateOf(false)
    private var disclosureAccepted by mutableStateOf(false)
    private var settingsBusy by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var downloads by mutableStateOf<Map<String, DownloadProgress>>(emptyMap())
    private val downloadJobs = mutableMapOf<String, Job>()
    private var refreshJob: Job? = null
    private var browserRole by mutableStateOf(ModelRole.ASR)
    private var showDisclosure by mutableStateOf(false)
    private var languageRequest by mutableStateOf<LanguageRequest?>(null)
    private var catalogSearch by mutableStateOf<Map<String, String>>(emptyMap())
    private var totalRamBytes = 0L

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ModelRepository(this)
        screen = if (UserSettings.isOnboardingDone(this)) Screen.DASHBOARD else Screen.WELCOME
        savedInstanceState?.getString("screen")?.let { saved ->
            Screen.entries.firstOrNull { it.name == saved }?.let { screen = it }
        }
        introductionStep = savedInstanceState?.getInt("introduction_step", 0) ?: 0
        savedInstanceState?.getString("browser_role")?.let { saved ->
            ModelRole.entries.firstOrNull { it.name == saved }?.let { browserRole = it }
        }
        val memory = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        totalRamBytes = memory.totalMem
        lifecycleScope.launch {
            catalogSearch = withContext(Dispatchers.Default) {
                ModelCatalog.models.associate { model ->
                    model.id to (listOf(model.name, model.id, model.family.name) +
                        model.languages.map { "$it ${languageName(it)}" } + model.languageVariants)
                        .joinToString("\n").lowercase(Locale.ROOT)
                }
            }
        }
        setContent {
            SpeakTheme {
                BackHandler(enabled = screen != Screen.DASHBOARD && screen != Screen.WELCOME) { screen = Screen.DASHBOARD }
                when (screen) {
                    Screen.WELCOME -> Introduction()
                    Screen.DASHBOARD -> Dashboard()
                    Screen.MODELS -> ModelBrowser()
                    Screen.PERMISSIONS -> Page(getString(R.string.permissions), back = true) { Permissions() }
                }
                if (showDisclosure) AccessibilityDisclosure()
                languageRequest?.let { request -> LanguageDialog(request) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen.name)
        outState.putInt("introduction_step", introductionStep)
        outState.putString("browser_role", browserRole.name)
        super.onSaveInstanceState(outState)
    }

    private fun refreshState() {
        if (!::repository.isInitialized) return
        microphoneGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val component = ComponentName(this, VoiceAccessibilityService::class.java)
        accessibilityEnabled = getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == component }
        disclosureAccepted = UserSettings.accessibilityDisclosureAccepted(this)
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            try {
                val state = withContext(Dispatchers.IO) {
                    SpeakSettings.load(this@MainActivity) to ModelCatalog.models.filter(repository::isInstalled)
                        .map(ModelSpec::id).toSet()
                }
                config = state.first
                installedIds = state.second
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                config = config.copy(enabled = false)
                message = getString(R.string.settings_error)
            }
        }
    }

    private fun persist(next: SpeakConfig) {
        if (settingsBusy) return
        settingsBusy = true
        message = null
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { SpeakSettings.save(this@MainActivity, next) }
                config = next
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message = getString(R.string.settings_error)
            } finally {
                settingsBusy = false
            }
        }
    }

    private fun openModels(role: ModelRole = ModelRole.ASR) {
        browserRole = role
        screen = Screen.MODELS
        message = null
    }

    private fun chooseModel(model: ModelSpec) {
        when (model.role) {
            ModelRole.ASR -> {
                val language = if (!model.manualLanguage) null else config.language?.takeIf { it in model.languages }
                    ?: if (model.autoLanguage) null else model.languages.singleOrNull()
                if (!model.autoLanguage && language == null) {
                    languageRequest = LanguageRequest(model.languages, null, false) {
                        persist(config.copy(activeModelId = model.id, language = it, enabled = true))
                    }
                } else persist(config.copy(activeModelId = model.id, language = language, enabled = true))
            }
            ModelRole.LANGUAGE_DETECTOR -> persist(config.copy(detectorModelId = model.id))
            ModelRole.SEMANTIC -> persist(config.copy(semanticEnabled = true))
        }
    }

    private fun stopUsing(model: ModelSpec) {
        val active = config.activeModelId == model.id
        persist(config.copy(
            activeModelId = if (active) null else config.activeModelId,
            language = if (active) null else config.language,
            enabled = config.enabled && !active,
            routingEnabled = config.routingEnabled && !active && config.detectorModelId != model.id && model.id !in config.routes.values,
            detectorModelId = config.detectorModelId.takeUnless { it == model.id },
            routes = config.routes.filterValues { it != model.id },
            semanticEnabled = config.semanticEnabled && model.role != ModelRole.SEMANTIC,
        ))
    }

    private fun download(model: ModelSpec) {
        if (model.id in downloadJobs) return
        message = null
        downloads = downloads + (model.id to DownloadProgress(0, model.totalBytes))
        lateinit var downloadJob: Job
        downloadJob = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                repository.download(model) { complete, total ->
                    runOnUiThread {
                        if (!isDestroyed && downloadJob.isActive && downloadJobs[model.id] === downloadJob) {
                            downloads = downloads + (model.id to DownloadProgress(complete, total))
                        }
                    }
                }
                installedIds = installedIds + model.id
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message = getString(R.string.download_error, model.name)
            } finally {
                downloads = downloads - model.id
                downloadJobs.remove(model.id)
            }
        }
        downloadJobs[model.id] = downloadJob
        downloadJob.start()
    }

    private fun remove(model: ModelSpec) {
        if (settingsBusy) return
        settingsBusy = true
        message = null
        lifecycleScope.launch {
            try {
                repository.delete(model)
                installedIds = installedIds - model.id
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message = getString(R.string.remove_error)
            } finally {
                settingsBusy = false
            }
        }
    }

    @Composable
    private fun Introduction() {
        Page(getString(R.string.app_name)) {
            if (introductionStep == 0) {
                Text(getString(R.string.introduction_title), style = MaterialTheme.typography.headlineLarge)
                Text(getString(R.string.introduction_body))
                Text(getString(R.string.introduction_steps))
                Spacer(Modifier.height(16.dp))
                Primary(getString(R.string.continue_setup)) { introductionStep = 1 }
            } else {
                Text(getString(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
                Permissions()
                Primary(getString(R.string.open_dashboard), enabled = !settingsBusy) {
                    settingsBusy = true
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                UserSettings.save(this@MainActivity, UserSettings.load(this@MainActivity))
                            }
                            screen = Screen.DASHBOARD
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            message = getString(R.string.settings_error)
                        } finally {
                            settingsBusy = false
                        }
                    }
                }
                TextButton(onClick = { introductionStep = 0 }) { Text(getString(R.string.back)) }
            }
        }
    }

    @Composable
    private fun Dashboard() {
        val serviceConnected by ServiceConnection.connected.collectAsState()
        val active = ModelCatalog.byId(config.activeModelId)
        val readyModel = active != null && active.id in installedIds
        val status = when {
            !readyModel -> R.string.status_choose_model
            !microphoneGranted -> R.string.status_microphone
            !accessibilityEnabled || !disclosureAccepted -> R.string.status_accessibility
            !serviceConnected -> R.string.status_service_disconnected
            !config.enabled -> R.string.status_paused
            else -> R.string.status_ready
        }
        var advanced by remember { mutableStateOf(false) }
        Page(getString(R.string.app_name)) {
            Text(getString(status), style = MaterialTheme.typography.headlineLarge,
                color = if (status == R.string.status_ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
            Text(getString(R.string.microphone_idle))
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(getString(R.string.active_model), style = MaterialTheme.typography.labelLarge)
            Text(active?.name ?: getString(R.string.no_model), style = MaterialTheme.typography.titleLarge)
            if (active != null) {
                Text(if (config.routingEnabled) getString(R.string.routing_mode)
                else config.language?.let(::languageName) ?: getString(R.string.automatic_language))
                Text(getString(R.string.installed_count, installedIds.count { ModelCatalog.byId(it)?.role == ModelRole.ASR }),
                    style = MaterialTheme.typography.bodySmall)
            }
            Primary(getString(if (config.enabled && readyModel) R.string.stop_dictation else R.string.resume_dictation), !settingsBusy) {
                if (!readyModel) openModels() else persist(config.copy(enabled = !config.enabled))
            }
            OutlinedButton(onClick = { openModels() }, modifier = Modifier.fillMaxWidth()) { Text(getString(R.string.choose_model)) }
            OutlinedButton(onClick = { screen = Screen.PERMISSIONS }, modifier = Modifier.fillMaxWidth()) { Text(getString(R.string.permissions)) }
            TextButton(onClick = { advanced = !advanced }) {
                Text(getString(if (advanced) R.string.hide_advanced else R.string.advanced_settings))
            }
            if (advanced) AdvancedSettings(active)
        }
    }

    @Composable
    private fun Permissions() {
        Text(getString(R.string.permission_explanation))
        Status(getString(R.string.microphone_permission), microphoneGranted)
        OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }, enabled = !microphoneGranted,
            modifier = Modifier.fillMaxWidth()) { Text(getString(if (microphoneGranted) R.string.allowed else R.string.allow_microphone)) }
        Status(getString(R.string.floating_microphone), accessibilityEnabled && disclosureAccepted)
        OutlinedButton(onClick = {
            if (disclosureAccepted) openIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) else showDisclosure = true
        }, modifier = Modifier.fillMaxWidth()) { Text(getString(R.string.review_accessibility)) }
        Text(getString(R.string.permission_denied_help), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { openIntent(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }) {
            Text(getString(R.string.open_app_settings))
        }
    }

    @Composable
    private fun AccessibilityDisclosure() {
        var confirmed by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text(getString(R.string.accessibility_disclosure_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(getString(R.string.accessibility_disclosure_field_access))
                    Text(getString(R.string.accessibility_disclosure_recording))
                    Text(getString(R.string.accessibility_disclosure_insertion))
                    Text(getString(R.string.accessibility_disclosure_secure_fields))
                    Text(getString(R.string.accessibility_disclosure_network))
                    Row {
                        Checkbox(confirmed, { confirmed = it }, modifier = Modifier.semantics {
                            contentDescription = getString(R.string.accessibility_disclosure_consent)
                        })
                        Text(getString(R.string.accessibility_disclosure_consent), Modifier.padding(top = 12.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = confirmed, onClick = {
                    UserSettings.acceptAccessibilityDisclosure(this)
                    disclosureAccepted = true
                    showDisclosure = false
                    openIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text(getString(R.string.accessibility_disclosure_continue)) }
            },
            dismissButton = { TextButton(onClick = { showDisclosure = false }) { Text(getString(R.string.cancel)) } },
        )
    }

    @Composable
    private fun AdvancedSettings(active: ModelSpec?) {
        HorizontalDivider()
        Text(getString(R.string.language_mode), style = MaterialTheme.typography.titleMedium)
        if (active != null) {
            OutlinedButton(enabled = !settingsBusy && !config.routingEnabled && active.manualLanguage, onClick = {
                languageRequest = LanguageRequest(active.languages, config.language, active.autoLanguage) { persist(config.copy(language = it)) }
            }, modifier = Modifier.fillMaxWidth()) { Text(config.language?.let(::languageName) ?: getString(R.string.automatic_language)) }
        } else Text(getString(R.string.choose_model_first))
        SettingSwitch(R.string.live_preview, R.string.live_preview_help, config.livePreview) { persist(config.copy(livePreview = it)) }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(getString(R.string.routing_title), style = MaterialTheme.typography.titleMedium)
        Text(getString(R.string.routing_help))
        Text(ModelCatalog.byId(config.detectorModelId)?.name ?: getString(R.string.no_detector))
        OutlinedButton(onClick = { openModels(ModelRole.LANGUAGE_DETECTOR) }, modifier = Modifier.fillMaxWidth()) { Text(getString(R.string.choose_detector)) }
        config.routes.toSortedMap().forEach { (language, modelId) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(getString(R.string.language_route, languageName(language), ModelCatalog.byId(modelId)?.name.orEmpty()),
                    Modifier.weight(1f).padding(top = 12.dp))
                TextButton(enabled = !settingsBusy && !config.routingEnabled, onClick = { persist(config.copy(routes = config.routes - language)) }) {
                    Text(getString(R.string.remove_route))
                }
            }
        }
        ModelCatalog.asrModels.filter { it.id in installedIds }.forEach { model ->
            TextButton(enabled = !settingsBusy && !config.routingEnabled, onClick = {
                languageRequest = LanguageRequest(model.languages, null, false) { language ->
                    if (language != null) persist(config.copy(routes = config.routes + (language to model.id)))
                }
            }) { Text(getString(R.string.add_model_route, model.name)) }
        }
        val canRoute = config.detectorModelId in installedIds && config.routes.isNotEmpty() && active != null
        SettingSwitch(R.string.enable_routing, R.string.routing_fallback_help, config.routingEnabled, canRoute || config.routingEnabled) {
            persist(config.copy(routingEnabled = it))
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(getString(R.string.semantic_title), style = MaterialTheme.typography.titleMedium)
        Text(getString(R.string.semantic_help))
        val semanticReady = ModelCatalog.semanticModels.any { it.id in installedIds }
        if (!semanticReady) OutlinedButton(onClick = { openModels(ModelRole.SEMANTIC) }, modifier = Modifier.fillMaxWidth()) {
            Text(getString(R.string.install_semantic))
        }
        SettingSwitch(R.string.enable_semantic, R.string.semantic_resource_help, config.semanticEnabled, semanticReady || config.semanticEnabled) {
            persist(config.copy(semanticEnabled = it))
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        var testText by remember { mutableStateOf("") }
        Text(getString(R.string.try_dictation), style = MaterialTheme.typography.titleMedium)
        Text(getString(R.string.try_dictation_help))
        OutlinedTextField(testText, { testText = it.take(10_000) }, Modifier.fillMaxWidth(), minLines = 4,
            label = { Text(getString(R.string.dictation_field)) })
    }

    @Composable
    private fun ModelBrowser() {
        var query by remember { mutableStateOf("") }
        var language by remember { mutableStateOf<String?>(null) }
        var maxBytes by remember { mutableStateOf<Long?>(null) }
        var automaticOnly by remember { mutableStateOf(false) }
        var streamingOnly by remember { mutableStateOf(false) }
        var compatibleOnly by remember { mutableStateOf(false) }
        var sortByError by remember { mutableStateOf(false) }
        val matching = ModelCatalog.models.filter { model ->
            model.role == browserRole && (language == null || language in model.languages || language in model.languageVariants) &&
                (maxBytes == null || model.totalBytes <= requireNotNull(maxBytes)) &&
                (!automaticOnly || model.autoLanguage) && (!streamingOnly || model.streaming) &&
                (!compatibleOnly || model.minRamBytes == null || model.minRamBytes <= totalRamBytes) &&
                (query.isBlank() || (catalogSearch[model.id] ?: "${model.name} ${model.id}")
                    .contains(query.trim().lowercase(Locale.ROOT)))
        }
        val benchmarkKeys = matching.flatMap { model -> model.benchmarks.filter { it.language == language } }
            .map { Triple(it.metric, it.dataset, it.methodology) }.distinct()
        val canSortError = language != null && benchmarkKeys.size == 1
        val visible = if (sortByError && canSortError) matching.sortedBy { model ->
            model.benchmarks.firstOrNull { it.language == language }?.errorRate ?: Double.MAX_VALUE
        } else matching.sortedBy(ModelSpec::totalBytes)
        Scaffold { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).safeDrawingPadding().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    TextButton(onClick = { screen = Screen.DASHBOARD }) { Text(getString(R.string.back)) }
                    Text(getString(R.string.models), style = MaterialTheme.typography.headlineLarge)
                }
                item {
                    Text(roleName(browserRole), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(query, { query = it.take(100) }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(getString(R.string.search_models)) })
                    TextButton(onClick = {
                        val variants = ModelCatalog.models.flatMap(ModelSpec::languageVariants)
                        languageRequest = LanguageRequest(ModelCatalog.languages + variants, language, true) { language = it }
                    }) { Text(language?.let(::languageName) ?: getString(R.string.all_languages)) }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null, 200_000_000L, 600_000_000L, 2_000_000_000L).forEach { limit ->
                            FilterChip(maxBytes == limit, { maxBytes = limit }, label = {
                                Text(limit?.let { getString(R.string.up_to_size, size(it)) } ?: getString(R.string.any_size))
                            })
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (browserRole == ModelRole.ASR) {
                            FilterChip(automaticOnly, { automaticOnly = !automaticOnly }, label = { Text(getString(R.string.automatic_filter)) })
                            FilterChip(streamingOnly, { streamingOnly = !streamingOnly }, label = { Text(getString(R.string.streaming_filter)) })
                        }
                        FilterChip(compatibleOnly, { compatibleOnly = !compatibleOnly }, label = { Text(getString(R.string.compatible_filter)) })
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!sortByError || !canSortError, { sortByError = false }, label = { Text(getString(R.string.sort_size)) })
                        if (browserRole == ModelRole.ASR) {
                            FilterChip(sortByError && canSortError, { sortByError = true }, enabled = canSortError,
                                label = { Text(getString(R.string.sort_error)) })
                        }
                    }
                    if (browserRole == ModelRole.ASR) Text(getString(R.string.error_comparison_help), style = MaterialTheme.typography.bodySmall)
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (visible.isEmpty()) Text(getString(R.string.no_matching_models))
                }
                items(visible, key = ModelSpec::id) { model -> ModelRow(model, language) }
                item { PolicyLink() }
            }
        }
    }

    @Composable
    private fun ModelRow(model: ModelSpec, language: String?) {
        val installed = model.id in installedIds
        val selected = when (model.role) {
            ModelRole.ASR -> config.activeModelId == model.id
            ModelRole.LANGUAGE_DETECTOR -> config.detectorModelId == model.id
            ModelRole.SEMANTIC -> installed && config.semanticEnabled
        }
        val used = config.activeModelId == model.id || config.detectorModelId == model.id ||
            model.id in config.routes.values || model.role == ModelRole.SEMANTIC && config.semanticEnabled
        val progress = downloads[model.id]
        val memoryFits = model.minRamBytes == null || model.minRamBytes <= totalRamBytes
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalDivider()
            Text(model.name, style = MaterialTheme.typography.titleLarge)
            Text(getString(R.string.download_size, size(model.totalBytes)), fontFamily = FontFamily.Monospace)
            Text(model.minRamBytes?.let { getString(R.string.memory_requirement, size(it)) } ?: getString(R.string.memory_unmeasured),
                style = MaterialTheme.typography.bodySmall)
            if (model.languages.isNotEmpty()) Text(getString(R.string.language_count, model.languages.size))
            if (model.role == ModelRole.ASR) {
                Text(getString(if (model.autoLanguage) R.string.automatic_supported else R.string.manual_language))
                if (model.streaming) Text(getString(R.string.streaming_supported))
            }
            val results = model.benchmarks.filter { language == null || it.language == language }
            if (model.role == ModelRole.ASR && results.isEmpty()) Text(getString(R.string.error_unmeasured), style = MaterialTheme.typography.bodySmall)
            results.take(3).forEach { result ->
                Text(getString(R.string.benchmark_result, languageName(result.language), result.metric, result.errorRate, result.dataset),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { openUrl(result.sourceUrl) }) { Text(getString(R.string.measurement_source)) }
            }
            Text(model.license, style = MaterialTheme.typography.bodySmall)
            if (!memoryFits) Text(getString(R.string.memory_incompatible), color = MaterialTheme.colorScheme.error)
            if (progress != null) {
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                Text(getString(R.string.download_progress, size(progress.complete), size(progress.total)), fontFamily = FontFamily.Monospace)
                OutlinedButton(onClick = { downloadJobs[model.id]?.cancel() }) { Text(getString(R.string.cancel_download)) }
            } else if (installed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { chooseModel(model) }, enabled = !selected && !settingsBusy && memoryFits,
                        modifier = Modifier.weight(1f)) {
                        Text(getString(if (selected) R.string.selected else R.string.select_model))
                    }
                    TextButton(onClick = { remove(model) }, enabled = !used && !settingsBusy,
                        modifier = Modifier.weight(1f)) { Text(getString(R.string.remove_model)) }
                }
                if (used) {
                    Text(getString(R.string.model_in_use), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { stopUsing(model) }, enabled = !settingsBusy) { Text(getString(R.string.stop_using_model)) }
                }
            } else Button(onClick = { download(model) }, enabled = memoryFits) { Text(getString(R.string.download_model)) }
            TextButton(onClick = { openUrl(model.sourceUrl) }) { Text(getString(R.string.model_source)) }
        }
    }

    @Composable
    private fun LanguageDialog(request: LanguageRequest) {
        var query by remember(request) { mutableStateOf("") }
        val languageNames = remember(request) {
            request.languages.map { it to languageName(it) }.sortedBy { it.second }
        }
        val languages = remember(languageNames, query) {
            languageNames.filter { (code, name) -> code.contains(query, true) || name.contains(query, true) }
        }
        AlertDialog(onDismissRequest = { languageRequest = null }, title = { Text(getString(R.string.choose_language)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it.take(80) }, singleLine = true, label = { Text(getString(R.string.search_languages)) })
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        if (request.allowAutomatic) item {
                            TextButton(onClick = { languageRequest = null; request.onPick(null) }, modifier = Modifier.fillMaxWidth()) {
                                Text(getString(if (screen == Screen.MODELS) R.string.all_languages else R.string.automatic_language))
                            }
                        }
                        items(languages, key = { it.first }) { (code, name) ->
                            TextButton(onClick = { languageRequest = null; request.onPick(code) }, modifier = Modifier.fillMaxWidth()) {
                                Text(name, fontWeight = if (code == request.selected) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { languageRequest = null }) { Text(getString(R.string.cancel)) } })
    }

    @Composable
    private fun SettingSwitch(title: Int, detail: Int, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text(getString(title), fontWeight = FontWeight.Medium)
                Text(getString(detail), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked, onChange, enabled = enabled && !settingsBusy,
                modifier = Modifier.semantics { contentDescription = getString(title) })
        }
    }

    @Composable
    private fun Status(label: String, ready: Boolean) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, Modifier.weight(1f))
            Text(getString(if (ready) R.string.allowed else R.string.not_enabled), fontWeight = FontWeight.Medium)
        }
    }

    @Composable
    private fun Page(title: String, back: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (back) TextButton(onClick = { screen = Screen.DASHBOARD }) { Text(getString(R.string.back)) }
                Text(title, style = MaterialTheme.typography.titleLarge)
                content()
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                PolicyLink()
            }
        }
    }

    @Composable
    private fun Primary(label: String, enabled: Boolean = true, action: () -> Unit) {
        Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label) }
    }

    @Composable
    private fun PolicyLink() {
        TextButton(onClick = { openUrl("https://majkey25.github.io/selia-speak/") }) { Text(getString(R.string.privacy_terms)) }
    }

    private fun openUrl(url: String) = openIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun openIntent(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            message = getString(R.string.settings_unavailable)
        }
    }

    private fun languageName(code: String): String = Locale.forLanguageTag(code)
        .getDisplayName(resources.configuration.locales[0]).replaceFirstChar { it.titlecase(resources.configuration.locales[0]) }

    private fun roleName(role: ModelRole): String = getString(when (role) {
        ModelRole.ASR -> R.string.role_dictation
        ModelRole.LANGUAGE_DETECTOR -> R.string.role_language_detection
        ModelRole.SEMANTIC -> R.string.role_semantic
    })

    private fun size(bytes: Long): String = if (bytes >= 1_000_000_000L) {
        String.format(resources.configuration.locales[0], "%.2f GB", bytes / 1_000_000_000.0)
    } else String.format(resources.configuration.locales[0], "%.1f MB", bytes / 1_000_000.0)

    private data class DownloadProgress(val complete: Long, val total: Long) {
        val fraction: Float get() = if (total > 0) (complete.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    }

    private data class LanguageRequest(val languages: Set<String>, val selected: String?, val allowAutomatic: Boolean, val onPick: (String?) -> Unit)
    private enum class Screen { WELCOME, DASHBOARD, MODELS, PERMISSIONS }
}
