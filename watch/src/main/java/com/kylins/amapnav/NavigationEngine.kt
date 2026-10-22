package com.kylins.amapnav

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.android.gms.wearable.*
import com.kylins.amapnav.core.*

/** A bounded display/transport service. No location permission, GPS or AMap SDK on the watch. */
class NavigationEngine : Service(), MessageClient.OnMessageReceivedListener {
    companion object {
        private val observers=mutableSetOf<()->Unit>()
        private var running: NavigationEngine?=null
        @Volatile var frame=NavFrame()
            private set(value) {field=value;observers.toList().forEach {it()}}
        fun observe(observer:()->Unit) {observers.add(observer)}
        fun unobserve(observer:()->Unit) {observers.remove(observer)}
        fun stop() {running?.end("导航已结束",notifyPhone=true)}
        fun terminal(raw: String) {
            val incoming=NavWire.decode(raw,SystemClock.elapsedRealtime()) ?: return
            if(!incoming.active && incoming.journeyId==frame.journeyId) running?.end(incoming.message,false)
        }
    }
    private val main=Handler(Looper.getMainLooper())
    private var session: NavSession?=null
    private var node=""
    private var closing=false
    private var lastAlert=""
    private val heartbeat=object:Runnable {override fun run() {
        if(closing) return
        val current=session ?: return
        if(current.expired(SystemClock.elapsedRealtime())) {
            end("手机连接中断，导航已停止",true);return
        }
        PairedLink.message(this@NavigationEngine,node,NavWire.HEARTBEAT,current.id)
        main.postDelayed(this,10_000)
    }}
    override fun onCreate() {super.onCreate();running=this;Wearable.getMessageClient(this).addListener(this)}
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action=="stop") {end("导航已结束",true);return START_NOT_STICKY}
        if(frame.active || closing) return START_NOT_STICKY
        val request=Journey.decode(intent?.getStringExtra("journey").orEmpty())
        node=Inbox.phone(this,request?.id.orEmpty())
        if(request==null || node.isBlank() || Inbox.ended(this,request.id)) {end("请从手机重新开始导航",false);return START_NOT_STICKY}
        session=NavSession(request.id,SystemClock.elapsedRealtime())
        try {
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("journey","手机导航指引",NotificationManager.IMPORTANCE_LOW))
            val touch=PendingIntent.getActivity(this,10,Intent(this,NavigationScreen::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop=PendingIntent.getService(this,11,Intent(this,NavigationEngine::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
            val builder=NotificationCompat.Builder(this,"journey").setSmallIcon(R.drawable.ic_ongoing)
                .setContentTitle("前往 ${request.title}").setContentText("手机导航中 · 点击返回")
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION).setOngoing(true).setContentIntent(touch).addAction(0,"结束导航",stop)
            OngoingActivity.Builder(this,51,builder).setStaticIcon(R.drawable.ic_ongoing).setTouchIntent(touch)
                .setStatus(Status.Builder().addTemplate("手机导航中").build()).build().apply(this)
            startForeground(51,builder.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            frame=NavFrame(active=true,message="等待手机导航指引",journeyId=request.id)
            main.post(heartbeat)
        } catch(_: Exception) {end("无法显示导航，请从手机重试",true)}
        return START_NOT_STICKY
    }
    override fun onMessageReceived(event: MessageEvent) {
        if(event.path!=NavWire.FRAME || event.sourceNodeId!=node || event.data.size>24_000) return
        val incoming=NavWire.decode(event.data.toString(Charsets.UTF_8),SystemClock.elapsedRealtime()) ?: return
        main.post {
            if(closing || session?.accept(incoming,SystemClock.elapsedRealtime())!=true) return@post
            if(!incoming.active) {end(incoming.message,false);return@post}
            frame=incoming
            if(incoming.alert.isNotEmpty() && incoming.alert!=lastAlert) {
                lastAlert=incoming.alert
                if(!GuidanceRules.outdated(SystemClock.elapsedRealtime(),incoming.stamp))
                    getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(160,VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }
    private fun end(reason: String, notifyPhone: Boolean) {
        if(closing) return
        closing=true;main.removeCallbacksAndMessages(null)
        session?.let {
            it.stop();Inbox.retire(this,it.id)
            if(notifyPhone) {
                // Durable stop survives a broken link; the phone also has its own heartbeat timeout.
                runCatching {PairedLink.message(this,node,NavWire.STOP,it.id)}
                runCatching {PairedLink.publish(this,NavWire.STOP,it.id)}
            }
        }
        getSystemService(Vibrator::class.java).cancel()
        frame=NavFrame(message=reason)
        try {runCatching {Journal.note(this,"watch","end",reason,session?.id.orEmpty())}}
        finally {stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    }
    override fun onDestroy() {
        if(!closing) end("手表导航已结束",true)
        Wearable.getMessageClient(this).removeListener(this)
        main.removeCallbacksAndMessages(null)
        if(running===this) running=null
        super.onDestroy()
    }
}
