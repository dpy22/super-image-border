package com.parker.imageborder.editor

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.*

/** Editorial label layout in a resolution-independent 1200-unit coordinate system. */
internal object MetadataTypography {
    data class Content(val camera:String,val settings:List<String>,val lens:String,val extra:List<String>)
    data class Run(val layout:StaticLayout,val x:Float,val y:Float,val opacity:Float,val inkTop:Float,val inkBottom:Float)
    data class Block(val runs:List<Run>,val scale:Float,val contentHeight:Float,val rule:Float?=null){
        val height get()=contentHeight*scale
        val truncated get()=runs.any{run->(0 until run.layout.lineCount).any{run.layout.getEllipsisCount(it)>0}}
    }
    fun reference(width:Float,height:Float):Float=if(height>width)min(height,width*1.6f)else min(width,height*2.4f)
    fun content(doc:EditDocument,asset:PhotoAsset?=doc.current):Content {
        val f=doc.fields(asset).filterKeys{it !in doc.hidden(asset)}.mapValues{it.value.trim()}
        val make=f["Make"].orEmpty();val model=f["Model"].orEmpty()
        val camera=when{model.isEmpty()->make;make.isEmpty()||model.startsWith(make.substringBefore(' '),true)->model;else->"$make $model"}
        val equivalent=f["FocalLengthIn35mmFilm"]?.takeIf{(Assets.rational(it)?:0.0)>0}
        val focal=equivalent?.let{Assets.display("FocalLengthIn35mmFilm",it)+" eq."}
            ?:f["FocalLength"]?.takeIf{it.isNotEmpty()}?.let{Assets.display("FocalLength",it)}
        val settings=listOfNotNull(focal,*listOf("FNumber","ExposureTime","PhotographicSensitivity").map{key->f[key]?.takeIf{it.isNotEmpty()}?.let{Assets.display(key,it)}}.toTypedArray())
        val date=listOfNotNull(f["DateTimeOriginal"]?.takeIf{it.isNotEmpty()}?.let{Assets.display("DateTimeOriginal",it)},f["OffsetTimeOriginal"]?.takeIf{it.isNotEmpty()}).joinToString(" ")
        return Content(camera,settings,f["LensModel"].orEmpty(),listOf(date,f["Artist"].orEmpty(),f["LocationText"].orEmpty()).filter{it.isNotBlank()})
    }
    fun measure(doc:EditDocument,asset:PhotoAsset?=doc.current,width:Float,reference:Float=width,variant:Int=0,compact:Boolean=false,alignment:String?=null):Block {
        val content=content(doc,asset);val scale=(reference/1200f).coerceAtLeast(.00001f)
        val column=(width/scale).coerceAtLeast(1f);val runs=mutableListOf<Run>()
        val align=alignment?:if(variant==3)"center"else"left"
        fun layout(text:String,w:Float,size:Float,weight:Boolean,maxLines:Int):StaticLayout {
            val paint=TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply{textSize=size;typeface=Typeface.create(if(weight)"sans-serif-medium"else"sans-serif",Typeface.NORMAL)}
            return StaticLayout.Builder.obtain(text,0,text.length,paint,w.roundToInt().coerceAtLeast(1))
                .setIncludePad(false).setLineSpacing(0f,1.12f).setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(when(align){"center"->Layout.Alignment.ALIGN_CENTER;"right"->Layout.Alignment.ALIGN_OPPOSITE;else->Layout.Alignment.ALIGN_NORMAL}).build()
        }
        fun tokens(values:List<String>,w:Float,size:Float):String {
            val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{textSize=size;typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
            val lines=mutableListOf<String>();var line=""
            for(token in values){val next=if(line.isEmpty())token else "$line   ·   $token"
                if(line.isNotEmpty()&&paint.measureText(next)>w){lines.add(line);line=token}else line=next}
            if(line.isNotEmpty())lines.add(line)
            return lines.joinToString("\n")
        }
        fun inkBounds(shaped:StaticLayout):Pair<Float,Float>{
            var top=Float.POSITIVE_INFINITY;var bottom=Float.NEGATIVE_INFINITY
            for(line in 0 until shaped.lineCount){
                val start=shaped.getLineStart(line);val end=shaped.getLineEnd(line)
                var visible=shaped.text.subSequence(start,end).toString().trimEnd()
                if(shaped.getEllipsisCount(line)>0)visible=visible.take(shaped.getEllipsisStart(line))+"…"
                if(visible.isNotBlank()){
                    val bounds=Rect();shaped.paint.getTextBounds(visible,0,visible.length,bounds)
                    top=min(top,shaped.getLineBaseline(line)+bounds.top.toFloat())
                    bottom=max(bottom,shaped.getLineBaseline(line)+bounds.bottom.toFloat())
                }
            }
            return if(top.isFinite()&&bottom>top)(top-.5f) to (bottom+.5f)else 0f to shaped.height.toFloat()
        }
        fun group(x:Float,w:Float,items:List<Triple<String,Float,Boolean>>,start:Float=0f):Float {
            var y=start;var index=0;var previousPrimary=false
            for((text,size,primary)in items)if(text.isNotBlank()){
                if(index>0)y+=if(previousPrimary)12f else 8f
                val shaped=layout(text,w,size,primary,max(if(primary)2 else 3,text.count{it=='\n'}+1))
                val (inkTop,inkBottom)=inkBounds(shaped)
                runs.add(Run(shaped,x,y-inkTop,if(primary)1f else if(index==1).9f else .74f,inkTop,inkBottom));y+=inkBottom-inkTop;index++;previousPrimary=primary
            }
            return y
        }
        val primary=if(compact)30f else 34f;val technical=if(compact)24f else 27f;val secondary=if(compact)20f else 22f
        if(variant==1){
            val left=column*.40f;val rightStart=column*.46f;val right=column-rightStart
            val a=group(0f,left,listOf(Triple(content.camera,primary,true),Triple(content.lens,secondary,false)))
            val b=group(rightStart,right,listOf(Triple(tokens(content.settings,right,technical),technical,false),Triple(tokens(content.extra,right,secondary),secondary,false)),if(content.camera.isNotEmpty())6f else 0f)
            return Block(runs,scale,max(a,b),column*.43f)
        }
        val height=group(0f,column,listOf(Triple(content.camera,primary,true),Triple(tokens(content.settings,column,technical),technical,false),Triple(content.lens,secondary,false),Triple(tokens(content.extra,column,secondary),secondary,false)))
        return Block(runs,scale,height)
    }
    fun draw(canvas:Canvas,rect:RectF,doc:EditDocument,asset:PhotoAsset?=doc.current,reference:Float=rect.width(),variant:Int=0,compact:Boolean=false,color:Int,alignment:String?=null){
        if(rect.width()<=0||rect.height()<=0)return
        val block=measure(doc,asset,rect.width(),reference,variant,compact,alignment)
        canvas.save();canvas.clipRect(rect);canvas.translate(rect.left,rect.top+max(0f,(rect.height()-block.height)/2));canvas.scale(block.scale,block.scale)
        for(run in block.runs){
            run.layout.paint.color=color;run.layout.paint.alpha=(Color.alpha(color)*run.opacity).roundToInt()
            canvas.save();canvas.translate(run.x,run.y);run.layout.draw(canvas);canvas.restore()
        }
        block.rule?.let{x->if(block.contentHeight>0){val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;alpha=40;strokeWidth=1f};canvas.drawLine(x,2f,x,block.contentHeight-2f,p)}}
        canvas.restore()
    }
}
