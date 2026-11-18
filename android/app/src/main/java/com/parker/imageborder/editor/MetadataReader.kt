package com.parker.imageborder.editor

import android.util.Xml
import androidx.exifinterface.media.ExifInterface
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.lang.reflect.Modifier
import kotlin.math.pow

/** Standard readable tags only. GPS, unique IDs, binary maker notes and raw XMP stay out of drafts. */
object MetadataReader {
    private val excluded=setOf("MakerNote","Xmp","UserComment","ImageUniqueID")
    private fun allowed(tag:String)=tag !in excluded&&!tag.startsWith("GPS")&&!tag.contains("Serial",true)
    private val tags by lazy{ExifInterface::class.java.fields.filter{it.name.startsWith("TAG_")&&it.type==String::class.java&&Modifier.isStatic(it.modifiers)}.mapNotNull{runCatching{it.get(null) as String}.getOrNull()}.distinct().filter(::allowed)}
    fun read(exif:ExifInterface):Map<String,String>{
        val raw=tags.mapNotNull{tag->runCatching{exif.getAttribute(tag)}.getOrNull()?.takeIf{it.isNotBlank()}?.let{tag to it.take(4096)}}.toMap()
        return normalize(raw,runCatching{exif.getAttribute(ExifInterface.TAG_XMP)}.getOrNull())
    }
    internal fun normalize(raw:Map<String,String>,xmp:String?=null):Map<String,String>{
        val values=raw.filterKeys(::allowed).toMutableMap()
        xmpValues(xmp).forEach{(k,v)->if(values[k].isNullOrBlank())values[k]=v}
        fun positive(key:String)=values[key]?.let(Assets::rational)?.takeIf{it.isFinite()&&it>0}
        if(positive("FNumber")==null)values["ApertureValue"]?.let(Assets::rational)?.let{2.0.pow(it/2)}?.takeIf{it.isFinite()&&it>0}?.let{values["FNumber"]=it.toString()}
        if(positive("ExposureTime")==null)values["ShutterSpeedValue"]?.let(Assets::rational)?.let{2.0.pow(-it)}?.takeIf{it.isFinite()&&it>0}?.let{values["ExposureTime"]=it.toString()}
        if(positive("PhotographicSensitivity")==null)listOf("ISOSpeedRatings","ISOSpeed","RecommendedExposureIndex","StandardOutputSensitivity").firstNotNullOfOrNull{positive(it)}?.let{values["PhotographicSensitivity"]=it.toLong().toString()}
        values["DateTimeOriginal"]?.let{date->
            if(values["OffsetTimeOriginal"].isNullOrBlank())Regex("(Z|[+-]\\d{2}:\\d{2})$").find(date)?.value?.let{values["OffsetTimeOriginal"]=if(it=="Z")"+00:00"else it}
            if('T' in date)values["DateTimeOriginal"]=date.replace('T',' ').replace(Regex("(Z|[+-]\\d{2}:\\d{2})$"),"")
        }
        return values
    }
    private fun xmpValues(source:String?):Map<String,String>{
        if(source.isNullOrBlank()||source.length>524288||Regex("<!\\s*(DOCTYPE|ENTITY)",RegexOption.IGNORE_CASE).containsMatchIn(source))return emptyMap()
        val aliases=mapOf("Make" to "Make","Model" to "Model","Lens" to "LensModel","LensModel" to "LensModel","FNumber" to "FNumber","ApertureValue" to "ApertureValue","ExposureTime" to "ExposureTime","ShutterSpeedValue" to "ShutterSpeedValue","FocalLength" to "FocalLength","FocalLengthIn35mmFilm" to "FocalLengthIn35mmFilm","FocalLengthIn35mmFormat" to "FocalLengthIn35mmFilm","ISOSpeedRatings" to "PhotographicSensitivity","PhotographicSensitivity" to "PhotographicSensitivity","DateTimeOriginal" to "DateTimeOriginal","OffsetTimeOriginal" to "OffsetTimeOriginal")
        fun canonical(name:String,namespace:String?):String?=if(listOf("exif","tiff","aux").any{namespace?.contains(it,true)==true})aliases[name]else null
        return runCatching{
            val out=linkedMapOf<String,String>();val parser=Xml.newPullParser().apply{setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);setInput(StringReader(source))}
            data class Entry(val key:String,val depth:Int,val text:StringBuilder=StringBuilder())
            val pending=mutableListOf<Entry>()
            while(parser.eventType!=XmlPullParser.END_DOCUMENT){
                when(parser.eventType){
                    XmlPullParser.START_TAG->{
                        canonical(parser.name,parser.namespace)?.let{pending.add(Entry(it,parser.depth))}
                        for(i in 0 until parser.attributeCount)canonical(parser.getAttributeName(i),parser.getAttributeNamespace(i))?.let{out.putIfAbsent(it,parser.getAttributeValue(i).trim())}
                    }
                    XmlPullParser.TEXT->pending.forEach{it.text.append(parser.text).append(' ')}
                    XmlPullParser.END_TAG->{val finished=pending.filter{it.depth==parser.depth};for(entry in finished){val value=entry.text.toString().trim();if(value.isNotBlank())out.putIfAbsent(entry.key,if(entry.key=="PhotographicSensitivity")value.split(Regex("\\s+")).first()else value)};pending.removeAll(finished.toSet())}
                };parser.next()
            };out
        }.getOrDefault(emptyMap())
    }
}
