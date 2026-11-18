package com.parker.imageborder.editor

import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.nio.*
import java.util.zip.*
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class EditorInstrumentedTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as EditorApplication
    private val dir get()=File(app.cacheDir,"acceptance").apply{mkdirs()}
    private fun fixture(w:Int=400,h:Int=300):PhotoAsset {
        val file=File(dir,"fixture-$w-$h.png")
        if(!file.exists()){
            val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val c=Canvas(b)
            c.drawColor(Color.rgb(46,92,80))
            val p=Paint();p.shader=LinearGradient(0f,0f,w.toFloat(),h.toFloat(),intArrayOf(Color.rgb(23,54,65),Color.rgb(228,189,129),Color.rgb(75,117,99)),null,Shader.TileMode.CLAMP);c.drawRect(0f,0f,w.toFloat(),h.toFloat(),p)
            p.shader=null;p.color=Color.rgb(237,207,152);c.drawCircle(w*.7f,h*.28f,w*.10f,p)
            p.color=Color.rgb(36,69,59);c.drawPath(Path().apply{moveTo(0f,h*.9f);lineTo(w*.3f,h*.35f);lineTo(w*.6f,h*.8f);lineTo(w*.8f,h*.55f);lineTo(w.toFloat(),h.toFloat());close()},p)
            file.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
        }
        return Assets.inspect(app,Uri.fromFile(file))
    }
    private fun export(doc:EditDocument,onProgress:(Float)->Unit={}):List<File> = runBlocking {
        app.cancelExport.set(false);val results=mutableListOf<File>()
        RenderExporter(app).renderFiles(doc,onProgress){f,i,_->val out=File(dir,"result-${uid()}-$i.${f.extension}");f.copyTo(out);results.add(out)}
        results
    }
    @Test fun allTemplatesAcrossOrientations(){
        assertEquals(32,app.templates.size)
        for((w,h) in listOf(400 to 300,300 to 400,360 to 360)){
            val asset=fixture(w,h).copy(exif=mapOf("Make" to "REDMI","Model" to "K90 Pro Max 超长型号测试中文署名与多行参数", "FocalLengthIn35mmFilm" to "35","FNumber" to "18/10","ExposureTime" to "1/125","PhotographicSensitivity" to "200","Artist" to "摄影者：中文长署名"))
            for(t in app.templates){
                val d=t.apply(EditDocument(assets=List(4){asset.copy(id=uid())},caption="山川与光影\n在旅途中记录日常，中文和 Latin 文字混合测试。",title="光影札记"))
                val p=app.renderer.preview(d,260)
                assertTrue(t.id,p.bitmap.width>0&&p.bitmap.height>0)
                val s=p.layout
                for(n in s.photos){assertTrue("${t.id} horizontal",n.rect.left>=0&&n.rect.right<=s.width);assertTrue("${t.id} vertical",n.rect.top>=0&&n.rect.bottom<=s.height)}
                p.bitmap.recycle()
            }
        }
    }
    @Test fun pngPhotoPixelsAndOrientation(){
        val asset=fixture(401,303);val source=BitmapFactory.decodeFile(Uri.parse(asset.uri).path)
        for(orientation in 1..8){
            val a=asset.copy(orientation=orientation,width=if(orientation>=5)303 else 401,height=if(orientation>=5)401 else 303)
            val doc=app.templates.first().apply(EditDocument(assets=listOf(a),export=ExportOptions(mode="original",format="png")))
            val s=app.renderer.layout(doc);val node=s.photos.single();val file=export(doc).single();val result=BitmapFactory.decodeFile(file.path)
            assertEquals(s.width.toInt(),result.width);assertEquals(s.height.toInt(),result.height)
            val expected=Bitmap.createBitmap(a.width,a.height,Bitmap.Config.ARGB_8888)
            Canvas(expected).drawBitmap(source,Assets.orientationMatrix(orientation,401f,303f),Paint())
            val p=IntArray(a.width*a.height);val q=IntArray(p.size);expected.getPixels(p,0,a.width,0,0,a.width,a.height)
            result.getPixels(q,0,a.width,node.rect.left.toInt(),node.rect.top.toInt(),a.width,a.height)
            assertArrayEquals("orientation=$orientation preserves every subject pixel",p,q)
            val ex=ExifInterface(file);assertEquals(1,ex.getAttributeInt(ExifInterface.TAG_ORIENTATION,0))
            result.recycle();expected.recycle();file.delete()
        };source.recycle()
    }
    @Test fun metadataSafetyAndOverrides(){
        for(make in listOf("REDMI","SONY","Canon","NIKON CORPORATION","FUJIFILM")){
            val f=File(dir,"$make.jpg");val b=BitmapFactory.decodeFile(Uri.parse(fixture().uri).path)
            f.outputStream().use{b.compress(Bitmap.CompressFormat.JPEG,100,it)};b.recycle()
            ExifInterface(f).apply{setAttribute("Make",make);setAttribute("Model","Camera X");setAttribute("FocalLength","50/1");setAttribute("FocalLengthIn35mmFilm","75");setAttribute("FNumber","28/10");setAttribute("ExposureTime","1/250");setAttribute("PhotographicSensitivity","400");setAttribute("DateTimeOriginal","2026:10:26 08:30:00");setAttribute("OffsetTimeOriginal","+08:00");setLatLong(31.23,121.47);setAttribute("BodySerialNumber","PRIVATE-123");saveAttributes()}
            val a=Assets.inspect(app,Uri.fromFile(f));assertEquals(make,a.exif["Make"]);assertFalse(a.exif.containsKey("GPSLatitude"));assertFalse(a.exif.containsKey("BodySerialNumber"))
            var d=EditDocument(assets=listOf(a),metadata=mapOf("Artist" to "我的署名"),export=ExportOptions(mode="original",format="jpeg"))
            assertTrue(Assets.caption(d).second.contains("75 mm eq."));assertTrue(Assets.caption(d).second.contains("ISO 400"))
            d=d.copy(overrides=mapOf(a.id to PhotoOverride(values=mapOf("Model" to "手动型号"),hidden=setOf("FocalLengthIn35mmFilm"))))
            assertTrue(Assets.caption(d).first.contains("手动型号"));assertTrue(Assets.caption(d).second.contains("50 mm"))
            val out=export(d).single();val ex=ExifInterface(out);assertNull(ex.latLong);assertNull(ex.getAttribute("BodySerialNumber"));assertEquals(make,ex.getAttribute("Make"));assertEquals("Camera X",ex.getAttribute("Model"));out.delete()
        }
    }
    @Test fun stripeAndSplitMatchWholeComposition(){
        val asset=fixture(900,680)
        val doc=app.templates.first{it.id=="glass-float"}.apply(EditDocument(assets=listOf(asset),layers=listOf(WatermarkLayer(text="光影 · Photo",angle=-20f,tiled=true,opacity=.2f)),export=ExportOptions(mode="original",format="png")))
        val whole=export(doc).single();val full=BitmapFactory.decodeFile(whole.path)
        val parts=export(doc.copy(export=doc.export.copy(gridSplit=true)))
        val bounds=RenderExporter(app).bounds(full.width,full.height,doc.export.copy(gridSplit=true))
        assertEquals(9,parts.size)
        for((i,file)in parts.withIndex()){
            val b=BitmapFactory.decodeFile(file.path);val r=bounds[i];val expected=IntArray(b.width*b.height);val actual=IntArray(expected.size)
            full.getPixels(expected,0,b.width,r.left,r.top,b.width,b.height);b.getPixels(actual,0,b.width,0,0,b.width,b.height)
            assertArrayEquals("tile $i",expected,actual);b.recycle();file.delete()
        };full.recycle();whole.delete()
    }
    @Test fun ultrahdrRebuildsGainmap(){
        val w=512;val h=384;val space=ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val base=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888,false,space);Canvas(base).drawColor(Color.rgb(160,135,90))
        val high=NativeCodec.hdrBitmap(w,h,1);val buffer=ByteBuffer.allocateDirect(high.byteCount).order(ByteOrder.nativeOrder())
        for(y in 0 until h)for(x in 0 until w){val boost=if(x>w/2)3.2f else .5f;buffer.putShort(android.util.Half.toHalf(boost));buffer.putShort(android.util.Half.toHalf(boost*.8f));buffer.putShort(android.util.Half.toHalf(boost*.5f));buffer.putShort(android.util.Half.toHalf(1f))}
        buffer.rewind();high.copyPixelsFromBuffer(buffer)
        val source=File(dir,"hdr-source.jpg");NativeCodec.encodeHdr(base,high,source.path,95,1,null);base.recycle();high.recycle()
        val asset=Assets.inspect(app,Uri.fromFile(source));assertTrue("Input gainmap",asset.hdr)
        val doc=app.templates.first{it.id=="plate"}.apply(EditDocument(assets=listOf(asset),metadata=mapOf("Make" to "TEST","Model" to "HDR Camera"),layers=listOf(WatermarkLayer(text="HDR 测试",x=.5f,y=.94f,color=Color.BLACK,shadow=false)),export=ExportOptions(mode="original",format="hdr")))
        val output=export(doc).single();val decoded=ImageDecoder.decodeBitmap(ImageDecoder.createSource(output)){d,_,_->d.allocator=ImageDecoder.ALLOCATOR_SOFTWARE}
        assertTrue("Output gainmap",decoded.hasGainmap());assertEquals(0,decoded.width%2);decoded.recycle()
        val linear=NativeCodec.decodeHdr(output.path,0)
        val r=app.renderer.layout(doc).photos.single().rect
        val raw=ByteBuffer.allocateDirect(linear.byteCount).order(ByteOrder.nativeOrder());linear.copyPixelsToBuffer(raw)
        fun red(x:Int,y:Int)=android.util.Half.toFloat(raw.getShort(y*linear.rowBytes+x*8))
        val left=red((r.left+100).toInt(),(r.top+100).toInt());val right=red((r.left+400).toInt(),(r.top+100).toInt())
        assertTrue("HDR highlights retained: left=$left right=$right",right>left*2&&right>2f)
        val border=red(4,4);assertTrue("White border remains SDR white: $border",border in .7f..1.3f)
        linear.recycle();output.delete()
    }
    @Test fun fiftyMegapixelStreaming(){
        val source=File(dir,"50mp-source.jpg");val w=8000;val h=6250
        if(!source.exists()){
            val strip=Bitmap.createBitmap(w,128,Bitmap.Config.ARGB_8888);strip.eraseColor(Color.rgb(78,121,154))
            val handle=NativeCodec.start(source.path,w,h,false,98,null);var y=0
            while(y<h){val rows=min(128,h-y);NativeCodec.write(handle,strip,rows);y+=rows};NativeCodec.finish(handle,true);strip.recycle()
        }
        val a=Assets.inspect(app,Uri.fromFile(source));assertEquals(50_000_000,a.width*a.height)
        val d=app.templates.first().apply(EditDocument(assets=listOf(a),export=ExportOptions(mode="original",format="jpeg")))
        val before=SystemClock.elapsedRealtime();val heap=Debug.getNativeHeapAllocatedSize();var peakPss=0
        val out=export(d){val memory=Debug.MemoryInfo();Debug.getMemoryInfo(memory);peakPss=max(peakPss,memory.totalPss)}.single()
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(out.path,bounds)
        val s=app.renderer.layout(d);assertEquals(s.width.toInt(),bounds.outWidth);assertEquals(s.height.toInt(),bounds.outHeight)
        val metric="50MP export ${bounds.outWidth}x${bounds.outHeight}; ${SystemClock.elapsedRealtime()-before}ms; process peak PSS ${peakPss/1024}MB; native heap delta ${(Debug.getNativeHeapAllocatedSize()-heap)/1024/1024}MB"
        println(metric);File(dir,"metrics.txt").appendText(metric+"\n")
        out.delete()
    }
    @Test fun hdrTwelveMegapixelPhoto(){
        val small=File(dir,"hdr-source.jpg");if(!small.exists())ultrahdrRebuildsGainmap()
        val large=File(dir,"hdr-12mp.jpg")
        if(!large.exists()){
            val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(small)){d,_,_->d.allocator=ImageDecoder.ALLOCATOR_SOFTWARE;d.setTargetSize(4000,3000)}
            assertTrue(bitmap.hasGainmap());large.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it))};bitmap.recycle()
        }
        val asset=Assets.inspect(app,Uri.fromFile(large));assertTrue("12MP fixture retains Ultra HDR",asset.hdr)
        val doc=app.templates.first().apply(EditDocument(assets=listOf(asset),export=ExportOptions(mode="original",format="hdr")))
        val start=SystemClock.elapsedRealtime();var peak=0
        val output=export(doc){val mem=Debug.MemoryInfo();Debug.getMemoryInfo(mem);peak=max(peak,mem.totalPss)}.single()
        val decoded=ImageDecoder.decodeBitmap(ImageDecoder.createSource(output)){d,_,_->d.allocator=ImageDecoder.ALLOCATOR_SOFTWARE;d.setTargetSize(16,16)}
        assertTrue("12MP framed output gainmap",decoded.hasGainmap());decoded.recycle()
        File(dir,"metrics.txt").appendText("12MP HDR export ${SystemClock.elapsedRealtime()-start}ms; sampled process PSS ${peak/1024}MB\n");output.delete()
    }
    @Test fun zoomViewportUsesOriginalRegions(){
        val asset=fixture(900,680);val doc=app.templates.first().apply(EditDocument(assets=listOf(asset)))
        val scene=app.renderer.layout(doc);val node=scene.photos.single()
        val viewport=app.renderer.viewport(doc,320,240,1f,-node.rect.left-200,-node.rect.top-160)
        val raw=BitmapFactory.decodeFile(Uri.parse(asset.uri).path);val expected=IntArray(320*240);val actual=IntArray(expected.size)
        raw.getPixels(expected,0,320,200,160,320,240);viewport.getPixels(actual,0,320,0,0,320,240)
        assertArrayEquals(expected,actual);raw.recycle();viewport.recycle()
    }
    @Test fun wideGamutAndColorAdjustments(){
        for(named in listOf(ColorSpace.Named.DISPLAY_P3,ColorSpace.Named.ADOBE_RGB)){
            val space=ColorSpace.get(named);val b=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888,false,space)
            val raw=ByteBuffer.allocate(b.byteCount).order(ByteOrder.LITTLE_ENDIAN)
            for(y in 0 until b.height)for(x in 0 until b.width)raw.putInt(0xff2080f0.toInt())
            raw.rewind();b.copyPixelsFromBuffer(raw)
            val file=File(dir,"wide-${named.name}.png");val handle=NativeCodec.start(file.path,320,240,true,100,ColorProfiles.encode(space as ColorSpace.Rgb))
            NativeCodec.write(handle,b,b.height);NativeCodec.finish(handle,true);b.recycle()
            val asset=Assets.inspect(app,Uri.fromFile(file));assertTrue(asset.colorSpace,asset.colorSpace.contains(if(named==ColorSpace.Named.DISPLAY_P3)"P3"else "Adobe",true))
            for(format in listOf("png","jpeg")){
                val doc=app.templates.first().apply(EditDocument(assets=listOf(asset),export=ExportOptions(mode="original",format=format,quality=100,profile="source")))
                val output=export(doc).single();val decoded=ImageDecoder.decodeBitmap(ImageDecoder.createSource(output)){d,_,_->d.allocator=ImageDecoder.ALLOCATOR_SOFTWARE}
                assertTrue("$named $format",decoded.colorSpace?.isWideGamut==true)
                val node=app.renderer.layout(doc).photos.single();val color=decoded.getColor(node.rect.left.toInt()+40,node.rect.top.toInt()+40)
                assertEquals("$named $format red",240/255f,color.red(),.025f);assertEquals("$named $format green",128/255f,color.green(),.025f)
                val adjusted=ImageEffects.adjust(decoded,Adjustments(saturation=.1f));assertEquals(decoded.colorSpace,adjusted.colorSpace)
                adjusted.recycle();decoded.recycle();output.delete()
            }
        }
    }
    @Test fun streamingAt12And24Megapixels(){
        for((w,h)in listOf(4000 to 3000,6000 to 4000)){
            val source=File(dir,"${w}x$h-source.jpg");val strip=Bitmap.createBitmap(w,128,Bitmap.Config.ARGB_8888);strip.eraseColor(Color.rgb(120,95,70))
            val handle=NativeCodec.start(source.path,w,h,false,95,null);var y=0
            while(y<h){val rows=min(128,h-y);NativeCodec.write(handle,strip,rows);y+=rows};NativeCodec.finish(handle,true);strip.recycle()
            val doc=app.templates.first().apply(EditDocument(assets=listOf(Assets.inspect(app,Uri.fromFile(source))),export=ExportOptions(mode="original",format="jpeg")))
            val start=SystemClock.elapsedRealtime();val output=export(doc).single();val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(output.path,bounds)
            val expected=app.renderer.layout(doc).output(doc.export);assertEquals(expected.first,bounds.outWidth);assertEquals(expected.second,bounds.outHeight)
            File(dir,"metrics.txt").appendText("${w*h/1_000_000}MP export ${bounds.outWidth}x${bounds.outHeight}; ${SystemClock.elapsedRealtime()-start}ms\n");output.delete()
        }
    }
    @Test fun presetsAndUntrustedAssets(){
        val f=File(dir,"preset.zip");f.outputStream().use{PresetPackages.export(app,EditDocument(caption="中文预设",layers=listOf(WatermarkLayer(text="作者"))),"个人样式",it)}
        val imported=PresetPackages.import(app,Uri.fromFile(f));assertEquals("个人样式",imported.first);assertEquals("中文预设",imported.second.caption);assertTrue(imported.second.assets.isEmpty())
        val evil=File(dir,"evil.zip");ZipOutputStream(evil.outputStream()).use{it.putNextEntry(ZipEntry("../escape"));it.write(byteArrayOf(1));it.closeEntry()}
        assertTrue(runCatching{PresetPackages.import(app,Uri.fromFile(evil))}.isFailure)
        val svg=File(dir,"evil.svg");svg.writeText("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>")
        assertTrue(runCatching{PresetPackages.importOverlay(app,Uri.fromFile(svg))}.isFailure)
    }
    @Test fun metadataBarsNeverCoverPhotos(){
        val asset=fixture()
        for(t in app.templates){
            val doc=t.apply(EditDocument(assets=listOf(asset))).copy(showMetadata=true)
            val scene=app.renderer.layout(doc)
            scene.metadata?.let{bar->for(photo in scene.photos)assertFalse(t.id,RectF.intersects(bar,photo.rect))}
            val roundtrip=EditDocument.read(doc.json());assertTrue(roundtrip.showMetadata)
            val preview=app.renderer.preview(doc,240);preview.bitmap.recycle()
        }
    }
    private suspend fun waitJob(id:String):JSONObject = withTimeout(120_000){
        while(true){delay(80);val j=JSONObject(app.database.records().get(id).payload)
            if(j.optString("status") in listOf("done","failed","paused"))return@withTimeout j}
        error("unreachable")
    }
    private fun outputs(root:JSONObject)=root.getJSONArray("items").objects().flatMap{item->val values=item.getJSONArray("outputs");(0 until values.length()).map{values.getString(it)}}.toSet()
    private suspend fun cleanupJob(id:String){
        val root=app.database.records().get(id)?.let{JSONObject(it.payload)}
        root?.let{for(value in outputs(it))runCatching{app.contentResolver.delete(Uri.parse(value),null,null)}}
        app.database.records().delete(id)
    }
    @Test fun failedItemCanBeRetriedWithoutRepeatingSuccesses()=runBlocking {
        val asset=fixture(100,80);val broken=File(dir,"corrupt.jpg").apply{writeText("not an image")}
        val good=EditDocument(assets=listOf(asset));val bad=good.copy(assets=listOf(asset.copy(id=uid(),uri=Uri.fromFile(broken).toString())))
        val items=JSONArray(listOf(good,bad,good).map{JSONObject().put("document",it.json()).put("status","waiting").put("outputs",JSONArray())})
        val id="acceptance-${uid()}";app.record(id,"job","验收临时失败任务",JSONObject().put("version",1).put("status","waiting").put("items",items).toString())
        try{
            ExportService.start(app,id);val failed=waitJob(id);assertEquals("failed",failed.getString("status"))
            assertEquals("failed",failed.getJSONArray("items").getJSONObject(1).getString("status"));val saved=outputs(failed);assertEquals(2,saved.size)
            failed.getJSONArray("items").getJSONObject(1).put("document",good.json());failed.put("status","waiting")
            app.record(id,"job","验收临时失败任务",failed.toString());delay(300);ExportService.start(app,id)
            val done=waitJob(id);assertEquals("done",done.getString("status"));val after=outputs(done);assertEquals(3,after.size);assertTrue(after.containsAll(saved))
        }finally{cleanupJob(id)}
    }
    @Test fun partialSplitTaskCanPauseAndResume()=runBlocking {
        val doc=app.templates.first{it.id=="glass-float"}.apply(EditDocument(assets=listOf(fixture(2700,2100)),export=ExportOptions(mode="original",split=3)))
        val items=JSONArray((0 until 3).map{JSONObject().put("document",doc.json()).put("status","waiting").put("outputs",JSONArray())})
        val id="acceptance-${uid()}";app.record(id,"job","验收临时暂停任务",JSONObject().put("version",1).put("status","waiting").put("items",items).toString())
        try{
            ExportService.start(app,id)
            withTimeout(30_000){while(outputs(JSONObject(app.database.records().get(id).payload)).isEmpty())delay(25)}
            app.cancelExport.set(true);val paused=waitJob(id);assertEquals("paused",paused.getString("status"));val saved=outputs(paused);assertTrue(saved.isNotEmpty())
            paused.put("status","waiting");app.record(id,"job","验收临时暂停任务",paused.toString());delay(300);ExportService.start(app,id)
            val done=waitJob(id);assertEquals("done",done.getString("status"));assertEquals(9,outputs(done).size);assertTrue(outputs(done).containsAll(saved))
        }finally{cleanupJob(id);app.cancelExport.set(false)}
    }
    @Test fun queue100ResumesWithoutDuplicateImages()=runBlocking {
        val a=fixture(100,80);val id="acceptance-${uid()}";val items=JSONArray((0 until 100).map{JSONObject().put("document",EditDocument(assets=listOf(a),export=ExportOptions(mode="original")).json()).put("status","waiting").put("outputs",JSONArray())})
        val root=JSONObject().put("version",1).put("status","waiting").put("items",items)
        app.record(id,"job","验收临时任务",root.toString());ExportService.start(app,id)
        suspend fun waitDone(){withTimeout(120_000){while(true){delay(150);val j=JSONObject(app.database.records().get(id).payload);if(j.optString("status") in listOf("done","failed","paused"))break}}}
        val outputs=mutableSetOf<String>()
        try{
            waitDone();val result=JSONObject(app.database.records().get(id).payload);assertEquals("done",result.getString("status"))
            for(item in result.getJSONArray("items").objects()){assertEquals("done",item.getString("status"));outputs.add(item.getJSONArray("outputs").getString(0))}
            assertEquals(100,outputs.size)
            // Simulate process death between publication and marking the first item complete.
            result.getJSONArray("items").getJSONObject(0).put("status","running");result.put("status","paused")
            app.record(id,"job","验收临时任务",result.toString());delay(200);ExportService.start(app,id);waitDone()
            val resumed=JSONObject(app.database.records().get(id).payload);assertEquals("done",resumed.getString("status"))
            val after=resumed.getJSONArray("items").objects().map{it.getJSONArray("outputs").getString(0)}.toSet();assertEquals(outputs,after)
        }finally{for(uri in outputs)app.contentResolver.delete(Uri.parse(uri),null,null);app.database.records().delete(id)}
    }
}
