package com.parker.imageborder.editor

import com.parker.imageborder.R

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import org.json.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

val Context.editorPreferences by preferencesDataStore("native_editor")
data class ExportProgress(val running:Boolean=false,val completed:Int=0,val total:Int=0,val text:String="",val jobId:String="",val outputs:List<String> = emptyList())

class EditorApplication:Application(){
    override fun onCreate(){super.onCreate();AppText.initialize(this)}
    val applicationScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    val database by lazy{Room.databaseBuilder(this,EditorDatabase::class.java,"photo-editor.db").build()}
    val templates by lazy{val j=JSONObject(assets.open("templates.json").bufferedReader().use{it.readText()});j.getJSONArray("templates").objects().map{
        TemplateDefinition(it.getString("id"),it.getString("name"),it.getInt("family"),it.getInt("variant"),it.getString("category"))}}
    val renderer by lazy{FrameRenderer(this,templates)}
    val progress=MutableStateFlow(ExportProgress())
    val cancelExport=AtomicBoolean(false)
    private val saveSequence=AtomicLong()
    private val saveMutex=Mutex()
    private var selectedSave=0L
    private val writtenDrafts=mutableMapOf<String,Long>()
    fun nextSave()=saveSequence.incrementAndGet()
    suspend fun persistDraft(doc:EditDocument,stamp:Long,select:Boolean)=saveMutex.withLock{
        if(stamp<(writtenDrafts[doc.id]?:0L))return@withLock
        record(doc.id,"draft",doc.title.ifBlank{doc.current?.name?:appText(R.string.ui_text_project_d280e8)},doc.json().toString());writtenDrafts[doc.id]=stamp
        if(select&&stamp>=selectedSave){editorPreferences.edit{it[stringPreferencesKey("last_document")]=doc.id};selectedSave=stamp}
    }
    suspend fun pruneLibrary(currentDraft:String)=withContext(Dispatchers.IO){
        database.runInTransaction{
            val dao=database.records()
            LibraryRetention.removable(dao.list("draft")+dao.list("job"),currentDraft).forEach{dao.delete(it)}
        }
    }
    suspend fun record(id:String,kind:String,title:String,payload:String){withContext(Dispatchers.IO){database.records().put(EditorRecord().apply{
        this.id=id;this.kind=kind;this.title=title;this.payload=payload;updated=System.currentTimeMillis()})}}
}

class EditorViewModel(application:Application):AndroidViewModel(application){
    val app=application as EditorApplication
    var document by mutableStateOf(EditDocument());private set
    var preview by mutableStateOf<PreviewFrame?>(null);private set
    var message by mutableStateOf("")
    var loading by mutableStateOf(false);private set
    var selectedLayer by mutableStateOf<String?>(null)
    var textEditorOpen by mutableStateOf(false);private set
    var watermarkInput by mutableStateOf("");private set
    private var textDraft:WatermarkLayer?=null
    private var textDraftPosition=0
    var previewError by mutableStateOf("");private set
    var frameResetGeneration by mutableIntStateOf(0);private set
    var favorites by mutableStateOf(setOf<String>());private set
    var records by mutableStateOf(listOf<EditorRecord>());private set
    var historyRevision by mutableIntStateOf(0);private set
    var migrated by mutableStateOf(false);private set
    var initialized by mutableStateOf(false);private set
    var hdrPreview by mutableStateOf(false);private set
    var rendering by mutableStateOf(false);private set
    var reduceTransparency by mutableStateOf(false);private set
    private val history=EditHistory()
    private val initializedSignal=CompletableDeferred<Unit>()
    private data class PreviewRequest(val document:EditDocument,val hdr:Boolean,val edge:Int,val revision:Long)
    private val previewRequests=Channel<PreviewRequest>(Channel.CONFLATED)
    private var previewRevision=0L
    private var refineJob:Job?=null;private var saveJob:Job?=null;private var gesture:EditDocument?=null
    val canUndo get()=historyRevision.let{history.canUndo||gesture?.let{it!=document}==true}
    val canRedo get()=historyRevision.let{history.canRedo&&(gesture==null||gesture==document)}
    init{
        viewModelScope.launch{
            for(request in previewRequests){
                val snapshot=request.document
                try{
                    val result=withContext(Dispatchers.Default){
                        if(snapshot.assets.isEmpty()&&!snapshot.textOnly)null
                        else if(request.hdr)RenderExporter(app).hdrPreview(snapshot,request.edge)
                        else app.renderer.preview(snapshot,request.edge)
                    }
                    // Publish intermediate frames while the user continues editing. Requiring an
                    // exact revision match would starve rendering during fast input.
                    if(document.id==snapshot.id&&document.current?.id==snapshot.current?.id&&hdrPreview==request.hdr){preview=result;previewError=""}
                }catch(e:CancellationException){throw e}
                catch(e:OutOfMemoryError){if(previewRevision==request.revision){previewError=appText(R.string.ui_not_enough_memory_for_preview_43f118);message=appText(R.string.ui_not_enough_memory_choose_a_smaller_photo_f569fa)}}
                catch(e:Exception){if(previewRevision==request.revision){previewError=e.message?:appText(R.string.ui_preview_failed_d28588);message=e.message?:appText(R.string.ui_preview_failed_relink_the_photo_92093d);if(request.hdr){hdrPreview=false;refresh()}}}
                finally{if(previewRevision==request.revision)rendering=false}
            }
        }
        val initialId=document.id;viewModelScope.launch{try{
        val prefs=app.editorPreferences.data.first();favorites=prefs[stringSetPreferencesKey("favorites")]?:emptySet()
        migrated=prefs[booleanPreferencesKey("migrated_1_1")]?:false
        reduceTransparency=prefs[booleanPreferencesKey("reduce_transparency")]?:false
        val last=prefs[stringPreferencesKey("last_document")]
        if(last!=null){val r=withContext(Dispatchers.IO){app.database.records().get(last)}
            if(r!=null&&document.id==initialId&&historyRevision==0)runCatching{EditDocument.read(JSONObject(r.payload))}.onSuccess{
                var loaded=it
                val asset=loaded.current
                if(asset!=null&&asset.metadataVersion<2){
                    val tags=withContext(Dispatchers.IO){runCatching{Assets.readMetadata(Assets.exif(app,Uri.parse(asset.uri)))}.getOrNull()}
                    if(tags!=null)loaded=loaded.copy(assets=loaded.assets.map{photo->if(photo.id==asset.id)photo.copy(exif=tags,metadataVersion=2)else photo})
                }
                if(document.id==initialId&&historyRevision==0){document=loaded;if(loaded!=it)app.persistDraft(loaded,app.nextSave(),true);refresh()}
            }.onFailure{message=appText(R.string.ui_cannot_read_this_draft_original_data_was_kept_fb5207)}}
        refreshRecords()
        }catch(e:Exception){message=appText(R.string.ui_cannot_read_drafts_retry_from_the_library_76631b)}finally{initialized=true;initializedSignal.complete(Unit)}
    }}
    fun beginGesture(){if(gesture==null)gesture=document}
    fun endGesture(){
        val changed=gesture?.let{it!=document}?:false
        val changedScene=gesture?.let{it.copy(export=document.export)!=document}?:false
        gesture?.let{if(it!=document){history.record(it);historyRevision++}};gesture=null
        if(changed&&changedScene)refresh(fast=false)
    }
    fun change(next:EditDocument,continuous:Boolean=false){
        val cleaned=next.clean()
        if(cleaned==document)return
        if(continuous)beginGesture()else{endGesture();history.record(document);historyRevision++}
        val previous=document
        val converting=(hdrPreview||document.current?.hdr==true||document.export.format=="hdr")&&document.adjustments.isNeutral&&!cleaned.adjustments.isNeutral
        if(converting){hdrPreview=false;message=appText(R.string.ui_switched_to_sdr_adjustments_0621af)}
        if(!cleaned.sceneAssets(app.renderer.template(cleaned.template)).any{it.hdr})hdrPreview=false
        val validHdr=cleaned.adjustments.isNeutral&&cleaned.sceneAssets(app.renderer.template(cleaned.template)).any{it.hdr}
        document=if(cleaned.export.format=="hdr"&&!validHdr)cleaned.copy(export=cleaned.export.copy(format="jpeg"))else cleaned
        if(previous.id!=document.id||previous.current?.id!=document.current?.id){preview=null;previewError="";deselectLayer()}
        if(document.layers.none{it.id==selectedLayer})selectedLayer=null
        save();if(previous.copy(export=document.export)!=document)refresh()
    }
    private fun restoreHistory(value:EditDocument){document=value.clean();deselectLayer();if(!document.adjustments.isNeutral||!document.sceneAssets(app.renderer.template(document.template)).any{it.hdr})hdrPreview=false;historyRevision++;save();refresh()}
    fun undo(){endGesture();history.undo(document)?.let(::restoreHistory)}
    fun redo(){endGesture();history.redo(document)?.let(::restoreHistory)}
    fun updateTransform(t:PhotoTransform,continuous:Boolean=false){
        if(document.currentTransform.locked&&t.locked)return
        val a=document.current
        change(if(a==null)document.copy(transform=t)else document.copy(overrides=document.overrides+(a.id to (document.overrides[a.id]?:PhotoOverride()).copy(transform=t))),continuous)
    }
    fun updateLayer(layer:WatermarkLayer,continuous:Boolean=false)=change(document.copy(layers=document.layers.map{if(it.id==layer.id)layer else it}),continuous)
    fun addLayer(layer:WatermarkLayer=WatermarkLayer(),continuous:Boolean=false){
        if(layer.uri.isBlank()&&layer.text.isBlank()){beginTextWatermark();return}
        if(document.layers.size>=30){message=appText(R.string.ui_add_up_to_30_watermarks_29f486);return}
        selectedLayer=layer.id;change(document.copy(layers=document.layers+layer),continuous)
    }
    fun selectLayer(id:String){endGesture();selectedLayer=id;val layer=document.layers.firstOrNull{it.id==id};textEditorOpen=false;textDraft=layer?.takeIf{it.uri.isBlank()};textDraftPosition=document.layers.indexOfFirst{it.id==id}.coerceAtLeast(0);watermarkInput=textDraft?.text?:""}
    fun deselectLayer(){endGesture();selectedLayer=null;textEditorOpen=false;textDraft=null;watermarkInput=""}
    fun beginTextWatermark(){
        if(document.layers.size>=30){message=appText(R.string.ui_add_up_to_30_watermarks_29f486);return}
        endGesture();selectedLayer=null;textDraft=WatermarkLayer(text="",x=1f,y=1f,anchor=if(app.renderer.template(document.template).family==7)"canvas"else "photo")
        textDraftPosition=document.layers.size
        watermarkInput="";textEditorOpen=true
    }
    fun editWatermarkText(value:String){
        if(textDraft==null||textDraft?.id!=selectedLayer&&selectedLayer!=null)textDraft=document.layers.firstOrNull{it.id==selectedLayer}?:WatermarkLayer(text="",x=1f,y=1f,anchor="photo")
        val layer=(document.layers.firstOrNull{it.id==textDraft?.id}?:requireNotNull(textDraft)).copy(text=value.take(2000));textDraft=layer;watermarkInput=layer.text;textEditorOpen=true
        val others=document.layers.filterNot{it.id==layer.id}
        if(layer.text.isBlank()){change(document.copy(layers=others),true);selectedLayer=null}
        else{if(others.size>=30){message=appText(R.string.ui_add_up_to_30_watermarks_29f486);return};selectedLayer=layer.id
            val existing=document.layers.indexOfFirst{it.id==layer.id};if(existing>=0)textDraftPosition=existing
            val layers=others.toMutableList().apply{add(textDraftPosition.coerceIn(0,size),layer)};change(document.copy(layers=layers),true)}
    }
    fun finishTextWatermark(){endGesture();textEditorOpen=false;if(watermarkInput.isBlank()){selectedLayer=null;textDraft=null;watermarkInput=""}}
    fun removeLayer(id:String?=selectedLayer){
        endGesture();if(id!=null)change(document.copy(layers=document.layers.filterNot{it.id==id}));deselectLayer()
    }
    fun activateAsset(index:Int){
        val asset=document.assets.getOrNull(index)?:return;val t=app.renderer.template(document.template);val chosen=document.sceneAssets(t)
        var slots=document.collageAssets
        if(t.family==7&&t.variant!=3&&chosen.none{it.id==asset.id}){
            val ids=chosen.map{it.id}.toMutableList();val slot=chosen.indexOfFirst{it.id==document.current?.id}.coerceAtLeast(0)
            if(ids.isNotEmpty())ids[slot]=asset.id;slots=ids
        }
        change(document.copy(active=index,collageAssets=slots))
    }
    fun setMetadataVisible(value:Boolean)=change(document.copy(showMetadata=value,metadataCustomized=true))
    fun removeAsset(id:String){
        val index=document.assets.indexOfFirst{it.id==id};if(index<0)return
        val remaining=document.assets.filterNot{it.id==id}
        val active=when{remaining.isEmpty()->0;index<document.active->document.active-1;index==document.active->index.coerceAtMost(remaining.lastIndex);else->document.active}
        change(document.copy(assets=remaining,active=active,overrides=document.overrides-id))
        if(remaining.isEmpty())preview=null
    }
    fun replaceAsset(uris:List<Uri>,append:Boolean=false,relink:Boolean=false){
        if(uris.size+(if(append)document.assets.size else 0)>100){message=appText(R.string.ui_select_up_to_100_photos_385ce1);return}
        loading=true
        viewModelScope.launch{
            initializedSignal.await()
            val results=withContext(Dispatchers.IO){uris.map{uri->runCatching{
                runCatching{app.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)}
                val asset=Assets.inspect(app,uri)
                // Shares without a persistent URI grant receive a durable private copy.
                val persistent=uri.scheme=="file"||app.contentResolver.persistedUriPermissions.any{it.uri==uri&&it.isReadPermission}||
                    MediaGallery.isDirectMedia(uri)&&MediaGallery.access(app)!=GalleryAccess.None
                if(persistent)asset else {
                    val dir=File(app.filesDir,"imports").apply{mkdirs()};val file=File(dir,asset.id+".image")
                    app.contentResolver.openInputStream(uri).use{input->requireNotNull(input);file.outputStream().use{input.copyTo(it)}}
                    asset.copy(uri=Uri.fromFile(file).toString())
                }
            }}}
            val valid=results.mapNotNull{it.getOrNull()}
            if(valid.isNotEmpty())applyImportedAssets(valid,append,relink)
            message=if(results.any{it.isFailure})results.first{it.isFailure}.exceptionOrNull()?.message?:appText(R.string.ui_some_photos_could_not_be_read_2cd483) else appText(R.string.ui_imported_1_s_7222dc, valid.size)
            loading=false
        }
    }
    internal fun applyImportedAssets(valid:List<PhotoAsset>,append:Boolean,relink:Boolean=false){
        if(valid.isEmpty())return
        if(relink&&document.current!=null){val current=document.current!!;val replacement=valid.first().copy(id=current.id)
            change(document.copy(assets=document.assets.map{if(it.id==current.id)replacement else it}));return}
        val archive=document.current!=null||document.textOnly&&(document.caption.isNotBlank()||document.layers.isNotEmpty())
        if(archive)persistNow(select=false)
        change(document.copy(id=if(archive)uid()else document.id,assets=if(append)document.assets+valid else valid,
            active=if(append)document.assets.size else 0,textOnly=false,overrides=if(append)document.overrides else emptyMap()))
    }
    fun resetFrame(){change(document.copy(frame=app.renderer.template(document.template).apply(document).frame));frameResetGeneration++}
    fun resetExport()=change(document.copy(export=ExportOptions()))
    fun refreshMetadata(){
        val asset=document.current?:return
        viewModelScope.launch{
            runCatching{withContext(Dispatchers.IO){Assets.readMetadata(Assets.exif(app,Uri.parse(asset.uri)))}}
                .onSuccess{tags->if(document.assets.any{it.id==asset.id}){change(document.copy(assets=document.assets.map{if(it.id==asset.id)it.copy(exif=tags,metadataVersion=2)else it}));message=appText(R.string.ui_original_metadata_fields_1_s_b285ac, tags.size)}}
                .onFailure{message=appText(R.string.ui_cannot_read_metadata_relink_the_original_92618e)}
        }
    }
    fun newDocument(textOnly:Boolean=false){if(document.current!=null||document.textOnly)persistNow(select=false);change(EditDocument(textOnly=textOnly));deselectLayer();preview=null}
    fun refresh(fast:Boolean=true){
        refineJob?.cancel()
        val snapshot=document;val wantHdr=hdrPreview
        if(snapshot.assets.isEmpty()&&!snapshot.textOnly){preview=null;rendering=false;return}
        fun enqueue(edge:Int){previewRevision++;rendering=true;previewRequests.trySend(PreviewRequest(snapshot,wantHdr,edge,previewRevision))}
        enqueue(if(fast)768 else if(wantHdr)1536 else 2048)
        if(fast)refineJob=viewModelScope.launch{
            delay(300)
            // An undo gesture may stay open while a text field retains focus.
            // Idle input must still receive the full-resolution refinement.
            if(document==snapshot)enqueue(if(wantHdr)1536 else 2048)
        }
    }
    fun changeReduceTransparency(value:Boolean){reduceTransparency=value;viewModelScope.launch{app.editorPreferences.edit{it[booleanPreferencesKey("reduce_transparency")]=value}}}
    fun toggleHdrPreview(){hdrPreview=!hdrPreview;refresh()}
    fun restoreAndEnableHdr(){change(document.copy(adjustments=Adjustments()));hdrPreview=true;refresh()}
    private fun save(){
        saveJob?.cancel();val snapshot=document;val stamp=app.nextSave()
        saveJob=viewModelScope.launch{delay(350);app.persistDraft(snapshot,stamp,true)}
    }
    fun persistNow(select:Boolean=true){
        if(!initialized)return
        saveJob?.cancel();val snapshot=document;val stamp=app.nextSave()
        app.applicationScope.launch{app.persistDraft(snapshot,stamp,select)}
    }
    fun refreshRecords(flushCurrent:Boolean=false){viewModelScope.launch{
        if(flushCurrent&&initialized&&(document.current!=null||document.textOnly&&(document.caption.isNotBlank()||document.layers.isNotEmpty()))){
            saveJob?.cancel();app.persistDraft(document,app.nextSave(),true)
        }
        if(flushCurrent&&initialized)app.pruneLibrary(document.id)
        records=withContext(Dispatchers.IO){listOf("draft","preset","job").flatMap{app.database.records().list(it)}}
    }}
    fun openRecord(record:EditorRecord){runCatching{
        val value=EditDocument.read(JSONObject(record.payload))
        change(if(record.kind=="preset")value.copy(id=document.id,assets=document.assets,active=document.active,overrides=document.overrides,textOnly=document.textOnly,
            caption=document.caption.ifBlank{value.caption},title=document.title.ifBlank{value.title},
            template=if(document.textOnly&&app.renderer.template(value.template).family !in listOf(0,4))"white"else value.template,
            frame=if(document.textOnly)value.frame.copy(autoColor=false)else value.frame,adjustments=if(document.textOnly)Adjustments()else value.adjustments)else value)
    }.onFailure{message=appText(R.string.ui_cannot_read_1_s_5c2543, it.message)}}
    fun deleteRecord(record:EditorRecord){viewModelScope.launch{withContext(Dispatchers.IO){app.database.records().delete(record.id)};refreshRecords()}}
    fun savePreset(name:String){viewModelScope.launch{val p=document.preset();app.record(p.id,"preset",name.ifBlank{appText(R.string.ui_my_style_b4a4b2)},p.json().toString());refreshRecords();message=appText(R.string.ui_preset_saved_on_this_device_e5aaf7)}}
    fun favorite(id:String){favorites=if(id in favorites)favorites-id else favorites+id;viewModelScope.launch{app.editorPreferences.edit{it[stringSetPreferencesKey("favorites")]=favorites}}}
    fun migrateLegacy(raw:String?){
        viewModelScope.launch{
            if(!raw.isNullOrBlank()&&document.layers.isEmpty())runCatching{
                val j=JSONObject(raw);val layer=WatermarkLayer(text=j.optString("text",appText(R.string.ui_my_work_d16228)),font=j.optString("font","sans-serif"),
                    x=j.float("x",1f),y=j.float("y",1f),size=j.float("size",3f)/100,opacity=j.float("opacity",85f)/100,
                    angle=j.float("angle",0f),color=runCatching{android.graphics.Color.parseColor(j.optString("color","#ffffff"))}.getOrDefault(android.graphics.Color.WHITE),
                    tiled=j.optString("mode")=="tile",bold=j.optBoolean("bold"),shadow=j.optBoolean("shadow",true),outline=j.optBoolean("outline"),background=j.optBoolean("background"))
                change(document.copy(layers=listOf(layer)));message=appText(R.string.ui_legacy_watermark_settings_imported_58f9f8)
            }
            app.editorPreferences.edit{it[booleanPreferencesKey("migrated_1_1")]=true};migrated=true
        }
    }
    fun startExport(batch:Boolean){
        if(app.progress.value.running){message=appText(R.string.ui_an_export_is_already_running_4f4d23);return}
        endGesture();val snapshot=document
        if(snapshot.assets.isEmpty()&&!snapshot.textOnly){message=appText(R.string.ui_choose_a_photo_or_create_a_text_canvas_415bbb);return}
        val multi=app.renderer.template(snapshot.template).family==7
        val documents=if(batch&&!multi&&!snapshot.textOnly)snapshot.assets.indices.map{snapshot.copy(active=it)}else listOf(snapshot)
        val id=uid();app.progress.value=ExportProgress(true,0,documents.size,appText(R.string.ui_preparing_export_6e17d6),id)
        viewModelScope.launch{
            try{val job=JSONObject().put("version",1).put("status","waiting").put("items",JSONArray(documents.map{
                JSONObject().put("document",it.json()).put("status","waiting").put("outputs",JSONArray()).put("error","")
            }))
            app.record(id,"job",appText(R.string.ui_export_1_s_785636, documents.size),job.toString());ExportService.start(app,id);refreshRecords()
            }catch(e:Exception){app.progress.value=ExportProgress();message=appText(R.string.ui_cannot_start_export_1_s_08ea04, e.message)}
        }
    }
    fun resumeJob(id:String){if(!app.progress.value.running){ExportService.start(app,id);message=appText(R.string.ui_resuming_task_b0f8b7)}}
    fun importPreset(uri:Uri){viewModelScope.launch{runCatching{withContext(Dispatchers.IO){PresetPackages.import(app,uri)}}.onSuccess{saveImportedPreset(it)}.onFailure{message=appText(R.string.ui_import_failed_1_s_7bab25, it.message)}}}
    private fun saveImportedPreset(pair:Pair<String,EditDocument>){viewModelScope.launch{app.record(pair.second.id,"preset",pair.first,pair.second.json().toString());refreshRecords();message=appText(R.string.ui_preset_imported_a64bd1)}}
}
