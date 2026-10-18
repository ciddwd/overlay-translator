package com.gameocr.app.overlay

import com.gameocr.app.data.GestureMotionSensitivity
import org.junit.Assert.*
import org.junit.Test

class GestureMotionDetectorTest {
    private class Stream(val detector: GestureMotionDetector) {
        var now = 100L
        var pitch = 0f
        var twist = 0f
        var roll = 0f
        val results = mutableListOf<GestureWakeMotion>()
        init { sample(0f, 0f) }
        fun sample(pitchRate: Float, twistRate: Float, roll: Float = 0f) {
            detector.sample(pitchRate, twistRate, roll, now)?.let(results::add)
        }
        fun to(nextPitch: Float = pitch, nextTwist: Float = twist, nextRoll: Float = roll, duration: Int = 240) {
            val pitchRate = (nextPitch-pitch)*1000/duration
            val twistRate = (nextTwist-twist)*1000/duration
            val rollRate = (nextRoll-roll)*1000/duration
            repeat(duration/20) { now += 20; sample(pitchRate,twistRate,rollRate) }
            pitch = nextPitch; twist = nextTwist; roll = nextRoll
        }
        fun quiet(duration: Int) { repeat(duration/20) { now += 20; sample(0f,0f) } }
        fun twistCycle(amplitude: Float) { for (v in listOf(-amplitude,amplitude,-amplitude,amplitude,0f)) to(nextTwist=v) }
        fun tiltCycle(amplitude: Float) { for (v in listOf(amplitude,0f,amplitude,0f)) to(nextPitch=v) }
    }

    @Test fun bothAxesRecognizeOnlyTheirOwnCompleteSequence() {
        val twist = Stream(GestureMotionDetector(true,3,true,3))
        twist.twistCycle(40f)
        assertEquals(listOf(GestureWakeMotion.TWIST),twist.results)
        val tilt = Stream(GestureMotionDetector(true,3,true,3))
        tilt.tiltCycle(40f)
        assertEquals(listOf(GestureWakeMotion.TILT),tilt.results)
    }
    @Test fun eitherTwistDirectionAndTiltDirectionAreAccepted() {
        for (direction in listOf(-1,1)) {
            val a=Stream(GestureMotionDetector(true,3,false,3));a.twistCycle(direction*40f)
            assertEquals(listOf(GestureWakeMotion.TWIST),a.results)
            val b=Stream(GestureMotionDetector(false,3,true,3));b.tiltCycle(direction*40f)
            assertEquals(listOf(GestureWakeMotion.TILT),b.results)
        }
    }
    @Test fun independentSensitivityChangesRequiredAmplitude() {
        val a=Stream(GestureMotionDetector(true,5,true,1));a.twistCycle(20f)
        assertEquals(listOf(GestureWakeMotion.TWIST),a.results)
        val b=Stream(GestureMotionDetector(true,1,true,5));b.twistCycle(20f)
        assertTrue(b.results.isEmpty())
        val c=Stream(GestureMotionDetector(true,1,true,5));c.tiltCycle(20f)
        assertEquals(listOf(GestureWakeMotion.TILT),c.results)
        val d=Stream(GestureMotionDetector(true,5,true,1));d.tiltCycle(20f)
        assertTrue(d.results.isEmpty())
    }
    @Test fun disabledMethodsAndOrdinaryPoseChangesDoNotFire() {
        val off=Stream(GestureMotionDetector(false,5,false,5));off.twistCycle(50f);off.tiltCycle(50f)
        assertTrue(off.results.isEmpty())
        val single=Stream(GestureMotionDetector(true,3,true,3));single.to(nextPitch=40f);single.to(nextPitch=0f)
        assertTrue(single.results.isEmpty())
        val wrist=Stream(GestureMotionDetector(true,3,false,3));wrist.to(nextTwist=-40f);wrist.to(nextTwist=40f)
        assertTrue(wrist.results.isEmpty())
        val pose=Stream(GestureMotionDetector(false,3,true,3));pose.to(nextPitch=40f);pose.quiet(600);pose.to(nextPitch=0f)
        assertTrue(pose.results.isEmpty())
    }
    @Test fun verySlowPoseChangesAndDisplayRotationDoNotFire() {
        val slow=Stream(GestureMotionDetector(true,3,false,3))
        for (v in listOf(20f,0f,20f,0f)) slow.to(nextTwist=v,duration=2000)
        assertTrue(slow.results.isEmpty())
        val rotate=Stream(GestureMotionDetector(true,5,true,5))
        repeat(2) { rotate.to(nextRoll=90f,duration=500);rotate.quiet(600);rotate.to(nextRoll=0f,duration=500);rotate.quiet(600) }
        assertTrue(rotate.results.isEmpty())
    }
    @Test fun smallSameSideWristReturnsTriggerBothMethods() {
        val wrist=Stream(GestureMotionDetector(true,5,false,3))
        for (v in listOf(8f,0f,8f,0f)) wrist.to(nextTwist=v)
        assertEquals(listOf(GestureWakeMotion.TWIST),wrist.results)
        val tilt=Stream(GestureMotionDetector(false,3,true,5))
        for (v in listOf(8f,0f,8f,0f)) tilt.to(nextPitch=v)
        assertEquals(listOf(GestureWakeMotion.TILT),tilt.results)
    }
    @Test fun mediumSensitivityAcceptsComfortableTwentyDegreeMoves() {
        val wrist=Stream(GestureMotionDetector(true,3,false,3))
        for (v in listOf(20f,0f,20f,0f)) wrist.to(nextTwist=v)
        assertEquals(listOf(GestureWakeMotion.TWIST),wrist.results)
    }
    @Test fun shortPausesAtTurningPointsDoNotEraseTheGesture() {
        val tilt=Stream(GestureMotionDetector(false,3,true,3))
        repeat(2) { tilt.to(nextPitch=20f);tilt.quiet(450);tilt.to(nextPitch=0f) }
        assertEquals(listOf(GestureWakeMotion.TILT),tilt.results)
    }
    @Test fun returnNeedNotHitTheExactStartingPoseAndAllowsCoupledMotion() {
        val tilt=Stream(GestureMotionDetector(false,3,true,3))
        for (v in listOf(20f,4f,22f,6f)) tilt.to(nextPitch=v)
        assertEquals(listOf(GestureWakeMotion.TILT),tilt.results)
        val wrist=Stream(GestureMotionDetector(true,3,true,3))
        wrist.to(nextTwist=20f,nextPitch=7f);wrist.to(nextTwist=0f,nextPitch=4f)
        wrist.to(nextTwist=20f,nextPitch=9f);wrist.to(nextTwist=0f,nextPitch=6f)
        assertEquals(listOf(GestureWakeMotion.TWIST),wrist.results)
    }
    @Test fun turningPhoneLikeASteeringWheelAlsoCountsAsLeftRightRotation() {
        val wrist=Stream(GestureMotionDetector(true,5,true,3))
        for (v in listOf(-8f,0f,8f,0f)) wrist.to(nextRoll=v)
        assertEquals(listOf(GestureWakeMotion.TWIST),wrist.results)
    }
    @Test fun slowSmallMotionRemainsValidAndTinyJitterDoesNotFire() {
        val wrist=Stream(GestureMotionDetector(true,5,false,3))
        for (v in listOf(8f,0f,8f,0f)) wrist.to(nextTwist=v,duration=500)
        assertEquals(listOf(GestureWakeMotion.TWIST),wrist.results)
        val noise=Stream(GestureMotionDetector(true,5,true,5))
        repeat(5) { noise.to(nextTwist=2f,nextPitch=-2f);noise.to(nextTwist=-2f,nextPitch=2f) }
        assertTrue(noise.results.isEmpty())
    }
    @Test fun quickSmallReversalsAreNotFilteredOutByTheOldTimingGates() {
        val wrist=Stream(GestureMotionDetector(true,5,false,3))
        for (v in listOf(8f,0f,8f,0f)) wrist.to(nextTwist=v,duration=80)
        assertEquals(listOf(GestureWakeMotion.TWIST),wrist.results)
        val tilt=Stream(GestureMotionDetector(false,3,true,5))
        for (v in listOf(8f,0f,8f,0f)) tilt.to(nextPitch=v,duration=80)
        assertEquals(listOf(GestureWakeMotion.TILT),tilt.results)
    }
    @Test fun oneSmallExcursionDoesNotFireEvenAtHighSensitivity() {
        val wrist=Stream(GestureMotionDetector(true,5,true,5))
        wrist.to(nextTwist=8f);wrist.to(nextTwist=0f);wrist.quiet(1500)
        assertTrue(wrist.results.isEmpty())
    }
    @Test fun cooldownPreventsRepeatedTriggersAndLaterMotionWorks() {
        val stream=Stream(GestureMotionDetector(true,3,false,3))
        stream.twistCycle(40f);stream.twistCycle(40f)
        assertEquals(1,stream.results.size)
        stream.quiet(2000);stream.twistCycle(40f)
        assertEquals(2,stream.results.size)
    }
    @Test fun resetDiscardsPartialMotionAndInvalidSamples() {
        val stream=Stream(GestureMotionDetector(true,3,true,3))
        stream.to(nextTwist=-40f);stream.to(nextTwist=40f)
        stream.detector.reset()
        stream.to(nextTwist=-40f);stream.to(nextTwist=40f);stream.to(nextTwist=0f)
        assertTrue(stream.results.isEmpty())
        stream.sample(Float.NaN,0f);assertTrue(stream.results.isEmpty())
    }
    @Test fun fiveSensitivityLevelsDecreaseAndClamp() {
        assertEquals(listOf(28f,21f,14f,9f,5f),(1..5).map(GestureMotionSensitivity::degrees))
        assertEquals(28f,GestureMotionSensitivity.degrees(-100))
        assertEquals(5f,GestureMotionSensitivity.degrees(100))
    }
}
