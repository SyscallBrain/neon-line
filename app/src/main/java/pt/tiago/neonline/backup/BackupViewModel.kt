package pt.tiago.neonline.backup

import android.app.Application
import android.net.Uri
import android.os.Build
import pt.tiago.neonline.BuildConfig
import pt.tiago.neonline.crash.CrashLog
import pt.tiago.neonline.data.DiagnosticEnvironment
import pt.tiago.neonline.data.ProfileDiagnostics
import pt.tiago.neonline.data.PerfectSearchDiagnostics
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import pt.tiago.neonline.AppContainer
import pt.tiago.neonline.R
import pt.tiago.neonline.data.Profile
import pt.tiago.neonline.data.ProfileBackup

/** Picker payload and confirmation survive Activity recreation; all document I/O is off the UI thread. */
class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as pt.tiago.neonline.App).container
    var busy by mutableStateOf(false)
        private set
    var candidate by mutableStateOf<Profile?>(null)
        private set
    var freeUnavailable by mutableStateOf(false)
        private set
    var tournamentUnavailable by mutableStateOf(false)
        private set
    var dailyUnavailable by mutableStateOf(false)
        private set
    var localUnavailable by mutableStateOf(false)
        private set
    var savedUnavailable by mutableStateOf(false)
        private set
    var message by mutableStateOf<Int?>(null)
        private set
    var replacedProfile by mutableStateOf<Profile?>(null)
        private set
    fun consumeReplacedProfile() { replacedProfile = null }
    var resetRequested by mutableStateOf(false)
        private set
    private var diagnosticExport = false
    private var exportBytes: ByteArray? = null

    fun prepareExport(diagnostic: Boolean = false, onReady: () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val profile = container.profile()
                if (!diagnostic) ProfileBackup.encode(profile) else {
                    val app = getApplication<Application>()
                    val crash = if (CrashLog.hasReport(app)) CrashLog.latestReport(app).toString(Charsets.UTF_8) else null
                    val perfectSearch = PerfectSearchDiagnostics.read(app.filesDir)
                    ProfileDiagnostics.encode(profile, container.catalog.load(), container.resumableMatch(profile.savedMatch) != null,
                        DiagnosticEnvironment(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT,
                            Build.VERSION.RELEASE, Build.MANUFACTURER, Build.MODEL), crash, perfectSearch)
                }
            } }
            busy = false
            result.onSuccess { diagnosticExport = diagnostic; exportBytes = it; onReady() }
                .onFailure { message = if (diagnostic) R.string.diagnostic_export_error else R.string.backup_export_error }
        }
    }

    fun exportTo(uri: Uri?) {
        val bytes = exportBytes
        exportBytes = null
        if (uri == null || bytes == null) return
        busy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    checkNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt"))
                        .use { it.write(bytes); it.flush() }
                }
            }
            busy = false
            message = if (diagnosticExport) {
                if (result.isSuccess) R.string.diagnostic_export_success else R.string.diagnostic_export_error
            } else if (result.isSuccess) R.string.backup_export_success else R.string.backup_export_error
        }
    }

    fun importFrom(uri: Uri?) {
        if (uri == null || busy) return
        busy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val profile = checkNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use(ProfileBackup::read)
                    ImportPreview(profile, profile.savedMatch != null && container.resumableMatch(profile.savedMatch) == null,
                        profile.localSession != null && pt.tiago.neonline.data.LocalSessionReplay.restore(profile.localSession) == null,
                        profile.savedDaily != null && container.resumableDaily(profile.savedDaily) == null,
                        profile.savedFreePlay != null && container.resumableFreePlay(profile.savedFreePlay) == null,
                        profile.savedTournament != null && container.resumableTournament(profile.savedTournament) == null)
                }
            }
            busy = false
            result.onSuccess { (profile, unavailable, localInvalid, dailyInvalid, freeInvalid, tournamentInvalid) -> candidate = profile; savedUnavailable = unavailable; localUnavailable = localInvalid; dailyUnavailable = dailyInvalid; freeUnavailable = freeInvalid; tournamentUnavailable = tournamentInvalid }
                .onFailure { message = R.string.backup_import_error }
        }
    }

    fun requestReset() { if (!busy && candidate == null) resetRequested = true }
    fun cancelReset() { if (!busy) resetRequested = false }

    fun confirmReset() {
        if (!resetRequested || busy) return
        busy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { container.profileStore.resetProgress() } }
            busy = false
            result.onSuccess { resetRequested = false; replacedProfile = it; message = R.string.reset_success }
                .onFailure { message = R.string.reset_error }
        }
    }

    fun cancelImport() { candidate = null; savedUnavailable = false; localUnavailable = false; dailyUnavailable = false; freeUnavailable = false; tournamentUnavailable = false }
    fun clearMessage() { message = null }
    fun pickerFailed() { exportBytes = null; message = R.string.backup_picker_error }

    fun confirmImport() {
        val profile = candidate ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            val restored = profile.copy(savedMatch = profile.savedMatch.takeUnless { savedUnavailable },
                localSession = profile.localSession.takeUnless { localUnavailable }, savedDaily = profile.savedDaily.takeUnless { dailyUnavailable }, savedFreePlay = profile.savedFreePlay.takeUnless { freeUnavailable },
                savedTournament = profile.savedTournament.takeUnless { tournamentUnavailable })
            val result = withContext(Dispatchers.IO) { runCatching { container.profileStore.importProfile(restored) } }
            busy = false
            if (result.isSuccess) {
                cancelImport()
                replacedProfile = restored
                message = R.string.backup_import_success
            } else message = R.string.backup_restore_error
        }
    }
}

private data class ImportPreview(val profile: Profile, val campaignUnavailable: Boolean, val localUnavailable: Boolean, val dailyUnavailable: Boolean, val freeUnavailable: Boolean, val tournamentUnavailable: Boolean)
