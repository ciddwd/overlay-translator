package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect

/** Preserve every OCR source region for ink recovery without painting the merged rectangle. */
internal object RecognizedTextBoxPolicy {
    fun build(width: Int, height: Int, candidate: BooleanArray,
              blocks: List<DelayedTextEraseMaskBuilder.ConfirmedBlock>): TextPixelMaskBuilder.Result {
        require(width > 0 && height > 0 && candidate.size == width * height)
        val detected = TextPixelMaskBuilder.build(width, height, candidate, blocks)
        val detectedByBlock = detected.masks.associateBy { it.blockIndex }
        val masks = mutableListOf<TextPixelMaskBuilder.BlockMask>()
        val decisions = blocks.map { block ->
            val boxes = block.sourceBoxes.mapNotNull { box ->
                val clipped = IntRect(box.left.coerceIn(0,width),box.top.coerceIn(0,height),
                    box.right.coerceIn(0,width),box.bottom.coerceIn(0,height))
                clipped.takeIf { it.width > 0 && it.height > 0 }
            }
            if (boxes.isEmpty()) return@map TextPixelMaskBuilder.Decision(block.blockIndex,false,
                TextPixelMaskBuilder.Reason.NO_SOURCE_BOXES)
            val detectedMask = detectedByBlock[block.blockIndex]
                ?: return@map TextPixelMaskBuilder.Decision(block.blockIndex, false,
                    TextPixelMaskBuilder.Reason.TEXT_CORE_EMPTY)
            val bounds = detectedMask.bounds
            val recognized = BooleanArray(bounds.width*bounds.height)
            boxes.forEach { box -> for(y in box.top until box.bottom) for(x in box.left until box.right) {
                recognized[(y-bounds.top)*bounds.width+x-bounds.left] = true
            } }
            val count = detectedMask.selectedCorePixels
            masks += detectedMask.copy(recognizedBoxPixels = recognized, recognizedSourceBoxes = boxes)
            TextPixelMaskBuilder.Decision(block.blockIndex,true,TextPixelMaskBuilder.Reason.ACCEPTED,
                count,recognized.count { it })
        }
        return TextPixelMaskBuilder.Result(masks,decisions)
    }

    /** Any part outside one verified bubble makes the entire recognized block free text. */
    fun classify(boxes: List<IntRect>, masks: List<BubbleSegmentationPostprocessor.InstanceMask>,
                 qualities: List<BubbleShapeMaskQuality>): TextRegionGranularity {
        if(boxes.isEmpty() || boxes.any { it.width<=0 || it.height<=0 }) return TextRegionGranularity.FREE_TEXT
        val contained = masks.withIndex().any { (index,mask) ->
            mask.width>0 && mask.height>0 && mask.pixels.size==mask.width*mask.height &&
                qualities.getOrNull(index)==BubbleShapeMaskQuality.TRUSTED && boxes.all { box ->
                box.left>=mask.left && box.top>=mask.top && box.right<=mask.left+mask.width &&
                    box.bottom<=mask.top+mask.height && (box.top until box.bottom).all { y ->
                    (box.left until box.right).all { x -> mask.pixels[(y-mask.top)*mask.width+x-mask.left] }
                }
            }
        }
        return if(contained) TextRegionGranularity.BUBBLE else TextRegionGranularity.FREE_TEXT
    }
}
