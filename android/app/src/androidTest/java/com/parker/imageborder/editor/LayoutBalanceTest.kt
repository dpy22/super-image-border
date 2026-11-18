package com.parker.imageborder.editor

import android.graphics.*
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.edit

class LayoutBalanceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as EditorApplication
    private val folder get()=File(app.getExternalFilesDir("layout-balance"),InstrumentationRegistry.getArguments().getString("phase","after")).apply{mkdirs()}
    private val ratios=listOf(16 to 9,3 to 2,4 to 3,1 to 1,3 to 4,2 to 3,9 to 16,3 to 1)
    private val tags=mapOf("Make" to "Canon","Model" to "Canon EOS R50","LensModel" to "RF-S18-150mm F3.5-6.3 IS STM","FocalLength" to "18/1","FNumber" to "8/1","ExposureTime" to "1/125","PhotographicSensitivity" to "100","DateTimeOriginal" to "2026:10:27 08:30:00","OffsetTimeOriginal" to "+08:00","Artist" to "林间来信")
    private fun sample(w:Int,h:Int):PhotoAsset {
        val file=File(app.cacheDir,"balance-$w-$h.jpg")
        if(!file.exists()){
            val source=instrumentation.context.assets.open("layout-samples/"+if(w>=h)"lake.jpg"else"flowers.jpg").use{BitmapFactory.decodeStream(it)}!!
            val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
            val scale=max(w.toFloat()/source.width,h.toFloat()/source.height)
            val rect=RectF((w-source.width*scale)/2,(h-source.height*scale)/2,(w+source.width*scale)/2,(h+source.height*scale)/2)
            canvas.drawBitmap(source,null,rect,Paint(Paint.FILTER_BITMAP_FLAG));file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,96,it)};source.recycle();bitmap.recycle()
        }
        return Assets.inspect(app,Uri.fromFile(file)).copy(exif=tags)
    }
    private fun save(bitmap:Bitmap,name:String){File(folder,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
    @Test fun captureDefaultLayoutsAcrossRatios(){
        val templates=listOf("white","plate","split-plate","side-plate","center-plate","glass-light")
        val sheet=Bitmap.createBitmap(templates.size*400,ratios.size*400,Bitmap.Config.ARGB_8888);val canvas=Canvas(sheet);canvas.drawColor(0xffe6e7e7.toInt())
        val label=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=16f;color=Color.DKGRAY}
        ratios.forEachIndexed{row,(rw,rh)->
            val width=if(rw>=rh)1200 else (1200f*rw/rh).roundToInt();val height=(width.toFloat()*rh/rw).roundToInt()
            val photo=sample(width,height)
            templates.forEachIndexed{col,id->
                val doc=app.templates.first{it.id==id}.apply(EditDocument(assets=listOf(photo),showMetadata=true,metadataCustomized=true))
                val preview=app.renderer.preview(doc,1000)
                try{
                    val scale=min(372f/preview.bitmap.width,355f/preview.bitmap.height);val w=preview.bitmap.width*scale;val h=preview.bitmap.height*scale
                    val x=col*400+200f;val y=row*400+180f
                    canvas.drawBitmap(preview.bitmap,null,RectF(x-w/2,y-h/2,x+w/2,y+h/2),Paint(Paint.FILTER_BITMAP_FLAG))
                    canvas.drawText("$id  $rw:$rh",col*400+12f,row*400+388f,label)
                    if(row in listOf(0,1,3,5)&&id in listOf("plate","split-plate","side-plate"))save(preview.bitmap,"$id-$rw-$rh.png")
                }finally{preview.bitmap.recycle()}
            }
        }
        save(sheet,"ratios.png");sheet.recycle()
    }
    @Test fun metadataIsProportionalAndDoesNotShrinkWithExtraFields(){
        for((rw,rh)in ratios){
            val multiple=ceil(600f/rw).toInt();val a=sample(rw*multiple,rh*multiple);val b=sample(rw*multiple*4,rh*multiple*4)
            for(id in listOf("white","plate","split-plate","side-plate","center-plate","glass-light")){
                fun scene(photo:PhotoAsset)=app.renderer.layout(app.templates.first{it.id==id}.apply(EditDocument(assets=listOf(photo),showMetadata=true,metadataCustomized=true)))
                val sa=scene(a);val sb=scene(b)
                assertEquals("$id $rw:$rh normalized width",sa.width/a.width,sb.width/b.width,.002f)
                assertEquals("$id $rw:$rh normalized height",sa.height/a.width,sb.height/b.width,.002f)
                val variant=if(id=="split-plate")1 else if(id=="center-plate")3 else 0
                val ma=MetadataTypography.measure(EditDocument(assets=listOf(a)),width=a.width.toFloat(),reference=MetadataTypography.reference(a.width.toFloat(),a.height.toFloat()),variant=variant)
                val mb=MetadataTypography.measure(EditDocument(assets=listOf(b)),width=b.width.toFloat(),reference=MetadataTypography.reference(b.width.toFloat(),b.height.toFloat()),variant=variant)
                assertEquals(ma.runs.map{it.layout.text.toString()},mb.runs.map{it.layout.text.toString()})
                assertEquals(ma.runs.map{it.layout.lineCount},mb.runs.map{it.layout.lineCount})
                assertEquals(ma.height/a.width,mb.height/b.width,.00001f)
                assertFalse("Normal metadata truncated: $id $rw:$rh",ma.truncated)
                val without=a.copy(exif=tags-"LensModel"-"DateTimeOriginal"-"OffsetTimeOriginal"-"Artist")
                val short=MetadataTypography.measure(EditDocument(assets=listOf(without)),width=a.width.toFloat(),reference=MetadataTypography.reference(a.width.toFloat(),a.height.toFloat()),variant=variant)
                assertEquals("Adding details must not reduce the primary font",short.runs.first().layout.paint.textSize,ma.runs.first().layout.paint.textSize,0f)
            }
        }
    }
    @Test fun captionsKeepLineBreaksAndProportionsAcrossResolution(){
        val text="2022年国庆，彼时的我尚对北京充满新奇感和探索欲。Photography across resolutions.\n第二段，保留文字与间距的视觉比例。"
        for((rw,rh)in ratios)for(mode in listOf("photo","chars","fixed")){
            val style=TextBlockStyle(widthMode=mode,widthRatio=.75f)
            fun block(w:Float):CaptionTypography.Block=CaptionTypography.measure(EditDocument(caption=text,textStyle=style),w,w*min(1f,rh.toFloat()/rw),Typeface.DEFAULT)!!
            val a=block(600f);val b=block(2400f)
            assertEquals("$mode $rw:$rh",a.paragraphs.map{it.text},b.paragraphs.map{it.text})
            assertEquals(a.paragraphs.map{it.layout.lineCount},b.paragraphs.map{it.layout.lineCount})
            assertEquals(a.height/600,b.height/2400,.00001f)
            assertEquals(a.width/600,b.width/2400,.00001f)
            assertEquals(a.paragraphs.map{it.layout.paint.textSize},b.paragraphs.map{it.layout.paint.textSize})
        }
    }
    @Test fun oldFixedWidthBindsToPhotoBeforePresetReuse(){
        val asset=sample(1200,800)
        val original=EditDocument(assets=listOf(asset),caption="文字宽度测试",textStyle=TextBlockStyle(widthMode="fixed",width=600,widthRatio=null,sizeBasis="short"))
        val json=original.json();json.getJSONObject("textStyle").remove("widthRatio");json.getJSONObject("textStyle").remove("sizeBasis")
        val restored=EditDocument.read(json)
        assertEquals(.5f,restored.textStyle.widthRatio!!,0f);assertEquals("short",restored.textStyle.sizeBasis)
        val preset=restored.preset();val other=preset.copy(assets=listOf(sample(2400,1600)))
        val small=CaptionTypography.geometry(restored.textStyle,1200f,800f,Typeface.DEFAULT)
        val big=CaptionTypography.geometry(other.textStyle,2400f,1600f,Typeface.DEFAULT)
        assertEquals(600f,small.width,.001f);assertEquals(1200f,big.width,.001f)
        val exact=CaptionTypography.geometry(TextBlockStyle(widthMode="fixed",widthRatio=600f/4032),4032f,3024f,Typeface.DEFAULT)
        assertEquals("A manually entered width must remain exact",600f,exact.width,.001f)
    }
    @Test fun focalFractionsAreHumanReadableWithoutChangingOriginalMetadata(){
        val asset=sample(600,400);val doc=EditDocument(assets=listOf(asset))
        assertEquals("18",Assets.editorValue("FocalLength","18/1"))
        assertEquals("3.93",Assets.editorValue("FocalLength","393/100"))
        assertEquals(1.0/18,Assets.editorValue("FocalLength","1/18").toDouble(),.0000001)
        assertEquals("18.",Assets.editorValue("FocalLength","18."))
        assertEquals("Canon EOS R50",Assets.caption(doc).first)
        assertTrue(Assets.caption(doc).second.contains("18 mm"));assertFalse(Assets.caption(doc).second.contains("实际"))
        val equivalent=doc.copy(metadata=mapOf("FocalLengthIn35mmFilm" to "28"))
        assertTrue(Assets.caption(equivalent).second.contains("28 mm eq."));assertFalse(Assets.caption(equivalent).second.contains("等效"))
        assertEquals("18/1",asset.exif["FocalLength"])
        assertEquals("NIKON D850",MetadataTypography.content(doc.copy(metadata=mapOf("Make" to "NIKON CORPORATION","Model" to "NIKON D850"))).camera)
        assertEquals("3.93 mm",Assets.display("FocalLength","393/100"))
        assertEquals("f/8",Assets.display("FNumber","8/1"))
        assertEquals("1 s",Assets.display("ExposureTime","1/1"))
        assertEquals("1/0",Assets.display("ExposureTime","1/0"))
    }
    @Test fun allTemplatesHaveSeparateCompleteMetadataAndCaptionRegions(){
        for((rw,rh)in ratios){val photo=sample(600,(600f*rh/rw).roundToInt())
            for(template in app.templates){
                val doc=template.apply(EditDocument(assets=List(4){photo.copy(id=uid())},caption="照片下方的正文\n独立段落与摄影参数分离",showMetadata=true,metadataCustomized=true))
                val scene=app.renderer.layout(doc);val body=scene.body!!
                for(node in scene.photos){assertTrue(body.top>=node.rect.bottom);node.label?.let{assertTrue(it.top>=node.rect.bottom);assertTrue(body.top>=it.bottom)}}
                scene.metadata?.let{assertTrue(body.top>=it.bottom);for(photoNode in scene.photos)assertFalse(RectF.intersects(it,photoNode.rect))}
                assertTrue(body.bottom<=scene.height+.01f)
            }
        }
    }
    @Test fun watermarksUseTheSameLogicalShapeAtDifferentResolutions(){
        val layer=WatermarkLayer(text="林间来信 / Photography",size=.04f,angle=12f)
        val a=sample(600,400);val b=sample(2400,1600)
        fun shape(asset:PhotoAsset):FrameRenderer.LayerShape {val doc=EditDocument(assets=listOf(asset),layers=listOf(layer));return app.renderer.layerShape(layer,app.renderer.layout(doc))!!}
        val x=shape(a);val y=shape(b)
        assertEquals(x.width/600,y.width/2400,.00001f);assertEquals(x.height/600,y.height/2400,.00001f)
        assertEquals(x.fit,y.fit,.0001f);assertEquals(x.text!!.text.toString(),y.text!!.text.toString())
        assertEquals(x.text.paint.textSize,y.text.paint.textSize,.001f)
    }

    @Test fun longAndMissingMetadataKeepsReadableTypeAndOrderedRows(){
        val photo=sample(1200,800)
        for(fields in listOf(emptyMap(),mapOf("FocalLength" to "18/1"),tags,tags+mapOf("Model" to "相机型号".repeat(80),"LensModel" to "Long Lens Name ".repeat(40)))){
            val doc=EditDocument(assets=listOf(photo.copy(exif=fields)))
            for(variant in 0..3){
                val block=MetadataTypography.measure(doc,width=if(variant==2)320f else 1200f,reference=1200f,variant=variant)
                for(run in block.runs){assertTrue(run.layout.paint.textSize>=22f);assertTrue(run.y+run.inkTop>=0);assertTrue(run.y+run.inkBottom<=block.contentHeight+.01f)}
                for(group in block.runs.groupBy{it.x}.values)group.zipWithNext().forEach{(a,b)->assertTrue(a.y+a.inkBottom<=b.y+b.inkTop)}
                if(fields.isEmpty())assertEquals(0f,block.height,0f)
            }
        }
    }
    @Test fun twelveAndFortyEightMegapixelGeometryUsesTheSameProportions(){
        val source=sample(1200,900)
        for(template in app.templates){
            fun scene(w:Int,h:Int)=app.renderer.layout(template.apply(EditDocument(assets=List(4){source.copy(id=uid(),width=w,height=h)},showMetadata=true,metadataCustomized=true)))
            val a=scene(4000,3000);val b=scene(8000,6000)
            assertEquals(a.width/4000,b.width/8000,.0005f)
            assertEquals(a.height/4000,b.height/8000,.0005f)
            assertEquals(a.photos.map{it.rect.width()/4000},b.photos.map{it.rect.width()/8000})
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion=34)
    @Test fun realEditorImportsSamplesAndRestoresCurrentProject()=kotlinx.coroutines.runBlocking {
        val device=androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked){"Unlock phone before layout review"}
        val key=androidx.datastore.preferences.core.stringPreferencesKey("last_document")
        val previous=app.editorPreferences.data.first()[key]
        val owner=previous?.let{app.database.records().get(it)}
        val temporary=mutableListOf<String>()
        var activity:androidx.activity.ComponentActivity?=null
        fun waitFor(condition:()->Boolean){val until=android.os.SystemClock.elapsedRealtime()+15000;while(!condition()){kotlin.check(android.os.SystemClock.elapsedRealtime()<until){"Layout UI did not become ready"};android.os.SystemClock.sleep(40)}}
        device.executeShellCommand("am start -n com.parker.imageborder/.MainActivity")
        waitFor{instrumentation.runOnMainSync{activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull{it.javaClass.name=="com.parker.imageborder.MainActivity"} as? androidx.activity.ComponentActivity};activity!=null}
        lateinit var vm:EditorViewModel
        instrumentation.runOnMainSync{vm=androidx.lifecycle.ViewModelProvider(activity!!)[EditorViewModel::class.java]};waitFor{vm.initialized}
        val original=vm.document
        try{
            for((w,h)in listOf(1200 to 800,800 to 1200,1000 to 1000)){
                val photo=sample(w,h)
                val doc=app.templates.first{it.id=="plate"}.apply(EditDocument(assets=listOf(photo),showMetadata=true,metadataCustomized=true))
                temporary.add(doc.id);instrumentation.runOnMainSync{vm.change(doc)}
                waitFor{!vm.rendering&&vm.preview!=null&&vm.document.id==doc.id}
                val tab=device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.res("editor-tool:metadata")),3000)?:error("Metadata tab missing")
                tab.click();android.os.SystemClock.sleep(600)
                assertFalse(device.hasObject(androidx.test.uiautomator.By.pkg(app.packageName).text(com.parker.imageborder.editor.appText(com.parker.imageborder.R.string.ui_original_metadata_ea4951))))
                val latch=java.util.concurrent.CountDownLatch(1);var bitmap:Bitmap?=null;var status=-1
                instrumentation.runOnMainSync{android.view.PixelCopy.request(android.view.PixelCopy.Request.Builder.ofWindow(activity!!.window.decorView).build(),app.mainExecutor){result->status=result.status;bitmap=result.bitmap;latch.countDown()}}
                assertTrue(latch.await(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,status)
                bitmap!!.let{save(it,"phone-$w-$h.png");it.recycle()}
            }
        }finally{
            instrumentation.runOnMainSync{vm.change(original);vm.persistNow();activity!!.finish()}
            waitFor{activity!!.isDestroyed}
            app.applicationScope.coroutineContext[kotlinx.coroutines.Job]?.children?.toList()?.forEach{it.join()}
            temporary.forEach{app.database.records().delete(it)}
            if(owner!=null)app.database.records().put(owner)
            app.editorPreferences.edit{if(previous==null)it.remove(key)else it[key]=previous}
            device.executeShellCommand("am start -n com.parker.imageborder/.MainActivity")
        }
    }

    @Test fun sidePaddingChangesTheColumnAndSmallLogosSurviveSave(){
        val photo=sample(1200,800)
        val doc=app.templates.first{it.id=="side-plate"}.apply(EditDocument(assets=listOf(photo)))
        val original=app.renderer.layout(doc).metadata!!
        val changed=doc.copy(metadataStyle=doc.metadataStyle.copy(gap=.08f))
        val padded=app.renderer.layout(changed).metadata!!
        assertTrue(padded.left>original.left);assertEquals(original.width(),padded.width(),.001f)
        val logo=WatermarkLayer(uri="file:///test-logo",size=.0025f)
        assertEquals(logo,WatermarkLayer.read(logo.json()))
        assertEquals(.028f,WatermarkLayer().size,0f)
    }

    @Test fun actualTextMarginsAreBalancedOnDefaultLabels(){
        val results=StringBuilder()
        for((w,h)in listOf(600 to 400,400 to 600,500 to 500,1200 to 400))for(id in listOf("white","plate","split-plate","center-plate"))for(position in listOf("top","bottom")){
            val photo=sample(w,h)
            val doc=app.templates.first{it.id==id}.apply(EditDocument(assets=listOf(photo),showMetadata=true))
                .copy(metadataStyle=MetadataStyle(position=position))
            val scene=app.renderer.layout(doc);val image=app.renderer.preview(doc,2400).bitmap
            val rect=scene.photos.single().rect
            var first=image.height;var last=-1
            val from=if(position=="top")0 else ceil(rect.bottom).toInt()
            val until=if(position=="top")floor(rect.top).toInt()else image.height
            for(y in from until until){var dark=false;for(x in 0 until image.width){val pixel=image.getPixel(x,y);if(Color.red(pixel)<160&&Color.green(pixel)<160&&Color.blue(pixel)<160){dark=true;break}}
                if(dark){first=min(first,y);last=y}}
            assertTrue("Missing rasterized text $id $w:$h $position",last>=first)
            val inside=if(position=="top")rect.top-last-1 else first-rect.bottom
            val outside=if(position=="top")first.toFloat()else (image.height-1-last).toFloat()
            results.append("$id $w:$h $position: photo gap=$inside outer margin=$outside\n")
            assertEquals("Visible glyph margins: $id $w:$h $position",outside,inside,3f)
            image.recycle()
        }
        File(folder,"visible-margins.txt").writeText(results.toString())
    }

    @Test fun metadataMovesWithoutCroppingAcrossTemplatesAndRatios(){
        for((rw,rh)in ratios){val photo=sample(rw*80,rh*80)
            for(template in app.templates)for(position in listOf("top","bottom","left","right")){
                val style=MetadataStyle(position=position,arrangement=if(template.variant%2==0)"stacked"else"split",alignment="right")
                val doc=template.apply(EditDocument(assets=List(if(template.family==7)4 else 1){photo.copy(id=uid())},caption="正文保留独立位置",showMetadata=true)).copy(metadataStyle=style)
                val scene=app.renderer.layout(doc)
                for(node in scene.photos){
                    assertEquals(photo.width.toFloat(),node.rect.width(),.001f);assertEquals(photo.height.toFloat(),node.rect.height(),.001f)
                    val label=(node.label?:scene.metadata)!!
                    assertFalse("$position overlaps photo",RectF.intersects(label,node.rect))
                    assertTrue(label.left>=-.01f&&label.top>=-.01f&&label.right<=scene.width+.01f&&label.bottom<=scene.height+.01f)
                    when(position){"top"->assertTrue(label.bottom<=node.rect.top);"bottom"->assertTrue(label.top>=node.rect.bottom);"left"->assertTrue(label.right<=node.rect.left);"right"->assertTrue(label.left>=node.rect.right)}
                    assertTrue("${template.id} $rw:$rh $position body=${scene.body} label=$label photo=${node.rect}",scene.body!!.top>=max(label.bottom,node.rect.bottom)-.01f)
                }
                assertEquals(style,EditDocument.read(doc.json()).metadataStyle)
                assertEquals(style,doc.preset().metadataStyle)
                val hidden=app.renderer.layout(doc.copy(showMetadata=false));assertNull(hidden.metadata);assertTrue(hidden.photos.all{it.label==null})
            }
        }
    }

    @Test fun legacyDefaultSpacingAndSideColumnsMigrateOnce(){
        val photo=sample(600,400)
        val doc=EditDocument(assets=listOf(photo),showMetadata=true)
        val json=doc.json().apply{remove("metadataStyle")}
        val migrated=EditDocument.read(json)
        assertNull(migrated.metadataStyle.gap)
        assertEquals(migrated.frame.bottom,migrated.metadataStyle.gap(migrated.frame,"bottom"),0f)
        val custom=json.apply{getJSONObject("frame").put("metadataPadding",.08)}
        assertEquals(.08f,EditDocument.read(custom).metadataStyle.gap!!,.00001f)
        val side=doc.copy(template="side-plate",frame=doc.frame.copy(right=.45f)).json().apply{remove("metadataStyle")}
        val converted=EditDocument.read(side)
        assertEquals(.05f,converted.frame.right,0f);assertEquals(.4f,converted.metadataStyle.columnWidth,.00001f)
        assertEquals(converted,EditDocument.read(converted.json()))
        val explicit=MetadataStyle(position="left",alignment="center",arrangement="split",gap=.073f,columnWidth=.65f)
        val retained=doc.copy(metadataStyle=explicit)
        assertEquals(explicit,EditDocument.read(retained.json()).metadataStyle)
        assertEquals(MetadataStyle(),app.templates.first{it.id=="plate"}.apply(retained).metadataStyle)
    }

    @Test fun captureMovableMetadataLayouts(){
        val cases=listOf(MetadataStyle(position="top"),MetadataStyle(position="bottom"),MetadataStyle(position="bottom",alignment="center"),MetadataStyle(position="bottom",alignment="right",arrangement="split"),MetadataStyle(position="left"),MetadataStyle(position="right"))
        val sheet=Bitmap.createBitmap(2400,1500,Bitmap.Config.ARGB_8888);val c=Canvas(sheet);c.drawColor(0xffe6e7e7.toInt())
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=16f;color=Color.DKGRAY}
        for((row,size)in listOf(1200 to 800,800 to 1200,1000 to 1000).withIndex())for((col,style)in cases.withIndex()){
            val photo=sample(size.first,size.second)
            val doc=app.templates.first{it.id=="plate"}.apply(EditDocument(assets=listOf(photo),showMetadata=true)).copy(metadataStyle=style)
            val rendered=app.renderer.preview(doc,1000).bitmap
            val scale=min(372f/rendered.width,448f/rendered.height);val w=rendered.width*scale;val h=rendered.height*scale
            c.drawBitmap(rendered,null,RectF(col*400+(400-w)/2,row*500+(460-h)/2,col*400+(400+w)/2,row*500+(460+h)/2),Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawText("${style.position} / ${style.alignment} / ${style.arrangement}",col*400+10f,row*500+485f,p);rendered.recycle()
        }
        save(sheet,"positions.png");sheet.recycle()
    }

    @Test fun captureEveryTemplateWithVisibleMetadata(){
        for((name,size)in listOf("landscape" to (720 to 480),"portrait" to (480 to 720),"square" to (600 to 600))){
            val sheet=Bitmap.createBitmap(3200,1800,Bitmap.Config.ARGB_8888);val c=Canvas(sheet);c.drawColor(0xffe6e7e7.toInt())
            val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=16f;color=Color.DKGRAY}
            val photo=sample(size.first,size.second)
            for((index,template)in app.templates.withIndex()){
                val col=index%8;val row=index/8
                val doc=template.apply(EditDocument(assets=List(if(template.family==7)4 else 1){photo.copy(id=uid())},showMetadata=true))
                val rendered=app.renderer.preview(doc,720).bitmap
                val scale=min(372f/rendered.width,390f/rendered.height);val w=rendered.width*scale;val h=rendered.height*scale
                c.drawBitmap(rendered,null,RectF(col*400+(400-w)/2,row*450+(410-h)/2,col*400+(400+w)/2,row*450+(410+h)/2),Paint(Paint.FILTER_BITMAP_FLAG))
                c.drawText(template.id,col*400+10f,row*450+434f,p);rendered.recycle()
            }
            save(sheet,"all-templates-$name.png");sheet.recycle()
        }
    }

    @Test fun exportedParameterPixelsMatchPreviewInEveryPosition()=kotlinx.coroutines.runBlocking {
        val photo=sample(600,400)
        for(position in listOf("top","bottom","left","right")){
            val doc=app.templates.first{it.id=="plate"}.apply(EditDocument(assets=listOf(photo),showMetadata=true)).copy(
                metadataStyle=MetadataStyle(position=position,arrangement="split",alignment="center"),
                export=ExportOptions(mode="original",format="png"))
            val target=File(app.cacheDir,"position-export-$position.png")
            val preview=app.renderer.preview(doc,2400)
            try{
                app.cancelExport.set(false)
                RenderExporter(app).renderFiles(doc,{}){file,_,_->file.copyTo(target,overwrite=true)}
                val output=BitmapFactory.decodeFile(target.path)
                try{
                    assertEquals(preview.bitmap.width,output.width);assertEquals(preview.bitmap.height,output.height)
                    val region=preview.layout.metadata!!
                    val x=floor(region.left).toInt();val y=floor(region.top).toInt()
                    val w=ceil(region.right).toInt()-x;val h=ceil(region.bottom).toInt()-y
                    val expected=IntArray(w*h);val actual=IntArray(w*h)
                    preview.bitmap.getPixels(expected,0,w,x,y,w,h);output.getPixels(actual,0,w,x,y,w,h)
                    assertArrayEquals("Parameter pixels in $position must survive PNG export",expected,actual)
                }finally{output.recycle()}
            }finally{preview.bitmap.recycle();target.delete()}
        }
    }

}
