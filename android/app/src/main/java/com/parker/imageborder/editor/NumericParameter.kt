package com.parker.imageborder.editor

import java.util.Locale
import kotlin.math.*

internal enum class NumericUnit { PIXELS, PERCENT, MULTIPLIER, EM, EV, DEGREES, RATIO, COUNT, NUMBER }
internal enum class NumericInputProblem { INVALID, PRECISION, RANGE }

/** Values are edited in the same units the user sees, then converted for storage. */
internal data class NumericParameter(
    val scale:Float=1f,
    val unit:NumericUnit=NumericUnit.PERCENT,
    val decimals:Int=0,
    val step:Float=1f,
    val dragStep:Float=step,
    val presets:List<Float> = emptyList(),
    val signed:Boolean=false
){
    init{require(scale.isFinite()&&scale>0&&step>0&&dragStep>0)}
    private val quantum get()=10.0.pow(-decimals)
    fun displayed(raw:Float)=raw*scale
    fun bounds(range:ClosedFloatingPointRange<Float>):ClosedFloatingPointRange<Float>{
        val low=ceil(range.start.toDouble()*scale/quantum-1e-4)*quantum
        val high=floor(range.endInclusive.toDouble()*scale/quantum+1e-4)*quantum
        return if(low<=high)low.toFloat()..high.toFloat() else displayed(range.start)..displayed(range.endInclusive)
    }
    fun raw(display:Float,range:ClosedFloatingPointRange<Float>)=(display/scale).coerceIn(range.start,range.endInclusive)
    fun snap(display:Float,range:ClosedFloatingPointRange<Float>,drag:Boolean=false):Float {
        val increment=if(drag)dragStep else step;val limits=bounds(range)
        return raw((round(display.toDouble()/increment)*increment).toFloat().coerceIn(limits.start,limits.endInclusive),range)
    }
    fun stepped(value:Float,direction:Int,range:ClosedFloatingPointRange<Float>):Float {
        val limits=bounds(range)
        // Start at the number actually displayed; retain a deliberately entered
        // fraction (e.g. -12.5° + 1° = -11.5°), rather than snapping it away.
        val next=(input(value).toDouble()+direction*step).coerceIn(limits.start.toDouble(),limits.endInclusive.toDouble())
        return raw(next.toFloat(),range)
    }
    fun input(value:Float):String=String.format(Locale.ROOT,"%.${decimals}f",displayed(value).let{if(abs(it)<quantum/2)0f else it})
    fun number(value:Float):String=if(signed&&displayed(value)>0)"+${input(value)}"else input(value)
    private fun read(text:String):Double? {
        val digits=buildString{for(c in text.trim())append(when{c=='，'->',';c=='．'->'.';c=='−'->'-';Character.digit(c,10)>=0->'0'+Character.digit(c,10);else->c})}
        val normalized=if(decimals==0&&digits.matches(Regex("[+-]?[0-9]{1,3}(,[0-9]{3})+")))digits.replace(",","")else digits.replace(',','.')
        if(!normalized.matches(Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")))return null
        return normalized.toDoubleOrNull()?.takeIf{it.isFinite()}
    }
    fun problem(text:String,range:ClosedFloatingPointRange<Float>):NumericInputProblem? {
        val number=read(text)?:return NumericInputProblem.INVALID
        val limits=bounds(range);val epsilon=quantum*1e-4
        if(number<limits.start-epsilon||number>limits.endInclusive+epsilon)return NumericInputProblem.RANGE
        if(abs(number/quantum-round(number/quantum))>1e-4)return NumericInputProblem.PRECISION
        return null
    }
    fun parse(text:String,range:ClosedFloatingPointRange<Float>):Float? {
        if(problem(text,range)!=null)return null
        val number=read(text)?:return null
        return raw((round(number/quantum)*quantum).toFloat(),range)
    }
}

/** Stable document keys, independent of UI language or the numeric range. */
internal fun numericParameter(key:String,pixelScale:Float=1f):NumericParameter=when(key){
    "四边留白","左边","上边","右边","下边","底栏留白","参数栏高度","圆角","外描边","内描边","投影","悬浮高度","平铺间距","图文间距","左右留白","上下留白","字号","Logo 宽度","文字宽度","参数留白","照片间距","参数区宽度"->NumericParameter(pixelScale,NumericUnit.PIXELS)
    "输出最长边"->NumericParameter(unit=NumericUnit.PIXELS)
    "每行字数"->NumericParameter(unit=NumericUnit.COUNT,presets=listOf(16f,20f,24f,28f,32f))
    "JPG 质量"->NumericParameter(unit=NumericUnit.NUMBER)
    "行距"->NumericParameter(unit=NumericUnit.MULTIPLIER,decimals=2,step=.01f,presets=listOf(1f,1.15f,1.3f,1.5f))
    "段间距"->NumericParameter(unit=NumericUnit.EM,decimals=2,step=.05f,presets=listOf(0f,.25f,.5f,.75f,1f))
    "曝光"->NumericParameter(unit=NumericUnit.EV,decimals=1,step=.1f,signed=true,presets=listOf(-1f,0f,1f))
    "旋转角度"->NumericParameter(unit=NumericUnit.DEGREES,decimals=1,step=1f,presets=listOf(-90f,0f,90f,180f))
    "画布宽高比"->NumericParameter(unit=NumericUnit.RATIO,decimals=2,step=.01f)
    "对比度","饱和度","色温","色调","高光","阴影"->NumericParameter(scale=100f,unit=NumericUnit.NUMBER,signed=true)
    "照片大小","水平位置","垂直位置","左边界","上边界","右边界","下边界","滤镜强度","不透明度","玻璃不透明度","照片染色","模糊","锐化","暗角","大小"->NumericParameter(scale=100f)
    else->NumericParameter(unit=NumericUnit.NUMBER,decimals=2,step=.01f)
}
