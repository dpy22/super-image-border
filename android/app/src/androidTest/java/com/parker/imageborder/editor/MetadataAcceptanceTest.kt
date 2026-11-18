package com.parker.imageborder.editor

import android.net.Uri
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Test

/** Explicit owner-authorized diagnosis; prints tag names only, never photo bytes, URI or timestamps. */
class MetadataAcceptanceTest {
    @Test fun selectedPhotoMetadataCoverage()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EditorApplication
        val id=app.editorPreferences.data.first()[stringPreferencesKey("last_document")]?:error("没有选中的照片")
        val doc=EditDocument.read(JSONObject(app.database.records().get(id).payload));val photo=requireNotNull(doc.current)
        require(!photo.uri.contains("acceptance")&&!photo.uri.contains("flow-audit"))
        val original=Assets.exif(app,Uri.parse(photo.uri));val read=Assets.readMetadata(original)
        val standard=Assets.fieldNames.keys-"LocationText"
        val xmp=original.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_XMP)
        val detail=jpegTagIds(app,Uri.parse(photo.uri))+"\nXMP property names: "+runCatching{val parser=android.util.Xml.newPullParser().apply{setInput(java.io.StringReader(xmp?:"<none/>"))};val names=linkedSetOf<String>();while(parser.eventType!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT){if(parser.eventType==org.xmlpull.v1.XmlPullParser.START_TAG){names.add(parser.name);for(i in 0 until parser.attributeCount)names.add(parser.getAttributeName(i))};parser.next()};names.joinToString()}.getOrDefault("unreadable")
        val summary="\nSelected source dimensions: ${photo.width}x${photo.height}, HDR=${photo.hdr}\nStored standard tags: ${standard.filter{!photo.exif[it].isNullOrBlank()}}\nOriginal standard tags: ${standard.filter{!read[it].isNullOrBlank()}}\nRecovered standard tags: ${standard.filter{photo.exif[it].isNullOrBlank()&&!read[it].isNullOrBlank()}}\nReadable non-location tag count: ${read.size}\nReadable tag names: ${read.keys.sorted()}\nXMP length: ${xmp?.length?:0}; parsed XMP fields: ${MetadataReader.normalize(emptyMap(),xmp).keys}\n$detail\n"
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream",summary)})
    }
    private fun jpegTagIds(app:EditorApplication,uri:Uri):String=runCatching{
        app.contentResolver.openInputStream(uri).use{stream->
            val input=java.io.DataInputStream(requireNotNull(stream));if(input.readUnsignedShort()!=0xffd8)return "Not JPEG"
            while(true){
                if(input.readUnsignedByte()!=255)break
                var marker=input.readUnsignedByte();while(marker==255)marker=input.readUnsignedByte()
                if(marker==0xda||marker==0xd9)break
                val size=input.readUnsignedShort()-2;if(size<0)break
                val data=ByteArray(size);input.readFully(data)
                if(marker!=0xe1||data.size<14||String(data,0,4,Charsets.US_ASCII)!="Exif")continue
                val buffer=java.nio.ByteBuffer.wrap(data,6,data.size-6).slice();buffer.order(if(buffer.getShort(0).toInt()==0x4949)java.nio.ByteOrder.LITTLE_ENDIAN else java.nio.ByteOrder.BIG_ENDIAN)
                fun entries(offset:Int):List<Triple<Int,Int,Int>>{
                    if(offset<8||offset+2>buffer.limit())return emptyList()
                    val count=buffer.getShort(offset).toInt() and 65535
                    return (0 until count.coerceAtMost(512)).mapNotNull{i->val at=offset+2+12*i;if(at+12>buffer.limit())null else Triple(buffer.getShort(at).toInt() and 65535,buffer.getShort(at+2).toInt() and 65535,buffer.getInt(at+8))}
                }
                val root=entries(buffer.getInt(4));val sub=entries(root.firstOrNull{it.first==0x8769}?.third?:0)
                return "Raw JPEG IFD0 tag IDs: ${root.map{it.first.toString(16)}}\nRaw JPEG EXIF tag IDs: ${sub.map{it.first.toString(16)}}"
            };"No EXIF segment"
        }
    }.getOrDefault("Raw tag structure unavailable")

}
