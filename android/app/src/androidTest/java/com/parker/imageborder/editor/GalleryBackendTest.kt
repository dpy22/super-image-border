package com.parker.imageborder.editor

import android.content.ContentValues
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Only this app's temporary synthetic pictures. Does not grant or request library permissions. */
class GalleryBackendTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EditorApplication
    private suspend fun withPictures(block:suspend(List<Uri>,String)->Unit){
        val path="Pictures/文字拼图验收/${uid()}/";val created=mutableListOf<Uri>()
        val file=File.createTempFile("gallery-fixture-",".jpg",app.cacheDir)
        try{
            val image=Bitmap.createBitmap(128,96,Bitmap.Config.ARGB_8888);val c=Canvas(image);c.drawColor(Color.rgb(70,130,165));c.drawCircle(84f,40f,22f,Paint().apply{color=Color.rgb(240,185,80)})
            file.outputStream().use{image.compress(Bitmap.CompressFormat.JPEG,98,it)};image.recycle()
            ExifInterface(file).apply{
                setAttribute(ExifInterface.TAG_MAKE,"TEST CAMERA");setAttribute(ExifInterface.TAG_MODEL,"GALLERY FIXTURE")
                setAttribute(ExifInterface.TAG_F_NUMBER,"2.8");setAttribute(ExifInterface.TAG_EXPOSURE_TIME,"1/250")
                setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,"400");setAttribute(ExifInterface.TAG_FOCAL_LENGTH,"85/1")
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,"2026:10:26 10:20:30");setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL,"+08:00")
                setLatLong(12.0,34.0);saveAttributes()
            }
            repeat(7){index->
                val uri=requireNotNull(app.contentResolver.insert(MediaGallery.collection,ContentValues().apply{
                    put(MediaStore.Images.Media.DISPLAY_NAME,"gallery-fixture-$index.jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH,path);put(MediaStore.Images.Media.IS_PENDING,1)
                }));created.add(uri)
                app.contentResolver.openOutputStream(uri).use{output->requireNotNull(output);file.inputStream().use{it.copyTo(output)}}
                app.contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
            }
            block(created,path)
        }finally{
            created.forEach{app.contentResolver.delete(it,null,null)};file.delete()
        }
    }
    @Test fun pagesAndAlbumsUseMediaStoreWithoutDecodingOriginals()=runBlocking {
        withPictures{created,path->
            val where="${MediaStore.Images.Media.RELATIVE_PATH} = ?";val args=arrayOf(path)
            val first=MediaGallery.page(app,offset=0,limit=3,scopeSelection=where,scopeArgs=args)
            val second=MediaGallery.page(app,offset=3,limit=3,scopeSelection=where,scopeArgs=args)
            val last=MediaGallery.page(app,offset=6,limit=3,scopeSelection=where,scopeArgs=args)
            assertEquals(3,first.photos.size);assertTrue(first.hasMore);assertEquals(3,second.photos.size);assertTrue(second.hasMore)
            assertEquals(1,last.photos.size);assertFalse(last.hasMore)
            assertEquals(created.map{it.toString()}.toSet(),(first.photos+second.photos+last.photos).map{it.uri}.toSet())
            val albums=MediaGallery.albums(app,where,args);assertEquals(1,albums.size);assertEquals(7,albums.single().count)
            val filtered=MediaGallery.page(app,album=albums.single().id,scopeSelection=where,scopeArgs=args)
            assertEquals(7,filtered.photos.size)
        }
    }
    @Test fun directMediaImportsKeepPhotographyTagsAndSmallThumbnails()=runBlocking {
        withPictures{created,path->
            val uri=created.first();assertTrue(MediaGallery.isDirectMedia(uri));assertFalse(MediaGallery.isDirectMedia(Uri.parse("content://media/picker/0/provider/media/1")))
            assertTrue(MediaStore.getRequireOriginal(MediaGallery.importUri(uri,true)));assertEquals(uri,MediaGallery.importUri(uri,false))
            val asset=Assets.inspect(app,uri)
            assertEquals("TEST CAMERA",asset.exif["Make"]);assertEquals("GALLERY FIXTURE",asset.exif["Model"])
            assertEquals(2.8,Assets.rational(asset.exif.getValue("FNumber"))!!,.001);assertEquals(1.0/250,Assets.rational(asset.exif.getValue("ExposureTime"))!!,.000001)
            assertEquals("400",asset.exif["PhotographicSensitivity"]);assertEquals(85.0,Assets.rational(asset.exif.getValue("FocalLength"))!!,.01)
            assertFalse(asset.exif.keys.any{it.startsWith("GPS")})
            val page=MediaGallery.page(app,scopeSelection="${MediaStore.Images.Media.RELATIVE_PATH} = ?",scopeArgs=arrayOf(path))
            val thumbnail=GalleryThumbnails(app).load(page.photos.first());assertNotNull(thumbnail);assertTrue(thumbnail!!.width<=512&&thumbnail.height<=512)
        }
    }
    @Test fun selectionIsOrderedRemovableAndLimited(){
        val choice=GallerySelection(3)
        assertTrue(choice.toggle("a"));assertTrue(choice.toggle("b"));assertTrue(choice.toggle("c"));assertFalse(choice.toggle("d"))
        assertTrue(choice.toggle("b"));assertTrue(choice.toggle("d"));assertEquals(listOf("a","c","d"),choice.uris)
        assertEquals(listOf("a","b"),GallerySelection(2,listOf("a","a","b","c")).uris)
        assertThrows(IllegalArgumentException::class.java){GallerySelection(0)}
    }
}
