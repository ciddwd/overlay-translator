package com.gameocr.app.ui

import androidx.work.WorkInfo
import com.gameocr.app.download.ModelDownloadSpec
import com.gameocr.app.download.ModelDownloadWorkPolicy
import com.gameocr.app.download.ModelDownloadWorker
import java.util.UUID

/** Work identity survives empty queued progress, cancellation and leaving settings. */
internal object AsrModelWorkUi {
    fun latest(infos: List<WorkInfo>, spec: ModelDownloadSpec, knownId: UUID?): WorkInfo? = infos
        .filter { info ->
            info.id == knownId || ModelDownloadWorkPolicy.specTag(spec) in info.tags ||
                info.progress.getString(ModelDownloadWorker.KEY_CURRENT_SPEC) == spec.encode() ||
                info.outputData.getString(ModelDownloadWorker.KEY_CURRENT_SPEC) == spec.encode() ||
                info.progress.getStringArray(ModelDownloadWorker.KEY_SPECS).orEmpty().contains(spec.encode()) ||
                info.outputData.getStringArray(ModelDownloadWorker.KEY_SPECS).orEmpty().contains(spec.encode())
        }
        .maxWithOrNull(compareBy<WorkInfo> { !it.state.isFinished }.thenBy {
            maxOf(ModelDownloadWorkPolicy.createdAt(it.tags), it.outputData.getLong(ModelDownloadWorker.KEY_FINISHED_AT, 0L))
        }.thenBy { it.id == knownId })

    fun ownProgress(info: WorkInfo?, spec: ModelDownloadSpec): Boolean = info != null &&
        info.progress.getString(ModelDownloadWorker.KEY_CURRENT_SPEC) == spec.encode()
}
