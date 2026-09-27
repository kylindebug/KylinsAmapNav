package com.kylins.amapnav.core

import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import org.json.JSONObject

data class Coordinate(val longitude: Double, val latitude: Double) {
    override fun toString() = "$longitude,$latitude"
    companion object {
        fun read(text: String): Coordinate? {
            val values=text.trim().split(','); if(values.size!=2) return null
            val x=values[0].toDoubleOrNull() ?: return null
            val y=values[1].toDoubleOrNull() ?: return null
            return if(x.isFinite() && y.isFinite() && x in -180.0..180.0 && y in -90.0..90.0) Coordinate(x,y) else null
        }
    }
}
data class Journey(val title: String, val destination: Coordinate, val origin: Coordinate?=null,
                   val mode: String="walk", val id: String=UUID.randomUUID().toString(), val created: Long=System.currentTimeMillis()) {
    fun encode(): String = JSONObject().put("version",1).put("title",title.take(60)).put("destination",destination.toString())
        .put("origin",origin?.toString() ?: "").put("mode",mode).put("id",id).put("created",created).toString()
    fun fresh(now: Long=System.currentTimeMillis())=created>0 && now-created in -60_000..600_000
    companion object {
        fun decode(text: String): Journey? = try {
            require(text.toByteArray().size <= 4096)
            val j=JSONObject(text); require(j.getInt("version")==1)
            val mode=j.getString("mode"); require(mode in listOf("walk","ride"))
            val id=j.getString("id"); require(UUID.fromString(id).toString()==id)
            val origin=j.getString("origin"); val start=if(origin.isBlank()) null else requireNotNull(Coordinate.read(origin))
            Journey(j.getString("title").take(60),requireNotNull(Coordinate.read(j.getString("destination"))),start,mode,id,j.getLong("created")).takeIf { it.fresh() }
        } catch (_: Exception) { null }
    }
}

/** Local parsing only. r= is a measured AMap walking-share format, not a guaranteed API. */
object ShareRoute {
    val hosts=setOf("surl.amap.com","wb.amap.com","uri.amap.com","www.amap.com","amap.com")
    fun urlIn(text: String): String? = Regex("https://[^\\s<>\"，。]+",RegexOption.IGNORE_CASE).find(text)?.value
    fun allowed(uri: URI)=uri.scheme.equals("https",true) && uri.host?.lowercase() in hosts && uri.userInfo==null && uri.port in listOf(-1,443)
    private fun placeName(raw:String):String {
        var name=raw
        repeat(2) { if(Regex("%[0-9a-fA-F]{2}").containsMatchIn(name))
            name=runCatching {URLDecoder.decode(name.replace("+","%2B"),"UTF-8")}.getOrDefault(name) }
        return name.filterNot {it.isISOControl()}.take(60).ifBlank {"高德目的地"}
    }
    fun parse(text: String): Journey? {
        if(text.length>8192) return null
        Coordinate.read(text)?.let { return Journey("目的地",it) }
        return try {
            val uri=URI(urlIn(text) ?: text.trim()); require(allowed(uri))
            val args=uri.rawQuery.orEmpty().split('&').mapNotNull {
                val p=it.split('=',limit=2)
                if(p.size==2) URLDecoder.decode(p[0],"UTF-8") to URLDecoder.decode(p[1],"UTF-8") else null
            }.toMap()
            if(args.containsKey("r")) {
                val p=args.getValue("r").split(','); require(p.size>=8 && p[7]=="2")
                val start=Coordinate.read("${p[1]},${p[0]}")
                Journey(placeName(p[5]),requireNotNull(Coordinate.read("${p[4]},${p[3]}")),start,"walk")
            } else if(args.containsKey("to")) {
                require(args["mode"] in listOf("walk","ride")); require(args["via"].isNullOrBlank())
                val to=args.getValue("to").split(','); require(to.size>=2)
                val from=args["from"]?.split(',')
                Journey(placeName(to.getOrNull(2) ?: "高德目的地"),requireNotNull(Coordinate.read("${to[0]},${to[1]}")),
                    if(from!=null && from.size>=2) Coordinate.read("${from[0]},${from[1]}") else null,args.getValue("mode"))
            } else if(args.containsKey("position")) {
                Journey(placeName(args["name"] ?: "高德地点"),requireNotNull(Coordinate.read(args.getValue("position"))))
            } else null
        } catch (_: Exception) { null }
    }
}

object GuidanceRules {
    fun outdated(now: Long, stamp: Long)=stamp<=0 || now-stamp>15_000
    fun meters(value: Int)=if(value<1000) "$value" else String.format(java.util.Locale.US,"%.1f",value/1000.0)
    fun turn(code: Int)=when(code) {
        2->"左转";3->"右转";4->"向左前方";5->"向右前方";6->"向左后方";7->"向右后方";8,19->"掉头";9,20->"直行";15->"到达"
        11,17,21,22,23,24,25,26,27,28->"进入环岛";12,18->"离开环岛";29->"人行横道";30->"人行天桥";31->"地下通道"
        34,51->"走楼梯";35,50->"乘电梯";52->"乘扶梯";45->"过桥";65->"向左汇入";66->"向右汇入";else->"沿路线前行"
    }
}
