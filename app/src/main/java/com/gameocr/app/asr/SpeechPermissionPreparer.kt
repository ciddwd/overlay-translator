package com.gameocr.app.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.content.ContextCompat
import com.gameocr.app.capture.RootShell
import com.gameocr.app.shizuku.ShizukuManager
import com.gameocr.app.shizuku.SpeechPermissionCommands
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Before the standard prompts, tries to authorize playback capture through a backend the user has
 * already set up: Shizuku with shell privilege, or root that was already granted. Nothing here
 * prompts; every command may be refused (OEM policy, revoked shell), in which case the normal
 * recording-permission and projection consent flow simply shows its dialogs.
 */
@Singleton
class SpeechPermissionPreparer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizuku: ShizukuManager,
    private val rootShell: RootShell,
) {
    private val mutex = Mutex()
    /** The app-op persists until the user or system resets it; once per process is enough. */
    @Volatile private var projectionPrepared = false

    internal enum class Backend { SHIZUKU, ROOT }

    suspend fun prepare() = mutex.withLock {
        val backend = when {
            shizuku.isServiceRunning() && shizuku.hasPermission() && shizuku.shellPrivilegeOk.value -> Backend.SHIZUKU
            rootShell.isGranted -> Backend.ROOT
            else -> return@withLock
        }
        val userId = Process.myUid() / 100_000
        val packageName = context.packageName
        if (!audioGranted()) {
            run(backend, SpeechPermissionCommands.grantRecordAudio(packageName, userId))
            Timber.i("[asr-permissions] backend=%s recordAudioGranted=%s", backend, audioGranted())
        }
        if (!projectionPrepared) {
            projectionPrepared = run(backend, SpeechPermissionCommands.allowProjectMedia(packageName, userId))
            Timber.i("[asr-permissions] backend=%s projectMediaAllowed=%s", backend, projectionPrepared)
        }
    }

    fun audioGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private suspend fun run(backend: Backend, command: Array<String>): Boolean = when (backend) {
        Backend.SHIZUKU -> shizuku.executeGrantCommand(command)
        Backend.ROOT -> rootShell.run(SpeechPermissionCommands.asShellLine(command))
    }
}
