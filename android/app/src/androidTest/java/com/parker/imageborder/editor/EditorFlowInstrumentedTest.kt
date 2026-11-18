package com.parker.imageborder.editor

import android.graphics.*
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.net.Uri
import android.os.SystemClock
import androidx.datastore.preferences.core.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import androidx.lifecycle.ViewModelProvider
import com.parker.imageborder.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Real controls and real screenshots; no ActivityScenario / synthetic lifecycle. */
class EditorFlowInstrumentedTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as EditorApplication
    private val device get()=UiDevice.getInstance(instrumentation)
    private val dir get()=File(app.cacheDir,"flow-audit").apply{mkdirs()}
    private val pkg="com.parker.imageborder"
    private var uiModel:EditorViewModel?=null
    private suspend fun ownerRecord():EditorRecord? {
        val current=app.editorPreferences.data.first()[stringPreferencesKey("last_document")]?.let{app.database.records().get(it)}
        fun isOwner(r:EditorRecord):Boolean=runCatching{r.title!="界面回归检查"&&EditDocument.read(JSONObject(r.payload)).assets.any{!it.uri.startsWith(Uri.fromFile(app.cacheDir).toString())&&!it.uri.contains("acceptance")&&!it.uri.contains("file:///unused")}}.getOrDefault(false)
        return current?.takeIf(::isOwner)?:app.database.records().list("draft").firstOrNull(::isOwner)
    }
    private fun waitFor(test:()->Boolean){val end=SystemClock.elapsedRealtime()+15_000;while(!test()){check(SystemClock.elapsedRealtime()<end){"等待编辑器超时"};SystemClock.sleep(40)}}
    private fun click(text:String){
        check(device.currentPackageName==pkg){"编辑器不在前台"}
        repeat(20){
            try{
                val node=device.wait(Until.findObject(By.pkg(pkg).text(text)),600)
                if(node!=null&&!node.visibleBounds.isEmpty){node.click();device.waitForIdle(500);return}
                val panel=device.findObject(By.res("tool-options"))?:return@repeat
                val bounds=panel.visibleBounds
                val start=bounds.top+(bounds.height()*.75f).toInt();val end=start-(bounds.height()*.12f).toInt()
                device.swipe(bounds.centerX(),start,bounds.centerX(),end,24)
            }catch(_:StaleObjectException){SystemClock.sleep(100)}
        };shot("missing-control").recycle();error("找不到可见控件 $text；HDR素材=${uiModel?.document?.current?.hdr}，已初始化=${uiModel?.initialized}")
    }
    private fun clickDescription(description:String){check(device.currentPackageName==pkg);val node=device.wait(Until.findObject(By.pkg(pkg).desc(description)),5_000)?:error("找不到 $description");node.click();device.waitForIdle(500)}
    private fun idle(){val vm=requireNotNull(uiModel);waitFor{vm.preview!=null&&!vm.rendering};device.waitForIdle(500);SystemClock.sleep(160)}
    private fun editor():UiObject2=device.wait(Until.findObject(By.pkg(pkg).clazz("android.widget.EditText")),4_000)?:error("找不到文字输入框")
    private fun photo():PhotoAsset {
        val bitmap=BitmapFactory.decodeResource(app.resources,R.drawable.preview_photo);val file=File(dir,"sample-photo.png")
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        return Assets.inspect(app,Uri.fromFile(file)).copy(exif=mapOf("Make" to "CAMERA","Model" to "PHOTO","FocalLengthIn35mmFilm" to "35","FNumber" to "2.8","ExposureTime" to "1/125","PhotographicSensitivity" to "200"))
    }
    private fun shot(name:String):Bitmap {
        check(device.currentPackageName==pkg){"停止截图：编辑器不在前台"}
        val file=File(dir,"$name.png");check(device.takeScreenshot(file));if(device.currentPackageName!=pkg){file.delete();error("停止截图：编辑器已不在前台")}
        return BitmapFactory.decodeFile(file.path)
    }
    private fun hdrSource():PhotoAsset {
        val w=600;val h=800
        val base=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888,false,ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
        val canvas=Canvas(base);canvas.drawColor(Color.rgb(100,100,100))
        val paint=Paint();val patches=listOf(Rect(60,100,180,220) to Color.RED,Rect(370,540,520,690) to Color.GREEN,Rect(370,100,520,220) to Color.BLUE)
        for((r,c)in patches){paint.color=c;canvas.drawRect(r,paint)}
        val high=NativeCodec.hdrBitmap(w,h,1);val bytes=ByteBuffer.allocateDirect(high.byteCount).order(ByteOrder.nativeOrder())
        for(y in 0 until h)for(x in 0 until w){val color=patches.firstOrNull{it.first.contains(x,y)}?.second
            val channels=when(color){Color.RED->floatArrayOf(2f,0f,0f);Color.GREEN->floatArrayOf(0f,2f,0f);Color.BLUE->floatArrayOf(0f,0f,2f);else->floatArrayOf(.13f,.13f,.13f)}
            for(v in channels)bytes.putShort(android.util.Half.toHalf(v));bytes.putShort(android.util.Half.toHalf(1f))}
        bytes.rewind();high.copyPixelsFromBuffer(bytes)
        val file=File(dir,"geometry-hdr.jpg");NativeCodec.encodeHdr(base,high,file.path,95,1,null);base.recycle();high.recycle()
        return Assets.inspect(app,Uri.fromFile(file))
    }
    private fun marker(bitmap:Bitmap,channel:Int):Rect {
        val result=Rect(bitmap.width,bitmap.height,0,0)
        // Restrict to the photo viewport; tool icons and thumbnails are excluded.
        for(y in 350 until minOf(1220,bitmap.height))for(x in 200 until minOf(1000,bitmap.width)){
            val c=bitmap.getPixel(x,y);val v=intArrayOf(Color.red(c),Color.green(c),Color.blue(c))
            if(v[channel]>65&&v[channel]>v[(channel+1)%3]*1.8f+20&&v[channel]>v[(channel+2)%3]*1.8f+20){
                result.left=minOf(result.left,x);result.top=minOf(result.top,y);result.right=maxOf(result.right,x);result.bottom=maxOf(result.bottom,y)
            }
        };return result
    }
    private suspend fun withDocument(doc:EditDocument,block:suspend()->Unit){
        val key=stringPreferencesKey("last_document");val owner=ownerRecord();val previous=owner?.id
        owner?.let{val backup=File(dir,"user-document-original.json");if(!backup.exists())backup.writeText(it.payload)}
        var activity:Activity?=null;var destroyed=false
        val observer=object:Application.ActivityLifecycleCallbacks{
            override fun onActivityCreated(a:Activity,b:Bundle?){if(a.javaClass.name=="com.parker.imageborder.MainActivity"){activity=a;destroyed=false}}
            override fun onActivityDestroyed(a:Activity){if(a===activity)destroyed=true}
            override fun onActivityStarted(a:Activity){};override fun onActivityResumed(a:Activity){};override fun onActivityPaused(a:Activity){}
            override fun onActivityStopped(a:Activity){};override fun onActivitySaveInstanceState(a:Activity,b:Bundle){}
        }
        app.registerActivityLifecycleCallbacks(observer)
        app.record(doc.id,"draft","界面回归检查",doc.json().toString());app.editorPreferences.edit{it[key]=doc.id}
        try{
            device.executeShellCommand("am start -f 0x10008000 -n $pkg/.MainActivity")
            waitFor{activity!=null&&device.currentPackageName==pkg};waitFor{device.hasObject(By.pkg(pkg).desc("编辑预览"))}
            instrumentation.runOnMainSync{uiModel=ViewModelProvider(requireNotNull(activity) as androidx.activity.ComponentActivity)[EditorViewModel::class.java]}
            waitFor{uiModel?.initialized==true};assertEquals("加载本次检查作品",doc.id,uiModel!!.document.id)
            idle();delay(250);block()
        }finally{
            instrumentation.runOnMainSync{activity?.finish()};if(activity!=null)waitFor{destroyed}
            app.applicationScope.coroutineContext[Job]?.children?.toList()?.forEach{it.join()}
            app.editorPreferences.edit{if(previous==null)it.remove(key)else it[key]=previous}
            app.database.records().delete(doc.id)
            app.unregisterActivityLifecycleCallbacks(observer)
            uiModel=null
        }
    }
    @Test fun hdrSwitchPreservesDisplayedGeometry()=runBlocking {
        val doc=app.templates.first{it.id=="white"}.apply(EditDocument(assets=listOf(hdrSource())))
        withDocument(doc){
            val before=shot("hdr-off")
            click("HDR");idle();val after=shot("hdr-on")
            val log=StringBuilder()
            for(channel in 0..2){val a=marker(before,channel);val b=marker(after,channel);log.append("channel=$channel off=$a on=$b\n")
                assertFalse("SDR marker visible",a.isEmpty);assertFalse("HDR marker visible",b.isEmpty)
                assertTrue("HDR changes photo geometry: $a / $b",kotlin.math.abs(a.centerX()-b.centerX())<8&&kotlin.math.abs(a.centerY()-b.centerY())<8&&kotlin.math.abs(a.width()-b.width())<8&&kotlin.math.abs(a.height()-b.height())<8)
            }
            File(dir,"hdr-screen-geometry.txt").writeText(log.toString());before.recycle();after.recycle()
        }
    }
    @Test fun selectedCameraHdrScreens()=runBlocking {
        val original=EditDocument.read(JSONObject(ownerRecord()?.payload?:error("没有用户草稿")))
        org.junit.Assume.assumeTrue("当前用户选择的是 SDR，真实 HDR 项不适用",original.current?.hdr==true)
        withDocument(original.copy(id=uid(),adjustments=Adjustments())){
            shot("camera-hdr-off").recycle();click("HDR");idle();shot("camera-hdr-on").recycle()
            click("HDR");idle();shot("camera-hdr-off-again").recycle()
        }
    }
    @Test fun watermarkCreationClearDeleteAndUndoUseRealControls()=runBlocking {
        withDocument(app.templates.first().apply(EditDocument(assets=listOf(photo())))){
            val vm=requireNotNull(uiModel);click("水印")
            repeat(3){click("添加文字");clickDescription("完成文字编辑");assertTrue(vm.document.layers.isEmpty());assertFalse(vm.textEditorOpen)}
            click("添加文字");editor().text="水印示例";waitFor{vm.document.layers.size==1};idle();shot("watermark-editing").recycle()
            editor().text="";waitFor{vm.document.layers.isEmpty()};clickDescription("完成文字编辑");idle()
            assertFalse(vm.textEditorOpen);assertNull(vm.selectedLayer);assertFalse(device.hasObject(By.pkg(pkg).clazz("android.widget.EditText")))
            shot("watermark-cleared").recycle()
            click("添加文字");editor().text="水印 A";clickDescription("完成文字编辑")
            click("添加文字");editor().text="水印 B";clickDescription("完成文字编辑");assertEquals(2,vm.document.layers.size)
            clickDescription("删除水印 1");assertEquals(1,vm.document.layers.size);clickDescription("撤销");assertEquals(2,vm.document.layers.size)
        }
    }
    @Test fun paragraphAlignmentFontAndClearingUseRealControls()=runBlocking {
        withDocument(app.templates.first().apply(EditDocument(assets=listOf(photo())))){
            val vm=requireNotNull(uiModel);click("文字");editor().text="图文作品\n正文跟随图片，并保留独立排版。\n第二段文字。"
            waitFor{vm.document.caption.contains("第二段")};device.pressBack();click("文字");click("居中");assertEquals("center",vm.document.textStyle.alignment)
            click("衬线");assertEquals("serif",vm.document.textStyle.font);idle();shot("paragraph-styled").recycle()
            val height=vm.preview!!.layout.height
            instrumentation.runOnMainSync{vm.change(vm.document.copy(caption=""))};idle();assertNull(vm.preview!!.layout.body);assertTrue(vm.preview!!.layout.height<height)
        }
    }
    @Test fun singlePhotoCollageMetadataSwitchShowsActualBar()=runBlocking {
        val template=app.templates.first{it.id=="diptych"}
        withDocument(template.apply(EditDocument(assets=listOf(photo()),showMetadata=false,metadataCustomized=true))){
            val vm=requireNotNull(uiModel);click("参数");click("显示摄影参数");idle();assertTrue(vm.document.showMetadata);assertNotNull(vm.preview!!.layout.metadata)
            shot("metadata-single-photo").recycle()
        }
    }
    @Test fun browsingDoesNotMovePhotoAndExportActionIsInitiallyVisible()=runBlocking {
        withDocument(app.templates.first().apply(EditDocument(assets=listOf(photo())))){
            val vm=requireNotNull(uiModel);click("调色");val old=vm.document.currentTransform
            val r=device.findObject(By.pkg(pkg).desc("编辑预览")).visibleBounds
            device.swipe(r.centerX(),r.centerY(),r.centerX()+90,r.centerY()+70,18);assertEquals(old,vm.document.currentTransform)
            click("经典负片·青");idle();assertEquals("经典负片·青",vm.document.adjustments.filter);shot("film-looks").recycle()
            click("导出");val save=device.wait(Until.findObject(By.pkg(pkg).text("保存当前作品")),4000)?:error("保存操作不可见")
            assertTrue(save.visibleBounds.height()>40);assertTrue(save.visibleBounds.bottom<=device.displayHeight);shot("export-action-visible").recycle()
        }
    }
    @Test fun transientNoticesKeepWatermarkAndHdrControlsAccessible()=runBlocking {
        withDocument(app.templates.first().apply(EditDocument(assets=listOf(hdrSource())))){
            val vm=requireNotNull(uiModel)
            instrumentation.runOnMainSync{vm.message="操作已完成"};click("水印");click("添加文字");clickDescription("完成文字编辑")
            instrumentation.runOnMainSync{vm.message="设置已保存"};click("HDR");idle();assertTrue(vm.hdrPreview)
            click("参数");click("显示摄影参数");idle();assertTrue(vm.document.showMetadata)
        }
    }
    @Test fun metadataTypingKeepsFocusAndBoundsWhenEmptyBecomesFilled()=runBlocking {
        for(tags in listOf(emptyMap(),mapOf("Make" to "ORIGINAL","DateTimeOriginal" to "2026:10:26 12:00:00"))){
            withDocument(app.templates.first().apply(EditDocument(assets=listOf(photo().copy(exif=tags)),showMetadata=true,metadataCustomized=true))){
                val vm=requireNotNull(uiModel);click("参数")
                var input=device.wait(Until.findObject(By.res("input:品牌")),1_000)
                if(input==null){device.findObject(By.res("tool-options"))?.scroll(Direction.DOWN,.5f);input=device.wait(Until.findObject(By.res("input:品牌")),4_000)}
                requireNotNull(input){"找不到品牌输入框"}
                input.click();input.text="";device.waitForIdle(500);SystemClock.sleep(500)
                var top:Int?=null
                for((index,keyCode) in listOf(android.view.KeyEvent.KEYCODE_A,android.view.KeyEvent.KEYCODE_B,android.view.KeyEvent.KEYCODE_C).withIndex()){
                    device.pressKeyCode(keyCode);SystemClock.sleep(180)
                    val focused=device.findObject(By.pkg(pkg).clazz("android.widget.EditText").focused(true))?:error("输入失焦")
                    assertEquals("abc".take(index+1),focused.text);assertEquals("abc".take(index+1),vm.document.fields()["Make"])
                    if(top==null)top=focused.visibleBounds.top else assertTrue("输入框跳动",kotlin.math.abs(focused.visibleBounds.top-top!!)<3)
                }
                assertEquals("abc",vm.document.fields()["Make"]);device.pressBack();idle();shot("metadata-stable-input").recycle()
            }
        }
    }
    @Test fun exportModesAndResetUseVisibleControls()=runBlocking {
        withDocument(app.templates.first().apply(EditDocument(assets=listOf(photo())))){
            val vm=requireNotNull(uiModel);click("导出");assertTrue(device.wait(Until.hasObject(By.pkg(pkg).text("JPG · 质量 95 · sRGB")),4000))
            click("自定义尺寸");click("PNG");assertEquals("png",vm.document.export.format)
            clickDescription("恢复导出默认");assertEquals(ExportOptions(),vm.document.export)
            shot("export-share-preset").recycle()
        }
    }

    @Test fun watermarkTagsAreCompactAndEffectsShareOneRow()=runBlocking {
        val image=photo()
        withDocument(app.templates.first().apply(EditDocument(assets=listOf(image),layers=listOf(WatermarkLayer(text="测试署名"),WatermarkLayer(text="",uri=image.uri))))){
            val vm=requireNotNull(uiModel);click("水印");click("测试署名");idle();shot("watermark-compact-tags").recycle()
            repeat(10){if(device.findObject(By.pkg(pkg).desc("加粗"))==null)device.findObject(By.res("tool-options"))?.scroll(Direction.DOWN,.5f)}
            val nodes=listOf("加粗","描边","阴影","深色底","锁定位置").map{device.findObject(By.pkg(pkg).desc(it))?:error("样式图标缺失 $it")}
            val centers=nodes.map{it.visibleBounds.centerY()};assertTrue(centers.max()-centers.min()<4)
            nodes.first().click();waitFor{vm.document.layers.first().bold};idle();shot("watermark-effect-icons").recycle()
        }
    }

    @Test fun textAndColorResetAreScopedAndKeepContent()=runBlocking {
        val caption="第一段\n第二段"
        withDocument(app.templates.first().apply(EditDocument(assets=listOf(photo()),caption=caption,textStyle=TextBlockStyle(lineSpacing=1.8f,paragraphSpacing=0f),adjustments=Adjustments(exposure=.6f)))){
            val vm=requireNotNull(uiModel);click("文字");clickDescription("恢复文字默认");idle()
            assertEquals(caption,vm.document.caption);assertEquals(1.15f,vm.document.textStyle.lineSpacing);assertEquals(.5f,vm.document.textStyle.paragraphSpacing)
            assertEquals(.6f,vm.document.adjustments.exposure)
            click("调色");clickDescription("恢复调色默认");idle();assertTrue(vm.document.adjustments.isNeutral);assertEquals(caption,vm.document.caption)
        }
    }
    @Test fun batchExportButtonsFitAboveSystemNavigation()=runBlocking {
        withDocument(app.templates.first().apply(EditDocument(assets=listOf(photo(),photo().copy(id=uid()))))){
            click("导出")
            val save=device.wait(Until.findObject(By.pkg(pkg).text("保存当前作品")),4000)?:error("保存不可见")
            val batch=device.wait(Until.findObject(By.pkg(pkg).text("批量保存 2 张")),4000)?:error("批量保存不可见")
            val density=app.resources.displayMetrics.density
            assertTrue(batch.visibleBounds.height()>=20*density);assertTrue(batch.visibleBounds.bottom<device.displayHeight-12*density)
            assertTrue(batch.visibleBounds.top>save.visibleBounds.bottom);shot("export-batch-safe-area").recycle()
        }
    }

    @Test fun characterWidthModeShowsAutomaticPhotoSizedFont()=runBlocking {
        val style=TextBlockStyle(size=.02f)
        withDocument(app.templates.first{it.id=="white"}.apply(EditDocument(assets=listOf(photo()),caption="中文 OpenAI Photography，标点……（测试）。".repeat(4),textStyle=style))){
            val vm=requireNotNull(uiModel);click("文字");click("按字数");idle()
            assertEquals("chars",vm.document.textStyle.widthMode);assertEquals(.02f,vm.document.textStyle.size)
            val scene=vm.preview!!.layout
            val metrics=CaptionTypography.geometry(vm.document.textStyle,scene.body!!.width(),scene.unit,app.renderer.typeface(style.font,style.bold))
            val label="${metrics.textSize.roundToInt()} px · 自动"
            click(label)
            assertTrue("Automatic font size must be visible",device.hasObject(By.pkg(pkg).text(label)))
            shot("caption-character-width").recycle()
        }
    }

}
