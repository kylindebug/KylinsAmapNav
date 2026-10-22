package com.kylins.amapnav

import android.app.AlertDialog
import android.content.Intent
import android.os.*
import android.view.WindowManager
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import androidx.wear.ambient.AmbientLifecycleObserver
import com.kylins.amapnav.core.*
import kotlin.math.min

/** Preview has its own Activity, so no real-navigation intent can reuse preview state. */
class PreviewScreen:NavigationScreen()

open class NavigationScreen:FragmentActivity() {
    private lateinit var panel:GuidancePanel
    private lateinit var compass:WatchCompass
    private lateinit var exitButton:Button
    private val timer=Handler(Looper.getMainLooper())
    private val preview get()=this is PreviewScreen
    private var ambient=false
    private var visible=false
    private var resumed=false
    private var mode=2 // 0 complete, 1 complete + keep screen on, 2 economical (default)
    private val prefs by lazy {getSharedPreferences("display",MODE_PRIVATE)}
    private var signature=""
    private fun summary(f:NavFrame)="${f.active}|${f.message}|${f.icon}|${f.meters}|${f.road}|${f.left}|${f.seconds}"
    private val navigationChanged:()->Unit=changed@{
        if(!preview && !NavigationEngine.frame.active) {
            timer.removeCallbacksAndMessages(null);applyDisplayPolicy()
            android.widget.Toast.makeText(this,NavigationEngine.frame.message,android.widget.Toast.LENGTH_LONG).show()
            finish()
            return@changed
        }
        val now=SystemClock.elapsedRealtime()
        val freshnessChanged=::panel.isInitialized && GuidanceRules.outdated(now,panel.frame.stamp)!=GuidanceRules.outdated(now,NavigationEngine.frame.stamp)
        if(mode!=2 || summary(NavigationEngine.frame)!=signature || freshnessChanged) refresh()
    }
    private fun refresh() {if(visible && (resumed || ambient)) {timer.removeCallbacks(pulse);timer.post(pulse)}}
    private val pulse=object:Runnable {override fun run() {
        if(!visible || (!resumed && !ambient)) return
        val frame=if(preview) previewFrame() else NavigationEngine.frame
        signature=summary(frame)
        panel.frame=frame;panel.ambient=ambient;panel.powerSaving=mode==2
        panel.heading=if(mode==2) "省电模式" else if(preview) "朝向 北 · 示例" else compass.text()
        panel.invalidate();applyDisplayPolicy()
        var delay=if(ambient) {if(mode==2) 60000L else 5000L} else 1000L
        val untilStale=frame.stamp+15001-SystemClock.elapsedRealtime()
        if(frame.stamp>0 && untilStale>0) delay=min(delay,untilStale)
        timer.postDelayed(this,delay)
    }}
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        if(!preview && !NavigationEngine.frame.active && intent.getStringExtra("journey")==null) {finish();return}
        mode=if(preview) 0 else prefs.getInt("mode",2).coerceIn(0,2)
        panel=GuidancePanel(this).apply {preview=this@NavigationScreen.preview;setOnLongClickListener {options();true}}
        compass=WatchCompass(this,{display?.rotation ?: 0},{refresh()})
        val column=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL
            setPadding(0,0,0,(12*resources.displayMetrics.density).toInt())
            addView(panel,LinearLayout.LayoutParams(-1,0,1f))
            exitButton=Button(this@NavigationScreen).apply {
                text=if(preview) "退出预览" else "结束导航";textSize=12f;isAllCaps=false
                setTextColor(android.graphics.Color.LTGRAY)
                background=android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.rgb(26,33,29));cornerRadius=24*resources.displayMetrics.density
                }
                setOnClickListener {endNavigation()}
            }
            addView(exitButton,LinearLayout.LayoutParams((120*resources.displayMetrics.density).toInt(),(36*resources.displayMetrics.density).toInt()))
        }
        setContentView(column)
        lifecycle.addObserver(AmbientLifecycleObserver(this,ambientCallback))
        startIfRequested(intent)
    }
    override fun onNewIntent(value:Intent) {super.onNewIntent(value);setIntent(value);startIfRequested(value);refresh()}
    private fun startIfRequested(value:Intent) {
        if(!preview && !NavigationEngine.frame.active && value.getStringExtra("journey")!=null)
            startForegroundService(Intent(this,NavigationEngine::class.java).putExtra("journey",value.getStringExtra("journey")))
        value.removeExtra("journey")
    }
    private fun endNavigation() {
        if(!preview) NavigationEngine.stop()
        timer.removeCallbacksAndMessages(null)
        if(::compass.isInitialized) compass.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        finish()
    }
    @Deprecated("Legacy back dispatch on this Activity")
    override fun onBackPressed() {
        if(preview || !NavigationEngine.frame.active) {endNavigation();return}
        AlertDialog.Builder(this).setTitle("结束导航？").setMessage("同时停止手机导航与手表指引。")
            .setPositiveButton("结束导航") {_,_->endNavigation()}
            .setNegativeButton("继续导航",null).setNeutralButton("返回后台") {_,_->finish()}.show()
    }
    private fun applyDisplayPolicy() {
        if(::exitButton.isInitialized) exitButton.visibility=if(ambient) android.view.View.INVISIBLE else android.view.View.VISIBLE
        if(!preview && NavigationEngine.frame.active && mode==1) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(::compass.isInitialized) {
            if(!preview && visible && resumed && !ambient && mode!=2 && NavigationEngine.frame.active) compass.start()
            else compass.stop()
        }
    }
    private fun options() {
        val names=arrayOf("完整显示（含息屏路线）","常亮完整显示","省电模式（关闭路线与朝向）",if(preview) "退出预览" else "结束导航")
        for(i in 0..2) if(i==mode) names[i]="✓ ${names[i]}"
        AlertDialog.Builder(this).setTitle("显示与导航").setItems(names) {_,which->
            if(which<=2) {mode=which;prefs.edit().putInt("mode",mode).apply();applyDisplayPolicy();refresh()
                Journal.note(this,"watch","display_mode",arrayOf("完整显示","常亮完整显示","省电显示")[mode])}
            else endNavigation()
        }.setNeutralButton("方向说明") {_,_->AlertDialog.Builder(this).setMessage("朝向为手表屏幕顶端相对磁北的方向，不是行进方向。请平放手表并远离磁铁；方向待校准时缓慢转动手腕。\n导航数据由高德提供。").setPositiveButton("知道了",null).show()}.show()
    }
    private val ambientCallback=object:AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails:AmbientLifecycleObserver.AmbientDetails) {
            ambient=true;Journal.note(this@NavigationScreen,"watch","ambient",if(mode==2) "省电息屏界面" else "完整息屏界面");applyDisplayPolicy();refresh()
        }
        override fun onExitAmbient() {ambient=false;Journal.note(this@NavigationScreen,"watch","interactive","返回交互界面");applyDisplayPolicy();refresh()}
        override fun onUpdateAmbient() {refresh()}
    }
    override fun onStart() {super.onStart();visible=true;NavigationEngine.observe(navigationChanged)}
    override fun onResume() {super.onResume();resumed=true;if(::panel.isInitialized) {applyDisplayPolicy();refresh()}}
    override fun onPause() {resumed=false;super.onPause();if(!ambient) {timer.removeCallbacks(pulse);if(::compass.isInitialized) compass.stop()}}
    override fun onStop() {visible=false;NavigationEngine.unobserve(navigationChanged);timer.removeCallbacks(pulse);if(::compass.isInitialized) compass.stop();super.onStop()}
    override fun onDestroy() {timer.removeCallbacksAndMessages(null);NavigationEngine.unobserve(navigationChanged);if(::compass.isInitialized) compass.stop();window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);super.onDestroy()}
    private fun previewFrame()=NavFrame(true,"左转","华山路",2,56,439,352,SystemClock.elapsedRealtime(),
        DemoRoute.previewPoints,DemoRoute.previewPoints.first())
}
