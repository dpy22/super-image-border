package com.parker.imageborder.editor

import com.parker.imageborder.R

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.security.MessageDigest
import kotlin.math.*

object Assets {
    val fieldNames = linkedMapOf("Make" to "品牌", "Model" to "相机型号", "LensModel" to "镜头", "FocalLength" to "实际焦距",
        "FocalLengthIn35mmFilm" to "等效焦距", "FNumber" to "光圈", "ExposureTime" to "快门", "PhotographicSensitivity" to "ISO",
        "DateTimeOriginal" to "拍摄日期", "OffsetTimeOriginal" to "时区", "Artist" to "摄影者", "LocationText" to "地点文字")
    val safeTags = fieldNames.keys - "LocationText" + listOf("Copyright", "ExposureBiasValue", "WhiteBalance", "Flash", "LensMake")
    fun exif(context:Context, uri:Uri):ExifInterface = runCatching{
        context.contentResolver.openFileDescriptor(uri,"r").use{descriptor->requireNotNull(descriptor){appText(R.string.ui_cannot_read_this_photo_relink_the_original_d7a804)};ExifInterface(descriptor.fileDescriptor)}
    }.getOrElse{
        context.contentResolver.openInputStream(uri).use{stream->requireNotNull(stream){appText(R.string.ui_cannot_read_this_photo_relink_the_original_d7a804)};ExifInterface(stream)}
    }
    fun readMetadata(exif:ExifInterface)=MetadataReader.read(exif)
    fun inspect(context:Context, uri:Uri):PhotoAsset {
        val name = runCatching{context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst()) c.getString(0) else null}}.getOrNull() ?: uri.lastPathSegment ?: appText(R.string.ui_photo_48e9e5)
        val opts = BitmapFactory.Options().apply{inJustDecodeBounds=true}
        context.contentResolver.openInputStream(uri).use{BitmapFactory.decodeStream(it,null,opts)}
        val exif = runCatching{exif(context,uri)}.getOrNull()
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION,1)?:1
        var width=opts.outWidth;var height=opts.outHeight;var space="sRGB";var hdr=false
        val small=ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,uri)){decoder,info,_->
            if(width<=0){width=info.size.width;height=info.size.height}
            space=info.colorSpace?.name?:"sRGB"
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            val s=min(1.0,256.0/max(info.size.width,info.size.height))
            decoder.setTargetSize(max(1,(info.size.width*s).roundToInt()),max(1,(info.size.height*s).roundToInt()))
        }
        if(Build.VERSION.SDK_INT>=34)hdr=small.hasGainmap()
        small.recycle()
        require(width>0&&height>0){appText(R.string.ui_unsupported_image_format_export_raw_as_jpg_or_heif_119e37)}
        val tags=exif?.let(::readMetadata)?:emptyMap()
        if(orientation in 5..8){val t=width;width=height;height=t}
        return PhotoAsset(uri=uri.toString(),name=name,width=width,height=height,orientation=orientation,colorSpace=space,hdr=hdr,exif=tags)
    }
    fun preview(context:Context, asset:PhotoAsset, maxEdge:Int=2048):Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,Uri.parse(asset.uri))){d,i,_->
        d.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        d.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        val s=min(1.0,maxEdge.toDouble()/max(i.size.width,i.size.height))
        d.setTargetSize(max(1,(i.size.width*s).roundToInt()),max(1,(i.size.height*s).roundToInt()))
    }.also{if(Build.VERSION.SDK_INT>=34&&it.hasGainmap())it.gainmap=null}
    fun rational(value:String):Double?=value.toDoubleOrNull()?:value.split('/').takeIf{it.size==2}?.let{p->
        val a=p[0].toDoubleOrNull();val b=p[1].toDoubleOrNull();if(a!=null&&b!=null&&b!=0.0)a/b else null}
    fun display(key:String,value:String):String = when(key){
        "FNumber"->rational(value)?.takeIf{it.isFinite()}?.let{"f/"+if(it==floor(it))it.toInt().toString()else "%.1f".format(java.util.Locale.US,it)}?:value
        "FocalLength","FocalLengthIn35mmFilm"->rational(value)?.takeIf{it.isFinite()}?.let{java.math.BigDecimal.valueOf(it).setScale(3,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()+" mm"}?:value
        "ExposureTime"->rational(value)?.takeIf{it.isFinite()}?.let{if(it>0&&it<1)"1/${(1/it).roundToInt()} s" else java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString()+" s"}?:value
        "PhotographicSensitivity"->"ISO $value"
        "DateTimeOriginal"->value.replaceFirst(Regex("^(\\d{4}):(\\d{2}):(\\d{2})"),"$1-$2-$3")
        else->value
    }
    fun editorValue(key:String,value:String)=when(key){
        "DateTimeOriginal"->display(key,value)
        "FocalLength","FocalLengthIn35mmFilm","FNumber"->if(value.contains('/'))rational(value)?.takeIf{it.isFinite()}?.let{java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString()}?:value else value
        else->value
    }
    fun caption(doc:EditDocument,asset:PhotoAsset?=doc.current):Pair<String,String> {
        val content=MetadataTypography.content(doc,asset)
        return content.camera to (content.settings+listOf(content.lens)+content.extra).filter{it.isNotBlank()}.joinToString("  ·  ")
    }

    @Synchronized fun cacheFile(context:Context,uri:String):File {
        val digest=MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString(""){"%02x".format(it)}
        val dir=File(context.cacheDir,"sources").apply{mkdirs()};val file=File(dir,digest)
        if(!file.exists())context.contentResolver.openInputStream(Uri.parse(uri)).use{input->
            requireNotNull(input){appText(R.string.ui_original_unavailable_relink_the_photo_d8c607)}
            val tmp=File(dir,"$digest.tmp")
            try{tmp.outputStream().use{input.copyTo(it)};check(tmp.renameTo(file))}finally{tmp.delete()}
        }
        file.setLastModified(System.currentTimeMillis());return file
    }
    fun orientationMatrix(orientation:Int,w:Float,h:Float):Matrix = Matrix().apply{setValues(when(orientation){
        2->floatArrayOf(-1f,0f,w,0f,1f,0f,0f,0f,1f)
        3->floatArrayOf(-1f,0f,w,0f,-1f,h,0f,0f,1f)
        4->floatArrayOf(1f,0f,0f,0f,-1f,h,0f,0f,1f)
        5->floatArrayOf(0f,1f,0f,1f,0f,0f,0f,0f,1f)
        6->floatArrayOf(0f,-1f,h,1f,0f,0f,0f,0f,1f)
        7->floatArrayOf(0f,-1f,h,-1f,0f,w,0f,0f,1f)
        8->floatArrayOf(0f,1f,0f,-1f,0f,w,0f,0f,1f)
        else->floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)})}
}

object ImageEffects {
    fun adjust(bitmap:Bitmap,a:Adjustments):Bitmap {
        if(a.copy(vignette=0f).isNeutral)return bitmap
        val b=bitmap.copy(Bitmap.Config.ARGB_8888,true)
        val rgb=b.colorSpace as? ColorSpace.Rgb
        val multiplier=2.0.pow(a.exposure.toDouble())
        val exposure=FloatArray(256){index->
            val v=index/255f
            if(a.exposure==0f)v else {
                val linear=rgb?.eotf?.applyAsDouble(v.toDouble())?:v.toDouble().pow(2.2)
                (rgb?.oetf?.applyAsDouble(linear*multiplier)?:(linear*multiplier).coerceAtLeast(0.0).pow(1/2.2)).toFloat()
            }
        }
        NativeCodec.adjustPixels(b,floatArrayOf(a.contrast,a.saturation,a.warmth,a.tint,a.highlights,a.shadows,a.sharpness,a.intensity),exposure,Adjustments.legacyFilters.indexOf(a.filter).coerceAtLeast(0))
        if(a.intensity>0f)FilmLooks.table(a.filter,b.colorSpace?:ColorSpace.get(ColorSpace.Named.SRGB))?.let{NativeCodec.applyLook(b,it,a.intensity)}
        return b
    }
    fun palette(bitmap:Bitmap):List<Int>{
        val sample=Bitmap.createScaledBitmap(bitmap,64,64,true);val p=IntArray(4096);sample.getPixels(p,0,64,0,0,64,64);if(sample!==bitmap)sample.recycle()
        val bins=mutableMapOf<Int,IntArray>()
        for(c in p){if(Color.alpha(c)<128)continue;val k=((Color.red(c)/32)shl 6)or((Color.green(c)/32)shl 3)or(Color.blue(c)/32)
            val b=bins.getOrPut(k){IntArray(4)};b[0]++;b[1]+=Color.red(c);b[2]+=Color.green(c);b[3]+=Color.blue(c)}
        val colors=bins.values.sortedByDescending{it[0]}.map{Color.rgb(it[1]/it[0],it[2]/it[0],it[3]/it[0])}.take(5).toMutableList()
        if(colors.isEmpty())colors.add(Color.GRAY);while(colors.size<5)colors.add(colors.last())
        return colors
    }
    fun blur(bitmap:Bitmap,amount:Float):Bitmap{
        val smallEdge=360
        val scale=smallEdge.toFloat()/max(bitmap.width,bitmap.height)
        val small=Bitmap.createScaledBitmap(bitmap,max(1,(bitmap.width*scale).roundToInt()),max(1,(bitmap.height*scale).roundToInt()),true)
        val w=small.width;val h=small.height;var pixels=IntArray(w*h);small.getPixels(pixels,0,w,0,0,w,h)
        val radius=(amount*16).roundToInt().coerceAtLeast(1)
        repeat(3){
            for(horizontal in listOf(true,false)){
                val out=IntArray(pixels.size);val count=radius*2+1
                val rows=if(horizontal)h else w;val length=if(horizontal)w else h
                fun index(row:Int,column:Int)=if(horizontal)row*w+column else column*w+row
                for(row in 0 until rows){
                    var r=0;var g=0;var b=0
                    for(k in -radius..radius){val c=pixels[index(row,k.coerceIn(0,length-1))];r+=Color.red(c);g+=Color.green(c);b+=Color.blue(c)}
                    for(x in 0 until length){out[index(row,x)]=Color.rgb(r/count,g/count,b/count)
                        val old=pixels[index(row,(x-radius).coerceIn(0,length-1))];val next=pixels[index(row,(x+radius+1).coerceIn(0,length-1))]
                        r+=Color.red(next)-Color.red(old);g+=Color.green(next)-Color.green(old);b+=Color.blue(next)-Color.blue(old)}
                };pixels=out
            }
        }
        val result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);result.setPixels(pixels,0,w,0,0,w,h)
        if(small!==bitmap)small.recycle();return result
    }
}
