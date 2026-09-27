package com.kylins.amapnav.core

import android.content.Context
import android.util.AtomicFile
import com.google.android.gms.wearable.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object Journal {
    private fun storage(c: Context)=AtomicFile(File(c.filesDir,"journeys.jsonl"))
    @Synchronized fun entries(c: Context): List<JSONObject> = try {
        storage(c).openRead().bufferedReader(Charsets.UTF_8).useLines { lines -> lines.mapNotNull { try { JSONObject(it) } catch (_: Exception) { null } }.toList() }
    } catch (_: java.io.FileNotFoundException) { emptyList() }
    @Synchronized fun add(c: Context, incoming: List<JSONObject>) {
        val items=(entries(c)+incoming).distinctBy { it.optString("eventId") }.takeLast(2000)
        val file=storage(c); val stream=file.startWrite()
        try { stream.write(items.joinToString("\n",postfix="\n").toByteArray()); file.finishWrite(stream) }
        catch(e: Exception) { file.failWrite(stream); throw e }
    }
    fun note(c: Context, source: String, kind: String, message: String, journey: String="") {
        add(c,listOf(JSONObject().put("eventId",UUID.randomUUID().toString()).put("time",System.currentTimeMillis())
            .put("source",source).put("journey",journey.take(40)).put("kind",kind.take(32)).put("message",message.take(160))))
        if(source=="watch") {
            val batch=JSONArray(); entries(c).takeLast(180).forEach { batch.put(it) }
            while(batch.length()>1 && batch.toString().toByteArray(Charsets.UTF_8).size>80000) batch.remove(0)
            PairedLink.publish(c,PairedLink.JOURNAL,batch.toString())
        }
    }
    fun absorb(c: Context, payload: String) {
        if(payload.length>90000) return
        try {
            val list=JSONArray(payload); if(list.length()>180) return
            add(c,(0 until list.length()).map { list.getJSONObject(it) }.filter {
                it.optString("eventId").length==36 && it.optString("source")=="watch" && it.toString().length<700
            })
        } catch (_: org.json.JSONException) { note(c,"phone","invalid_journal","拒绝错误日志包") }
    }
}

object PairedLink {
    const val ROUTE="/kylins/v1/route"
    const val JOURNAL="/kylins/v1/journal"
    fun publish(c: Context, path: String, value: String, result: (Boolean)->Unit={}) {
        val data=PutDataMapRequest.create(path)
        data.dataMap.putString("body",value); data.dataMap.putString("revision",UUID.randomUUID().toString())
        Wearable.getDataClient(c).putDataItem(data.asPutDataRequest().setUrgent())
            .addOnSuccessListener { result(true) }.addOnFailureListener { result(false) }
    }
    fun body(item: DataItem): String? = try {
        if((item.data?.size ?: 0)>100000) null else DataMapItem.fromDataItem(item).dataMap.getString("body")
    } catch (_: Exception) { null }
    fun fetch(c: Context, path: String, action: (String)->Unit) {
        Wearable.getDataClient(c).dataItems.addOnSuccessListener { buffer ->
            try { for(item in buffer) if(item.uri.path==path) body(item)?.let(action) } finally { buffer.release() }
        }
    }
}
