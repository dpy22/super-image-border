package com.parker.imageborder.editor

import android.app.Application
import android.app.Activity
import android.os.Bundle
import android.graphics.Bitmap
import android.view.PixelCopy
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.os.LocaleList
import android.os.SystemClock
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import com.parker.imageborder.R
import java.io.File

class LocalizationInstrumentedTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as EditorApplication
    private val pkg="com.parker.imageborder"
    private val device get()=UiDevice.getInstance(instrumentation)
    private fun waitFor(test:()->Boolean){val end=SystemClock.elapsedRealtime()+15000;while(!test()){check(SystemClock.elapsedRealtime()<end){"App locale transition timed out"};SystemClock.sleep(60)}}
    @Test fun resourceCatalogIsBilingualAndKeepsDocumentKeys(){
        fun resources(tag:String)=app.createConfigurationContext(Configuration(app.resources.configuration).apply{setLocales(LocaleList.forLanguageTags(tag))}).resources
        val english=resources("en");val chinese=resources("zh-Hans")
        assertEquals("Settings",english.getString(R.string.settings_title));assertEquals("设置",chinese.getString(R.string.settings_title))
        val fields=R.string::class.java.fields.filter{it.name.startsWith("ui_")||it.name.startsWith("template_")||it.name.startsWith("settings_")||it.name.startsWith("privacy_")}
        assertTrue(fields.size>400)
        for(field in fields){
            val id=field.getInt(null);val en=english.getString(id);val zh=chinese.getString(id)
            assertTrue("Empty English resource: ${field.name}",en.isNotBlank());assertTrue("Empty Chinese resource: ${field.name}",zh.isNotBlank())
            val slots=Regex("%([0-9]+)@s").findAll(en.replace('$','@')).map{it.groupValues[1].toInt()}.toList()
            if(slots.isNotEmpty())assertFalse("Unformatted resource: ${field.name}",english.getString(id,*Array(slots.max()){1}).replace('$','@').contains(Regex("%[0-9]+@s")))
            assertEquals("Format slots differ: ${field.name}",Regex("%([0-9]+)@s").findAll(zh.replace('$','@')).map{it.value}.toSet(),Regex("%([0-9]+)@s").findAll(en.replace('$','@')).map{it.value}.toSet())
        }
        assertEquals("纯白",app.templates.first{it.id=="white"}.name)
        assertTrue("经典负片·青" in Adjustments.filters)
        val source=EditDocument(caption="原文 OpenAI",layers=listOf(WatermarkLayer(text="我的署名")),adjustments=Adjustments(filter="经典负片·青"))
        assertTrue("Localization changed document values",source==EditDocument.read(source.json()))
        assertEquals("Classic Negative · Cyan",english.getString(R.string.ui_classic_negative_cyan_8c19ee))
    }
    @Test fun languagePickerRecreatesPageAndPreservesCurrentProject(){
        var activity:Activity?=null
        val observer=object:Application.ActivityLifecycleCallbacks{
            override fun onActivityCreated(a:Activity,b:Bundle?){if(a.javaClass.name=="com.parker.imageborder.MainActivity")activity=a}
            override fun onActivityStarted(a:Activity){};override fun onActivityResumed(a:Activity){if(a.javaClass.name=="com.parker.imageborder.MainActivity")activity=a}
            override fun onActivityPaused(a:Activity){};override fun onActivityStopped(a:Activity){};override fun onActivitySaveInstanceState(a:Activity,b:Bundle){};override fun onActivityDestroyed(a:Activity){}
        }
        app.registerActivityLifecycleCallbacks(observer)
        device.executeShellCommand("am start -n $pkg/.MainActivity")
        waitFor{
            instrumentation.runOnMainSync{activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull{it.javaClass.name=="com.parker.imageborder.MainActivity"}?:activity}
            activity!=null&&device.currentPackageName==pkg
        }
        val previous=AppLanguage.selection();lateinit var vm:EditorViewModel
        instrumentation.runOnMainSync{vm=ViewModelProvider(activity as ComponentActivity)[EditorViewModel::class.java]};waitFor{vm.initialized}
        val document=vm.document.json().toString()
        fun unchanged():Boolean {
            lateinit var active:EditorViewModel
            instrumentation.runOnMainSync{active=ViewModelProvider(requireNotNull(activity) as ComponentActivity)[EditorViewModel::class.java]}
            waitFor{active.initialized}
            return document==active.document.json().toString()
        }
        fun click(text:String){
            repeat(5){
                check(device.currentPackageName==pkg)
                try{val target=device.wait(Until.findObject(By.pkg(pkg).text(text)),3000)?:device.wait(Until.findObject(By.pkg(pkg).desc(text)),1000)?:error("Missing app control: $text");target.click();device.waitForIdle(500);SystemClock.sleep(180);return}
                catch(_:StaleObjectException){SystemClock.sleep(100)}
            };error("App control kept changing: $text")
        }
        fun screenshot(name:String){
            check(device.currentPackageName==pkg)
            lateinit var image:Bitmap;val latch=CountDownLatch(1);var result=-1
            instrumentation.runOnMainSync{
                val window=requireNotNull(activity).window
                image=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888)
                PixelCopy.request(window,image,{status->result=status;latch.countDown()},Handler(Looper.getMainLooper()))
            }
            check(latch.await(5,TimeUnit.SECONDS)&&result==PixelCopy.SUCCESS){"App window capture failed"}
            val dir=File(app.cacheDir,"localization-audit").apply{mkdirs()}
            try{File(dir,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{image.recycle()}
        }

        try{
            click(appText(R.string.settings_title));click(appText(R.string.settings_language));click("简体中文")
            waitFor{AppLanguage.selection()=="zh"&&device.hasObject(By.pkg(pkg).text("语言"))};screenshot("settings-zh")
            assertTrue("Project changed after Chinese switch",unchanged())
            click("语言");click("English")
            waitFor{AppLanguage.selection()=="en"&&device.hasObject(By.pkg(pkg).text("Language"))};screenshot("settings-en")
            assertTrue("Project changed after English switch",unchanged())
            click("Language");click("Follow system")
            waitFor{AppLanguage.selection()=="system"&&device.hasObject(By.pkg(pkg).text(appText(R.string.settings_follow_system)))}
            device.waitForIdle(500);screenshot("settings-system")
            assertTrue("Project changed after system switch",unchanged())
            click(appText(R.string.settings_privacy));waitFor{device.hasObject(By.pkg(pkg).text(appText(R.string.privacy_photos_title)))};screenshot("privacy")
            device.pressBack();waitFor{device.hasObject(By.pkg(pkg).text(appText(R.string.settings_language)))}
        }finally{
            instrumentation.runOnMainSync{AppLanguage.set(previous)}
            waitFor{AppLanguage.selection()==previous}
            app.unregisterActivityLifecycleCallbacks(observer)
            if(device.currentPackageName==pkg)device.pressBack()
        }
    }
    @Test fun englishEditorPagesDoNotChangeCurrentProject(){
        val previousLanguage=AppLanguage.selection()
        if(appText(R.string.settings_title)!="Settings")instrumentation.runOnMainSync{AppLanguage.set("en")}
        try{
        waitFor{appText(R.string.settings_title)=="Settings"}
        device.executeShellCommand("am start -n $pkg/.MainActivity")
        waitFor{device.currentPackageName==pkg}
        var activity:Activity?=null
        waitFor{
            instrumentation.runOnMainSync{activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull{it.javaClass.name=="com.parker.imageborder.MainActivity"}}
            activity!=null
        }
        lateinit var vm:EditorViewModel
        instrumentation.runOnMainSync{vm=ViewModelProvider(activity as ComponentActivity)[EditorViewModel::class.java]};waitFor{vm.initialized}
        val before=vm.document.json().toString()
        instrumentation.runOnMainSync{requireNotNull(activity).window.insetsController?.hide(android.view.WindowInsets.Type.ime());requireNotNull(activity).currentFocus?.clearFocus()}
        SystemClock.sleep(400)
        // A prior test or the owner can leave Settings open; return through its own Back action.
        repeat(2){
            val back=device.findObject(By.pkg(pkg).desc(appText(R.string.settings_back)))
            if(back!=null){back.click();device.waitForIdle(500);SystemClock.sleep(350)}
        }
        fun shot(name:String){
            check(device.currentPackageName==pkg)
            lateinit var bitmap:Bitmap;var result=-1;val latch=CountDownLatch(1)
            instrumentation.runOnMainSync{
                val window=requireNotNull(activity).window
                val focused=if(android.os.Build.VERSION.SDK_INT>=34)android.view.inspector.WindowInspector.getGlobalWindowViews().lastOrNull{it.isShown&&it.hasWindowFocus()&&it.context.packageName==pkg}else null
                val view=focused?:window.decorView
                bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                if(android.os.Build.VERSION.SDK_INT>=34)PixelCopy.request(PixelCopy.Request.Builder.ofWindow(view).setDestinationBitmap(bitmap).build(),app.mainExecutor){result=it.status;latch.countDown()}
                else PixelCopy.request(window,bitmap,{result=it;latch.countDown()},Handler(Looper.getMainLooper()))
            }
            check(latch.await(5,TimeUnit.SECONDS)&&result==PixelCopy.SUCCESS)
            try{File(app.cacheDir,"english-layout-audit").apply{mkdirs()}.resolve("$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
        }
        fun chooseTab(label:String){
            val key=if(label=="Templates")"template"else label.lowercase()
            repeat(12){
                val candidate=device.findObject(By.res("editor-tool:$key"))
                if(candidate!=null&&!candidate.visibleBounds.isEmpty){candidate.click();device.waitForIdle(500);SystemClock.sleep(700);return}
                val dock=device.wait(Until.findObject(By.res("editor-tool-dock")),1200)
                if(dock==null){shot("blocked-$key");error("No tool dock while opening $label")}
                val bounds=dock.visibleBounds
                val left=bounds.left+bounds.width()/5;val right=bounds.right-bounds.width()/5
                device.swipe(if(key=="template")left else right,bounds.centerY(),if(key=="template")right else left,bounds.centerY(),18)
            };shot("unreachable-$key");error("Tab unreachable: $label")
        }
        for((index,label)in listOf("Templates","Canvas","Frame","Text","Metadata","Watermark","Adjust").withIndex()){
            chooseTab(label);shot("0${index+1}-"+label.lowercase())
            assertEquals("Browsing changed the current project",before,vm.document.json().toString())
        }
        val export=device.wait(Until.findObject(By.pkg(pkg).text(appText(R.string.ui_export_188896))),3000)?:error("No export button")
        export.click();waitFor{device.hasObject(By.pkg(pkg).desc(appText(R.string.ui_close_export_3122cb)))};device.waitForIdle(500);SystemClock.sleep(700);shot("08-export")
        val close=device.wait(Until.findObject(By.pkg(pkg).desc(appText(R.string.ui_close_export_3122cb))),3000)?:error("No close export")
        close.click();device.waitForIdle(500)
        assertEquals("Export sheet changed the current project",before,vm.document.json().toString())
        }finally{if(AppLanguage.selection()!=previousLanguage)instrumentation.runOnMainSync{AppLanguage.set(previousLanguage)}}
    }

    @Test fun applicationBrandIncludesAdaptiveAndThemedIcons(){
        val context=instrumentation.targetContext;val info=context.packageManager.getApplicationInfo(context.packageName,0)
        assertEquals("灵感边框",context.packageManager.getApplicationLabel(info).toString())
        val icon=context.packageManager.getApplicationIcon(info)
        assertTrue("Adaptive launcher icon",icon is android.graphics.drawable.AdaptiveIconDrawable)
        if(android.os.Build.VERSION.SDK_INT>=33)assertNotNull("Themed monochrome layer",(icon as android.graphics.drawable.AdaptiveIconDrawable).monochrome)
    }

}
