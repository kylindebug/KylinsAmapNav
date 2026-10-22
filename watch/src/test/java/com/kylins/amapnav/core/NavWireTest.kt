package com.kylins.amapnav.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NavWireTest {
    private val id=UUID.randomUUID().toString()
    private fun sample()=NavFrame(active=true,message="左转",road="华山路",icon=2,meters=55,left=400,
        seconds=300,stamp=990_000,route=DemoRoute.previewPoints,position=DemoRoute.previewPoints.first(),
        journeyId=id,sequence=7,alert="3:2")

    @Test fun transfersAgeBetweenDevicesWithDifferentBootTimes() {
        val decoded=NavWire.decode(NavWire.encode(sample(),1_000_000),50_000)!!
        assertEquals(40_000L,decoded.stamp)
        assertFalse(GuidanceRules.outdated(50_000,decoded.stamp))
        assertTrue(GuidanceRules.outdated(55_001,decoded.stamp))
        assertEquals(sample().route,decoded.route)
        assertEquals(sample().position,decoded.position)
        assertEquals("3:2",decoded.alert)
    }
    @Test fun staleOrMissingFixNeverBecomesFreshOnDelivery() {
        for(stamp in listOf(0L,1L,984_999L)) {
            val decoded=NavWire.decode(NavWire.encode(sample().copy(stamp=stamp),1_000_000),50_000)!!
            assertEquals(0L,decoded.stamp)
            assertTrue(GuidanceRules.outdated(50_000,decoded.stamp))
        }
    }
    @Test fun rejectsInvalidProtocolAndBoundsGeometry() {
        val raw=NavWire.encode(sample(),1_000_000)
        assertNull(NavWire.decode("{",50_000))
        assertNull(NavWire.decode(" ".repeat(24_001),50_000))
        for((field,value) in listOf("version" to 1,"id" to "invalid","sequence" to 0,"age" to -2,"position" to "NaN,31"))
            assertNull(NavWire.decode(JSONObject(raw).put(field,value).toString(),50_000))
        val large=sample().copy(route=List(1_000) {Coordinate(121.0,31.0)})
        assertEquals(80,NavWire.decode(NavWire.encode(large,1_000_000),50_000)!!.route.size)
    }
    @Test fun ignoresOtherSessionsDuplicatesAndOutOfOrderFrames() {
        val session=NavSession(id,10_000)
        assertFalse(session.accept(sample().copy(journeyId=UUID.randomUUID().toString()),11_000))
        assertEquals(10_000L,session.lastReceived)
        assertTrue(session.accept(sample(),12_000))
        assertFalse(session.accept(sample(),13_000))
        assertFalse(session.accept(sample().copy(sequence=6),14_000))
        assertEquals(12_000L,session.lastReceived)
        assertFalse(session.expired(41_999))
        assertTrue(session.expired(42_000))
    }
    @Test fun stopAndArrivalAreTerminalEvenWhenNewerUpdatesArrive() {
        val stopped=NavSession(id,10_000)
        assertTrue(stopped.accept(sample(),11_000));stopped.stop()
        assertFalse(stopped.accept(sample().copy(sequence=8),12_000))
        val arrived=NavSession(id,10_000)
        assertTrue(arrived.accept(sample().copy(active=false),11_000))
        assertFalse(arrived.accept(sample().copy(sequence=8),12_000))
    }
    @Test fun phoneStopsWhenWatchNeverConfirmsOrHeartbeatsCease() {
        val unconfirmed=WatchLease(10_000)
        assertFalse(unconfirmed.expired(99_999))
        assertTrue(unconfirmed.expired(100_000))
        val confirmed=WatchLease(10_000)
        confirmed.acknowledge(20_000)
        assertFalse(confirmed.expired(79_999))
        assertTrue(confirmed.expired(80_000))
        confirmed.acknowledge(70_000)
        assertFalse(confirmed.expired(129_999))
        assertTrue(confirmed.expired(130_000))
    }
}
