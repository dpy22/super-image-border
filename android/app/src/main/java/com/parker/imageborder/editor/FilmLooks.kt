package com.parker.imageborder.editor

import android.graphics.ColorSpace
import android.util.LruCache
import kotlin.math.*

/** Original film-inspired grades. No camera profiles or proprietary LUTs are bundled. */
object FilmLooks {
    data class Look(val name:String,val group:String,val contrast:Float=1f,val saturation:Float=1f,
        val lift:Float=0f,val white:Float=1f,val cyan:Float=0f,val amber:Float=0f,val magenta:Float=0f,
        val greenHue:Float=0f,val blueHue:Float=0f,val skin:Float=0f,val mono:Boolean=false,val sepia:Boolean=false)
    val presets=listOf(
        Look("经典负片","胶片",1.25f,.92f,.014f,.97f,.055f,.018f,.032f,13f,-4f,.35f),
        Look("经典负片·青","胶片",1.22f,.88f,.012f,.96f,.105f,.014f,.026f,22f,-8f,.4f),
        Look("经典正片","胶片",1.16f,.72f,.008f,.96f,.025f,.014f,0f,8f,-7f,.35f),
        Look("怀旧负片","胶片",1.10f,1.04f,.025f,.98f,.018f,.085f,.015f,-8f,-5f,.45f),
        Look("暖色负片","胶片",1.05f,.94f,.035f,.97f,.012f,.062f,.01f,-14f,0f,.55f),
        Look("鲜艳反转","胶片",1.22f,1.28f,0f,.985f,.008f,.008f,0f,5f,-5f,.60f),
        Look("日系通透","胶片",.94f,.86f,.055f,.98f,.035f,.018f,.012f,12f,-3f,.55f),
        Look("影院柔调","电影",.82f,.72f,.040f,.94f,.040f,.025f,0f,7f,-5f,.30f),
        Look("海岸青橙","电影",1.16f,.94f,.015f,.97f,.095f,.075f,0f,18f,-15f,.35f),
        Look("漂白电影","电影",1.42f,.34f,.005f,.97f,.020f,.008f,0f,0f,0f,.25f),
        Look("琥珀夜景","电影",1.18f,.88f,.008f,.95f,.115f,.115f,.01f,18f,-10f,.35f),
        Look("都市冷调","电影",1.18f,.82f,.008f,.97f,.085f,-.028f,.012f,18f,-10f,.35f),
        Look("柔光人像","人像",.90f,.90f,.032f,.98f,.008f,.035f,.018f,0f,0f,.90f),
        Look("粉雾人像","人像",.88f,.80f,.060f,.98f,.010f,.018f,.065f,0f,0f,.80f),
        Look("柔蓝时刻","人像",.98f,.86f,.035f,.96f,.065f,-.025f,.008f,5f,-10f,.70f),
        Look("胶片黑白","黑白",1.16f,0f,.025f,.98f,.008f,.012f,0f,mono=true),
        Look("银盐黑白","黑白",1.38f,0f,.006f,.97f,.015f,.008f,0f,mono=true),
        Look("复古棕褐","黑白",1.05f,0f,.025f,.96f,mono=true,sepia=true)
    )
    const val EDGE=33
    private val tables=object:LruCache<String,FloatArray>(12){}
    fun group(name:String)=presets.firstOrNull{it.name==name}?.group?:if(name.contains("黑白"))"黑白"else "基础"
    private fun smooth(low:Float,high:Float,value:Float):Float {val t=((value-low)/(high-low)).coerceIn(0f,1f);return t*t*(3-2*t)}
    private fun grade(input:FloatArray,p:Look):FloatArray {
        val r=input[0];val g=input[1];val b=input[2]
        val hi=max(r,max(g,b));val lo=min(r,min(g,b));val d=hi-lo
        var hue=if(d<.0001f)0f else when(hi){r->60*((g-b)/d%6);g->60*((b-r)/d+2);else->60*((r-g)/d+4)}
        if(hue<0)hue+=360
        val skin=smooth(8f,24f,hue)*(1-smooth(40f,62f,hue))*smooth(.08f,.3f,d)*p.skin
        val green=smooth(55f,90f,hue)*(1-smooth(155f,185f,hue))
        val blue=smooth(170f,210f,hue)*(1-smooth(255f,285f,hue))
        hue=(hue+p.greenHue*green+p.blueHue*blue+360)%360
        val sat=(if(hi<=0)0f else d/hi)*(p.saturation*(1-skin)+skin)
        val chroma=hi*sat;val x=chroma*(1-abs((hue/60%2)-1));val m=hi-chroma
        val rgb=when(hue.toInt()/60){0->floatArrayOf(chroma,x,0f);1->floatArrayOf(x,chroma,0f);2->floatArrayOf(0f,chroma,x);3->floatArrayOf(0f,x,chroma);4->floatArrayOf(x,0f,chroma);else->floatArrayOf(chroma,0f,x)}
        for(i in 0..2)rgb[i]+=m
        val l=.24f*r+.67f*g+.09f*b
        if(p.mono)for(i in 0..2)rgb[i]=l
        fun curve(v:Float):Float {val t=v.coerceIn(0f,1f)
            val curved=if(p.contrast>=1)t+(t*t*(3-2*t)-t)*(p.contrast-1)*1.8f else (t-.5f)*p.contrast+.5f
            return p.lift+(p.white-p.lift)*curved}
        for(i in 0..2)rgb[i]=curve(rgb[i])
        val shadow=1-smooth(.08f,.72f,l);val highlight=smooth(.38f,1f,l);val protect=1-skin*.7f
        rgb[0]+=(-p.cyan*shadow*.7f+p.amber*highlight*.8f+p.magenta*highlight*.35f)*protect
        rgb[1]+=(p.cyan*shadow*.12f+p.amber*highlight*.18f-p.magenta*highlight*.5f)*protect
        rgb[2]+=(p.cyan*shadow*.5f-p.amber*highlight*.9f+p.magenta*highlight*.35f)*protect
        if(p.sepia){rgb[0]*=1.08f;rgb[1]*=.97f;rgb[2]*=.78f}
        for(i in 0..2)rgb[i]=rgb[i].coerceIn(0f,1f)
        return rgb
    }
    @Synchronized fun table(name:String,space:ColorSpace):FloatArray? {
        val look=presets.firstOrNull{it.name==name}?:return null
        val key="$name:${space.hashCode()}";tables[key]?.let{return it}
        val working=ColorSpace.get(ColorSpace.Named.SRGB)
        val into=ColorSpace.connect(space,working);val out=ColorSpace.connect(working,space)
        val table=FloatArray(EDGE*EDGE*EDGE*3);var n=0
        for(r in 0 until EDGE)for(g in 0 until EDGE)for(b in 0 until EDGE){
            val values=out.transform(grade(into.transform(floatArrayOf(r/(EDGE-1f),g/(EDGE-1f),b/(EDGE-1f))),look))
            for(v in values)table[n++]=v.coerceIn(0f,1f)
        }
        tables.put(key,table);return table
    }
}
