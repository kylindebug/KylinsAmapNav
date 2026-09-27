package com.kylins.amapnav

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.view.WindowManager
import android.graphics.drawable.Icon
import android.view.Gravity
import android.widget.*
import com.google.android.gms.wearable.*
import com.kylins.amapnav.core.*

object Inbox {
    private fun store(c: Context)=c.getSharedPreferences("inbox",Context.MODE_PRIVATE)
    fun pending(c: Context)=Journey.decode(store(c).getString("pending","").orEmpty())
    @Synchronized fun accept(c: Context, raw: String) {
        val route=Journey.decode(raw) ?: return
        if(store(c).getString("last","")==route.id) return
        if(!store(c).edit().putString("last",route.id).putString("pending",raw).commit()) return
        Journal.note(c,"watch","received","手机路线已收到：${route.title}",route.id)
        c.sendBroadcast(Intent("com.kylins.amapnav.INBOX_UPDATED").setPackage(c.packageName))
        val manager=c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("inbox","收到路线",NotificationManager.IMPORTANCE_DEFAULT))
        val target=Intent(c,WatchHome::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(RouteEntry.uri(route.id)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open=PendingIntent.getActivity(c,21,target,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val start=PendingIntent.getActivity(c,22,Intent(target).setAction("com.kylins.amapnav.START_ROUTE"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if(c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
            manager.notify(52,Notification.Builder(c,"inbox").setSmallIcon(R.drawable.ic_navigation).setContentTitle(route.title)
                .setContentText("路线已就绪，请使用下方开始按钮").setContentIntent(open).setAutoCancel(true)
                .addAction(Notification.Action.Builder(Icon.createWithResource(c,R.drawable.ic_ongoing),if(route.mode=="walk") "开始步行" else "开始骑行",start).build()).build())
    }
    fun clear(c: Context) { store(c).edit().remove("pending").apply() }
}
class WatchInbox : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) { for(e in events) if(e.type==DataEvent.TYPE_CHANGED && e.dataItem.uri.path==PairedLink.ROUTE) PairedLink.body(e.dataItem)?.let { Inbox.accept(this,it) } }
}
class WatchHome : Activity() {
    private var selected: Journey?=null
    private var requestedId:String?=null
    private var startRequested=false
    private var openedId=""
    private var resumed=false
    private var retries=0
    private var resumeActiveOnEntry=false
    private val handler=Handler(Looper.getMainLooper())
    private val retry=Runnable {resolveEntry()}
    private val inboxChanged=object:BroadcastReceiver() {override fun onReceive(c:Context,intent:Intent) {show();resolveEntry()} }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    override fun onCreate(state: Bundle?) { super.onCreate(state); selected=state?.getString("selected")?.let { Journey.decode(it) };readEntry(intent)
        if(BuildConfig.SHOWCASE) {startActivity(Intent(this,PreviewScreen::class.java));finish()}
    }
    override fun onNewIntent(value:Intent) {super.onNewIntent(value);setIntent(value);readEntry(value)}
    private fun readEntry(value:Intent) {
        requestedId=RouteEntry.id(value.dataString);startRequested=value.action=="com.kylins.amapnav.START_ROUTE" && requestedId!=null;retries=0
        resumeActiveOnEntry=requestedId==null && (value.action==Intent.ACTION_MAIN || value.action==null)
        if(requestedId!=null) {setTurnScreenOn(true);window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            handler.postDelayed({window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)},30000)}
    }
    override fun onSaveInstanceState(out: Bundle) { out.putString("selected",selected?.encode());super.onSaveInstanceState(out) }
    override fun onStart() { super.onStart();registerReceiver(inboxChanged,IntentFilter("com.kylins.amapnav.INBOX_UPDATED"),RECEIVER_NOT_EXPORTED) }
    override fun onStop() { unregisterReceiver(inboxChanged);setTurnScreenOn(false);window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);super.onStop() }
    override fun onResume() {
        super.onResume();if(BuildConfig.SHOWCASE) return;resumed=true;show()
        if(resumeActiveOnEntry && requestedId==null && NavigationEngine.frame.active) {resumeActiveOnEntry=false;openNavigation();return}
        resumeActiveOnEntry=false
        PairedLink.fetch(this,PairedLink.ROUTE) { Inbox.accept(this,it);if(!isFinishing) {show();resolveEntry()} }
        resolveEntry()
    }
    override fun onPause() {resumed=false;handler.removeCallbacks(retry);super.onPause()}
    override fun onDestroy() {handler.removeCallbacksAndMessages(null);super.onDestroy()}
    private fun resolveEntry() {
        if(!resumed) return
        val id=requestedId ?: return
        val pending=Inbox.pending(this)
        if(pending?.id==id) {
            handler.removeCallbacks(retry);selected=pending
            if(openedId!=id) {openedId=id;Journal.note(this,"watch","route_page","已打开手机路线确认页",id)}
            show()
            if(startRequested) {startRequested=false;handler.post {if(resumed) authorize()}}
        } else if(retries++<16) {handler.removeCallbacks(retry);handler.postDelayed(retry,500)}
        else {requestedId=null;startRequested=false;show();Toast.makeText(this,"对应路线未收到或已过期，请从手机重发",Toast.LENGTH_LONG).show()}
    }
    private fun openNavigation() {startActivity(Intent(this,NavigationScreen::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))}
    private fun show() {
        val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(28),dp(30),dp(28),dp(38));setBackgroundColor(Color.BLACK) }
        setContentView(ScrollView(this).apply { addView(column) })
        fun label(s:String,size:Float,color:Int=Color.WHITE) { column.addView(TextView(this).apply { text=s;textSize=size;gravity=Gravity.CENTER;setTextColor(color);setPadding(0,dp(4),0,dp(8)) }) }
        fun button(s:String,accent:Boolean=false,action:()->Unit) { column.addView(Button(this).apply {
            text=s;textSize=14f;isAllCaps=false;setTextColor(if(accent) Color.BLACK else Color.WHITE)
            background=GradientDrawable().apply { cornerRadius=dp(24).toFloat();setColor(if(accent) Color.rgb(213,255,117) else Color.rgb(26,33,29)) }
            setOnClickListener { action() }
        },LinearLayout.LayoutParams(-1,dp(44)).apply { bottomMargin=dp(8) }) }
        label("麒麟步迹",24f,Color.rgb(213,255,117));label("让路线留在手腕上",11f,Color.GRAY)
        if(NavigationEngine.frame.active) button("返回导航",true) {openNavigation()}
        val pending=Inbox.pending(this)?.takeIf {requestedId==null || it.id==requestedId}
        if(pending!=null) {
            label(pending.title,18f)
            if(NavigationEngine.frame.active) label("新路线已收到\n请先结束当前导航",12f,Color.LTGRAY)
            else button(if(pending.mode=="walk") "开始步行" else "开始骑行",true) { selected=pending; authorize() }
        } else label(if(requestedId!=null) "正在接收手机路线…" else "从手机分享一条路线\n即可开始出发",13f,Color.LTGRAY)
        button("界面预览（不导航）") { startActivity(Intent(this,PreviewScreen::class.java)) }
        button("刷新手机路线") { PairedLink.fetch(this,PairedLink.ROUTE) { Inbox.accept(this,it);show() } }
        if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            button("开启路线通知") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),23) }
        label("导航中长按屏幕\n可选省电、常亮或结束",10f,Color.GRAY)
        if(!BuildConfig.HAS_KEY) label("等待配置新包名的高德 Key",10f,Color.GRAY)
    }
    private fun authorize() {
        if(NavigationEngine.frame.active) {openNavigation();return}
        if(!BuildConfig.HAS_KEY) { Toast.makeText(this,"请先配置新包名的 Key",Toast.LENGTH_LONG).show();return }
        val prefs=getSharedPreferences("privacy",MODE_PRIVATE)
        if(!prefs.getBoolean("accepted",false)) AlertDialog.Builder(this).setTitle("开始导航")
            .setMessage("高德 SDK 将处理位置、目的地及必要设备信息；导航状态同步到配对手机本地日志。\n隐私说明：lbs.amap.com/pages/privacy/")
            .setNegativeButton("取消",null).setPositiveButton("同意并继续") { _,_->prefs.edit().putBoolean("accepted",true).apply();permissions() }.show()
        else permissions()
    }
    private fun permissions() {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.POST_NOTIFICATIONS),22)
        else begin()
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray) { super.onRequestPermissionsResult(code,permissions,results)
        if(code==22 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) begin() }
    private fun begin() {
        val route=selected?.takeIf { it.fresh() } ?: run {Toast.makeText(this,"路线已过期，请从手机重发",Toast.LENGTH_LONG).show();return}
        Inbox.clear(this)
        getSystemService(NotificationManager::class.java).cancel(52)
        requestedId=null
        setIntent(Intent(this,WatchHome::class.java).setAction(Intent.ACTION_MAIN))
        startActivity(Intent(this,NavigationScreen::class.java).putExtra("journey",route.encode())
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}
