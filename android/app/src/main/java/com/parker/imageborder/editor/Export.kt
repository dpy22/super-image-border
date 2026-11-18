package com.parker.imageborder.editor

import com.parker.imageborder.R

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.*
import org.json.*
import java.io.*
import kotlin.math.*

object MetadataWriter {
    fun write(context:Context,file:File,doc:EditDocument,width:Int,height:Int){
        val exif=ExifInterface(file)
        val singlePhoto=!doc.textOnly&&((context.applicationContext as EditorApplication).renderer.template(doc.template).family!=7||doc.assets.size==1)
        if(doc.export.keepExif&&singlePhoto){doc.current?.exif?.forEach{(tag,value)->if(tag in Assets.safeTags)exif.setAttribute(tag,value)}}
        if(doc.export.keepGps&&singlePhoto){
            doc.current?.let{asset->runCatching{Assets.exif(context,Uri.parse(asset.uri))}.getOrNull()?.let{source->
                for(tag in listOf("GPSLatitude","GPSLatitudeRef","GPSLongitude","GPSLongitudeRef","GPSAltitude","GPSAltitudeRef","GPSDateStamp","GPSTimeStamp"))source.getAttribute(tag)?.let{exif.setAttribute(tag,it)}
            }}
        }
        exif.setAttribute(ExifInterface.TAG_ORIENTATION,"1")
        exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION,width.toString());exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION,height.toString())
        exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH,width.toString());exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH,height.toString())
        exif.setAttribute(ExifInterface.TAG_SOFTWARE,"com.parker.imageborder ${com.parker.imageborder.BuildConfig.VERSION_NAME}")
        exif.saveAttributes()
    }
    fun block(context:Context,doc:EditDocument,width:Int,height:Int):ByteArray {
        val file=File.createTempFile("exif-",".jpg",context.cacheDir)
        try{
            val b=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);file.outputStream().use{b.compress(Bitmap.CompressFormat.JPEG,95,it)};b.recycle()
            write(context,file,doc,width,height)
            DataInputStream(file.inputStream()).use{input->input.readUnsignedShort()
                while(true){if(input.readUnsignedByte()!=255)break;val marker=input.readUnsignedByte();if(marker==0xda||marker==0xd9)break
                    val size=input.readUnsignedShort()-2;val data=ByteArray(size);input.readFully(data)
                    if(marker==0xe1&&data.size>=6&&String(data,0,4,Charsets.US_ASCII)=="Exif")return data}
            }
            return ByteArray(0)
        }finally{file.delete()}
    }
    fun jpegIcc(file:File):ByteArray?=runCatching{
        val chunks=sortedMapOf<Int,ByteArray>();var count=0
        DataInputStream(file.inputStream()).use{input->if(input.readUnsignedShort()!=0xffd8)return null
            while(true){if(input.readUnsignedByte()!=255)break;var marker=input.readUnsignedByte();while(marker==255)marker=input.readUnsignedByte()
                if(marker==0xda||marker==0xd9)break;val n=input.readUnsignedShort()-2;if(n<0)break
                val bytes=ByteArray(n);input.readFully(bytes)
                if(marker==0xe2&&n>=14&&String(bytes,0,11,Charsets.US_ASCII)=="ICC_PROFILE"){chunks[bytes[12].toInt()and 255]=bytes.copyOfRange(14,n);count=bytes[13].toInt()and 255}}
        }
        if(chunks.isEmpty()||chunks.size!=count)null else ByteArrayOutputStream().apply{chunks.values.forEach{write(it)}}.toByteArray()
    }.getOrNull()
}

class RenderExporter(private val context:Context){
    private val app=context.applicationContext as EditorApplication
    private val renderer get()=app.renderer
    fun bounds(w:Int,h:Int,options:ExportOptions):List<Rect>{
        val cols=if(options.gridSplit)3 else options.split;val rows=if(options.gridSplit)3 else 1
        return (0 until rows).flatMap{y->(0 until cols).map{x->Rect(x*w/cols,y*h/rows,(x+1)*w/cols,(y+1)*h/rows)}}
    }
    private fun targetSpace(doc:EditDocument):Pair<ColorSpace,ByteArray?> {
        if(doc.export.profile!="source"||doc.current==null)return ColorSpace.get(ColorSpace.Named.SRGB) to null
        var source:ColorSpace?=null
        val b=ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,Uri.parse(doc.current!!.uri))){d,i,_->source=i.colorSpace;d.allocator=ImageDecoder.ALLOCATOR_SOFTWARE;d.setTargetSize(1,1)};b.recycle()
        val space=source?:ColorSpace.get(ColorSpace.Named.SRGB)
        if(space.isSrgb)return space to null
        require(listOf("P3","Adobe","sRGB").any{space.name.contains(it,true)}){appText(R.string.ui_cannot_preserve_this_gamut_choose_srgb_cf0336)}
        val rgb=space as? ColorSpace.Rgb?:error(appText(R.string.ui_cannot_preserve_this_color_space_choose_srgb_763866))
        return space to ColorProfiles.encode(rgb)
    }
    suspend fun renderFiles(doc:EditDocument,onProgress:(Float)->Unit,onFile:suspend(File,Int,Int)->Unit){
        require(doc.assets.isNotEmpty()||doc.textOnly){appText(R.string.ui_no_photo_or_text_to_export_a3e215)}
        val scene=renderer.layout(doc);val size=scene.output(doc.export)
        require(size.first<=60000&&size.second<=60000){appText(R.string.ui_output_exceeds_60_000_pixels_per_edge_choose_a_sma_17b67a)}
        if(doc.export.format=="hdr"||doc.export.mode=="hdr"){
            if(Build.VERSION.SDK_INT<34)error(appText(R.string.ui_hdr_export_requires_android_14_or_later_62e2c2))
            require(!doc.export.gridSplit&&doc.export.split==1){appText(R.string.ui_use_jpg_to_split_images_hdr_supports_a_whole_image_884100)}
            require(doc.adjustments.isNeutral){appText(R.string.ui_restore_original_colors_for_hdr_export_creative_ad_ba525a)}
            val file=File.createTempFile("hdr-export-",".jpg",context.cacheDir)
            try{renderHdr(doc,scene,0,file);onProgress(1f);onFile(file,0,1)}finally{file.delete()};return
        }
        val (space,icc)=targetSpace(doc);val resources=renderer.resources(doc)
        val pieces=bounds(size.first,size.second,doc.export)
        val scale=size.first/scene.width
        for((pieceIndex,piece)in pieces.withIndex()){
            currentCoroutineContext().ensureActive()
            val file=File.createTempFile("export-",if(doc.export.format=="png")".png" else ".jpg",context.cacheDir)
            var handle=0L
            val stripeHeight=min(256,max(16,8*1024*1024/(size.first*4))).coerceAtMost(size.second)
            val stripe=Bitmap.createBitmap(size.first,stripeHeight,Bitmap.Config.ARGB_8888,true,space)
            try{
                handle=NativeCodec.start(file.path,piece.width(),piece.height(),doc.export.format=="png",doc.export.quality,icc)
                RegionPixels(context,space).use{pixels->
                    var row=0
                    while(row<piece.height()){
                        currentCoroutineContext().ensureActive();check(!app.cancelExport.get()){ appText(R.string.ui_export_paused_resume_from_export_tasks_983653) }
                        // Every output piece uses the same full-width, globally aligned render stripes.
                        // Clip-dependent blur/text rasterization therefore matches the unsplit image.
                        val globalRow=piece.top+row;val stripeTop=globalRow/stripeHeight*stripeHeight
                        val offset=globalRow-stripeTop;val rows=min(stripeHeight-offset,piece.height()-row)
                        stripe.eraseColor(Color.TRANSPARENT)
                        renderer.draw(Canvas(stripe),doc,scene,pixels,scale,resources,stripeTop)
                        NativeCodec.writeRegion(handle,stripe,piece.left,offset,rows);row+=rows
                        onProgress((pieceIndex+row/piece.height().toFloat())/pieces.size)
                    }
                }
                val owned=handle;handle=0;NativeCodec.finish(owned,true)
                MetadataWriter.write(context,file,doc,piece.width(),piece.height())
                onFile(file,pieceIndex,pieces.size)
            }finally{if(handle!=0L)NativeCodec.finish(handle,false);stripe.recycle();file.delete()}
        }
    }
    fun hdrPreview(doc:EditDocument,maxEdge:Int=1536):PreviewFrame {
        if(Build.VERSION.SDK_INT<34)error(appText(R.string.ui_hdr_preview_requires_android_14_or_later_035393))
        val scene=renderer.layout(doc);val file=File.createTempFile("hdr-preview-",".jpg",context.cacheDir)
        try{
            // Display the single floating-point scene. Preview never composites a JPEG base
            // and gainmap a second time through a device-specific display shader.
            val bitmap=renderHdr(doc,scene,maxEdge,file,previewOnly=true)?:error(appText(R.string.ui_hdr_preview_failed_7967ca))
            return PreviewFrame(bitmap,scene,renderer.resources(doc).second)
        }finally{file.delete()}
    }
    @androidx.annotation.RequiresApi(34)
    private fun renderHdr(doc:EditDocument,scene:SceneLayout,previewEdge:Int,file:File,previewOnly:Boolean=false):Bitmap?{
        require(Build.VERSION.SDK_INT>=34){appText(R.string.ui_hdr_requires_android_14_or_later_124c08)}
        require(scene.photos.any{it.asset.hdr}){appText(R.string.ui_this_photo_has_no_supported_hdr_gain_map_28bc52)}
        require(doc.adjustments.isNeutral){appText(R.string.ui_restore_original_colors_for_hdr_preview_and_export_94e41e)}
        val selected=if(previewEdge>0)doc.export.copy(mode="custom",maxEdge=previewEdge)else if(doc.export.mode=="hdr")doc.export.copy(mode="original")else doc.export
        val (ow,oh)=scene.output(selected.copy(format="png"));val width=if(previewOnly)ow else (ow+1)/2*2;val height=if(previewOnly)oh else (oh+1)/2*2
        val manager=context.getSystemService(ActivityManager::class.java);val mem=ActivityManager.MemoryInfo();manager.getMemoryInfo(mem)
        fun workPixels(node:PhotoNode):Long {
            val ratio=if(previewEdge>0)min(1.0,previewEdge.toDouble()/max(node.asset.width,node.asset.height))else 1.0
            return (node.asset.width*node.asset.height.toDouble()*ratio*ratio).toLong()
        }
        val outputPixels=width.toLong()*height
        val largestSource=scene.photos.maxOfOrNull{workPixels(it)}?:0
        val sourceBytes=scene.photos.sumOf{workPixels(it)*if(it.asset.hdr)8 else 4}
        val estimate=maxOf(sourceBytes+largestSource*16,sourceBytes+outputPixels*12+largestSource*4,outputPixels*40)+64L*1024*1024
        val budget=min(1024L*1024*1024,mem.availMem/4)
        require(estimate<=budget){appText(R.string.ui_hdr_needs_about_1_s_mb_of_memory_choose_a_smaller__7048d2, estimate/1024/1024)}
        val images=mutableMapOf<String,Bitmap>();val borrowed=mutableSetOf<Bitmap>();var base:Bitmap?=null;var hdr:Bitmap?=null
        try{
            for(node in scene.photos){
                val asset=node.asset
                images[asset.id]=if(asset.hdr){
                    if(previewOnly)renderer.hdrPreviewSource(asset,previewEdge).also{borrowed.add(it)}
                    else HdrSourceDecoder.decode(context,asset,0)
                }
                    else {
                        // RegionPixels expects encoded orientation; remove the orientation already applied by ImageDecoder.
                        val raw=BitmapFactory.decodeFile(Assets.cacheFile(context,asset.uri).path,BitmapFactory.Options().apply{
                            if(previewEdge>0){var sample=1;while(max(asset.width,asset.height)/sample>previewEdge*2)sample*=2;inSampleSize=sample}
                        })?:error(appText(R.string.ui_cannot_read_a_photo_in_this_hdr_scene_bf2833))
                        raw
                    }
            }
            val first=images.values.firstOrNull{it.config==Bitmap.Config.RGBA_F16}
            val color=first?.colorSpace?:ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
            val gamut=NativeCodec.gamut(color)
            if(!previewOnly)base=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888,false,ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
            hdr=Bitmap.createBitmap(width,height,Bitmap.Config.RGBA_F16,false,color)
            // The extra row/column required by JPEG is padding, not a scale operation.
            val resources=renderer.resources(doc);val scale=ow/scene.width
            if(!previewOnly)RegionPixels(context,ColorSpace.get(ColorSpace.Named.DISPLAY_P3)).use{renderer.draw(Canvas(base!!),doc,scene,it,scale,resources)}
            BitmapPixels(images).use{renderer.draw(Canvas(hdr),doc,scene,it,scale,resources)}
            images.values.filterNot{it in borrowed}.forEach{it.recycle()};images.clear()
            if(previewOnly){val result=hdr;hdr=null;return result}
            NativeCodec.encodeHdr(base!!,hdr,file.path,doc.export.quality,gamut,MetadataWriter.block(context,doc,width,height))
            val verified=ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)){d,_,_->d.allocator=ImageDecoder.ALLOCATOR_SOFTWARE;d.setTargetSize(16,16)}
            val valid=Build.VERSION.SDK_INT>=34&&verified.hasGainmap();verified.recycle();check(valid){appText(R.string.ui_hdr_gain_map_verification_failed_file_not_saved_bb4e60)}
        }finally{images.values.filterNot{it in borrowed}.forEach{it.recycle()};base?.recycle();hdr?.recycle()}
        return null
    }
}

class ExportService:Service(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var running:Job?=null
    private val app get()=application as EditorApplication
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action=="pause"){app.cancelExport.set(true);return START_NOT_STICKY}
        if(running?.isActive==true)return START_NOT_STICKY
        val id=intent?.getStringExtra("job")?:return START_NOT_STICKY
        app.cancelExport.set(false)
        if(Build.VERSION.SDK_INT>=35)startForeground(42,notification(appText(R.string.ui_preparing_export_6e17d6)),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        running=scope.launch{runQueue(id);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
        return START_NOT_STICKY
    }
    private suspend fun runQueue(id:String){
        val record=app.database.records().get(id)?:return
        val root=JSONObject(record.payload);val items=root.getJSONArray("items");val allOutputs=mutableListOf<String>();var completed=0
        suspend fun checkpoint(){withContext(NonCancellable){app.record(id,"job",record.title,root.toString())}}
        try{
            root.put("status","running");checkpoint()
            for(index in 0 until items.length()){
                val item=items.getJSONObject(index);val outputs=item.optJSONArray("outputs")?:JSONArray().also{item.put("outputs",it)}
                if(item.optString("status")=="done"){completed++;(0 until outputs.length()).forEach{allOutputs.add(outputs.getString(it))};continue}
                if(app.cancelExport.get())break
                try{
                    val doc=EditDocument.read(item.getJSONObject("document"));item.put("status","running");item.put("error","");checkpoint()
                    RenderExporter(app).renderFiles(doc,{fraction->
                        val text="${index+1}/${items.length()} · ${(fraction*100).toInt()}%"
                        app.progress.value=ExportProgress(true,completed,items.length(),text,id,allOutputs.toList())
                        getSystemService(NotificationManager::class.java).notify(42,notification(text))
                    }){file,part,total->
                        if(part<outputs.length()){
                            val uri=Uri.parse(outputs.getString(part));val pending=app.contentResolver.query(uri,arrayOf(MediaStore.Images.Media.IS_PENDING),null,null,null)?.use{c->if(c.moveToFirst())c.getInt(0) else 1}?:1
                            if(pending==0){allOutputs.add(uri.toString());return@renderFiles}else runCatching{app.contentResolver.delete(uri,null,null)}
                        }
                        val mime=if(file.extension=="png")"image/png"else "image/jpeg"
                        val name="灵感边框-${System.currentTimeMillis()}-${id.take(6)}-${index+1}-${part+1}.${file.extension}"
                        val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,name);put(MediaStore.Images.Media.MIME_TYPE,mime);put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/文字拼图");put(MediaStore.Images.Media.IS_PENDING,1)}
                        val uri=app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:error(appText(R.string.ui_cannot_create_an_image_in_the_gallery_5280ec))
                        outputs.put(part,uri.toString());checkpoint()
                        try{
                            app.contentResolver.openOutputStream(uri).use{out->requireNotNull(out);file.inputStream().use{it.copyTo(out)}}
                            check(app.contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)>0)
                            allOutputs.add(uri.toString());checkpoint()
                        }catch(e:Exception){app.contentResolver.delete(uri,null,null);throw e}
                    }
                    item.put("status","done");completed++
                }catch(e:OutOfMemoryError){item.put("status","failed").put("error",appText(R.string.ui_not_enough_memory_choose_a_smaller_output_size_61d3b0))}
                catch(e:CancellationException){item.put("status","paused").put("error",appText(R.string.ui_task_paused_e4f239));checkpoint();throw e}
                catch(e:Exception){item.put("status",if(app.cancelExport.get())"paused"else "failed").put("error",e.message?:appText(R.string.ui_export_failed_675338))}
                checkpoint()
            }
            val failed=(0 until items.length()).count{items.getJSONObject(it).optString("status")=="failed"}
            root.put("status",if(app.cancelExport.get())"paused"else if(failed>0)"failed"else "done");checkpoint()
            app.progress.value=ExportProgress(false,completed,items.length(),if(app.cancelExport.get())appText(R.string.ui_paused_resume_from_export_tasks_e9e647)else if(failed>0)appText(R.string.ui_saved_1_s_failed_2_s_view_export_tasks_for_details_fc093a, completed, failed)else appText(R.string.ui_saved_to_gallery_1_s_51dd07, completed),id,allOutputs.toList())
        }catch(e:Exception){root.put("status","paused");checkpoint();app.progress.value=ExportProgress(false,completed,items.length(),e.message?:appText(R.string.ui_task_paused_e4f239),id,allOutputs)}
    }
    private fun notification(text:String):Notification{
        val manager=getSystemService(NotificationManager::class.java);manager.createNotificationChannel(NotificationChannel("exports",appText(R.string.ui_photo_export_5d752c),NotificationManager.IMPORTANCE_LOW))
        val pause=PendingIntent.getService(this,1,Intent(this,ExportService::class.java).setAction("pause"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open=PendingIntent.getActivity(this,0,Intent(this,com.parker.imageborder.MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this,"exports").setSmallIcon(com.parker.imageborder.R.drawable.ic_notification_frame).setContentTitle(appText(R.string.ui_exporting_2f866b)).setContentText(text).setOngoing(true).setContentIntent(open).addAction(0,appText(R.string.ui_pause_130448),pause).build()
    }
    override fun onTimeout(startId:Int,fgsType:Int){app.cancelExport.set(true);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    override fun onDestroy(){scope.cancel();super.onDestroy()}
    companion object {
        fun start(context:Context,id:String){val intent=Intent(context,ExportService::class.java).putExtra("job",id)
            if(Build.VERSION.SDK_INT>=35)context.startForegroundService(intent)else context.startService(intent)}
    }
}
