package com.parker.imageborder.editor

import android.graphics.*
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.icu.text.BreakIterator
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import java.util.Locale
import kotlin.math.*

/** One measured layout is used by preview, stripes, HDR composition and export. */
object CaptionTypography {
    data class Paragraph(val text:String,val layout:StaticLayout,val top:Float)
    data class Block(val paragraphs:List<Paragraph>,val width:Float,val padding:Float,val gap:Float,val contentHeight:Float,val horizontalPadding:Float=padding,val layoutScale:Float=1f){val height get()=contentHeight+padding*2+gap}
    data class Geometry(val width:Float,val padding:Float,val textSize:Float,val horizontalPadding:Float)

    fun geometry(style:TextBlockStyle,width:Float,unit:Float,face:Typeface):Geometry {
        val scale=width/1200f;val logicalUnit=unit/scale
        val padding=logicalUnit*style.padding
        val horizontalPadding=logicalUnit*style.horizontalPadding
        val available=(1200f-horizontalPadding*2).coerceAtLeast(1f)
        val column=(if(style.widthMode=="fixed")min(available,style.widthRatio?.let{1200f*it}?:style.width/scale)else available).coerceAtLeast(1f)
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply{typeface=face}
        val size=if(style.widthMode=="chars"){
            // Character mode controls the font, never a narrower text column.
            // Use full-width CJK advances in the actual typeface (including fallback).
            val sample="字".repeat(style.characters.coerceIn(5,120))
            paint.textSize=1000f
            val estimate=column/paint.measureText(sample).coerceAtLeast(.001f)*1000f
            var low=0f;var high=max(1f,estimate*2)
            // Android rounds glyph advances internally. Fit against the same Paint
            // used by StaticLayout so the final character does not wrap spuriously.
            repeat(20){val middle=(low+high)/2;paint.textSize=middle
                if(paint.measureText(sample)<=column)low=middle else high=middle}
            low.coerceAtLeast(.001f)
        }else ((if(style.sizeBasis=="width")1200f else logicalUnit)*style.size).coerceAtLeast(.001f)
        return Geometry(column*scale,padding*scale,size*scale,horizontalPadding*scale)
    }

    /** A shaped line is atomic: the outer paragraph must not wrap it a second time. */
    private class ShapedLine(private val layout:StaticLayout):ReplacementSpan(){
        override fun getSize(paint:Paint,text:CharSequence,start:Int,end:Int,fm:Paint.FontMetricsInt?):Int {
            fm?.let{it.ascent=layout.getLineAscent(0);it.descent=layout.getLineDescent(0);it.top=it.ascent;it.bottom=it.descent}
            return ceil(layout.getLineMax(0)).toInt()
        }
        override fun draw(canvas:Canvas,text:CharSequence,start:Int,end:Int,x:Float,top:Int,y:Int,bottom:Int,paint:Paint){
            layout.paint.color=paint.color
            canvas.save();canvas.translate(x-layout.getLineLeft(0),y-layout.getLineBaseline(0).toFloat())
            layout.draw(canvas);canvas.restore()
        }
    }

    /** Grapheme boundaries define soft wraps; shaping, measuring and drawing share one line. */
    private fun characterFlow(text:String,paint:TextPaint,width:Float,fillLast:Boolean):CharSequence {
        if(text.isEmpty())return text
        fun shape(line:String,tracking:Float=0f):StaticLayout {
            val linePaint=TextPaint(paint).apply{letterSpacing=tracking}
            // Leave ample shaping room. The resulting run is measured before it is
            // placed in the actual column, and is never laid out again at that width.
            val room=ceil(max(width*2, linePaint.measureText(line)*2+width)).toInt().coerceAtLeast(1)
            return StaticLayout.Builder.obtain(line,0,line.length,linePaint,room)
                .setIncludePad(false).setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
        }
        fun fits(line:String)=shape(line).getLineMax(0)<=width
        val iterator=BreakIterator.getCharacterInstance(Locale.ROOT).apply{setText(text)}
        val edges=mutableListOf<Int>();var edge=iterator.first()
        while(edge!=BreakIterator.DONE){edges.add(edge);edge=iterator.next()}
        val result=SpannableStringBuilder();var startIndex=0
        val closing="，。！？、；：,.!?;:…）】》〉」』〕］｝％%”’\"'"
        while(startIndex<edges.lastIndex){
            val start=edges[startIndex]
            val estimate=start+paint.breakText(text,start,text.length,true,width,null)
            val found=edges.binarySearch(estimate)
            var endIndex=(if(found>=0)found else -found-2).coerceAtLeast(startIndex+1)
            while(endIndex>startIndex+1&&!fits(text.substring(start,edges[endIndex])))endIndex--
            while(endIndex<edges.lastIndex&&fits(text.substring(start,edges[endIndex+1])))endIndex++
            // Keep a sentence-ending punctuation-only tail with its preceding
            // grapheme. Tracking still fills the preceding non-final line.
            if(endIndex<edges.lastIndex&&endIndex>startIndex+1&&text.substring(edges[endIndex]).all{it in closing})endIndex--
            val last=endIndex==edges.lastIndex
            val raw=text.substring(start,edges[endIndex]);val line=if(last)raw else raw.trimEnd(' ','\t')
            val position=result.length;result.append(line)
            if(line.isNotEmpty()){
                var shaped=shape(line)
                val clusters=BreakIterator.getCharacterInstance(Locale.ROOT).apply{setText(line)}
                var count=0;while(clusters.next()!=BreakIterator.DONE)count++
                if((!last||fillLast)&&count>1&&shaped.getLineMax(0)<width){
                    var low=0f;var high=max(1f,(width-shaped.getLineMax(0))/(paint.textSize*(count-1))*2)
                    repeat(16){val middle=(low+high)/2;val candidate=shape(line,middle)
                        if(candidate.getLineMax(0)<=width){low=middle;shaped=candidate}else high=middle}
                }
                result.setSpan(ShapedLine(shaped),position,result.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            startIndex=endIndex
            if(!last)while(startIndex<edges.lastIndex&&text.substring(edges[startIndex],edges[startIndex+1]).all{it==' '||it=='\t'})startIndex++
            if(startIndex<edges.lastIndex)result.append('\n')
        }
        return result
    }

    fun measure(doc:EditDocument,width:Float,unit:Float,face:Typeface):Block? {
        if(doc.caption.isBlank())return null
        val style=doc.textStyle;val geometry=geometry(style,width,unit,face)
        val scale=width/1200f
        val padding=geometry.padding;val size=geometry.textSize/scale;val column=geometry.width/scale
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply{textSize=size;typeface=face}
        var height=0f;var previousLineHeight=0f
        val paragraphs=doc.caption.split('\n').mapIndexed{index,text->
            if(index>0)height+=previousLineHeight*(style.lineSpacing-1f)+size*style.paragraphSpacing
            val flowing=if(style.widthMode=="chars")characterFlow(text,paint,column,style.alignment=="distribute")else text
            val layout=StaticLayout.Builder.obtain(flowing,0,flowing.length,paint,ceil(column).toInt().coerceAtLeast(1))
                .setIncludePad(false).setLineSpacing(0f,style.lineSpacing)
                .setAlignment(when(style.alignment){"center"->Layout.Alignment.ALIGN_CENTER;"right"->Layout.Alignment.ALIGN_OPPOSITE;else->Layout.Alignment.ALIGN_NORMAL})
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setJustificationMode(if(style.widthMode!="chars"&&style.alignment=="justify")LineBreaker.JUSTIFICATION_MODE_INTER_WORD else LineBreaker.JUSTIFICATION_MODE_NONE).build()
            Paragraph(flowing.toString(),layout,height*scale).also{height+=layout.height;val last=layout.lineCount-1;previousLineHeight=(layout.getLineBottom(last)-layout.getLineTop(last)).toFloat()}
        }
        return Block(paragraphs,geometry.width,padding,if(doc.textOnly)0f else unit*style.gap,height*scale,geometry.horizontalPadding,scale)
    }

    fun draw(canvas:Canvas,rect:RectF,doc:EditDocument,unit:Float,face:Typeface,color:Int){
        val block=measure(doc,rect.width(),unit,face)?:return
        val available=(rect.width()-block.horizontalPadding*2).coerceAtLeast(1f)
        val x=rect.left+block.horizontalPadding+when(doc.textStyle.alignment){"center"->(available-block.width)/2;"right"->available-block.width;else->0f}
        doc.textStyle.background?.let{background->canvas.drawRect(RectF(rect.left,rect.top+block.gap,rect.right,rect.bottom),Paint().apply{this.color=background})}
        canvas.save();canvas.clipRect(rect);canvas.translate(x,rect.top+block.gap+block.padding);canvas.scale(block.layoutScale,block.layoutScale)
        for(paragraph in block.paragraphs){
        val paint=paragraph.layout.paint;paint.color=color
        val alignmentShift=when(doc.textStyle.alignment){"center"->(block.width/block.layoutScale-paragraph.layout.width)/2;"right"->block.width/block.layoutScale-paragraph.layout.width;else->0f}
        canvas.save();canvas.translate(alignmentShift,paragraph.top/block.layoutScale)
        if(doc.textStyle.alignment!="distribute"||doc.textStyle.widthMode=="chars")paragraph.layout.draw(canvas)
        else {
            val iterator=BreakIterator.getCharacterInstance(Locale.ROOT)
            for(line in 0 until paragraph.layout.lineCount){
                val start=paragraph.layout.getLineStart(line);val end=paragraph.layout.getLineEnd(line)
                val text=paragraph.text.substring(start,end).trimEnd('\n','\r');iterator.setText(text)
                val clusters=mutableListOf<String>();var a=iterator.first();var b=iterator.next()
                while(b!=BreakIterator.DONE){clusters.add(text.substring(a,b));a=b;b=iterator.next()}
                val used=clusters.sumOf{paint.measureText(it).toDouble()}.toFloat()
                val gap=if(clusters.size>1)((block.width/block.layoutScale-used)/(clusters.size-1)).coerceAtLeast(0f)else 0f
                var position=0f;val baseline=paragraph.layout.getLineBaseline(line).toFloat()
                for(cluster in clusters){canvas.drawText(cluster,position,baseline,paint);position+=paint.measureText(cluster)+gap}
            }
        };canvas.restore()
        };canvas.restore()
    }
}
