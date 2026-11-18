package com.parker.imageborder.editor

import com.parker.imageborder.R

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class GalleryAccess { None, Selected, Full }
data class GalleryPhoto(val uri:String,val name:String,val width:Int,val height:Int,val date:Long,val albumId:String,val albumName:String)
data class GalleryAlbum(val id:String,val name:String,val count:Int)
data class GalleryPage(val photos:List<GalleryPhoto>,val hasMore:Boolean)

/** Album queries never run before a human grants photo access. No paths or private file access. */
object MediaGallery {
    private fun granted(context:Context,permission:String)=ContextCompat.checkSelfPermission(context,permission)==PackageManager.PERMISSION_GRANTED
    fun access(context:Context)=when{
        Build.VERSION.SDK_INT>=33&&granted(context,Manifest.permission.READ_MEDIA_IMAGES)->GalleryAccess.Full
        Build.VERSION.SDK_INT>=34&&granted(context,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)->GalleryAccess.Selected
        Build.VERSION.SDK_INT<=32&&granted(context,Manifest.permission.READ_EXTERNAL_STORAGE)->GalleryAccess.Full
        else->GalleryAccess.None
    }
    fun permissions():Array<String> = when{
        Build.VERSION.SDK_INT>=34->arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,Manifest.permission.ACCESS_MEDIA_LOCATION)
        Build.VERSION.SDK_INT>=33->arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.ACCESS_MEDIA_LOCATION)
        else->arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.ACCESS_MEDIA_LOCATION)
    }
    fun originalAccess(context:Context)=granted(context,Manifest.permission.ACCESS_MEDIA_LOCATION)
    fun importUri(uri:Uri,original:Boolean):Uri=if(original)MediaStore.setRequireOriginal(uri)else uri
    fun isDirectMedia(uri:Uri)=uri.scheme=="content"&&uri.authority==MediaStore.AUTHORITY&&uri.pathSegments.firstOrNull()!="picker"&&uri.pathSegments.contains("images")&&uri.pathSegments.contains("media")
    val collection:Uri get()=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    suspend fun page(context:Context,album:String?=null,offset:Int=0,limit:Int=180,scopeSelection:String?=null,scopeArgs:Array<String>?=null):GalleryPage=withContext(Dispatchers.IO){
        require(offset>=0&&limit in 1..500)
        val columns=arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.WIDTH,MediaStore.Images.Media.HEIGHT,MediaStore.Images.Media.DATE_TAKEN,MediaStore.Images.Media.DATE_ADDED,MediaStore.Images.Media.BUCKET_ID,MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val clauses=listOfNotNull(album?.let{"${MediaStore.Images.Media.BUCKET_ID} = ?"},scopeSelection)
        val args=(listOfNotNull(album)+(scopeArgs?.toList()?:emptyList())).toTypedArray()
        val query=Bundle().apply{
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,"${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT,limit+1);putInt(ContentResolver.QUERY_ARG_OFFSET,offset)
            if(clauses.isNotEmpty()){putString(ContentResolver.QUERY_ARG_SQL_SELECTION,clauses.joinToString(" AND "));putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,args)}
        }
        val cancel=CancellationSignal();val job=currentCoroutineContext()[Job]?.invokeOnCompletion{if(it is CancellationException)cancel.cancel()}
        try{
            val photos=mutableListOf<GalleryPhoto>()
            context.contentResolver.query(collection,columns,query,cancel)?.use{c->
                while(c.moveToNext()&&photos.size<=limit){
                    currentCoroutineContext().ensureActive()
                    val id=c.getLong(0);val taken=c.getLong(4);val date=if(taken>0)taken else c.getLong(5)*1000
                    photos.add(GalleryPhoto(ContentUris.withAppendedId(collection,id).toString(),c.getString(1)?:appText(R.string.ui_photo_48e9e5),c.getInt(2),c.getInt(3),date,c.getString(6)?:"",c.getString(7)?:appText(R.string.ui_uncategorized_b28f13)))
                }
            };GalleryPage(photos.take(limit),photos.size>limit)
        }finally{job?.dispose()}
    }
    suspend fun albums(context:Context,scopeSelection:String?=null,scopeArgs:Array<String>?=null):List<GalleryAlbum> = withContext(Dispatchers.IO){
        val groups=linkedMapOf<String,Pair<String,Int>>()
        context.contentResolver.query(collection,arrayOf(MediaStore.Images.Media.BUCKET_ID,MediaStore.Images.Media.BUCKET_DISPLAY_NAME),scopeSelection,scopeArgs,"${MediaStore.Images.Media.DATE_ADDED} DESC")?.use{c->
            while(c.moveToNext()){currentCoroutineContext().ensureActive();val id=c.getString(0)?:"";val old=groups[id];groups[id]=(c.getString(1)?:appText(R.string.ui_uncategorized_b28f13)) to ((old?.second?:0)+1)}
        };groups.map{(id,v)->GalleryAlbum(id,v.first,v.second)}
    }
}

class GallerySelection(val limit:Int,initial:List<String> = emptyList()) {
    init{require(limit in 1..100)}
    var uris:List<String> = initial.distinct().take(limit);private set
    /** False means the limit prevented adding; removing is always possible. */
    fun toggle(uri:String):Boolean {
        if(uri in uris){uris=uris-uri;return true}
        if(uris.size>=limit)return false
        uris=uris+uri;return true
    }
}

class GalleryThumbnails(private val context:Context){
    private val semaphore=Semaphore(4)
    private val cache=object:android.util.LruCache<String,Bitmap>(20*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount/1024}
    suspend fun load(photo:GalleryPhoto):Bitmap?=semaphore.withPermit{withContext(Dispatchers.IO){
        cache[photo.uri]?.let{return@withContext it}
        val signal=CancellationSignal();val job=currentCoroutineContext()[Job]?.invokeOnCompletion{if(it is CancellationException)signal.cancel()}
        try{runCatching{context.contentResolver.loadThumbnail(Uri.parse(photo.uri),Size(256,256),signal)}.getOrNull()?.also{cache.put(photo.uri,it)}}finally{job?.dispose()}
    }}
    fun clear()=cache.evictAll()
}
