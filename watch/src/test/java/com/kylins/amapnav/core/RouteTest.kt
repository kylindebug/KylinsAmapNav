package com.kylins.amapnav.core
import org.junit.Assert.*
import org.junit.Test

class RouteTest {
    @Test fun observedWalkingShareSwapsLatitudeLongitudeExactlyOnce() {
        val r=ShareRoute.parse("https://wb.amap.com//?r=31.120159,121.783461,Start,31.199005,121.433095,Station,,2,0")!!
        assertEquals(Coordinate(121.433095,31.199005),r.destination)
        assertEquals(Coordinate(121.783461,31.120159),r.origin)
        assertEquals("walk",r.mode)
        assertEquals("上海交大",ShareRoute.parse("https://wb.amap.com//?r=31.120159,121.783461,A,31.199005,121.433095,%25E4%25B8%258A%25E6%25B5%25B7%25E4%25BA%25A4%25E5%25A4%25A7,,2,0")!!.title)
    }
    @Test fun officialUriAndHostBoundaries() {
        assertEquals("ride",ShareRoute.parse("https://uri.amap.com/navigation?to=121.433095,31.199005,Station&mode=ride")!!.mode)
        listOf("https://amap.com.evil.test/?to=1,2&mode=walk","https://amap.com@evil.test/?to=1,2&mode=walk","http://amap.com/?to=1,2&mode=walk","https://127.0.0.1/","https://www.amap.com/?r=31.120159,121.783461,A,31.199005,121.433095,B,,0").forEach { assertNull(it,ShareRoute.parse(it)) }
        assertNull(ShareRoute.parse("https://uri.amap.com/navigation?to=1,2&mode=walk&via=1,3"))
    }
    @Test fun transportRejectsBadCoordinatesStaleAndInvalidVersion() {
        assertNull(Coordinate.read("NaN,2")); assertNull(Coordinate.read("180,91")); assertNull(Coordinate.read("1,2,3"))
        val j=Journey("站",Coordinate(121.433095,31.199005)); assertNotNull(Journey.decode(j.encode()))
        assertNull(Journey.decode(j.copy(created=1).encode()))
        assertNull(Journey.decode(j.encode().replace("\"version\":1","\"version\":2")))
        assertTrue(GuidanceRules.outdated(20_001,5000)); assertFalse(GuidanceRules.outdated(20_000,5000))
    }
}
