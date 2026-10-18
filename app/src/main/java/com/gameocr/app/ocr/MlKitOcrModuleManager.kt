package com.gameocr.app.ocr

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** The optional module belongs to Google Play services, not to our model-file installer. */
@Singleton
class MlKitOcrModuleManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val client by lazy { ModuleInstall.getClient(context) }
    val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }
    private val mutex = Mutex()
    private val generation = AtomicLong()
    @Volatile private var lastUnavailableAt: Long? = null

    suspend fun available(refresh: Boolean = false): Boolean {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) return false
        // Do not add an IPC timeout to every Auto frame when the optional model has never been installed.
        if (!refresh && lastUnavailableAt?.let { SystemClock.elapsedRealtime() - it < 15_000 } == true) return false
        val ready = try {
            withTimeoutOrNull(2_000) { client.areModulesAvailable(recognizer).await().areModulesAvailable() } == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.d(error, "Optional OCR module availability check failed")
            false
        }
        lastUnavailableAt = if (ready) null else SystemClock.elapsedRealtime()
        return ready
    }

    suspend fun download() = mutex.withLock {
        if (available(refresh = true)) return@withLock
        val request = generation.incrementAndGet()
        val updates = Channel<MlKitOcrInstallUpdate>(Channel.UNLIMITED)
        val listener = InstallStatusListener { updates.trySend(MlKitOcrInstallUpdate(it.sessionId, it.installState, it.errorCode)) }
        var complete = false
        val abandoned = AtomicBoolean(false)
        fun releaseIfCurrent() {
            if (generation.get() == request) {
                client.releaseModules(recognizer).addOnFailureListener {
                    Timber.w(it, "Optional OCR module cancellation was not accepted")
                }
            }
        }
        try {
            val task = client.installModules(ModuleInstallRequest.newBuilder()
                .addApi(recognizer).setListener(listener).build())
            // A request can be accepted after our coroutine was cancelled. Do not cancel a newer request.
            task.addOnSuccessListener {
                if (abandoned.get()) releaseIfCurrent()
            }
            val response = task.await()
            awaitMlKitOcrModule(response.sessionId, response.areModulesAlreadyInstalled(), updates)
            check(available(refresh = true)) { "Google Play services: OCR module unavailable" }
            complete = true
        } finally {
            abandoned.set(!complete)
            updates.close()
            client.unregisterListener(listener).addOnFailureListener { Timber.d(it, "OCR listener cleanup failed") }
            if (!complete) releaseIfCurrent()
        }
    }
}

internal data class MlKitOcrInstallUpdate(val sessionId: Int, val state: Int, val errorCode: Int = 0)

internal suspend fun awaitMlKitOcrModule(
    sessionId: Int, alreadyInstalled: Boolean, updates: ReceiveChannel<MlKitOcrInstallUpdate>,
) {
    if (alreadyInstalled) return
    while (true) {
        val update = updates.receive()
        if (update.sessionId != sessionId) continue
        when (update.state) {
            InstallState.STATE_COMPLETED -> return
            InstallState.STATE_FAILED -> error("Google Play services: ${update.errorCode}")
            InstallState.STATE_CANCELED -> throw CancellationException("Google Play services cancelled OCR download")
        }
    }
}
