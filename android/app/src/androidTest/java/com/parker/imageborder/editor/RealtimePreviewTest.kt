package com.parker.imageborder.editor

import android.graphics.*
import android.net.Uri
import android.os.SystemClock
import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RealtimePreviewTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as EditorApplication
    private val dir get()=File(app.cacheDir,"acceptance").apply{mkdirs()}
    private suspend fun awaitReady(test:()->Boolean){withTimeout(15_000){while(!test())delay(20)}}
    private fun source():PhotoAsset {
        val file=File(dir,"live-preview-source.png")
        val bitmap=Bitmap.createBitmap(1200,900,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);val paint=Paint().apply{shader=LinearGradient(0f,0f,1200f,900f,Color.RED,Color.BLUE,Shader.TileMode.CLAMP)}
        canvas.drawRect(0f,0f,1200f,900f,paint);paint.shader=null;paint.color=Color.YELLOW;canvas.drawCircle(800f,280f,180f,paint)
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        return Assets.inspect(app,Uri.fromFile(file))
    }
    @Test fun continuousTextAndFilterChangesPublishBeforeInputStops()=runBlocking {
        val key=stringPreferencesKey("last_document");val previous=app.editorPreferences.data.first()[key]
        val store=ViewModelStore();lateinit var vm:EditorViewModel
        val ownIds=mutableSetOf<String>()
        instrumentation.runOnMainSync{vm=EditorViewModel(app);store.put("live-preview",vm)}
        try{
            awaitReady{vm.initialized}
            val asset=source();val layer=WatermarkLayer(text="开始",size=.1f,x=.5f,y=.5f)
            val doc=app.templates.first().apply(EditDocument(assets=listOf(asset),layers=listOf(layer)))
            ownIds.add(doc.id)
            instrumentation.runOnMainSync{vm.change(doc)};awaitReady{!vm.rendering&&vm.preview!=null}
            suspend fun exercise(name:String,edit:(Int)->Unit){
                var previousFrame=vm.preview;val initial=vm.preview!!.bitmap;var frames=0;val start=SystemClock.elapsedRealtime()
                instrumentation.runOnMainSync{vm.beginGesture()}
                repeat(60){index->
                    instrumentation.runOnMainSync{edit(index)};delay(20)
                    if(vm.preview!==previousFrame){frames++;previousFrame=vm.preview}
                }
                assertTrue("$name must publish during input, got $frames frames",frames>=3)
                // Keeping focus in a field must not leave the 768px interaction
                // frame on screen indefinitely. Undo grouping is still open here.
                delay(450);awaitReady{!vm.rendering}
                val expected=withContext(Dispatchers.Default){app.renderer.preview(vm.document,2048)}
                assertTrue("$name must refine while the edit gesture remains open",vm.preview!!.bitmap.sameAs(expected.bitmap));expected.bitmap.recycle()
                instrumentation.runOnMainSync{vm.endGesture()};awaitReady{!vm.rendering}
                assertFalse("$name must visibly change the image",initial.sameAs(vm.preview!!.bitmap))
                File(dir,"metrics.txt").appendText("$name live preview: $frames frames during ${SystemClock.elapsedRealtime()-start}ms of edits and final refinement\n")
            }
            exercise("Text"){i->vm.updateLayer(vm.document.layers.single().copy(text="实时文字 $i"),true)}
            exercise("Filter"){i->vm.change(vm.document.copy(adjustments=Adjustments(filter="暖日",intensity=(i+1)/60f)),true)}
        }finally{
            instrumentation.runOnMainSync{store.clear()}
            app.applicationScope.coroutineContext[Job]?.children?.toList()?.forEach{it.join()}
            app.editorPreferences.edit{if(previous==null)it.remove(key)else it[key]=previous}
            ownIds.forEach{app.database.records().delete(it)}
        }
    }
    @Test fun hdrPreviewIsOneFloatingPointSceneWithStableGeometry(){
        val w=512;val h=384
        val base=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888,false,ColorSpace.get(ColorSpace.Named.DISPLAY_P3));base.eraseColor(Color.rgb(100,100,100))
        val high=NativeCodec.hdrBitmap(w,h,1);val buffer=ByteBuffer.allocateDirect(high.byteCount).order(ByteOrder.nativeOrder())
        for(y in 0 until h)for(x in 0 until w){val value=if(x in 300..420&&y in 70..180)3f else .25f
            repeat(3){buffer.putShort(android.util.Half.toHalf(value))};buffer.putShort(android.util.Half.toHalf(1f))}
        buffer.rewind();high.copyPixelsFromBuffer(buffer)
        val source=File(dir,"live-hdr.jpg");NativeCodec.encodeHdr(base,high,source.path,95,1,null);base.recycle();high.recycle()
        val asset=Assets.inspect(app,Uri.fromFile(source))
        val original=app.templates.first().apply(EditDocument(assets=listOf(asset)))
        val doc=original.copy(frame=original.frame.copy(left=.05f,right=.052f))
        val frame=RenderExporter(app).hdrPreview(doc,2048)
        assertEquals(Bitmap.Config.RGBA_F16,frame.bitmap.config);assertFalse(frame.bitmap.hasGainmap())
        assertEquals(1,NativeCodec.gamut(frame.bitmap.colorSpace!!))
        val rect=frame.layout.photos.single().rect
        // Bitmap.getColor clamps custom RGB spaces to their nominal range. Inspect
        // the actual half-float buffer to measure HDR headroom above SDR white.
        val pixels=ByteBuffer.allocateDirect(frame.bitmap.byteCount).order(ByteOrder.nativeOrder());frame.bitmap.copyPixelsToBuffer(pixels)
        fun red(x:Int,y:Int)=android.util.Half.toFloat(pixels.getShort(y*frame.bitmap.rowBytes+x*8))
        val bright=red((rect.left+350).toInt(),(rect.top+120).toInt())
        val dark=red((rect.left+100).toInt(),(rect.top+120).toInt())
        assertTrue("Highlights retain headroom: $bright/$dark",bright>dark*4&&bright>1f)
        val mixed=doc.copy(assets=listOf(source().copy(hdr=false),asset),active=0)
        assertThrows(IllegalArgumentException::class.java){RenderExporter(app).hdrPreview(mixed,768)}
        frame.bitmap.recycle()
    }
}
