package com.kylins.amapnav

import android.content.Context
import android.hardware.*
import android.os.SystemClock
import android.view.Surface
import com.kylins.amapnav.core.CompassMath
import kotlin.math.*

class WatchCompass(context:Context,private val rotation:()->Int,private val changed:()->Unit):SensorEventListener {
    private val sensors=context.getSystemService(SensorManager::class.java)
    private val sensor=sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val matrix=FloatArray(9)
    private var running=false
    private var last=0L
    private var sector:Int?=null
    private var east=0.0
    private var north=0.0
    private var filtered=false
    private var status=if(sensor==null) "无方向传感器" else "方向待更新"
    fun text():String=when {
        !running->if(sensor==null) "无方向传感器" else "方向已暂停"
        SystemClock.elapsedRealtime()-last>5000->"方向待更新"
        else->status
    }
    fun start() {
        if(running || sensor==null) return
        last=0;filtered=false;sector=null;status="方向待更新"
        running=sensors.registerListener(this,sensor,200_000)
        if(!running) status="方向传感器不可用"
    }
    fun stop() { if(running) sensors.unregisterListener(this);running=false;last=0;filtered=false }
    private fun report(value:String) { val old=status;status=value;last=SystemClock.elapsedRealtime();if(old!=value) changed() }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int) {
        if(accuracy<=SensorManager.SENSOR_STATUS_ACCURACY_LOW) {filtered=false;sector=null;report("方向待校准")}
    }
    override fun onSensorChanged(event:SensorEvent) {
        if(event.accuracy<=SensorManager.SENSOR_STATUS_ACCURACY_LOW) {report("方向待校准");return}
        if(event.values.any {!it.isFinite()}) {report("方向待更新");return}
        SensorManager.getRotationMatrixFromVector(matrix,event.values)
        val degrees=when(rotation()) {Surface.ROTATION_90->90;Surface.ROTATION_180->180;Surface.ROTATION_270->270;else->0}
        val bearing=CompassMath.topHeading(matrix,degrees) ?: run {filtered=false;report("请放平手表");return}
        val radians=Math.toRadians(bearing)
        if(!filtered) {east=sin(radians);north=cos(radians);filtered=true}
        else {east=.65*east+.35*sin(radians);north=.65*north+.35*cos(radians)}
        sector=CompassMath.stableSector(Math.toDegrees(atan2(east,north)),sector)
        report("朝向 ${CompassMath.name(sector!!)}")
    }
}
