package com.kylins.amapnav.phone

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import com.amap.api.maps.MapsInitializer
import com.amap.api.navi.*
import com.amap.api.navi.model.*
import com.kylins.amapnav.core.*
import java.lang.reflect.Proxy
import com.google.android.gms.wearable.*
import kotlin.math.pow

class PhoneNavigationEngine : Service(), MessageClient.OnMessageReceivedListener {
    companion object {
        @Volatile var frame=NavFrame(); private set
        @Volatile private var running: PhoneNavigationEngine?=null
        fun stop(id: String?=null) {
            val service=running ?: return
            service.main.post { if(id==null || service.request?.id==id) service.end("导航已结束") }
        }

    }
    private val main=Handler(Looper.getMainLooper())
    private var sdk: AMapNavi?=null
    private var request: Journey?=null
    private var planned=false
    private var started=false
    private var closing=false
    private var lastEmit=0L
    private var lastStep=-1
    private var routeVersion=0
    private val vibrations=mutableSetOf<String>()
    private var node=""
    private var sequence=0L
    private var lease: WatchLease?=null
    private val heartbeat=object:Runnable {override fun run() {
        if(closing) return
        val now=SystemClock.elapsedRealtime()
        if(lease?.expired(now)==true) {
            end("手表未确认或连接已中断，导航已停止");return
        }
        publishFrame();main.postDelayed(this,3000)
    }}
    override fun onCreate() {super.onCreate();running=this;Wearable.getMessageClient(this).addListener(this)}
    override fun onMessageReceived(event: MessageEvent) {
        if(event.sourceNodeId!=node || event.data.size>100) return
        val id=event.data.toString(Charsets.UTF_8)
        main.post {
            if(closing || id!=request?.id) return@post
            when(event.path) {
                NavWire.HEARTBEAT -> lease?.acknowledge(SystemClock.elapsedRealtime())
                NavWire.STOP -> end("手表已结束导航")
            }
        }
    }
    private fun publishFrame(terminal: Boolean=false) {
        val current=frame.position
        val near=if(current==null) 0 else frame.route.indices.minByOrNull {
            val point=frame.route[it]
            (point.longitude-current.longitude).pow(2)+(point.latitude-current.latitude).pow(2)
        } ?: 0
        val outgoing=frame.copy(sequence=++sequence,route=frame.route.drop((near-2).coerceAtLeast(0)).take(80))
        val body=NavWire.encode(outgoing,SystemClock.elapsedRealtime())
        if(node.isNotEmpty()) PairedLink.message(this,node,NavWire.FRAME,body)
        if(terminal) PairedLink.publish(this,NavWire.TERMINAL,body)
    }
    private val timeout=Runnable { end("定位或算路超时，请到开阔处重试") }
    private fun log(kind: String, message: String) = Journal.note(this,"phone",kind,message,request?.id.orEmpty())
    private val callbacks: AMapNaviListener = Proxy.newProxyInstance(AMapNaviListener::class.java.classLoader,arrayOf(AMapNaviListener::class.java)) { proxy,method,args ->
        when(method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy===args?.firstOrNull()
            "toString" -> "KylinsNavigationCallbacks"
            else -> { main.post { if(!closing) event(method.name,args ?: emptyArray()) }; null }
        }
    } as AMapNaviListener
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action=="stop") { end("导航已结束"); return START_NOT_STICKY }
        if(frame.active) return START_NOT_STICKY
        if(closing) return START_NOT_STICKY
        node=intent?.getStringExtra("node").orEmpty()
        request=Journey.decode(intent?.getStringExtra("journey").orEmpty())
        frame=NavFrame(journeyId=request?.id.orEmpty())
        if(!BuildConfig.HAS_KEY || request==null || node.isBlank() || intent?.getBooleanExtra("consent",false)!=true ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            end("缺少 Key、路线或定位权限"); return START_NOT_STICKY
        }
        try {
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("journey","正在导航",NotificationManager.IMPORTANCE_LOW))
            val touch=PendingIntent.getActivity(this,10,Intent(this,PhoneHome::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop=PendingIntent.getService(this,11,Intent(this,PhoneNavigationEngine::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
            val builder=NotificationCompat.Builder(this,"journey").setSmallIcon(com.kylins.amapnav.phone.R.drawable.ic_ongoing)
                .setContentTitle("前往 ${request!!.title}").setContentText("点此返回导航").setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setOngoing(true).setContentIntent(touch).addAction(0,"结束",stop)
            if(Build.VERSION.SDK_INT>=29) startForeground(51,builder.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(51,builder.build())
            frame=NavFrame(active=true,message="手机正在定位",journeyId=request!!.id)
            lease=WatchLease(SystemClock.elapsedRealtime());main.post(heartbeat)
            MapsInitializer.updatePrivacyShow(this,true,true); MapsInitializer.updatePrivacyAgree(this,true)
            NaviSetting.updatePrivacyShow(this,true,true); NaviSetting.updatePrivacyAgree(this,true)
            sdk=AMapNavi.getInstance(applicationContext)
            // Navigation must not keep the phone screen lit.
            sdk!!.naviSetting.setScreenAlwaysBright(false)
            sdk!!.addAMapNaviListener(callbacks); sdk!!.setUseInnerVoice(false,false)
            if(!sdk!!.startGPS()) { end("无法启动手机定位"); return START_NOT_STICKY }
            main.postDelayed(timeout,90_000)
            log("start","${request!!.mode};手机导航已启用")
        } catch(e: Exception) { end("导航初始化失败 ${e.javaClass.simpleName}") }
        return START_NOT_STICKY
    }
    private fun event(name: String, args: Array<out Any?>) {
        when(name) {
            "onInitNaviFailure" -> end("导航引擎初始化失败")
            "onLocationChange" -> {
                val loc=args.firstOrNull() as? AMapNaviLocation ?: return
                val p=loc.coord ?: return
                val point=Coordinate.read("${p.longitude},${p.latitude}") ?: return
                if(point.longitude==0.0 && point.latitude==0.0) return
                frame=frame.copy(position=point)
                if(!planned && loc.accuracy.isFinite() && loc.accuracy in 0f..100f) {
                    planned=true; frame=frame.copy(message="正在规划路线")
                    log("fix","定位精度 ${loc.accuracy.toInt()} 米")
                    val j=request ?: return; val dest=NaviLatLng(j.destination.latitude,j.destination.longitude)
                    val from=j.origin?.let { NaviLatLng(it.latitude,it.longitude) }
                    val ok=if(j.mode=="walk") { if(from==null) sdk!!.calculateWalkRoute(dest) else sdk!!.calculateWalkRoute(from,dest) }
                        else { if(from==null) sdk!!.calculateRideRoute(dest) else sdk!!.calculateRideRoute(from,dest) }
                    if(!ok) end("路线请求未被接受")
                }
            }
            "onCalculateRouteSuccess" -> {
                routeVersion++
                val points=sdk?.naviPath?.coordList.orEmpty().map { Coordinate(it.longitude,it.latitude) }
                frame=frame.copy(route=points,message="等待导航指引")
                main.removeCallbacks(timeout)
                if(!started) { started=true; log("route_ready","路线已规划，${points.size} 个路线点")
                    if(sdk?.startNavi(1)!=true) end("无法开启实时导航") }
            }
            "onCalculateRouteFailure" -> {
                val code=when(val x=args.firstOrNull()) { is Int->x; is AMapCalcRouteResult->x.errorCode;else->-1 }
                end("算路失败（$code）")
            }
            "onNaviInfoUpdate" -> {
                val i=args.firstOrNull() as? NaviInfo ?: return
                val now=SystemClock.elapsedRealtime()
                frame=frame.copy(message=GuidanceRules.turn(i.iconType),road=i.nextRoadName.orEmpty(),icon=i.iconType,
                    meters=i.curStepRetainDistance,left=i.pathRetainDistance,seconds=i.pathRetainTime,stamp=now)
                if(i.curStep!=lastStep || now-lastEmit>=10000) { lastStep=i.curStep; lastEmit=now
                    log("guidance","step=${i.curStep} turn=${i.iconType} distance=${i.curStepRetainDistance} road=${i.nextRoadName.orEmpty().take(50)}") }
                val key="$routeVersion:${i.curStep}:${i.iconType}"
                if(i.iconType !in 2..8 || i.curStepRetainDistance !in 0..60 || frame.alert!=key) frame=frame.copy(alert="")
                if(i.iconType in 2..8 && i.curStepRetainDistance in 0..60 && vibrations.add(key)) {
                    frame=frame.copy(alert=key)
                    publishFrame()
                    log("turn_alert","已发送手表转弯提醒 step=${i.curStep}")
                }
            }
            "onGpsSignalWeak" -> if(args.firstOrNull()==true) { frame=frame.copy(stamp=0,message="定位信号弱"); log("weak_signal","暂不显示旧指引") }
            "onReCalculateRouteForYaw" -> { vibrations.clear(); frame=frame.copy(stamp=0,alert="",message="正在重新规划"); log("reroute","偏航重新规划") }
            "onArriveDestination" -> end("已到达目的地")
        }
    }
    private fun releaseSdk() {
        val engine=sdk ?: return
        sdk=null
        // Each cleanup runs even if a preceding SDK call fails.
        runCatching {engine.removeAMapNaviListener(callbacks)}
        runCatching {engine.stopNavi()}
        runCatching {engine.stopGPS()}
        runCatching {AMapNavi.destroy()}
    }
    private fun end(reason: String) {
        if(closing) return
        closing=true;main.removeCallbacksAndMessages(null)
        releaseSdk()
        frame=frame.copy(active=false,message=reason,stamp=0,alert="",route=emptyList(),position=null)
        try {
            if(request!=null) runCatching {publishFrame(terminal=true)}
            runCatching {log("end",reason)}
        } finally {stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    }
    override fun onDestroy() {
        if(!closing) end("手机导航服务已停止")
        Wearable.getMessageClient(this).removeListener(this)
        main.removeCallbacksAndMessages(null);releaseSdk()
        if(running===this) running=null
        super.onDestroy()
    }
}
