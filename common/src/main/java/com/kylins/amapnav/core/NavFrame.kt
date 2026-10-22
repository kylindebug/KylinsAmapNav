package com.kylins.amapnav.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class NavFrame(val active: Boolean=false,val message: String="准备出发",val road: String="",val icon: Int=9,
    val meters: Int=-1,val left: Int=0,val seconds: Int=0,val stamp: Long=0,
    val route: List<Coordinate> = emptyList(),val position: Coordinate?=null,
    val journeyId: String="",val sequence: Long=0,val alert: String="")

/** Elapsed-realtime clocks belong to one device. Send an age, never a phone timestamp. */
object NavWire {
    const val FRAME="/kylins/v2/frame"
    const val HEARTBEAT="/kylins/v2/heartbeat"
    const val STOP="/kylins/v2/stop"
    const val TERMINAL="/kylins/v2/terminal"
    const val WATCH_TIMEOUT=30_000L
    const val PHONE_TIMEOUT=60_000L
    const val START_TIMEOUT=90_000L
    fun validId(id: String)=runCatching { UUID.fromString(id).toString()==id }.getOrDefault(false)
    fun encode(frame: NavFrame, now: Long): String {
        val points=JSONArray()
        frame.route.take(80).forEach { points.put(it.toString()) }
        return JSONObject().put("version",2).put("id",frame.journeyId).put("sequence",frame.sequence)
            .put("active",frame.active).put("message",frame.message.take(100)).put("road",frame.road.take(60))
            .put("icon",frame.icon).put("meters",frame.meters).put("left",frame.left).put("seconds",frame.seconds)
            .put("age",if(frame.stamp>0) (now-frame.stamp).coerceAtLeast(0) else -1)
            .put("route",points).put("position",frame.position?.toString().orEmpty()).put("alert",frame.alert.take(80)).toString()
    }
    fun decode(raw: String, now: Long): NavFrame? = runCatching {
        require(raw.toByteArray(Charsets.UTF_8).size<=24_000)
        val j=JSONObject(raw)
        require(j.getInt("version")==2 && validId(j.getString("id")))
        val sequence=j.getLong("sequence");require(sequence>0)
        val age=j.getLong("age");require(age>=-1)
        val points=j.getJSONArray("route");require(points.length()<=80)
        val position=j.getString("position")
        NavFrame(j.getBoolean("active"),j.getString("message").take(100),j.getString("road").take(60),
            j.getInt("icon"),j.getInt("meters"),j.getInt("left"),j.getInt("seconds"),
            if(age in 0..15_000) (now-age).coerceAtLeast(1) else 0,
            (0 until points.length()).map { requireNotNull(Coordinate.read(points.getString(it))) },
            if(position.isEmpty()) null else requireNotNull(Coordinate.read(position)),j.getString("id"),sequence,j.getString("alert").take(80))
    }.getOrNull()
}

/** Only the explicitly selected session can update a display; late packets cannot revive it. */
class NavSession(val id: String, startedAt: Long) {
    var lastReceived=startedAt; private set
    private var sequence=0L
    private var ended=false
    fun accept(frame: NavFrame, now: Long): Boolean {
        if(ended || frame.journeyId!=id || frame.sequence<=sequence) return false
        sequence=frame.sequence;lastReceived=now;ended=!frame.active
        return true
    }
    fun stop() { ended=true }
    fun expired(now: Long)=now-lastReceived>=NavWire.WATCH_TIMEOUT
}

/** The phone allows a bounded confirmation window, then requires periodic watch heartbeats. */
class WatchLease(private val startedAt: Long) {
    private var lastAck: Long?=null
    fun acknowledge(now: Long) {lastAck=now}
    fun expired(now: Long)=lastAck?.let {now-it>=NavWire.PHONE_TIMEOUT}
        ?: (now-startedAt>=NavWire.START_TIMEOUT)
}
