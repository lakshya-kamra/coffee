package com.laksh.coffee

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.laksh.coffee.data.StartupTasks
import com.laksh.coffee.data.ai.AiSettingsStore
import com.laksh.coffee.data.ai.AnalysisRepository
import com.laksh.coffee.data.ai.ChatClients
import com.laksh.coffee.data.ai.HttpTransport
import com.laksh.coffee.data.backup.BackupExporter
import com.laksh.coffee.data.backup.BackupImporter
import com.laksh.coffee.data.catalog.AssetCatalogSource
import com.laksh.coffee.data.catalog.CatalogRepository
import com.laksh.coffee.data.db.CoffeeDatabase
import com.laksh.coffee.data.photos.PhotoAttachments
import com.laksh.coffee.data.photos.PhotoRepository
import com.laksh.coffee.data.photos.PhotoStore
import com.laksh.coffee.data.prefs.Settings
import com.laksh.coffee.data.prefs.SettingsStore
import com.laksh.coffee.data.repo.CoffeeRepository
import com.laksh.coffee.domain.backup.BackupApp
import com.laksh.coffee.domain.catalog.Catalog
import com.laksh.coffee.ui.screens.insights.InsightsState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class CoffeeApp : Application() {

    val repository: CoffeeRepository by lazy { CoffeeRepository(this, appScope) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val catalog: CatalogRepository by lazy { CatalogRepository() }

    /**
     * Settings as last read (null before DataStore's first answer), kept hot for the process so
     * the theme and every screen's first frame can read them without waiting.
     */
    val settingsState: StateFlow<Settings?> by lazy {
        settings.settings.stateIn(appScope, SharingStarted.Eagerly, null)
    }

    private val todayState = MutableStateFlow(LocalDate.now())

    /**
     * The calendar day, re-read whenever the activity resumes. The tabs stay composed and their
     * state is built ahead of time, so "today" has to be data that can change: a process that
     * lives through midnight would otherwise keep yesterday's greeting, streak and day headers.
     */
    val today: StateFlow<LocalDate> = todayState.asStateFlow()

    fun refreshToday() {
        todayState.value = LocalDate.now()
    }

    /**
     * Insights' statistics, computed off the main thread as soon as the lists are known and kept
     * current for the process, so the tab only ever reads finished numbers (null until then).
     */
    val insightsState: StateFlow<InsightsState?> by lazy { InsightsState.hot(repository, today, appScope) }

    /** Lives as long as the process so an analysis finishes and saves even after you leave the screen. */
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            // Runs already turn failures into an error card. Anything that still slips through
            // must not take the whole app down, and is never logged.
            CoroutineExceptionHandler { _, _ -> },
    )

    private val photoStore: PhotoStore by lazy { PhotoStore(this) }

    /** Photos of brews and bags. Files stay in the app's own storage; nothing here uses the network. */
    val photos: PhotoRepository by lazy { PhotoRepository(CoffeeDatabase.get(this).photoDao(), photoStore) }

    val aiSettings: AiSettingsStore by lazy { AiSettingsStore(this) }

    /** One client per wire format (OpenAI-compatible, Anthropic Messages) over the app's only HTTP code. */
    val aiClients: ChatClients by lazy {
        ChatClients(HttpTransport(userAgent = "Coffee-Android/${BuildConfig.VERSION_NAME}"))
    }
    val analysis: AnalysisRepository by lazy {
        AnalysisRepository(
            coffee = repository,
            dao = CoffeeDatabase.get(this).analysisDao(),
            settings = aiSettings,
            clients = aiClients,
            scope = appScope,
            photos = PhotoAttachments(CoffeeDatabase.get(this).photoDao(), photoStore),
            tempUnit = { settings.settings.first().tempUnit },
        )
    }

    /**
     * Backup v2: everything logged, as one file the person saves where they choose. It reads the
     * AI settings only through AiSettingsStore.backup(), which never touches a key.
     */
    val backupExporter: BackupExporter by lazy {
        BackupExporter(
            context = this,
            db = CoffeeDatabase.get(this),
            photoFiles = photoStore.files,
            settings = settings,
            ai = aiSettings,
            app = BackupApp(platform = "android", appVersion = BuildConfig.VERSION_NAME, packageName = packageName),
        )
    }

    /** Restores a backup or a 1.x export. App-scoped, so an import finishes after the screen is left. */
    val backupImporter: BackupImporter by lazy {
        BackupImporter(
            context = this,
            db = CoffeeDatabase.get(this),
            photoFiles = photoStore.files,
            settings = settings,
            ai = aiSettings,
            scope = appScope,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // First, before anything can read the catalog.
        Catalog.install(AssetCatalogSource(assets))
        // Start the hot caches now, so the first screen and the theme find them filled.
        settingsState
        repository
        insightsState
        // Parse shared/ off the main thread; a screen that gets there first waits on the same lazy.
        appScope.launch(Dispatchers.IO) {
            // Load java.time's zone rules and this locale's day and month names here, not on the
            // main thread in the first frame of whichever tab formats a date first.
            runCatching { DateTimeFormatter.ofPattern("EEE d MMM HH:mm").format(ZonedDateTime.now()) }
            Catalog.preloadAll()
            StartupTasks.run(this@CoffeeApp)
            // Reads the AI settings once now, so the one-off move from the 1.x layout runs on the
            // first launch after an update (and a fresh install is recorded as "no provider chosen").
            runCatching { aiSettings.snapshot() }
        }
    }
}

/** Pulls the app container out of the ViewModel factory extras. Keeps DI to one file. */
val CreationExtras.app: CoffeeApp
    get() = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CoffeeApp)
