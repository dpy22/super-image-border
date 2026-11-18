package com.parker.imageborder.editor

import android.graphics.*
import android.os.SystemClock
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.exifinterface.media.ExifInterface
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicitly invoked after the owner selects an HDR photo. Never scans the gallery. */
class RealPhotoAcceptanceTest {
    @Test fun selectedRealHdrPhotoExportsAtOriginalResolution()=runBlocking {
        assumeTrue("Select an HDR photo before opting in to this test",
            InstrumentationRegistry.getArguments().getString("useSelectedPhoto") == "true")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EditorApplication
        val prefs=app.editorPreferences.data.first();val last=prefs[stringPreferencesKey("last_document")]?:error("尚未选择照片")
        val record=app.database.records().get(last)?:error("当前草稿未保存")
        val current=EditDocument.read(JSONObject(record.payload))
        val source=current.current?.takeIf{it.hdr}?:error("当前照片不是可识别的 HDR 原图")
        require(!source.uri.contains("acceptance")){"需要用户选择的真实原图"}
        val doc=app.templates.first{it.id=="plate"}.apply(current.copy(assets=listOf(source),active=0,adjustments=Adjustments(),layers=emptyList(),caption="",title="",export=ExportOptions(mode="original",format="hdr")))
        app.cancelExport.set(false);val start=SystemClock.elapsedRealtime();val scene=app.renderer.layout(doc)
        val expected=scene.output(doc.export);var savedUri=""
        RenderExporter(app).renderFiles(doc,{}){file,_,_->
            val decoded=ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)){d,_,_->d.allocator=ImageDecoder.ALLOCATOR_SOFTWARE}
            assertTrue("Real output gainmap",decoded.hasGainmap());assertEquals(expected.first,decoded.width);assertEquals(expected.second,decoded.height);decoded.recycle()
            val exif=ExifInterface(file);assertNull(exif.latLong);assertNull(exif.getAttribute("BodySerialNumber"));assertEquals(1,exif.getAttributeInt("Orientation",0))
            source.exif["Model"]?.let{assertEquals(it,exif.getAttribute("Model"))}
            val output=File(File(app.cacheDir,"shared-images").apply{mkdirs()},"real-hdr-check.jpg");file.copyTo(output,true)
            savedUri=FileProvider.getUriForFile(app,app.packageName+".fileprovider",output).toString()
        }
        val dir=File(app.cacheDir,"acceptance").apply{mkdirs()}
        File(dir,"real-hdr-result.txt").writeText("Source: ${source.width}x${source.height}, ${source.colorSpace}, orientation=${source.orientation}\nCamera: ${source.exif["Make"]?:"missing"} ${source.exif["Model"]?:"missing"}\nOutput: ${expected.first}x${expected.second}, gainmap=true, orientation=1, GPS/serial removed\nElapsed: ${SystemClock.elapsedRealtime()-start}ms\nURI: $savedUri\n")
    }
}
