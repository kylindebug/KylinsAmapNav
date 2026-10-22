package com.kylins.amapnav.phone

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
import android.content.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.Gravity
import android.widget.*
import com.google.android.gms.wearable.*
import com.kylins.amapnav.core.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import android.net.Uri
import androidx.wear.remote.interactions.RemoteActivityHelper

class PhoneInbox: WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for(e in events) if(e.type==DataEvent.TYPE_CHANGED) PairedLink.body(e.dataItem)?.let {raw->
            when(e.dataItem.uri.path) {
                PairedLink.JOURNAL -> Journal.absorb(this,raw)
                NavWire.STOP -> if(NavWire.validId(raw)) PhoneNavigationEngine.stop(raw)
            }
        }
    }
}
class PhoneHome: Activity() {
    private val background=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("destination",MODE_PRIVATE) }
    private var journey=Journey(DemoRoute.destinationName,DemoRoute.destination)
    private lateinit var destination: TextView
    private lateinit var status: TextView
    private lateinit var navigationStatus: TextView
    private lateinit var journal: TextView
    private lateinit var linkInput: EditText
    private lateinit var originToggle: CheckBox
    private var logVisible=false
    private val walkId=android.view.View.generateViewId()
    private val rideId=android.view.View.generateViewId()
    private var awaiting=""
    private var sending=false
    private var sendGeneration=0
    private var visible=false
    private val tick=object:Runnable {override fun run() {
        refreshJournal()
        val frame=PhoneNavigationEngine.frame
        navigationStatus.text=if(frame.active) "手机导航中 · ${frame.message} ${frame.road}" else frame.message
        main.postDelayed(this,2000)
    }}
    private val dark=Color.rgb(22,34,27)
    private val green=Color.rgb(209,246,119)
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        if(BuildConfig.SHOWCASE) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor=Color.rgb(243,245,239)
        window.navigationBarColor=Color.rgb(243,245,239)
        window.decorView.systemUiVisibility=android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        prefs.getString("end",null)?.let { Coordinate.read(it) }?.let { journey=Journey(prefs.getString("name","目的地")!!,it) }
        build(); takeShare(intent)
    }
    private fun box(color:Int,radius:Int=24)=GradientDrawable().apply { setColor(color);cornerRadius=dp(radius).toFloat() }
    private fun build() {
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(28),dp(22),dp(36));setBackgroundColor(Color.rgb(243,245,239)) }
        setContentView(ScrollView(this).apply { isFillViewport=true;addView(root) })
        fun text(parent:LinearLayout,s:String,size:Float,color:Int=dark):TextView=TextView(this).apply {
            text=s;textSize=size;setTextColor(color);setPadding(0,dp(5),0,dp(9));parent.addView(this)
        }
        fun button(parent:LinearLayout,s:String,accent:Boolean=false,action:()->Unit):Button=Button(this).apply {
            text=s;isAllCaps=false;textSize=15f;setTextColor(if(accent) dark else Color.WHITE)
            background=box(if(accent) green else dark,18);setOnClickListener { action() }
            parent.addView(this,LinearLayout.LayoutParams(-1,dp(54)).apply {topMargin=dp(8);bottomMargin=dp(6)})
        }
        text(root,"KYLiNS / WALK",12f).apply { letterSpacing=.16f }
        text(root,"麒麟步迹",32f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) }
        text(root,"把路线放到手腕上。",16f,Color.rgb(105,117,108))
        status=text(root,"正在连接手表…",13f)
        navigationStatus=text(root,"手机负责定位、算路与导航；手表显示指引",13f)
        val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(20),dp(22),dp(22));background=box(dark) }
        root.addView(card,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(16);bottomMargin=dp(14)})
        text(card,"下一站",12f,green)
        destination=text(card,journey.title,26f,Color.WHITE)
        val startHint=text(card,"从手机当前位置出发",13f,Color.LTGRAY)
        originToggle=CheckBox(this).apply {
            text="使用指定起点";setTextColor(Color.LTGRAY);visibility=android.view.View.GONE;card.addView(this)
            setOnCheckedChangeListener { _,checked -> startHint.text=if(checked) "请确认指定起点与实际位置一致" else "从手机当前位置出发" }
        }
        val choices=RadioGroup(this).apply {orientation=RadioGroup.HORIZONTAL;card.addView(this)}
        val tint=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(green,Color.LTGRAY))
        RadioButton(this).apply {id=walkId;text="步行";setTextColor(Color.WHITE);buttonTintList=tint;isChecked=true;choices.addView(this)}
        RadioButton(this).apply {id=rideId;text="骑行";setTextColor(Color.WHITE);buttonTintList=tint;choices.addView(this)}
        choices.setOnCheckedChangeListener { _,id->journey=journey.copy(mode=if(id==walkId) "walk" else "ride") }
        button(card,"开始手机导航并打开手表  ↗",true) { authorizeNavigation() }
        button(root,"结束导航（手机与手表）") { stopNavigation() }
        button(root,"加载上海测试路线（不定位）") {
            journey=Journey(DemoRoute.destinationName,DemoRoute.destination,DemoRoute.origin)
            showDestination();originToggle.isChecked=true
            startHint.text="测试起点：上海东站 → 上海交大\n固定坐标，不读取真实定位"
            status.text="测试路线 · 上海交通大学徐汇校区"
        }
        text(root,"从高德导入",19f).apply { typeface=Typeface.DEFAULT_BOLD }
        text(root,"高德 → 路线分享 → 复制链接，然后在这里粘贴。将导入起终点，由手机重新算路。",13f,Color.rgb(105,117,108))
        button(root,"粘贴高德链接",false) {
            val clip=getSystemService(ClipboardManager::class.java).primaryClip
            val raw=if(clip!=null && clip.itemCount>0) clip.getItemAt(0).coerceToText(this).toString() else ""
            linkInput.setText(raw.take(8192)); import(raw)
        }
        linkInput=EditText(this).apply { hint="也可以粘贴分享链接或经纬度";textSize=13f;setMaxLines(3);root.addView(this) }
        button(root,"解析输入") { import(linkInput.text.toString()) }
        val manual=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;visibility=android.view.View.GONE;root.addView(this)}
        text(manual,"名称 / GCJ-02 经度,纬度",12f)
        val name=EditText(this).apply {hint="目的地名称";setText(journey.title);manual.addView(this)}
        val coords=EditText(this).apply {hint="终点经度,纬度";setText(journey.destination.toString());manual.addView(this)}
        val start=EditText(this).apply {hint="起点留空：手机定位";manual.addView(this)}
        button(manual,"应用起止点",true) {
            val end=Coordinate.read(coords.text.toString());val from=start.text.toString().trim()
            if(end==null || (from.isNotEmpty() && Coordinate.read(from)==null)) toast("请填写有效的 GCJ-02 经度,纬度")
            else { journey=Journey(name.text.toString().ifBlank {"目的地"},end,Coordinate.read(from),journey.mode);showDestination();originToggle.isChecked=journey.origin!=null;manual.visibility=android.view.View.GONE }
        }
        button(root,"手动设置起止点") { manual.visibility=if(manual.visibility==android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE }
        button(root,"连接与调试记录") { logVisible=!logVisible;journal.visibility=if(logVisible) android.view.View.VISIBLE else android.view.View.GONE;sync() }
        journal=text(root,"",12f).apply {setTextIsSelectable(true);visibility=android.view.View.GONE}
        button(root,"导出测试日志") { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE,"kylins-walk-${System.currentTimeMillis()}.jsonl"),30) }
        text(root,"0.5 · MIT 开源应用代码\n高德服务与 SDK 遵循其独立条款",11f,Color.GRAY)
    }
    private fun showDestination() {
        destination.text=journey.title;originToggle.visibility=if(journey.origin==null) android.view.View.GONE else android.view.View.VISIBLE
        originToggle.isChecked=false
        findViewById<RadioButton>(if(journey.mode=="walk")walkId else rideId).isChecked=true
        prefs.edit().putString("end",journey.destination.toString()).putString("name",journey.title).apply()
    }
    private fun import(raw:String) {
        if(raw.isBlank()) {toast("剪贴板为空，请先在高德复制路线链接");return}
        status.text="正在解析高德分享…"
        background.execute {
            val result=runCatching { ShareResolver.resolve(raw) }
            main.post { if(!isFinishing) result.fold({journey=it;showDestination();status.text="已导入 ${it.title}，确认后发送"}, {status.text=it.message ?: "无法解析链接"}) }
        }
    }
    private fun authorizeNavigation() {
        if(BuildConfig.SHOWCASE) {toast("截图展示版本不发送路线、不启动定位");return}
        if(PhoneNavigationEngine.frame.active || sending) {toast("请先结束当前导航");return}
        if(!BuildConfig.HAS_KEY) {toast("请在手机应用配置高德 Android Key 后重新构建");return}
        val privacy=getSharedPreferences("privacy",MODE_PRIVATE)
        if(!privacy.getBoolean("accepted",false)) AlertDialog.Builder(this).setTitle("开始手机导航")
            .setMessage("高德 SDK 将在手机上处理位置、目的地及必要设备信息；导航指引同步到配对手表。\n隐私说明：lbs.amap.com/pages/privacy/")
            .setNegativeButton("取消",null).setPositiveButton("同意并继续") {_,_->privacy.edit().putBoolean("accepted",true).apply();permissions()}.show()
        else permissions()
    }
    private fun permissions() {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            val permissions=mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
            if(Build.VERSION.SDK_INT>=33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(permissions.toTypedArray(),22)
        } else send()
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==22) {
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) send()
            else toast("需要手机精确定位权限才能导航；手表不需要定位权限")
        }
    }
    private fun send() {
        if(sending || PhoneNavigationEngine.frame.active) return
        sending=true
        val generation=++sendGeneration
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener {nodes->
            if(generation!=sendGeneration) return@addOnSuccessListener
            if(isFinishing || isDestroyed || !visible) {sending=false;return@addOnSuccessListener}
            val node=nodes.firstOrNull()
            if(node==null) {sending=false;status.text="手表未连接，请连接后再开始";return@addOnSuccessListener}
            startAndSend(node.id,generation)
        }.addOnFailureListener {if(generation==sendGeneration) {sending=false;status.text="无法连接手表，请重试"}}
    }
    private fun stopNavigation() {
        val id=PhoneNavigationEngine.frame.takeIf {it.active}?.journeyId ?: awaiting
        sendGeneration++;sending=false
        PhoneNavigationEngine.stop()
        // Also cancel a start request still queued before Service.onCreate.
        stopService(Intent(this,PhoneNavigationEngine::class.java))
        if(NavWire.validId(id)) PairedLink.publish(this,NavWire.TERMINAL,NavWire.encode(
            NavFrame(message="导航已结束",journeyId=id,sequence=Long.MAX_VALUE),SystemClock.elapsedRealtime()))
        awaiting="";navigationStatus.text="导航已结束"
    }
    private fun startAndSend(node: String,generation: Int) {
        val outgoing=journey.copy(origin=if(originToggle.isChecked) journey.origin else null,id=UUID.randomUUID().toString(),created=System.currentTimeMillis())
        try {
            startForegroundService(Intent(this,PhoneNavigationEngine::class.java)
                .putExtra("journey",outgoing.encode()).putExtra("consent",true).putExtra("node",node))
        } catch(_: Exception) {sending=false;status.text="无法启动手机导航，请检查定位权限后重试";return}
        awaiting=outgoing.id
        Journal.note(this,"phone","send","${outgoing.mode}，等待手表收件",outgoing.id)
        PairedLink.publish(this,PairedLink.ROUTE,outgoing.encode()) { ok->
            if(generation!=sendGeneration) return@publish
            sending=false
            status.text=if(ok) "路线已提交 · 等待手表确认" else "发送失败，请检查配对连接"
            Journal.note(this,"phone",if(ok)"queued" else "send_failed",if(ok)"已排队，收件以 received 日志为准" else "配对通信失败",outgoing.id)
            if(ok) openWatch(outgoing.id) else PhoneNavigationEngine.stop(outgoing.id)
        }
    }
    private fun openWatch(id:String) {
        val executor=Executor {main.post(it)}
        val result=RemoteActivityHelper(this,executor).startRemoteActivity(
            Intent(Intent.ACTION_VIEW,Uri.parse(RouteEntry.uri(id))).addCategory(Intent.CATEGORY_BROWSABLE),null)
        result.addListener({runCatching {result.get()}.fold({
            Journal.note(this,"phone","open_requested","已请求系统打开手表路线页",id)
        },{
            Journal.note(this,"phone","open_failed","系统未能打开手表，请点击路线通知",id)
            if(awaiting==id) {status.text="路线已提交；请在手表通知中点开始";awaiting=""}
        })},executor)
    }
    private fun sync() {
        if(BuildConfig.SHOWCASE) {status.text="截图展示 · 固定测试坐标 · 不定位";return}
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener {nodes-> status.text=if(nodes.isEmpty()) "手表未连接 · 请检查 Galaxy Wearable" else "●  已连接 ${nodes.first().displayName}" }
            .addOnFailureListener {status.text="连接服务暂不可用"}
        PairedLink.fetch(this,PairedLink.JOURNAL) {Journal.absorb(this,it);refreshJournal()}
    }
    private fun refreshJournal() {
        if(awaiting.isNotEmpty()) {
            val events=Journal.entries(this).filter {it.optString("journey")==awaiting}
            if(events.any {it.optString("kind")=="route_page"}) {status.text="✓  已打开手表路线页 · 点开始即可";awaiting=""}
            else if(events.any {it.optString("kind")=="received"}) status.text="手表已收到 · 正在请求打开路线页"
        }
        if(!::journal.isInitialized || !logVisible) return
        val clock=SimpleDateFormat("HH:mm:ss",Locale.US)
        journal.text=Journal.entries(this).takeLast(80).reversed().joinToString("\n\n") {"${clock.format(Date(it.optLong("time")))}  ${it.optString("source")} · ${it.optString("kind")}\n${it.optString("message")}"}
    }
    private fun takeShare(value:Intent) {if(value.action==Intent.ACTION_SEND && value.type=="text/plain") value.getStringExtra(Intent.EXTRA_TEXT)?.let {linkInput.setText(it.take(8192));import(it)} }
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);takeShare(intent)}
    override fun onResume() {super.onResume();visible=true;sync();main.post(tick)}
    override fun onPause() {visible=false;main.removeCallbacks(tick);super.onPause()}
    override fun onDestroy() {background.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy()}
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    override fun onActivityResult(code:Int,result:Int,data:Intent?) {super.onActivityResult(code,result,data)
        if(code==30 && result==RESULT_OK) data?.data?.let {uri->try {
            (contentResolver.openOutputStream(uri) ?: error("no stream")).use {it.write(Journal.entries(this).joinToString("\n",postfix="\n").toByteArray())};toast("日志已保存")
        } catch (_:Exception) {toast("保存失败，请重新选择位置")} }
    }
}
