package com.kylins.amapnav.core

import java.net.URI
import java.util.UUID
import kotlin.math.*

object RouteEntry {
    fun uri(id:String)="kylins-amapnav://route/$id"
    fun id(value:String?):String?=try {
        val u=URI(value ?: "")
        require(u.scheme=="kylins-amapnav" && u.host=="route" && u.userInfo==null && u.port==-1 && u.query==null && u.fragment==null)
        val id=u.path.removePrefix("/");require(UUID.fromString(id).toString()==id);id
    } catch (_:Exception) {null}
}

/** Project the display's top edge onto the horizontal plane; north is magnetic north. */
object CompassMath {
    private val sectors=arrayOf("北","东北","东","东南","南","西南","西","西北")
    fun topHeading(matrix:FloatArray,displayDegrees:Int):Double? {
        if(matrix.size<9 || matrix.any {!it.isFinite()}) return null
        val (x,y)=when(displayDegrees) {90->-1.0 to 0.0;180->0.0 to -1.0;270->1.0 to 0.0;else->0.0 to 1.0}
        val east=matrix[0]*x+matrix[1]*y
        val north=matrix[3]*x+matrix[4]*y
        if(hypot(east,north)<.25) return null
        return normalize(Math.toDegrees(atan2(east,north)))
    }
    fun normalize(degrees:Double)=(degrees%360+360)%360
    fun sector(degrees:Double)=floor((normalize(degrees)+22.5)/45).toInt()%8
    fun name(sector:Int)=sectors[(sector%8+8)%8]
    fun stableSector(degrees:Double,previous:Int?):Int {
        if(previous==null) return sector(degrees)
        val delta=abs((normalize(degrees)-previous*45+540)%360-180)
        return if(delta<=27.5) previous else sector(degrees)
    }
}
