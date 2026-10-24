package com.gameocr.app.ui

import com.gameocr.app.data.SettingsSecretStorageException
import android.content.Context
import com.gameocr.app.R

internal fun settingsWriteErrorMessage(context: Context, error: Exception): String =
    if (error is SettingsSecretStorageException) context.getString(R.string.settings_storage_save_failed)
    else error.localizedMessage ?: error.javaClass.simpleName

/** Catch only a rejected settings write; cancellation and unrelated failures retain their behavior. */
internal suspend fun runSettingsWrite(onFailure: suspend () -> Unit, write: suspend () -> Unit): Boolean =
    try {
        write()
        true
    } catch (_: SettingsSecretStorageException) {
        onFailure()
        false
    }
