package com.kylins.amapnav

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import com.kylins.amapnav.core.GuidanceRules
import kotlin.math.*
import java.text.SimpleDateFormat
import java.util.*

/** Original vector interface. Route geometry comes exclusively from the navigation path. */
class GuidancePanel(context: Context): View(context) {
    var frame=NavFrame()
    var ambient=false
    var preview=false
    var powerSaving=false
    var heading="方向待更新"
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG)
    private val line=Path()
    private val lime=Color.rgb(213,255,117)
    private val faint=Color.rgb(125,140,131)
    private val clock=SimpleDateFormat("HH:mm",Locale.US)
    private val date=Date()
    init { isLongClickable=true; contentDescription="导航指引；长按打开选项" }
    private fun label(c: Canvas,s: String,x: Float,y: Float,size: Float,color: Int=Color.WHITE,bold: Boolean=false) {
        ink.style=Paint.Style.FILL; ink.color=color; ink.textSize=size
        ink.typeface=if(bold) Typeface.create("sans-serif-medium",Typeface.NORMAL) else Typeface.create("sans-serif",Typeface.NORMAL)
        ink.textAlign=Paint.Align.CENTER
        c.drawText(s,x,y,ink)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ink.isAntiAlias=!ambient
        canvas.drawColor(Color.BLACK)
        canvas.save(); val scale=min(width,height)/480f; canvas.translate((width-480*scale)/2,(height-480*scale)/2); canvas.scale(scale,scale)
        if(ambient) { val shift=((System.currentTimeMillis()/60000)%5-2).toFloat(); canvas.translate(shift,-shift) }
        val fresh=frame.active && !GuidanceRules.outdated(SystemClock.elapsedRealtime(),frame.stamp) && frame.meters>=0
        val accent=if(ambient) Color.LTGRAY else lime
        date.time=System.currentTimeMillis()
        label(canvas,clock.format(date),240f,48f,24f,if(ambient) Color.LTGRAY else Color.WHITE)
        label(canvas,when {preview->"预览 · 非实时导航";fresh->"前方 · ${GuidanceRules.turn(frame.icon)}";else->"麒麟步迹"},240f,78f,18f,accent)
        if(fresh) {
            arrow(canvas,frame.icon,115f,145f,accent)
            label(canvas,GuidanceRules.meters(frame.meters),292f,181f,82f,Color.WHITE,true)
            label(canvas,if(frame.meters<1000) "米" else "公里",378f,180f,22f,faint)
            label(canvas,frame.road.ifBlank { "沿当前道路前行" }.take(13),240f,239f,30f,Color.WHITE,true)
        } else {
            label(canvas,"···",240f,170f,70f,accent)
            label(canvas,if(frame.active && frame.stamp>0) "等待定位更新" else frame.message.take(17),240f,231f,26f)
        }
        if(!powerSaving) {
            ink.color=Color.rgb(29,39,31); ink.strokeWidth=2f; canvas.drawLine(72f,263f,408f,263f,ink)
            if(frame.route.size>=2 && frame.active) sketch(canvas) else label(canvas,"等待路线",168f,335f,20f,faint)
            label(canvas,if(frame.left>0) String.format(Locale.US,"%.1f",frame.left/1000f) else "—",350f,315f,32f,accent,true)
            label(canvas,"公里剩余",350f,340f,17f,faint)
            label(canvas,if(frame.seconds>0) "${ceil(frame.seconds/60.0).toInt()} 分钟" else "—",350f,380f,23f)
            label(canvas,heading,240f,432f,20f,if(ambient) Color.LTGRAY else accent)
        } else label(canvas,"省电模式",240f,340f,18f,faint)
        contentDescription="${clock.format(date)}，${if(preview) "界面预览" else "实时导航"}，${if(fresh) "${GuidanceRules.turn(frame.icon)}，${frame.meters}米，${frame.road}" else frame.message}，${if(powerSaving) "省电模式" else heading}；长按打开选项"
        canvas.restore()
    }
    private fun arrow(c: Canvas,code: Int,x: Float,y: Float,color: Int) {
        c.save(); c.translate(x,y)
        if(code in listOf(2,4,6,8)) c.scale(-1f,1f)
        ink.color=color; ink.style=Paint.Style.STROKE; ink.strokeWidth=14f; ink.strokeCap=Paint.Cap.ROUND; ink.strokeJoin=Paint.Join.ROUND
        line.reset()
        when(code) {
            2,3->{line.moveTo(-20f,42f);line.lineTo(-20f,-8f);line.quadTo(-20f,-32f,4f,-32f);line.lineTo(43f,-32f);line.moveTo(22f,-52f);line.lineTo(44f,-32f);line.lineTo(22f,-12f)}
            8,19->{line.moveTo(-25f,42f);line.lineTo(-25f,-22f);line.cubicTo(-25f,-58f,28f,-58f,28f,-22f);line.lineTo(28f,18f);line.moveTo(8f,-2f);line.lineTo(28f,19f);line.lineTo(48f,-2f)}
            4,5->{line.moveTo(-20f,40f);line.lineTo(-20f,10f);line.lineTo(30f,-40f);line.moveTo(0f,-40f);line.lineTo(30f,-40f);line.lineTo(30f,-10f)}
            6,7->{line.moveTo(-25f,40f);line.lineTo(-25f,-40f);line.lineTo(35f,20f);line.moveTo(35f,-10f);line.lineTo(35f,20f);line.lineTo(5f,20f)}
            9,20->{line.moveTo(0f,42f);line.lineTo(0f,-46f);line.moveTo(-25f,-20f);line.lineTo(0f,-46f);line.lineTo(25f,-20f)}
            else->{line.moveTo(-30f,25f);line.lineTo(-30f,-20f);line.lineTo(28f,20f);line.lineTo(28f,-25f)}
        }
        c.drawPath(line,ink); c.restore()
    }
    private fun sketch(c: Canvas) {
        val all=frame.route
        val current=frame.position ?: all.first()
        val near=all.indices.minByOrNull { i -> val p=all[i]; (p.longitude-current.longitude).pow(2)+(p.latitude-current.latitude).pow(2) } ?: 0
        val selected=all.drop(max(0,near-2)).take(80)
        if(selected.size<2) return
        val cosLat=cos(Math.toRadians(current.latitude))
        val points=selected.map { ((it.longitude-current.longitude)*cosLat*111320).toFloat() to (-(it.latitude-current.latitude)*111320).toFloat() }
        val minX=min(0f,points.minOf { it.first });val maxX=max(0f,points.maxOf { it.first })
        val minY=min(0f,points.minOf { it.second });val maxY=max(0f,points.maxOf { it.second })
        val s=min(190f/max(30f,maxX-minX),110f/max(30f,maxY-minY))
        fun px(x:Float)=166f+(x-(minX+maxX)/2)*s
        fun py(y:Float)=334f+(y-(minY+maxY)/2)*s
        c.save(); c.clipRect(63f,276f,277f,397f)
        line.reset();points.forEachIndexed { i,p -> if(i==0) line.moveTo(px(p.first),py(p.second)) else line.lineTo(px(p.first),py(p.second)) }
        ink.style=Paint.Style.STROKE
        if(!ambient) {ink.strokeWidth=13f;ink.color=Color.rgb(34,52,28);c.drawPath(line,ink)}
        ink.strokeWidth=if(ambient) 3f else 5f;ink.color=if(ambient) Color.LTGRAY else lime;c.drawPath(line,ink)
        ink.style=Paint.Style.FILL;ink.color=Color.BLACK;c.drawCircle(px(0f),py(0f),11f,ink)
        ink.color=Color.WHITE;c.drawCircle(px(0f),py(0f),7f,ink)
        c.restore()
    }
}
