package com.parker.imageborder.editor

import android.graphics.*
import android.net.Uri
import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import com.parker.imageborder.R
import kotlin.math.*

class EditorRegressionTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as EditorApplication
    private val dir get()=File(app.cacheDir,"regressions").apply{mkdirs()}
    private fun source(w:Int=500,h:Int=400):PhotoAsset {
        val file=File(dir,"source-$w-$h.png");val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
        canvas.drawColor(Color.rgb(65,113,161));val p=Paint().apply{color=Color.rgb(212,153,103)};canvas.drawRect(w*.1f,h*.2f,w*.45f,h*.65f,p)
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();return Assets.inspect(app,Uri.fromFile(file))
    }
    private suspend fun withVm(block:suspend(EditorViewModel)->Unit){
        val key=stringPreferencesKey("last_document");val previous=app.editorPreferences.data.first()[key];val store=ViewModelStore();lateinit var vm:EditorViewModel
        instrumentation.runOnMainSync{vm=EditorViewModel(app);store.put("regression",vm)}
        withTimeout(10_000){while(!vm.initialized)delay(20)}
        val own=mutableSetOf<String>()
        try{val doc=app.templates.first().apply(EditDocument(assets=listOf(source())));own.add(doc.id);instrumentation.runOnMainSync{vm.change(doc)};block(vm)}
        finally{own.add(vm.document.id);instrumentation.runOnMainSync{store.clear()};app.applicationScope.coroutineContext[Job]?.children?.toList()?.forEach{it.join()};app.editorPreferences.edit{if(previous==null)it.remove(key)else it[key]=previous};own.forEach{app.database.records().delete(it)}}
    }
    @Test fun numericCancelAndUndoPreserveTheWholeDocument()=runBlocking {
        withVm{vm->instrumentation.runOnMainSync{
            val initial=vm.document;val revision=vm.historyRevision
            val p=numericParameter("左边",vm.app.renderer.layout(initial).unit)
            vm.endGesture();vm.beginGesture()
            vm.change(vm.document.copy(frame=vm.document.frame.copy(left=p.parse("100",0f.. .5f)!!)),true)
            vm.change(initial,true);vm.endGesture()
            assertEquals(initial,vm.document);assertEquals(revision,vm.historyRevision)
            vm.beginGesture()
            for(value in listOf("100","101","102"))vm.change(vm.document.copy(frame=vm.document.frame.copy(left=p.parse(value,0f.. .5f)!!)),true)
            vm.endGesture();val edited=vm.document
            assertEquals(revision+1,vm.historyRevision)
            vm.undo();assertEquals(initial,vm.document)
            vm.redo();assertEquals(edited,vm.document)
        }}
    }
    @Test fun blankWatermarksAreTransientAndDeleteRestoresCleanSelection()=runBlocking {
        withVm{vm->instrumentation.runOnMainSync{
            repeat(8){vm.beginTextWatermark()};assertEquals(0,vm.document.layers.size)
            vm.editWatermarkText("有效文字");assertEquals(1,vm.document.layers.size)
            val id=vm.document.layers.single().id;vm.updateLayer(vm.document.layers.single().copy(size=.08f))
            vm.editWatermarkText("有效文字2");assertEquals(.08f,vm.document.layers.single().size)
            vm.editWatermarkText("");assertTrue(vm.document.layers.isEmpty());assertNull(vm.selectedLayer)
            vm.finishTextWatermark();assertFalse(vm.textEditorOpen)
            vm.undo();assertTrue(vm.document.layers.isNotEmpty());vm.removeLayer(id);assertTrue(vm.document.layers.isEmpty())
            val old=vm.document.json().put("layers",org.json.JSONArray().put(WatermarkLayer(text=" ").json()).put(WatermarkLayer(text="keep").json()))
            assertEquals(listOf("keep"),EditDocument.read(old).layers.map{it.text})
        }}
    }
    @Test fun allTemplatesShowSinglePhotoMetadataAndRespectVisibility(){
        val a=source().copy(exif=mapOf("Model" to "Camera","FNumber" to "2.8","ExposureTime" to "1/125","PhotographicSensitivity" to "200"))
        for(t in app.templates){
            val doc=t.apply(EditDocument(assets=listOf(a),showMetadata=true,metadataCustomized=true))
            val scene=app.renderer.layout(doc)
            assertTrue("${t.id} must allocate visible metadata",scene.metadata!=null||t.family==1&&t.variant==2||t.family==1&&scene.footer.height()>0||scene.photos.any{it.label!=null})
            val off=doc.copy(showMetadata=false);assertNull(app.renderer.layout(off).metadata)
            assertFalse(t.apply(off.copy(metadataCustomized=true)).showMetadata)
            val p=app.renderer.preview(doc,600);val q=app.renderer.preview(off,600)
            assertFalse("${t.id} toggle must affect output",p.bitmap.sameAs(q.bitmap));p.bitmap.recycle();q.bitmap.recycle()
        }
    }
    @Test fun restoringOnePhotoAutoValueDoesNotChangeOthersOrTransform(){
        val a=source().copy(exif=mapOf("Model" to "Original A"));val b=a.copy(id=uid(),exif=mapOf("Model" to "Original B"));val t=PhotoTransform(zoom=1.6f,rotation=13f,x=.3f)
        val doc=EditDocument(assets=listOf(a,b),metadata=mapOf("Model" to "Shared"),transform=t)
        val o=doc.overrideFor(a).copy(autoFields=setOf("Model"))
        val next=doc.copy(overrides=mapOf(a.id to o));assertEquals("Original A",next.fields(a)["Model"]);assertEquals("Shared",next.fields(b)["Model"]);assertEquals(t,next.currentTransform)
        assertEquals(next,EditDocument.read(next.json()))
    }
    @Test fun captionsExpandAllTemplatesAndEmptyCaptionsRemoveTheirRegion(){
        val a=source();val text=List(30){"第 ${it+1} 行：中文与 paragraph 排版，保留所有内容。"}.joinToString("\n")
        val style=TextBlockStyle(size=.04f,lineSpacing=1.8f,padding=.04f,gap=.06f,alignment="right",color=Color.MAGENTA,autoColor=false)
        for(t in app.templates){
            val d=t.apply(EditDocument(assets=listOf(a),caption=text,textStyle=style));val scene=app.renderer.layout(d)
            val block=CaptionTypography.measure(d,scene.body!!.width(),scene.unit,app.renderer.typeface(style.font,style.bold))!!
            assertTrue("${t.id}: region=${scene.body.height()} text=${block.height}",scene.body.height()+.001f>=block.height);assertTrue(t.id,scene.body.top>=scene.photos.maxOf{it.rect.bottom})
            val clear=app.renderer.layout(d.copy(caption=""));assertNull(clear.body);assertTrue(t.id,scene.height>clear.height)
        }
        val doc=EditDocument(assets=listOf(a),caption=text,textStyle=style);assertEquals(style,EditDocument.read(doc.json()).textStyle)
    }
    @Test fun leftCenterAndRightCaptionAlignmentAffectActualPixels(){
        val a=source();val positions=mutableListOf<Float>()
        for(alignment in listOf("left","center","right")){
            val doc=app.templates.first().apply(EditDocument(assets=listOf(a),caption="TEST",textStyle=TextBlockStyle(size=.06f,color=Color.BLACK,autoColor=false,alignment=alignment,gap=0f)))
            val frame=app.renderer.preview(doc,2048);val r=frame.layout.body!!;var total=0L;var count=0
            for(y in ceil(r.top).toInt() until floor(r.bottom).toInt())for(x in ceil(r.left).toInt() until floor(r.right).toInt()){
                val c=frame.bitmap.getPixel(x,y);if(Color.red(c)<80&&Color.green(c)<80&&Color.blue(c)<80){total+=x;count++}
            }
            assertTrue(count>10);positions.add(total.toFloat()/count);frame.bitmap.recycle()
        }
        assertTrue(positions[1]-positions[0]>100);assertTrue(positions[2]-positions[1]>100)
    }
    @Test fun collageSelectionIsVisibleAndLongLayoutDoesNotDropPhotos()=runBlocking {
        withVm{vm->
            val a=source();val photos=List(25){a.copy(id=uid())};val template=app.templates.first{it.id=="diptych"}
            instrumentation.runOnMainSync{
                vm.change(template.apply(vm.document.copy(assets=photos)))
                val before=app.renderer.layout(vm.document);vm.activateAsset(1);val next=app.renderer.layout(vm.document)
                assertEquals(before.width,next.width);assertEquals(before.height,next.height)
                vm.activateAsset(18);assertTrue(vm.document.sceneAssets(template).any{it.id==photos[18].id})
                val long=app.templates.first{it.id=="long"}.apply(vm.document);assertEquals(25,app.renderer.layout(long).photos.size)
            }
        }
    }
    @Test fun photoAnchoredWatermarksStayInsidePhotoWhenFramesChange(){
        val a=source();val layer=WatermarkLayer(text="中文署名",x=1f,y=1f,size=.05f,anchor="photo")
        for(template in app.templates.filter{it.family<7}){
            val doc=template.apply(EditDocument(assets=listOf(a),layers=listOf(layer)));val scene=app.renderer.layout(doc)
            val bounds=app.renderer.layerShape(layer,scene)!!.bounds;assertTrue(template.id,scene.photos.single().rect.contains(bounds))
        }
    }
    @Test fun originalFilmLooksHaveDistinctPixelsAndContinuousIntensity(){
        val bitmap=Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);val colors=listOf(Color.rgb(20,50,45),Color.rgb(55,125,75),Color.rgb(80,145,210),Color.rgb(202,151,116),Color.rgb(220,85,55),Color.rgb(235,227,207))
        colors.forEachIndexed{i,c->canvas.drawRect(i*20f,0f,i*20f+20,80f,Paint().apply{color=c})}
        val outputs=FilmLooks.presets.map{p->ImageEffects.adjust(bitmap,Adjustments(filter=p.name))}
        assertEquals(26,Adjustments.filters.size)
        assertTrue(outputs.map{b->colors.indices.map{b.getPixel(it*20+10,40)}}.distinct().size>=16)
        val none=ImageEffects.adjust(bitmap,Adjustments(filter="经典负片·青",intensity=0f));assertTrue(none.sameAs(bitmap))
        val full=ImageEffects.adjust(bitmap,Adjustments(filter="经典负片·青"));val half=ImageEffects.adjust(bitmap,Adjustments(filter="经典负片·青",intensity=.5f))
        for(i in colors.indices){val x=i*20+10;val a=bitmap.getPixel(x,40);val b=full.getPixel(x,40);val h=half.getPixel(x,40)
            for(shift in listOf(0,8,16))assertTrue(abs((h shr shift and 255)-((a shr shift and 255)+(b shr shift and 255))/2)<=2)}
        outputs.forEach{it.recycle()};bitmap.recycle();full.recycle();half.recycle()
        val input=BitmapFactory.decodeResource(app.resources,R.drawable.preview_photo)
        val sample=Bitmap.createScaledBitmap(input,360,480,true);input.recycle()
        val sheet=Bitmap.createBitmap(1200,2310,Bitmap.Config.ARGB_8888);val board=Canvas(sheet);board.drawColor(Color.rgb(242,242,244))
        val label=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(30,32,34);textSize=23f;textAlign=Paint.Align.CENTER}
        for((index,name) in Adjustments.filters.withIndex()){
            val b=ImageEffects.adjust(sample,Adjustments(filter=name));val x=(index%4)*300f;val y=(index/4)*330f
            board.drawBitmap(b,null,RectF(x+52,y+12,x+248,y+274),Paint(Paint.FILTER_BITMAP_FLAG));board.drawText(name,x+150,y+312,label)
            if(b!==sample)b.recycle()
        }
        File(dir,"looks-26.png").outputStream().use{sheet.compress(Bitmap.CompressFormat.PNG,100,it)};sheet.recycle();sample.recycle()
    }
    @Test fun presetIncludesParagraphStyleAndLocalFont() {
        val font=File("/system/fonts").listFiles()?.firstOrNull{it.extension=="ttf"&&it.length() in 1000..1_000_000}?:error("测试系统字体缺失")
        val ownFont=File(File(app.filesDir,"fonts").apply{mkdirs()},"caption-regression.font");font.copyTo(ownFont,true)
        val doc=EditDocument(caption="正文",textStyle=TextBlockStyle(font=ownFont.path,alignment="justify",color=Color.CYAN,autoColor=false,lineSpacing=1.7f))
        val zip=File(dir,"paragraph-preset.zip");zip.outputStream().use{PresetPackages.export(app,doc,"文字预设",it)}
        val imported=PresetPackages.import(app,Uri.fromFile(zip)).second
        assertEquals(doc.textStyle.copy(font=imported.textStyle.font),imported.textStyle);assertTrue(File(imported.textStyle.font).isFile)
        File(imported.textStyle.font).delete();ownFont.delete();zip.delete()
    }
    @Test fun changingExportSettingsDoesNotRepaintEditingScene()=runBlocking {
        withVm{vm->
            withTimeout(10_000){while(vm.rendering||vm.preview==null)delay(20)};val before=vm.preview
            instrumentation.runOnMainSync{vm.change(vm.document.copy(export=vm.document.export.copy(quality=99)))}
            delay(500);assertSame(before,vm.preview)
        }
    }
    @Test fun lateArchiveDoesNotReplaceCurrentDocument()=runBlocking {
        withVm{vm->
            val old=vm.document;val stamp=app.nextSave()
            val delayed=app.applicationScope.launch{delay(700);app.persistDraft(old,stamp,false)}
            instrumentation.runOnMainSync{vm.newDocument(true);vm.change(vm.document.copy(caption="新作品"))}
            val current=vm.document.id;delay(1000);delayed.join()
            assertEquals(current,app.editorPreferences.data.first()[stringPreferencesKey("last_document")]);assertNotNull(app.database.records().get(old.id))
        }
    }
    @Test fun automaticPaletteFollowsVisibleCropPosition(){
        val bitmap=Bitmap.createBitmap(500,400,Bitmap.Config.ARGB_8888);val c=Canvas(bitmap);c.drawColor(Color.RED);c.drawRect(250f,0f,500f,400f,Paint().apply{color=Color.GREEN})
        val file=File(dir,"palette-split.png");file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val asset=Assets.inspect(app,Uri.fromFile(file));val doc=app.templates.first().apply(EditDocument(assets=listOf(asset),transform=PhotoTransform(zoom=2f,x=0f)))
        val left=app.renderer.resources(doc).second.first();val right=app.renderer.resources(doc.copy(transform=doc.transform.copy(x=1f))).second.first()
        assertTrue(Color.red(left)>200&&Color.green(left)<30);assertTrue(Color.green(right)>200&&Color.red(right)<30)
    }
    @Test fun presetKeepsCurrentTextAndTextOnlyWorkflow()=runBlocking {
        withVm{vm->instrumentation.runOnMainSync{
            vm.newDocument(true);vm.change(vm.document.copy(caption="正在写的正文"))
            val preset=app.templates.first{it.id=="glass-float"}.apply(EditDocument(caption="旧预设正文",adjustments=Adjustments(filter="经典负片"),export=ExportOptions(format="hdr")))
            val record=EditorRecord().apply{kind="preset";payload=preset.json().toString()}
            vm.openRecord(record);assertTrue(vm.document.textOnly);assertEquals("正在写的正文",vm.document.caption);assertFalse(vm.document.frame.autoColor);assertTrue(vm.document.adjustments.isNeutral);assertEquals("jpeg",vm.document.export.format)
        }}
    }
    @Test fun paragraphSpacingOnlyChangesExplicitParagraphBreaks(){
        val doc=EditDocument(caption="第一段\n第二段",textStyle=TextBlockStyle(size=.05f,paragraphSpacing=0f,sizeBasis="short"))
        val face=Typeface.DEFAULT
        val a=CaptionTypography.measure(doc,500f,400f,face)!!
        val b=CaptionTypography.measure(doc.copy(textStyle=doc.textStyle.copy(paragraphSpacing=1.25f)),500f,400f,face)!!
        assertEquals(25f,b.height-a.height,.01f)
        val wrapped=doc.copy(caption="只有一段文字，自动换行不会额外加入段间距。".repeat(5))
        assertEquals(CaptionTypography.measure(wrapped,180f,400f,face)!!.height,CaptionTypography.measure(wrapped.copy(textStyle=wrapped.textStyle.copy(paragraphSpacing=2f)),180f,400f,face)!!.height,.01f)
        assertEquals(doc,EditDocument.read(doc.json()))
        assertEquals(.5f,TextBlockStyle.read(JSONObject()).paragraphSpacing)
        assertEquals(0f,TextBlockStyle.read(JSONObject().put("paragraphSpacing",0f)).paragraphSpacing)
    }
    @Test fun metadataFallbacksReadApexIsoAndNamespacedXmp(){
        val xmp="""<x:xmpmeta xmlns:x="adobe:ns:meta/" xmlns:exif="http://ns.adobe.com/exif/1.0/" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:RDF><rdf:Description tiff:Make="CAMERA" exif:FocalLength="50/1" exif:DateTimeOriginal="2026-10-26T12:34:56+08:00"><exif:ISOSpeedRatings><rdf:Seq><rdf:li>640</rdf:li></rdf:Seq></exif:ISOSpeedRatings></rdf:Description></rdf:RDF></x:xmpmeta>"""
        val out=MetadataReader.normalize(mapOf("ApertureValue" to "4/1","ShutterSpeedValue" to "7/1","GPSLatitude" to "secret","BodySerialNumber" to "private"),xmp)
        assertEquals(4.0,Assets.rational(out.getValue("FNumber"))!!,.0001)
        assertEquals(1.0/128,Assets.rational(out.getValue("ExposureTime"))!!,.000001)
        assertEquals("640",out["PhotographicSensitivity"]);assertEquals("50/1",out["FocalLength"]);assertEquals("+08:00",out["OffsetTimeOriginal"])
        assertEquals("2026-10-26 12:34:56",out["DateTimeOriginal"])
        assertFalse("GPSLatitude" in out);assertFalse("BodySerialNumber" in out)
        assertEquals("2026-10-26 12:34:56",Assets.editorValue("DateTimeOriginal","2026:10:26 12:34:56"))
        assertTrue(MetadataReader.normalize(emptyMap(),"<!DOCTYPE x [<!ENTITY evil SYSTEM 'file:///does-not-exist'>]><x>&evil;</x>").isEmpty())
        assertEquals("CANONICAL",MetadataReader.normalize(mapOf("Make" to "CANONICAL"),xmp)["Make"])
    }
    @Test fun metadataClearRestoreAndPerPhotoVisibilityAreIndependent(){
        val a=source().copy(exif=mapOf("DateTimeOriginal" to "2026:10:26 10:00:00","OffsetTimeOriginal" to "+08:00"));val b=a.copy(id=uid())
        val doc=EditDocument(assets=listOf(a,b),metadata=mapOf("DateTimeOriginal" to "Shared"),hiddenFields=setOf("DateTimeOriginal"))
        val show=doc.withMetadataField("DateTimeOriginal",null,true,true)
        assertFalse("DateTimeOriginal" in show.hidden(a));assertTrue("DateTimeOriginal" in show.hidden(b))
        val cleared=show.withMetadataField("DateTimeOriginal","");assertEquals("",cleared.fields(a)["DateTimeOriginal"]);assertEquals("Shared",cleared.fields(b)["DateTimeOriginal"])
        val restored=cleared.withMetadataField("DateTimeOriginal",null);assertEquals(a.exif["DateTimeOriginal"],restored.fields(a)["DateTimeOriginal"])
        val global=restored.withMetadataField("DateTimeOriginal","New all",false);assertEquals("New all",global.fields(a)["DateTimeOriginal"]);assertEquals("New all",global.fields(b)["DateTimeOriginal"])
        assertEquals(global,EditDocument.read(global.json()))
    }
    @Test fun addingPhotosArchivesPreviousWorkWithoutLosingBatchAssets()=runBlocking {
        withVm{vm->
            val old=vm.document
            instrumentation.runOnMainSync{vm.applyImportedAssets(listOf(source(320,240)),true)}
            val next=vm.document
            assertNotEquals(old.id,next.id);assertEquals(2,next.assets.size);assertEquals(1,next.active)
            vm.persistNow();app.applicationScope.coroutineContext[Job]?.children?.toList()?.forEach{it.join()}
            val saved=EditDocument.read(JSONObject(app.database.records().get(old.id).payload))
            assertEquals(old.assets,saved.assets);assertEquals(next.id,app.editorPreferences.data.first()[stringPreferencesKey("last_document")])
        }
    }
    @Test fun frameAndExportResetsPreserveContentAndOtherStyles()=runBlocking {
        withVm{vm->instrumentation.runOnMainSync{
            val initial=app.templates.first{it.id=="side-plate"}.apply(vm.document).copy(caption="保留正文",frame=FrameStyle(left=.4f,color=Color.RED),export=ExportOptions(mode="custom",format="png",maxEdge=4096,keepGps=true))
            vm.change(initial);vm.resetFrame()
            assertEquals(app.renderer.template(initial.template).apply(initial).frame,vm.document.frame)
            assertEquals(initial.caption,vm.document.caption);assertEquals(initial.export,vm.document.export)
            vm.resetExport();assertEquals(ExportOptions(),vm.document.export);assertEquals(initial.caption,vm.document.caption)
        }}
    }
    @Test fun sharingIsAnExplicitPresetAndCustomRetainsFullControl(){
        val custom=ExportOptions(mode="custom",format="png",quality=100,maxEdge=4000,profile="source")
        val share=custom.forMode("share");assertEquals("jpeg",share.format);assertEquals(95,share.quality);assertEquals(2560,share.maxEdge);assertEquals("srgb",share.profile)
        assertEquals(share.copy(mode="custom"),share.forMode("custom"))
        assertEquals("custom",ExportOptions.read(custom.copy(mode="share").json()).mode)
        assertTrue(2560 in ExportOptions.shareEdges)
    }

    @Test fun openingLibraryFlushesTheMostRecentText()=runBlocking {
        withVm{vm->
            instrumentation.runOnMainSync{vm.change(vm.document.copy(caption="刚输入的正文"),true);vm.refreshRecords(true)}
            withTimeout(5000){while(vm.records.none{it.id==vm.document.id&&EditDocument.read(JSONObject(it.payload)).caption=="刚输入的正文"})delay(20)}
        }
    }

    @Test fun watermarkColorAlphaAndOpacityCombine(){
        val layer=WatermarkLayer(text="A",color=Color.argb(128,200,80,40),opacity=.5f)
        val scene=app.renderer.layout(EditDocument(assets=listOf(source())))
        assertEquals(64,app.renderer.layerShape(layer,scene)!!.paint.alpha)
        val transparent=layer.copy(color=Color.TRANSPARENT)
        assertEquals(0,app.renderer.layerShape(transparent,scene)!!.paint.alpha)
    }

    @Test fun characterCountFitsFullPhotoWidthWithActualTypeface(){
        for(face in listOf(Typeface.DEFAULT,Typeface.DEFAULT_BOLD,Typeface.SERIF))for(count in listOf(5,24,80)){
            val style=TextBlockStyle(widthMode="chars",characters=count,padding=.02f,size=.01f)
            val doc=EditDocument(caption="字".repeat(count*2),textStyle=style)
            val block=CaptionTypography.measure(doc,1200f,800f,face)!!
            assertEquals(1200f,block.width,.01f)
            assertEquals(0f,block.horizontalPadding,.01f)
            val inset=CaptionTypography.geometry(style.copy(sidePadding=.03f),1200f,800f,face)
            assertEquals(1152f,inset.width,.01f)
            val layout=block.paragraphs.single().layout
            assertEquals("$count characters should fill one line",count,layout.text.subSequence(0,layout.getLineEnd(0)).toString().trimEnd('\n').length)
            assertEquals(2,layout.lineCount)
            assertTrue("A full line should reach the right edge: count=$count face=$face line=${layout.getLineMax(0)} column=${block.width} size=${layout.paint.textSize} paint=${layout.paint.measureText("字".repeat(count))} desired=${android.text.Layout.getDesiredWidth("字".repeat(count),layout.paint)}",layout.getLineMax(0)>block.width*.995f)
            assertTrue("Overflow count=$count font=$face line=${layout.getLineMax(0)} column=${block.width} raw=${layout.text}",layout.getLineMax(0)<=block.width+.1f)
            val changed=CaptionTypography.measure(doc.copy(textStyle=style.copy(size=.1f)),1200f,800f,face)!!
            assertEquals(layout.paint.textSize,changed.paragraphs.single().layout.paint.textSize,.001f)
            val wide=CaptionTypography.measure(doc,2400f,800f,face)!!
            assertEquals("Logical type size must be resolution independent",layout.paint.textSize,wide.paragraphs.single().layout.paint.textSize,.001f)
            assertEquals(block.layoutScale*2,wide.layoutScale,.001f)
        }
        assertEquals(0f,TextBlockStyle.read(JSONObject().put("widthMode","chars").put("padding",.02)).horizontalPadding)
        assertEquals(.02f,TextBlockStyle.read(JSONObject().put("padding",.02)).horizontalPadding)
        val custom=TextBlockStyle(widthMode="chars",sidePadding=.04f);assertEquals(custom,TextBlockStyle.read(custom.json()))
        assertEquals(1.15f,TextBlockStyle().lineSpacing)
        assertEquals(1.15f,TextBlockStyle.read(JSONObject()).lineSpacing)
        assertEquals(1.3f,TextBlockStyle.read(JSONObject().put("lineSpacing",1.3)).lineSpacing)
    }
    @Test fun automaticCaptionFontMatchesStripedPngExport()=runBlocking {
        val asset=source(960,640)
        val doc=app.templates.first{it.id=="white"}.apply(EditDocument(assets=listOf(asset),caption=("中文 OpenAI Photography，标点……（测试）👨‍👩‍👧‍👦 e\u0301。".repeat(4)),textStyle=TextBlockStyle(widthMode="chars",characters=24,color=Color.BLACK,autoColor=false),export=ExportOptions(mode="original",format="png",profile="srgb")))
        val preview=app.renderer.preview(doc,4096);var compared=false
        File(dir,"mixed-flow-preview.png").outputStream().use{preview.bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        app.cancelExport.set(false)
        try{RenderExporter(app).renderFiles(doc,{}){file,_,_->
            val output=BitmapFactory.decodeFile(file.path)
            try{
                assertEquals(preview.bitmap.width,output.width);assertEquals(preview.bitmap.height,output.height)
                val body=preview.layout.body!!;val w=body.width().roundToInt();val h=body.height().roundToInt()
                val a=IntArray(w*h);val b=IntArray(w*h)
                preview.bitmap.getPixels(a,0,w,body.left.roundToInt(),body.top.roundToInt(),w,h)
                output.getPixels(b,0,w,body.left.roundToInt(),body.top.roundToInt(),w,h)
                assertTrue("Body text must match preview pixel for pixel",a.contentEquals(b));compared=true
            }finally{output.recycle()}
        }}finally{preview.bitmap.recycle()}
        assertTrue(compared)
    }

    @Test fun libraryRetentionKeepsFiveRecentAndProtectsCurrentAndUnfinished(){
        fun record(id:String,kind:String,time:Long,status:String="done")=EditorRecord().apply{
            this.id=id;this.kind=kind;updated=time;payload=JSONObject().put("status",status).toString()
        }
        val drafts=(1..9).map{record("draft-$it","draft",it.toLong())}
        val jobs=(1..9).map{record("job-$it","job",it.toLong())}
        val unfinished=listOf("waiting","running","paused","failed").mapIndexed{i,status->record("active-$i","job",0,status)}
        val preset=record("preset","preset",0);val unknown=record("unknown","job",0).apply{payload="invalid"}
        val rows=drafts+jobs+unfinished+listOf(preset,unknown)
        val removed=LibraryRetention.removable(rows,"draft-1").toSet();val remaining=rows.filter{it.id !in removed}
        assertEquals(setOf("draft-1","draft-6","draft-7","draft-8","draft-9"),remaining.filter{it.kind=="draft"}.map{it.id}.toSet())
        assertEquals(setOf("job-5","job-6","job-7","job-8","job-9"),jobs.filter{it.id !in removed}.map{it.id}.toSet())
        assertTrue(unfinished.all{it.id !in removed});assertFalse("preset" in removed);assertFalse("unknown" in removed)
        assertTrue(LibraryRetention.removable(remaining,"draft-1").isEmpty())
        assertEquals(setOf("draft-1","draft-2","draft-3","draft-4"),LibraryRetention.removable(drafts,"missing").toSet())
    }

    @Test fun characterFlowFillsPunctuationAndEnglishWithoutEarlyWordWrap(){
        val style=TextBlockStyle(widthMode="chars",characters=24,padding=0f)
        for(text in listOf("字".repeat(23)+"（下一行继续）", "字".repeat(20)+"Photography continues across lines and punctuation, naturally.","中文，English words with punctuation……（不要整词提前换行）。".repeat(8))){
            for(alignment in listOf("left","center","right","justify","distribute")){
                val doc=EditDocument(caption=text,textStyle=style.copy(alignment=alignment))
                val block=CaptionTypography.measure(doc,960f,640f,Typeface.DEFAULT)!!
                val layout=block.paragraphs.single().layout
                assertTrue(layout.lineCount>1)
                for(line in 0 until layout.lineCount-1){
                    assertTrue("Full line $line is too short for $alignment: ${layout.getLineMax(line)}",layout.getLineMax(line)*block.layoutScale>=block.width*.995f)
                    assertTrue("Overflow $alignment line=$line measured=${layout.getLineMax(line)} target=${block.width} text=${layout.text.subSequence(layout.getLineStart(line),layout.getLineEnd(line))}",layout.getLineMax(line)*block.layoutScale<=block.width+.2f)
                }
                val first=layout.text.subSequence(0,layout.getLineEnd(0)).toString().trimEnd('\n')
                if(text.startsWith("字".repeat(23)))assertEquals("字".repeat(23)+"（",first)
                if(text.startsWith("字".repeat(20)+"Photography")){assertTrue(first.contains("Photo"));assertFalse(first.contains("Photography"))}
                assertEquals(text,doc.caption)
            }
        }
        val last=CaptionTypography.measure(EditDocument(caption="一段正文，自动排满。".repeat(8)+"\n末行",textStyle=style),960f,640f,Typeface.DEFAULT)!!
        assertEquals(2,last.paragraphs.size)
        val ending=last.paragraphs.last().layout;assertEquals(1,ending.lineCount);assertTrue(ending.getLineWidth(0)*last.layoutScale<last.width/2)
    }
    @Test fun mixedCaptionDoesNotWrapAnAlreadyMeasuredLineAgain(){
        val text="2022年国庆，彼时的我尚对北京充满新奇感和探索欲。"
        for(font in listOf("sans-serif","serif","monospace"))for(width in listOf(500f,960f,1080f,4032f,6000f))for(bold in listOf(false,true)){
            val doc=EditDocument(caption=text,textStyle=TextBlockStyle(font=font,bold=bold,widthMode="chars",characters=24))
            val p=CaptionTypography.measure(doc,width,640f,app.renderer.typeface(font,bold))!!.paragraphs.single()
            assertFalse("Closing punctuation must not occupy a line on its own",p.text.split('\n').any{it=="。"})
            assertEquals("Second wrap: $font $bold width=$width authored=${p.text.replace("\n","|")} actual="+(0 until p.layout.lineCount).joinToString("|"){p.layout.text.subSequence(p.layout.getLineStart(it),p.layout.getLineEnd(it)).toString()},p.text.count{it=='\n'}+1,p.layout.lineCount)
        }
    }
    @Test fun detailPreviewRetainsPhysicalPixelsOnWideViewports(){
        val file=File(dir,"preview-stripes.png")
        val original=Bitmap.createBitmap(3200,600,Bitmap.Config.ARGB_8888)
        val row=IntArray(3200){if(it%2==0)Color.BLACK else Color.WHITE}
        for(y in 0 until original.height)original.setPixels(row,0,row.size,0,y,row.size,1)
        file.outputStream().use{original.compress(Bitmap.CompressFormat.PNG,100,it)}
        val asset=Assets.inspect(app,Uri.fromFile(file))
        val doc=EditDocument(assets=listOf(asset),frame=FrameStyle(left=0f,right=0f,top=0f,bottom=0f,footer=0f),caption="字".repeat(2000),textStyle=TextBlockStyle(widthMode="chars"))
        val scene=app.renderer.layout(doc);val photo=scene.photos.single().rect
        assertTrue("Fixture must exceed the full-scene preview budget",scene.height>2048)
        val detail=app.renderer.viewport(doc,2560,240,1f,-photo.left-200,-photo.top-100)
        try{
            assertEquals(2560,detail.width);assertEquals(240,detail.height)
            val expected=IntArray(2560*240);val actual=IntArray(expected.size)
            original.getPixels(expected,0,2560,200,100,2560,240);detail.getPixels(actual,0,2560,0,0,2560,240)
            assertArrayEquals("One-pixel photo detail must survive the visible-region preview",expected,actual)
            val (w,h)=previewViewportSize(10000,8000)
            assertTrue(w.toLong()*h<=4_194_304L+4096);assertTrue(max(w,h)<=4096)
        }finally{original.recycle();detail.recycle()}
    }
    @Test fun characterFlowNeverSplitsGraphemeClusters(){
        val text=("中文👨‍👩‍👧‍👦e\u0301🇨🇳👩🏽‍💻OpenAI，".repeat(25))
        val doc=EditDocument(caption=text,textStyle=TextBlockStyle(widthMode="chars",characters=8))
        val paragraph=CaptionTypography.measure(doc,800f,640f,Typeface.DEFAULT)!!.paragraphs.single()
        val iterator=android.icu.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT).apply{setText(text)}
        val boundaries=mutableSetOf<Int>();var end=iterator.first()
        while(end!=android.icu.text.BreakIterator.DONE){boundaries.add(end);end=iterator.next()}
        var offset=0
        for(line in paragraph.text.split('\n')){offset+=line.length;assertTrue("Split inside a grapheme at $offset",offset in boundaries)}
        assertEquals(text,paragraph.text.replace("\n",""));assertEquals(text,doc.caption)
    }

}
