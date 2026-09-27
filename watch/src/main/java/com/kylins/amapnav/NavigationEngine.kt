package com.kylins.amapnav

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.amap.api.maps.MapsInitializer
import com.amap.api.navi.*
import com.amap.api.navi.model.*
import com.kylins.amapnav.core.*
import java.lang.reflect.Proxy

data class NavFrame(val active: Boolean=false,val message: String="准备出发",val road: String="",val icon: Int=9,
    val meters: Int=-1,val left: Int=0,val seconds: Int=0,val stamp: Long=0,
    val route: List<Coordinate> = emptyList(),val position: Coordinate?=null)

class NavigationEngine : Service() {
    companion object {
        private val observers=mutableSetOf<()->Unit>()
        @Volatile var frame=NavFrame()
            private set(value) {field=value;observers.toList().forEach {it()}}
        fun observe(observer:()->Unit) {observers.add(observer)}
        fun unobserve(observer:()->Unit) {observers.remove(observer)}
    }
    private val main=Handler(Looper.getMainLooper())
    private var sdk: AMapNavi?=null
    private var request: Journey?=null
    private var planned=false
    private var started=false
    private var closing=false
    private var lastEmit=0L
    private var lastStep=-1
    private val vibrations=mutableSetOf<String>()
    private val timeout=Runnable { end("定位或算路超时，请到开阔处重试") }
    private fun log(kind: String, message: String) = Journal.note(this,"watch",kind,message,request?.id.orEmpty())
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
        request=Journey.decode(intent?.getStringExtra("journey").orEmpty())
        if(!BuildConfig.HAS_KEY || request==null || intent?.getBooleanExtra("consent",false)!=true ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            end("缺少 Key、路线或定位权限"); return START_NOT_STICKY
        }
        try {
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("journey","正在导航",NotificationManager.IMPORTANCE_LOW))
            val touch=PendingIntent.getActivity(this,10,Intent(this,NavigationScreen::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop=PendingIntent.getService(this,11,Intent(this,NavigationEngine::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
            val builder=NotificationCompat.Builder(this,"journey").setSmallIcon(com.kylins.amapnav.R.drawable.ic_ongoing)
                .setContentTitle("前往 ${request!!.title}").setContentText("点此返回导航").setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setOngoing(true).setContentIntent(touch).addAction(0,"结束",stop)
            OngoingActivity.Builder(this,51,builder).setStaticIcon(com.kylins.amapnav.R.drawable.ic_ongoing)
                .setTouchIntent(touch).setStatus(Status.Builder().addTemplate("导航进行中").build()).build().apply(this)
            startForeground(51,builder.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            frame=NavFrame(active=true,message="正在定位")
            MapsInitializer.updatePrivacyShow(this,true,true); MapsInitializer.updatePrivacyAgree(this,true)
            NaviSetting.updatePrivacyShow(this,true,true); NaviSetting.updatePrivacyAgree(this,true)
            sdk=AMapNavi.getInstance(applicationContext)
            // AMap defaults to a bright screen wake lock; Wear OS owns display policy here.
            sdk!!.naviSetting.setScreenAlwaysBright(false)
            sdk!!.addAMapNaviListener(callbacks); sdk!!.setUseInnerVoice(false,false)
            if(!sdk!!.startGPS()) { end("无法启动手表定位"); return START_NOT_STICKY }
            main.postDelayed(timeout,90_000)
            log("start","${request!!.mode};原生持续导航已启用")
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
                val key="${i.curStep}:${i.iconType}"
                if(i.iconType in 2..8 && i.curStepRetainDistance in 0..60 && vibrations.add(key)) {
                    getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(160,VibrationEffect.DEFAULT_AMPLITUDE))
                    log("turn_alert","转弯震动 step=${i.curStep}")
                }
            }
            "onGpsSignalWeak" -> if(args.firstOrNull()==true) { frame=frame.copy(stamp=0,message="定位信号弱"); log("weak_signal","暂不显示旧指引") }
            "onReCalculateRouteForYaw" -> { vibrations.clear(); frame=frame.copy(stamp=0,message="正在重新规划"); log("reroute","偏航重新规划") }
            "onArriveDestination" -> end("已到达目的地")
        }
    }
    private fun end(reason: String) {
        if(closing) return
        closing=true; frame=frame.copy(active=false,message=reason,stamp=0)
        log("end",reason); main.removeCallbacksAndMessages(null); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        if(!closing) { frame=frame.copy(active=false,message="导航已结束",stamp=0); log("end","用户结束") }
        closing=true; main.removeCallbacksAndMessages(null)
        sdk?.removeAMapNaviListener(callbacks); sdk?.stopNavi(); sdk?.stopGPS(); AMapNavi.destroy(); super.onDestroy()
    }
}
