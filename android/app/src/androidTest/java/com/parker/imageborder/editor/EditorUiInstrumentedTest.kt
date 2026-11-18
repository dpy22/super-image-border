package com.parker.imageborder.editor

import android.graphics.*
import android.net.Uri
import android.os.*
import android.view.PixelCopy
import androidx.lifecycle.ViewModelProvider
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import android.content.Intent
import android.app.Activity
import android.app.Application
import java.util.concurrent.atomic.AtomicReference
import androidx.test.platform.app.InstrumentationRegistry
import com.parker.imageborder.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EditorUiInstrumentedTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EditorApplication
    private val dir get()=File(app.cacheDir,"acceptance").apply{mkdirs()}
    private fun waitUntil(condition:()->Boolean){val end=SystemClock.elapsedRealtime()+10_000;while(!condition()){check(SystemClock.elapsedRealtime()<end){"等待界面超时"};SystemClock.sleep(50)}}
    @Test fun groupedHistoryAndDraftPersistence()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val prefs=app.editorPreferences.data.first();val key=stringPreferencesKey("last_document");val previous=prefs[key]
        val store=androidx.lifecycle.ViewModelStore();lateinit var vm:EditorViewModel
        instrumentation.runOnMainSync{vm=EditorViewModel(app);store.put("acceptance",vm)}
        try{
            waitUntil{vm.initialized};val before=vm.document
            instrumentation.runOnMainSync{
                assertFalse(vm.canUndo)
                vm.beginGesture();vm.change(before.copy(id=uid(),caption="尚未离开输入框"),true)
                assertTrue("Pending typing must be undoable",vm.canUndo);vm.undo();assertEquals(before,vm.document)
                vm.change(EditDocument(caption="草稿验收"));val start=vm.document
                vm.beginGesture();repeat(20){vm.change(vm.document.copy(title="Title $it"),true)};assertTrue("Typing enables Undo before focus leaves",vm.canUndo);vm.undo();assertEquals(start.title,vm.document.title)
                vm.redo();assertEquals("Title 19",vm.document.title)
            }
            delay(1000);val saved=app.database.records().get(vm.document.id);assertNotNull(saved)
            assertEquals("Title 19",EditDocument.read(org.json.JSONObject(saved.payload)).title)
            app.database.records().delete(vm.document.id)
            instrumentation.runOnMainSync{
                val assets=(0 until 3).map{PhotoAsset(uri="file:///unused-$it",name="$it",width=100,height=80)}
                vm.change(EditDocument(assets=assets,active=1));vm.removeAsset(assets[0].id);assertEquals(assets[1].id,vm.document.current?.id)
                vm.removeAsset(assets[1].id);assertEquals(assets[2].id,vm.document.current?.id)
                vm.removeAsset(assets[2].id);assertTrue(vm.document.assets.isEmpty());assertNull(vm.preview)
                vm.undo();assertEquals(assets[2].id,vm.document.current?.id)
            }
        }finally{
            instrumentation.runOnMainSync{store.clear()}
            app.applicationScope.coroutineContext[Job]?.children?.toList()?.forEach{it.join()}
            app.editorPreferences.edit{if(previous==null)it.remove(key)else it[key]=previous}
        }
    }
    @Test fun templateContactSheet(){
        val source=File(dir,"fixture-400-300.png");val asset=Assets.inspect(app,Uri.fromFile(source)).copy(exif=mapOf("Make" to "CAMERA","Model" to "PHOTO","FocalLengthIn35mmFilm" to "35","FNumber" to "2.8","ExposureTime" to "1/125","PhotographicSensitivity" to "200"))
        val contact=Bitmap.createBitmap(1600,3200,Bitmap.Config.ARGB_8888);val c=Canvas(contact);c.drawColor(Color.rgb(35,39,36))
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=24f}
        for((i,t) in app.templates.withIndex()){
            val doc=t.apply(EditDocument(assets=List(4){asset.copy(id=uid())},title="光影札记",caption=if(t.family==6)"山川与光影\n记录旅途中的每一个瞬间。"else ""))
            val start=SystemClock.elapsedRealtime();val preview=app.renderer.preview(doc,360);val elapsed=SystemClock.elapsedRealtime()-start
            val x=(i%4)*400f;val y=(i/4)*400f;val scale=minOf(360f/preview.bitmap.width,330f/preview.bitmap.height)
            val width=preview.bitmap.width*scale;val height=preview.bitmap.height*scale
            c.drawBitmap(preview.bitmap,null,RectF(x+200-width/2,y+170-height/2,x+200+width/2,y+170+height/2),p)
            c.drawText("${i+1}. ${t.name}",x+20,y+380,p);preview.bitmap.recycle()
            File(dir,"metrics.txt").appendText("${t.id} 360px preview ${elapsed}ms\n")
        }
        File(dir,"templates-32.png").outputStream().use{contact.compress(Bitmap.CompressFormat.PNG,100,it)};contact.recycle()
    }
}
