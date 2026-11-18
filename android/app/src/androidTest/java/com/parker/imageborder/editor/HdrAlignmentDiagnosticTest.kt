package com.parker.imageborder.editor

import android.graphics.*
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class HdrAlignmentDiagnosticTest {
    @Before fun requireSelectedPhoto() {
        assumeTrue("Select an HDR photo before opting in to these diagnostics",
            InstrumentationRegistry.getArguments().getString("useSelectedPhoto") == "true")
    }

    @Test fun compareCurrentPreviewGeometry()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EditorApplication
        val last=app.editorPreferences.data.first()[stringPreferencesKey("last_document")]!!
        val doc=EditDocument.read(JSONObject(app.database.records().get(last).payload)).copy(adjustments=Adjustments())
        val low=app.renderer.preview(doc,1000);val high=RenderExporter(app).hdrPreview(doc,1000)
        val w=low.bitmap.width;val h=low.bitmap.height
        val sheet=Bitmap.createBitmap(w*2,h,Bitmap.Config.ARGB_8888);val canvas=Canvas(sheet)
        canvas.drawBitmap(low.bitmap,null,RectF(0f,0f,w.toFloat(),h.toFloat()),null)
        canvas.drawBitmap(high.bitmap,null,RectF(w.toFloat(),0f,w*2f,h.toFloat()),null)
        val dir=File(app.cacheDir,"acceptance").apply{mkdirs()}
        File(dir,"current-preview-comparison.png").outputStream().use{sheet.compress(Bitmap.CompressFormat.PNG,100,it)}
        File(dir,"current-preview-layout.txt").writeText("template=${doc.template}, transform=${doc.currentTransform.json()}\nSDR=$w x $h, HDR=${high.bitmap.width} x ${high.bitmap.height}, scene=${low.layout.width} x ${low.layout.height}")
        listOf(low.bitmap,high.bitmap,sheet).forEach{it.recycle()}
    }
    @Test fun compareDecoders()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EditorApplication
        val last=app.editorPreferences.data.first()[stringPreferencesKey("last_document")]!!
        val doc=EditDocument.read(JSONObject(app.database.records().get(last).payload));val asset=doc.current!!
        val source=Assets.cacheFile(app,asset.uri);val dir=File(app.cacheDir,"acceptance").apply{mkdirs()}
        val width=600;val height=(600.0*asset.height/asset.width).toInt()
        val sheet=Bitmap.createBitmap(width*2,(height+48)*2,Bitmap.Config.ARGB_8888);val canvas=Canvas(sheet);canvas.drawColor(Color.DKGRAY)
        val labels=listOf("Android SDR","Native HDR resized","Native HDR full","BitmapFactory SDR")
        val log=StringBuilder("asset ${asset.width}x${asset.height} orientation=${asset.orientation}\n")
        for(i in 0..3){
            val raw=when(i){0->Assets.preview(app,asset,1024);1->NativeCodec.decodeHdr(source.path,1024);2->NativeCodec.decodeHdr(source.path,0);else->BitmapFactory.decodeFile(source.path,BitmapFactory.Options().apply{inSampleSize=4})}
            log.append("${labels[i]} ${raw.width}x${raw.height} ${raw.config} ${raw.colorSpace}\n")
            val target=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);val out=Canvas(target)
            if(i==0)out.drawBitmap(raw,null,RectF(0f,0f,width.toFloat(),height.toFloat()),Paint(Paint.FILTER_BITMAP_FLAG))
            else{out.scale(width/asset.width.toFloat(),height/asset.height.toFloat());out.concat(app.renderer.photoMatrix(PhotoNode(asset,RectF(0f,0f,asset.width.toFloat(),asset.height.toFloat()),PhotoTransform())));BitmapPixels(mapOf(asset.id to raw)).draw(out,asset,Adjustments())}
            val x=(i%2)*width.toFloat();val y=(i/2)*(height+48).toFloat()
            canvas.drawBitmap(target,x,y+48,null);canvas.drawText(labels[i],x+12,y+32,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=26f})
            target.recycle();raw.recycle()
        }
        File(dir,"decoder-comparison.png").outputStream().use{sheet.compress(Bitmap.CompressFormat.PNG,100,it)};sheet.recycle()
        File(dir,"decoder-comparison.txt").writeText(log.toString())
    }
    @Test fun compareComposites()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EditorApplication
        val dir=File(app.cacheDir,"acceptance");val last=app.editorPreferences.data.first()[stringPreferencesKey("last_document")]!!
        val current=EditDocument.read(JSONObject(app.database.records().get(last).payload));val asset=current.current!!
        val doc=app.templates.first{it.id=="plate"}.apply(current.copy(assets=listOf(asset),active=0,adjustments=Adjustments(),layers=emptyList(),caption="",title="",export=ExportOptions(mode="custom",format="hdr",maxEdge=1000)))
        val scene=app.renderer.layout(doc);val size=scene.output(doc.export);val width=size.first;val height=size.second
        val raw=NativeCodec.decodeHdr(Assets.cacheFile(app,asset.uri).path,1000)
        val base=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888,false,ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
        val high=Bitmap.createBitmap(width,height,Bitmap.Config.RGBA_F16,false,raw.colorSpace!!)
        val res=app.renderer.resources(doc);val scale=width/scene.width
        RegionPixels(app,ColorSpace.get(ColorSpace.Named.DISPLAY_P3)).use{app.renderer.draw(Canvas(base),doc,scene,it,scale,res)}
        BitmapPixels(mapOf(asset.id to raw)).use{app.renderer.draw(Canvas(high),doc,scene,it,scale,res)}
        val file=File(dir,"diagnostic-composite.jpg");NativeCodec.encodeHdr(base,high,file.path,95,0,MetadataWriter.block(app,doc,width,height))
        val encodedBase=ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)){d,_,_->d.allocator=ImageDecoder.ALLOCATOR_SOFTWARE}.also{it.gainmap=null}
        val encodedHdr=NativeCodec.decodeHdr(file.path,0)
        val sheet=Bitmap.createBitmap(width*2,(height+40)*2,Bitmap.Config.ARGB_8888);val canvas=Canvas(sheet);canvas.drawColor(Color.DKGRAY)
        for((i,b)in listOf(base,high,encodedBase,encodedHdr).withIndex()){
            val x=(i%2)*width.toFloat();val y=(i/2)*(height+40).toFloat()
            canvas.drawBitmap(b,null,RectF(x,y+40,x+width,y+40+height),Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.drawText(listOf("Before: SDR scene","Before: HDR scene","Encoded: SDR base","Encoded: HDR native")[i],x+10,y+28,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=22f})
        }
        File(dir,"composite-comparison.png").outputStream().use{sheet.compress(Bitmap.CompressFormat.PNG,100,it)}
        listOf(raw,base,high,encodedBase,encodedHdr,sheet).forEach{it.recycle()}
    }

}
