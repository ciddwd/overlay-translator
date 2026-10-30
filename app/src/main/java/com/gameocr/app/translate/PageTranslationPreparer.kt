package com.gameocr.app.translate

import android.content.Context
import android.graphics.Bitmap
import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.Settings
import com.gameocr.app.data.adaptiveOverlayActive
import com.gameocr.app.glossary.SourcePreservationPlan
import com.gameocr.app.glossary.SourcePreservationService
import com.gameocr.app.ocr.TextBlock
import com.gameocr.app.ocr.TextOrientation
import com.gameocr.app.ocr.sortTextBlocksForMergedPage
import com.gameocr.app.overlay.AdaptiveOverlayStyle
import com.gameocr.app.overlay.AdaptiveOverlayStyleAnalyzer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

internal data class PreparedPageTranslation(
    val blocks: List<TextBlock>,
    val preservation: SourcePreservationPlan,
    val visual: PreparedTranslationVisualContext,
    val adaptiveStyles: List<AdaptiveOverlayStyle>,
)

/** The post-OCR page consumed by both live overlays and file rendering. */
@Singleton
class PageTranslationPreparer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sourcePreservation: SourcePreservationService,
) {
    internal suspend fun prepare(
        bitmap: Bitmap,
        blocks: List<TextBlock>,
        settings: Settings,
        readingOrientation: TextOrientation,
        presentation: RenderMode,
        origin: String,
        debugStore: TranslationVisualDebugStore,
        lookup: (suspend () -> SourcePreservationService.Lookup?)? = null,
    ): PreparedPageTranslation {
        val preservation = if (lookup == null) sourcePreservation.plan(blocks, settings)
            else sourcePreservation.plan(blocks, settings, lookup)
        val mergeAll = PageTranslationGroupingPolicy.shouldMergeAll(
            presentation, settings.mergeAdjacentBlocks, settings.mergeStrength)
        val retained = if (mergeAll) sortTextBlocksForMergedPage(
            preservation.retainedBlocks, readingOrientation) else preservation.retainedBlocks
        return withContext(Dispatchers.Default) {
            PreparedPageTranslation(
                blocks = retained,
                preservation = preservation,
                visual = TranslationVisualContextPreparer.prepare(
                    bitmap, retained, settings, presentation, mergeAll, origin, debugStore),
                adaptiveStyles = analyzeStyles(bitmap, retained, settings),
            )
        }
    }

    internal fun analyzeStyles(
        bitmap: Bitmap,
        blocks: List<TextBlock>,
        settings: Settings,
    ): List<AdaptiveOverlayStyle> {
        if (!adaptiveOverlayActive(settings.overlayStyleMode, settings.renderMode) || blocks.isEmpty()) return emptyList()
        return runCatching {
            AdaptiveOverlayStyleAnalyzer.analyze(bitmap, blocks,
                context.resources.displayMetrics.density * context.resources.configuration.fontScale)
        }.getOrElse { error ->
            Timber.w(error, "Adaptive style analysis failed; use safe overlay fallback")
            emptyList()
        }
    }
}
