package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import kotlin.math.abs

/** Recover ink inside each original line; the paragraph rectangle is never a fill target. */
internal object SourceTextInkPolicy {
    data class Plan(val erase: BooleanArray, val colors: IntArray, val unresolved: BooleanArray)

    fun plan(width:Int, height:Int, source:IntArray, core:BooleanArray, boxes:List<IntRect>,
             includeLowContrastOutline:Boolean = true):Plan {
        require(width>0 && height>0 && source.size==width*height && core.size==source.size)
        val erase=BooleanArray(source.size)
        val colors=IntArray(source.size)
        val haloRadius=ByteArray(source.size)
        val unresolved=BooleanArray(source.size)
        val protected=BooleanArray(source.size)
        val sourceRegion=BooleanArray(source.size)
        boxes.forEach { box -> for(y in box.top.coerceIn(0,height) until box.bottom.coerceIn(0,height))
            for(x in box.left.coerceIn(0,width) until box.right.coerceIn(0,width))sourceRegion[y*width+x]=true }
        // Wide pale outlines may outnumber the actual background inside a narrow line.
        // Independent samples outside all recognized lines can disambiguate that pair.
        val contextHistogram=IntArray(4096)
        if(includeLowContrastOutline)for(i in source.indices)
            if(!sourceRegion[i] && !core[i])contextHistogram[bin(source[i])]++
        val contextBin=contextHistogram.indices.maxBy { contextHistogram[it] }
        val contextCount=contextHistogram[contextBin]
        val contextTotal=contextHistogram.sum()
        val contextRunnerUp=contextHistogram.indices.filter { it!=contextBin }.maxOf { contextHistogram[it] }
        val queue=IntArray(source.size)
        // These arrays depend only on the exact background color and the fixed source region,
        // not on which original line is being classified. Reuse consecutive identical colors.
        var cachedBackground: Int? = null
        var cachedFrames: BooleanArray? = null
        var cachedCandidate: BooleanArray? = null
        for(raw in boxes) {
            val box=IntRect(raw.left.coerceIn(0,width),raw.top.coerceIn(0,height),
                raw.right.coerceIn(0,width),raw.bottom.coerceIn(0,height))
            if(box.width<=0 || box.height<=0)continue
            val histogram=IntArray(4096)
            val cleanCount=(box.top until box.bottom).sumOf { y ->
                (box.left until box.right).count { x -> !core[y*width+x] }
            }
            // Sparse samples outside a broad DBNet core can consist only of exterior art.
            // Use them only when they represent a substantial part of this original line.
            val excludeCore=cleanCount>=maxOf(16,(box.width*box.height*.30f).toInt())
            var sampleCount=0
            for(y in box.top until box.bottom)for(x in box.left until box.right) {
                val i=y*width+x
                if(excludeCore && core[i])continue
                histogram[bin(source[i])]++;sampleCount++
            }
            var dominant=histogram.indices.maxBy { histogram[it] }
            fun binColor(value:Int)=0xff000000.toInt() or (((value ushr 8)*16+8) shl 16) or
                ((((value ushr 4) and 15)*16+8) shl 8) or ((value and 15)*16+8)
            val localCenter=binColor(dominant)
            val contextCenter=binColor(contextBin)
            if(includeLowContrastOutline && contextCount>=16 && contextCount>=contextTotal*.30f &&
                contextCount>=contextRunnerUp*1.05f && histogram[contextBin]>=sampleCount*.20f &&
                distance(localCenter,contextCenter)<=24 && chroma(contextCenter)>chroma(localCenter) &&
                luminanceDifference(localCenter,contextCenter)>3000)dominant=contextBin
            val center=0xff000000.toInt() or (((dominant ushr 8)*16+8) shl 16) or
                ((((dominant ushr 4) and 15)*16+8) shl 8) or ((dominant and 15)*16+8)
            var r=0L;var g=0L;var b=0L;var count=0;var nearbyCount=0
            for(y in box.top until box.bottom)for(x in box.left until box.right) {
                val i=y*width+x
                if(excludeCore && core[i])continue
                val color=source[i]
                if(distance(color,center)<=24)nearbyCount++
                // A white outline can be close to a pale blue background but lies in another
                // histogram bin. Do not average that foreground into the replacement color.
                if(if(includeLowContrastOutline) bin(color)==dominant else distance(color,center)<=24) {
                    r+=color ushr 16 and 255;g+=color ushr 8 and 255;b+=color and 255;count++
                }
            }
            if(count<16 || nearbyCount<sampleCount*.55f) {
                for(y in box.top until box.bottom)for(x in box.left until box.right)unresolved[y*width+x]=true
                continue
            }
            val background=0xff000000.toInt() or ((r/count).toInt() shl 16) or ((g/count).toInt() shl 8) or (b/count).toInt()
            val outlineRadius=if(includeLowContrastOutline)
                (minOf(box.width,box.height)*.12f).toInt().coerceIn(3,8).toByte() else 0.toByte()
            // JPEG/antialiasing fringes can bridge a glyph to the balloon outline. Establish
            // independent ink bodies first; recover their faint rim only after ownership.
            if (cachedBackground != background) {
                cachedFrames = null
                cachedCandidate = null
                val observedInk=BooleanArray(source.size) { distance(source[it],background)>80 }
                val frames=TextRegionBorderMask.detect(width,height,
                    BooleanArray(source.size) { observedInk[it] && chroma(source[it])<=24 },sourceRegion)
                cachedFrames = frames
                cachedCandidate = BooleanArray(source.size) { observedInk[it] && !frames[it] }
                cachedBackground = background
            }
            val frames = checkNotNull(cachedFrames)
            for(i in frames.indices)if(frames[i])protected[i]=true
            val candidate=checkNotNull(cachedCandidate)
            val seen=BooleanArray(source.size)
            val sparseLoops=mutableListOf<IntArray>()
            val glyphs=mutableListOf<Pair<IntArray,IntRect>>()
            var hasInteriorGlyph=false
            val margin=(minOf(box.width,box.height)*.35f).toInt().coerceIn(2,20)
            for(y in (box.top-margin).coerceAtLeast(0) until (box.bottom+margin).coerceAtMost(height))
                for(x in (box.left-margin).coerceAtLeast(0) until (box.right+margin).coerceAtMost(width)) {
                val start=y*width+x
                if(!sourceRegion[start] && !core[start])continue
                if(seen[start] || !candidate[start])continue
                var head=0;var tail=1;var inside=0;var seeded=0
                var left=width;var top=height;var right=0;var bottom=0
                var touchesCrop=false
                queue[0]=start;seen[start]=true
                while(head<tail) {
                    val i=queue[head++];val px=i%width;val py=i/width
                    if(sourceRegion[i])inside++
                    if(core[i])seeded++
                    left=minOf(left,px);top=minOf(top,py);right=maxOf(right,px+1);bottom=maxOf(bottom,py+1)
                    touchesCrop=touchesCrop || px==0 || py==0 || px==width-1 || py==height-1
                    for(dy in -1..1)for(dx in -1..1) {
                        val nx=px+dx;val ny=py+dy
                        if(nx !in 0 until width || ny !in 0 until height)continue
                        val next=ny*width+nx
                        if(!seen[next] && candidate[next] && sameInkFamily(source[start],source[next])) {
                            seen[next]=true;queue[tail++]=next
                        }
                    }
                }
                // Artwork and borders continue beyond the source line. An isolated glyph may
                // extend a little beyond the detector's edge and still belongs to that line.
                val clippedGlyph=seeded>=maxOf(2,(tail*.3f).toInt()) &&
                    maxOf(right-left,bottom-top)<=minOf(box.width,box.height)*1.5f
                if(touchesCrop || inside==0 || (inside<tail*.8f && !clippedGlyph)) {
                    for(position in 0 until tail)protected[queue[position]]=true
                    continue
                }
                val sparseLoop=(right-left)>=box.width*.7f && (bottom-top)>=box.height*.7f &&
                    tail<(right-left)*(bottom-top)*.2f
                if(sparseLoop) { sparseLoops+=queue.copyOf(tail);continue }
                if(seeded>0 && tail>=4)hasInteriorGlyph=true
                glyphs+=queue.copyOf(tail) to IntRect(left,top,right,bottom)
            }
            val minimumBody=(minOf(box.width,box.height)*.1f).toInt().coerceIn(3,12)
            val bodies=glyphs.filter { (pixels,bounds) -> pixels.size>=minimumBody &&
                maxOf(bounds.width,bounds.height)>=minimumBody }.map { it.second }
            glyphs.forEach { (pixels,bounds) ->
                val body=pixels.size>=minimumBody && maxOf(bounds.width,bounds.height)>=minimumBody
                val adjacent=bodies.any { other -> bounds.right>=other.left-3 && bounds.left<=other.right+3 &&
                    bounds.bottom>=other.top-3 && bounds.top<=other.bottom+3 }
                val onTextAxis=bodies.size>=2 && if(box.height>box.width) {
                    bounds.top>=bodies.minOf { it.top } && bounds.bottom<=bodies.maxOf { it.bottom } &&
                        bodies.any { bounds.left>=it.left-2 && bounds.right<=it.right+2 }
                } else {
                    bounds.left>=bodies.minOf { it.left } && bounds.right<=bodies.maxOf { it.right } &&
                        bodies.any { bounds.top>=it.top-2 && bounds.bottom<=it.bottom+2 }
                }
                if(body || adjacent || onTextAxis)for(i in pixels) {
                    erase[i]=true;colors[i]=background;haloRadius[i]=outlineRadius
                }
            }
            // An isolated thin O is text; a large sparse enclosure around smaller confirmed
            // glyphs is the balloon border, even if DBNet included it in the source rectangle.
            sparseLoops.forEach { loop -> for(i in loop) {
                if(hasInteriorGlyph)protected[i]=true else { erase[i]=true;colors[i]=background }
            } }
        }
        for(i in erase.indices)if(protected[i])erase[i]=false
        val protectedRim=BinarySquareDilation.dilate(protected,width,height,1)
        // Include the antialiased rim, without extending a repair rectangle over the drawing.
        val bodies=erase.copyOf()
        for(i in bodies.indices)if(bodies[i]) {
            val x=i%width;val y=i/width
            for(dy in -4..4)for(dx in -4..4) {
                val nx=x+dx;val ny=y+dy
                if(nx !in 0 until width || ny !in 0 until height)continue
                val next=ny*width+nx
                val near=abs(dx)<=2 && abs(dy)<=2
                val supportedEdge=includeLowContrastOutline && core[next] && isFaintInkEdge(source[next],colors[i],source[i])
                if(sourceRegion[next] && !protectedRim[next] && !erase[next] &&
                    (near || supportedEdge) && distance(source[next],colors[i])<=96) {
                    erase[next]=true;colors[next]=colors[i]
                }
            }
        }
        if(includeLowContrastOutline)
            recoverLowContrastOutline(width,height,source,bodies,erase,colors,haloRadius,sourceRegion,protectedRim)
        return Plan(erase,colors,unresolved)
    }

    /** Follow a contrasting outline from confirmed ink, never flood a detector rectangle. */
    private fun recoverLowContrastOutline(
        width:Int,height:Int,source:IntArray,bodies:BooleanArray,erase:BooleanArray,colors:IntArray,
        bodyRadius:ByteArray,sourceRegion:BooleanArray,protected:BooleanArray,
    ) {
        val steps=ByteArray(source.size) { -1 }
        val polarity=ByteArray(source.size)
        val radii=ByteArray(source.size)
        val queue=IntArray(source.size)
        var head=0;var tail=0
        for(i in bodies.indices)if(bodies[i] && bodyRadius[i]>0) {
            val background=colors[i]
            val delta=luminanceDifference(source[i],background)
            if(delta==0)continue
            val inkPolarity=if(delta<0) -1 else 1
            val availableContrast=if(inkPolarity<0) {
                255-minOf(background ushr 16 and 255,background ushr 8 and 255,background and 255)
            } else maxOf(background ushr 16 and 255,background ushr 8 and 255,background and 255)
            if(availableContrast<6)continue
            val x=i%width;val y=i/width
            // The existing antialias rim connects the ink to its opposite-polarity outline.
            for(dy in -2..2)for(dx in -2..2) {
                val nx=x+dx;val ny=y+dy
                if(nx !in 0 until width || ny !in 0 until height)continue
                val next=ny*width+nx
                if(!erase[next] || !sourceRegion[next] || protected[next] || steps[next]>=0)continue
                steps[next]=0;polarity[next]=inkPolarity.toByte();radii[next]=bodyRadius[i]
                queue[tail++]=next
            }
        }
        while(head<tail) {
            val i=queue[head++]
            if(steps[i]>=radii[i])continue
            val x=i%width;val y=i/width;val background=colors[i]
            for(dy in -1..1)for(dx in -1..1) {
                val nx=x+dx;val ny=y+dy
                if(nx !in 0 until width || ny !in 0 until height)continue
                val next=ny*width+nx
                if(erase[next] || !sourceRegion[next] || protected[next] ||
                    !isOppositeOutline(source[next],background,polarity[i].toInt()))continue
                erase[next]=true;colors[next]=background
                steps[next]=(steps[i]+1).toByte();polarity[next]=polarity[i];radii[next]=radii[i]
                queue[tail++]=next
            }
        }
    }

    private fun isOppositeOutline(color:Int,background:Int,inkPolarity:Int):Boolean {
        if(distance(color,background)>80)return false
        val direction=-inkPolarity
        val r=((color ushr 16 and 255)-(background ushr 16 and 255))*direction
        val g=((color ushr 8 and 255)-(background ushr 8 and 255))*direction
        val b=((color and 255)-(background and 255))*direction
        return minOf(r,g,b)>=-4 && maxOf(r,g,b)>=6 && r*299+g*587+b*114>=3000
    }

    private fun isFaintInkEdge(color:Int,background:Int,ink:Int):Boolean {
        if(distance(color,background) !in 24..80 || !sameInkFamily(color,ink))return false
        val edge=luminanceDifference(color,background);val body=luminanceDifference(ink,background)
        return (edge<0 && body<0) || (edge>0 && body>0)
    }

    private fun luminanceDifference(color:Int,background:Int):Int =
        ((color ushr 16 and 255)-(background ushr 16 and 255))*299+
            ((color ushr 8 and 255)-(background ushr 8 and 255))*587+
            ((color and 255)-(background and 255))*114

    private fun bin(c:Int)=((c ushr 20 and 15) shl 8) or ((c ushr 12 and 15) shl 4) or (c ushr 4 and 15)
    private fun distance(a:Int,b:Int)=maxOf(abs((a ushr 16 and 255)-(b ushr 16 and 255)),
        abs((a ushr 8 and 255)-(b ushr 8 and 255)),abs((a and 255)-(b and 255)))
    private fun chroma(c:Int)=maxOf(c ushr 16 and 255,c ushr 8 and 255,c and 255)-
        minOf(c ushr 16 and 255,c ushr 8 and 255,c and 255)
    private fun sameInkFamily(a:Int,b:Int):Boolean {
        val ac=chroma(a);val bc=chroma(b)
        if(ac<=24 || bc<=24)return ac<=24 && bc<=24
        val amin=minOf(a ushr 16 and 255,a ushr 8 and 255,a and 255)
        val bmin=minOf(b ushr 16 and 255,b ushr 8 and 255,b and 255)
        val ar=(a ushr 16 and 255)-amin;val ag=(a ushr 8 and 255)-amin;val ab=(a and 255)-amin
        val br=(b ushr 16 and 255)-bmin;val bg=(b ushr 8 and 255)-bmin;val bb=(b and 255)-bmin
        val dot=(ar*br+ag*bg+ab*bb).toDouble()
        return dot*dot>=(ar*ar+ag*ag+ab*ab).toDouble()*(br*br+bg*bg+bb*bb)*.85
    }
}
