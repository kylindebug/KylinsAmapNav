package com.kylins.amapnav.core
import org.junit.Assert.*
import org.junit.Test

class WatchInteractionTest {
    @Test fun displayTopRespectsRotationAndRejectsVerticalProjection() {
        val north=floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)
        assertEquals(0.0,CompassMath.topHeading(north,0)!!,.01)
        assertEquals(270.0,CompassMath.topHeading(north,90)!!,.01)
        assertEquals(180.0,CompassMath.topHeading(north,180)!!,.01)
        assertEquals(90.0,CompassMath.topHeading(north,270)!!,.01)
        assertNull(CompassMath.topHeading(floatArrayOf(1f,0f,0f,0f,0f,-1f,0f,1f,0f),0))
        assertNull(CompassMath.topHeading(floatArrayOf(Float.NaN),0))
    }
    @Test fun eightDirectionsWrapAndHaveHysteresis() {
        val names=listOf("北","东北","东","东南","南","西南","西","西北")
        names.forEachIndexed {i,n->assertEquals(n,CompassMath.name(CompassMath.sector(i*45.0)))}
        assertEquals("北",CompassMath.name(CompassMath.sector(359.0)))
        assertEquals(0,CompassMath.stableSector(25.0,0))
        assertEquals(1,CompassMath.stableSector(29.0,0))
        assertEquals(0,CompassMath.stableSector(-1.0,0))
    }
    @Test fun remoteEntryOnlyAcceptsCanonicalRouteIds() {
        val id=java.util.UUID.randomUUID().toString()
        assertEquals(id,RouteEntry.id(RouteEntry.uri(id)))
        listOf("kylins-amapnav://route/not-a-route","kylins-amapnav://route/$id/extra","https://route/$id","kylins-amapnav://evil@route/$id","kylins-amapnav://route/$id?start=1").forEach {assertNull(RouteEntry.id(it))}
    }
}
