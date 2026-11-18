@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.parker.imageborder.editor

import com.parker.imageborder.R

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.*
import androidx.compose.ui.semantics.*
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.*
import kotlin.math.*

@Composable fun EditorScreen(vm:EditorViewModel,openLegacy:()->Unit,migrate:()->Unit,hdrWindow:(Boolean)->Unit){
    LiquidEditorTheme(isSystemInDarkTheme(),vm.reduceTransparency){EditorContent(vm,openLegacy,migrate,hdrWindow)}
}

@Composable private fun EditorContent(vm:EditorViewModel,openLegacy:()->Unit,migrate:()->Unit,hdrWindow:(Boolean)->Unit){
    val backdrop=rememberLayerBackdrop()
    CompositionLocalProvider(LocalLiquidBackdrop provides backdrop){
        val context=LocalContext.current;val scope=rememberCoroutineScope();val doc=vm.document
        var panel by rememberSaveable{mutableIntStateOf(0)};var expanded by rememberSaveable{mutableStateOf(true)};var exportSheet by remember{mutableStateOf(false)};var library by remember{mutableStateOf(false)}
        var hdrConflict by remember{mutableStateOf(false)};var fontForText by remember{mutableStateOf(false)}
        var colorEdit by remember{mutableStateOf<Pair<Int,(Int)->Unit>?>(null)};var presetName by remember{mutableStateOf<String?>(null)}
        var append by remember{mutableStateOf(false)};var relink by remember{mutableStateOf(false)};var pickColor by remember{mutableStateOf(false)}
        var libraryMenu by remember{mutableStateOf(false)};var deleteRecord by remember{mutableStateOf<EditorRecord?>(null)}
        var settingsOpen by rememberSaveable{mutableStateOf(false)}
        var gallery by rememberSaveable{mutableStateOf(false)}
        val snackbar=remember{SnackbarHostState()};val progress by vm.app.progress.collectAsState()
        val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
        val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->if(uris.isNotEmpty())vm.replaceAsset(uris,append,relink)}
        val systemPicker=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)){uris->if(uris.isNotEmpty())vm.replaceAsset(uris,append,relink)}
        val logoPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch{
            runCatching{withContext(Dispatchers.IO){PresetPackages.importOverlay(context,uri)}}.onSuccess{vm.addLayer(WatermarkLayer(uri=it,text="",size=.015f,anchor=if(vm.app.renderer.template(vm.document.template).family==7)"canvas"else "photo"))}.onFailure{vm.message=it.message?:appText(R.string.ui_could_not_import_logo_dcc8e6)}}}
        val fontPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch{
            runCatching{withContext(Dispatchers.IO){PresetPackages.importFont(context,uri)}}.onSuccess{path->if(fontForText)vm.change(vm.document.copy(textStyle=vm.document.textStyle.copy(font=path)))else vm.document.layers.firstOrNull{it.id==vm.selectedLayer}?.let{vm.updateLayer(it.copy(font=path))}}.onFailure{vm.message=it.message?:appText(R.string.ui_could_not_import_font_5ca5e8)}}}
        val presetPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.importPreset(uri)}
        val presetWriter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->if(uri!=null)scope.launch{
            runCatching{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri).use{out->requireNotNull(out);PresetPackages.export(context,vm.document,appText(R.string.ui_my_frame_style_d3c223),out)}}}
                .onSuccess{vm.message=appText(R.string.ui_preset_exported_327020)}.onFailure{vm.message=it.message?:appText(R.string.ui_could_not_export_preset_9cb613)}}}
        LaunchedEffect(vm.message){if(vm.message.isNotBlank()){
            val message=vm.message;scope.launch{snackbar.showSnackbar(message)};vm.message=""
        }}
        LaunchedEffect(progress.running,progress.jobId,progress.text){
            if(!progress.running&&progress.text.isNotBlank()){
                scope.launch{
                    val result=snackbar.showSnackbar(progress.text,actionLabel=if(progress.outputs.isNotEmpty())appText(R.string.ui_share_7a9243)else null)
                    if(result==SnackbarResult.ActionPerformed)share(context,progress.outputs)
                }
                if(vm.app.progress.value==progress)vm.app.progress.value=ExportProgress()
            }
        }
        LaunchedEffect(vm.initialized,vm.migrated){if(vm.initialized&&!vm.migrated){delay(300);migrate()}}
        LaunchedEffect(vm.hdrPreview){hdrWindow(vm.hdrPreview)}
        LaunchedEffect(doc.textOnly){if(doc.textOnly&&panel in listOf(EditorTools.Metadata,EditorTools.Adjust))panel=EditorTools.Text}
        val keyboard=LocalSoftwareKeyboardController.current
        val focus=androidx.compose.ui.platform.LocalFocusManager.current
        fun choose(add:Boolean=false,replace:Boolean=false){
            if(add&&vm.document.assets.size>=100){vm.message=appText(R.string.ui_select_up_to_100_photos_385ce1);return}
            vm.endGesture();focus.clearFocus();keyboard?.hide();append=add;relink=replace;gallery=true
        }
        fun finishInput(){focus.clearFocus();keyboard?.hide();vm.finishTextWatermark();vm.endGesture()}
        BackHandler(enabled=expanded&&!library&&!exportSheet&&!settingsOpen){finishInput();expanded=false}
        if(settingsOpen)SettingsScreen(vm.reduceTransparency,vm::changeReduceTransparency,{settingsOpen=false},{settingsOpen=false;openLegacy()}) else Box(Modifier.fillMaxSize()){
            LiquidEditorWorkspace(backdrop=backdrop,hasPhoto=doc.current!=null||doc.textOnly,hdr=vm.hdrPreview,panel=panel,expanded=expanded,
                onExpanded={finishInput();expanded=!expanded},onPanel={finishInput();panel=it;expanded=true;if(it!=EditorTools.Watermark)vm.deselectLayer()},
                onLibrary={finishInput();library=true;vm.refreshRecords(true)},onSettings={vm.endGesture();vm.finishTextWatermark();focus.clearFocus();keyboard?.hide();vm.persistNow();settingsOpen=true},onExport={finishInput();exportSheet=true},
                canExport=(doc.current!=null||doc.textOnly&&(doc.caption.isNotBlank()||doc.layers.isNotEmpty()))&&!progress.running,canUndo=vm.canUndo,canRedo=vm.canRedo,onUndo={finishInput();vm.undo()},onRedo={finishInput();vm.redo()},
                sourceRibbon={if(doc.assets.isNotEmpty())LiquidPhotoRail(vm){choose(true)}},
                sourceInfo={
                    if(doc.sceneAssets(vm.app.renderer.template(doc.template)).any{it.hdr})LiquidSurface(clear=true,shape=RoundedCornerShape(24.dp)){
                        TextButton(onClick={if(!vm.hdrPreview&&!doc.adjustments.isNeutral)hdrConflict=true else vm.toggleHdrPreview()},modifier=Modifier.height(48.dp)){
                            Icon(if(vm.hdrPreview)Icons.Outlined.HdrOn else Icons.Outlined.HdrOff,null,Modifier.size(22.dp));Spacer(Modifier.width(6.dp));Text("HDR",style=MaterialTheme.typography.labelMedium)
                        }
                    }
                },photo={
                    if(vm.preview!=null)PhotoStage(vm,vm.preview!!,pickColor,editPhoto=panel==EditorTools.Canvas,onSelectLayer={id->vm.selectLayer(id);panel=EditorTools.Watermark;expanded=true},onColor={picked->pickColor=false;vm.change(vm.document.copy(frame=vm.document.frame.copy(color=picked,autoColor=false)))})
                    else Column(Modifier.align(Alignment.Center).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){
                        Icon(if(doc.current!=null)Icons.Outlined.BrokenImage else Icons.Outlined.AddPhotoAlternate,null,tint=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.65f),modifier=Modifier.size(52.dp))
                        if(doc.current!=null){
                            Text(vm.previewError.ifBlank{appText(R.string.ui_reading_photo_21bea4)},style=MaterialTheme.typography.bodyMedium)
                            Button(onClick={choose(false,true)}){Text(appText(R.string.ui_relink_photo_b25b42))}
                            TextButton(onClick={doc.current?.let{vm.removeAsset(it.id)}}){Text(appText(R.string.ui_remove_photo_284a67))}
                        }else{
                            Button(onClick={choose()},contentPadding=PaddingValues(horizontal=24.dp,vertical=14.dp)){Text(appText(R.string.ui_choose_photos_4a8a30))}
                            TextButton(onClick={vm.newDocument(true);panel=EditorTools.Text;expanded=true}){Text(appText(R.string.ui_text_canvas_3c9525))}
                        }
                    }
                    if(vm.loading)CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp),strokeWidth=2.dp)
                    if(pickColor)LiquidSurface(Modifier.align(Alignment.TopCenter).padding(12.dp),clear=true){Text(appText(R.string.ui_tap_to_sample_e24590),modifier=Modifier.padding(horizontal=16.dp,vertical=10.dp),style=MaterialTheme.typography.labelMedium)}
                    if(vm.rendering&&vm.preview!=null)CircularProgressIndicator(Modifier.align(Alignment.BottomEnd).padding(12.dp).size(16.dp),strokeWidth=1.5f.dp)
                    if(vm.previewError.isNotBlank()&&vm.preview!=null)LiquidSurface(Modifier.align(Alignment.TopCenter).padding(8.dp)){
                        TextButton(onClick={vm.refresh(false)}){Icon(Icons.Outlined.Refresh,null,Modifier.size(18.dp));Text(appText(R.string.ui_retry_preview_151114))}
                    }
                },controls={
                    when(panel){
                        0->TemplatesPanel(vm)
                        1->CanvasPanel(vm,{choose(false,true)})
                        2->FramePanel(vm,{color,callback->colorEdit=color to callback},{pickColor=!pickColor})
                        3->ParagraphPanel(vm,{fontForText=true;fontPicker.launch(arrayOf("font/*","application/octet-stream","application/x-font-ttf","application/vnd.ms-opentype"))},{color,callback->colorEdit=color to callback})
                        4->MetadataPanel(vm)
                        5->WatermarkPanel(vm,{logoPicker.launch(arrayOf("image/*","image/svg+xml"))},{fontForText=false;fontPicker.launch(arrayOf("font/*","application/octet-stream","application/x-font-ttf","application/vnd.ms-opentype"))},{color,callback->colorEdit=color to callback})
                        6->AdjustPanel(vm)
                    }
                },progress={if(progress.running)LiquidSurface(Modifier.fillMaxWidth()){
                    Row(Modifier.fillMaxWidth().padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Spacer(Modifier.width(10.dp));Text(progress.text,modifier=Modifier.weight(1f),style=MaterialTheme.typography.labelMedium);TextButton(onClick={vm.app.cancelExport.set(true)}){Text(appText(R.string.ui_pause_130448))}}
                }},hasSources=doc.assets.isNotEmpty(),hasProgress=progress.running,
                textOnly=doc.textOnly,requestedControlHeight=when{panel==EditorTools.Watermark&&vm.selectedLayer==null&&!vm.textEditorOpen->if(doc.layers.isEmpty())72.dp else 128.dp;panel==EditorTools.Metadata&&!doc.showMetadata->128.dp;else->null},
                hasNotice=snackbar.currentSnackbarData!=null,notice={SnackbarHost(snackbar,Modifier.fillMaxWidth().padding(horizontal=4.dp))},onReset=when(panel){EditorTools.Frame->{ {finishInput();vm.resetFrame()} };EditorTools.Text->{ {finishInput();vm.change(vm.document.copy(textStyle=TextBlockStyle()))} };EditorTools.Adjust->{ {finishInput();vm.change(vm.document.copy(adjustments=Adjustments()))} };else->null})
        }
        if(colorEdit!=null){val (initial,callback)=colorEdit!!;ColorDialog(initial,vm.preview?.palette.orEmpty(),{colorEdit=null}){color->callback(color);colorEdit=null}}
        if(gallery)GalleryPicker(if(relink)1 else (100-if(append)vm.document.assets.size else 0).coerceAtLeast(1),dismiss={gallery=false},
            files={gallery=false;picker.launch(arrayOf("image/*"))},
            systemPicker={gallery=false;systemPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},
            confirm={uris->gallery=false;vm.replaceAsset(uris,append,relink)})
        if(hdrConflict)AlertDialog(onDismissRequest={hdrConflict=false},title={Text(appText(R.string.ui_enable_hdr_4d4fc7))},text={Text(appText(R.string.ui_the_current_adjustments_use_sdr_restore_original_c_1e29f0))},confirmButton={TextButton(onClick={hdrConflict=false;vm.restoreAndEnableHdr()}){Text(appText(R.string.ui_restore_and_enable_9c7c7e))}},dismissButton={TextButton(onClick={hdrConflict=false}){Text(appText(R.string.ui_keep_adjustments_1c9abc))}})
        if(presetName!=null){var name by remember{mutableStateOf(appText(R.string.ui_my_frame_style_d3c223))};AlertDialog(onDismissRequest={presetName=null},title={Text(appText(R.string.ui_save_preset_ced36e))},text={LiquidTextField(name,appText(R.string.ui_preset_name_a32b08),{name=it})},confirmButton={TextButton(onClick={vm.savePreset(name);presetName=null}){Text(appText(R.string.ui_save_fadf24))}},dismissButton={TextButton(onClick={presetName=null}){Text(appText(R.string.ui_cancel_4d0b46))}})}
        if(exportSheet)ModalBottomSheet(onDismissRequest={exportSheet=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=Color.Transparent,scrimColor=Color.Black.copy(alpha=.25f),dragHandle=null){
            LiquidSurface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(topStart=32.dp,topEnd=32.dp)){
                Column(Modifier.fillMaxHeight(.9f)){
                    Row(Modifier.fillMaxWidth().padding(start=16.dp,end=8.dp,top=8.dp),verticalAlignment=Alignment.CenterVertically){Text(appText(R.string.ui_export_188896),style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));TextButton(onClick=vm::resetExport,modifier=Modifier.semantics{contentDescription=appText(R.string.ui_reset_export_settings_8e5cc0)}){Text(appText(R.string.ui_reset_a19193))};LiquidIconButton(Icons.Outlined.Close,appText(R.string.ui_close_export_3122cb),{exportSheet=false})}
                    ExportPanel(vm,{batch->exportSheet=false;if(Build.VERSION.SDK_INT>=33)notifications.launch(Manifest.permission.POST_NOTIFICATIONS);vm.startExport(batch)},Modifier.weight(1f))
                }
            }
        }
        deleteRecord?.let{record->AlertDialog(onDismissRequest={deleteRecord=null},title={Text(when(record.kind){"draft"->appText(R.string.ui_delete_draft_41d7dc);"preset"->appText(R.string.ui_delete_preset_884ea1);else->appText(R.string.ui_delete_export_record_d5bfe5)})},text={Text(if(record.kind=="job")appText(R.string.ui_saved_images_will_be_kept_6393e9)else appText(R.string.ui_this_cannot_be_undone_bb7f36))},confirmButton={TextButton(onClick={vm.deleteRecord(record);deleteRecord=null}){Text(appText(R.string.ui_delete_3755f5),color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={deleteRecord=null}){Text(appText(R.string.ui_cancel_4d0b46))}})}
        if(library)ModalBottomSheet(onDismissRequest={library=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surface,scrimColor=Color.Black.copy(alpha=.25f),shape=RoundedCornerShape(topStart=32.dp,topEnd=32.dp)){
            Row(Modifier.fillMaxWidth().padding(start=16.dp,end=12.dp),verticalAlignment=Alignment.CenterVertically){
                Text(appText(R.string.ui_library_a32c78),style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                Box{
                    TextButton(onClick={libraryMenu=true}){Text(appText(R.string.ui_more_9b0c6c))}
                    DropdownMenu(libraryMenu,onDismissRequest={libraryMenu=false}){
                        DropdownMenuItem(text={Text(appText(R.string.ui_save_as_preset_74c1d9))},onClick={libraryMenu=false;presetName="save"})
                        DropdownMenuItem(text={Text(appText(R.string.ui_import_preset_e4ee6f))},onClick={libraryMenu=false;presetPicker.launch(arrayOf("application/zip","application/octet-stream"))})
                        DropdownMenuItem(text={Text(appText(R.string.ui_export_current_preset_3b77b3))},onClick={libraryMenu=false;presetWriter.launch(appText(R.string.ui_my_frame_style_zip_f88408))})
                    }
                }
                TextButton(onClick={library=false}){Text(appText(R.string.ui_done_33246f))}
            }
            ActionRow(Modifier.padding(horizontal=16.dp,vertical=8.dp)){
                FilledTonalButton(onClick={library=false;vm.newDocument()},modifier=Modifier.heightIn(min=48.dp)){Text(appText(R.string.ui_new_project_50280d))}
                OutlinedButton(onClick={library=false;vm.newDocument(true);panel=EditorTools.Text},modifier=Modifier.heightIn(min=48.dp)){Text(appText(R.string.ui_text_canvas_3c9525))}
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=520.dp).navigationBarsPadding().padding(horizontal=16.dp)){
                for(kind in listOf("draft","preset","job").filter{group->vm.records.any{it.kind==group}}){
                    item{Text(when(kind){"job"->appText(R.string.ui_export_tasks_a79849);"preset"->appText(R.string.ui_presets_36f2c2);else->appText(R.string.ui_drafts_0f4368)},style=MaterialTheme.typography.titleSmall,modifier=Modifier.padding(top=16.dp,bottom=6.dp))}
                    items(vm.records.filter{it.kind==kind},key={it.id}){record->
                        Row(Modifier.fillMaxWidth().clickable{
                            if(kind=="job"){val j=org.json.JSONObject(record.payload);val errors=j.getJSONArray("items").objects().mapNotNull{it.optString("error").takeIf(String::isNotBlank)}
                                if(errors.isNotEmpty())vm.message=errors.distinct().joinToString("\n")
                                else vm.message=jobStatus(j.optString("status"))
                            }else{vm.openRecord(record);library=false}
                        }.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1f)){Text(record.title,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis);if(kind=="job")Text(jobStatus(org.json.JSONObject(record.payload).optString("status")),style=MaterialTheme.typography.labelSmall)}
                            if(kind=="job"){
                                val job=org.json.JSONObject(record.payload)
                                if(job.optString("status")=="done")IconButton(onClick={val outputs=job.getJSONArray("items").objects().flatMap{item->val values=item.getJSONArray("outputs");(0 until values.length()).map{values.getString(it)}};share(context,outputs)}){Icon(Icons.Outlined.Share,appText(R.string.ui_share_image_df80b4))}
                                else TextButton(onClick={vm.resumeJob(record.id);library=false},enabled=!progress.running){Text(if(job.optString("status")=="failed")appText(R.string.ui_retry_e2d53a)else appText(R.string.ui_continue_1fc1af))}
                            }
                            TextButton(onClick={deleteRecord=record},enabled=!(progress.running&&progress.jobId==record.id)){Text(appText(R.string.ui_delete_3755f5),style=MaterialTheme.typography.bodySmall)}
                        }
                    }
                }
                item{Spacer(Modifier.height(24.dp))}
            }
        }
    }
}

@Composable private fun TemplatesPanel(vm:EditorViewModel){
    var category by remember{mutableIntStateOf(vm.app.renderer.template(vm.document.template).family)};val doc=vm.document
    val categoryScroll=androidx.compose.foundation.lazy.rememberLazyListState()
    val availableTemplates=vm.app.templates.filter{!doc.textOnly||it.family in listOf(0,4)}
    val families=availableTemplates.map{it.family}.distinct()
    val thumbnailDocument=doc.copy(layers=emptyList(),export=ExportOptions())
    LaunchedEffect(category,families){categoryScroll.animateScrollToItem(if(category<0)0 else (families.indexOf(category)+1).coerceAtLeast(0))}
    LaunchedEffect(doc.template,doc.textOnly){if(category!=-1)category=if(doc.textOnly&&vm.app.renderer.template(doc.template).family !in listOf(0,4))0 else vm.app.renderer.template(doc.template).family}
    Column(Modifier.fillMaxSize()){
        LazyRow(state=categoryScroll,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){
            item{FilterChip(selected=category==-1,onClick={category=-1},label={Icon(Icons.Outlined.StarBorder,appText(R.string.ui_favorites_d07cee),Modifier.size(18.dp))},border=null,shape=RoundedCornerShape(20.dp),colors=liquidChipColors())}
            items(availableTemplates.distinctBy{it.family}){t->FilterChip(selected=category==t.family,onClick={category=t.family},label={Text(listOf(appText(R.string.ui_minimal_4d9492),appText(R.string.ui_camera_plate_65e490),appText(R.string.ui_palette_5ef29a),appText(R.string.ui_glass_9d90a6),appText(R.string.ui_paper_ba3eee),appText(R.string.ui_film_a27997),appText(R.string.ui_editorial_ad150c),appText(R.string.ui_collage_78b698))[t.family])},border=null,shape=RoundedCornerShape(20.dp),colors=liquidChipColors())}
        }
        LazyRow(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=10.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
            items(availableTemplates.filter{if(category==-1)it.id in vm.favorites else it.family==category},key={it.id}){t->
                var thumbnail by remember(t.id,doc.current?.id){mutableStateOf<Bitmap?>(null)}
                LaunchedEffect(t.id,thumbnailDocument){if(doc.current!=null||doc.textOnly){delay(220);thumbnail=withContext(Dispatchers.Default){runCatching{vm.app.renderer.preview(t.apply(thumbnailDocument),200).bitmap}.getOrNull()}}}
                Surface(onClick={vm.change(t.apply(vm.document).copy(textOnly=vm.document.textOnly))},modifier=Modifier.width(maxOf(104f,76f*LocalDensity.current.fontScale).dp).fillMaxHeight(),shape=RoundedCornerShape(16.dp),
                    color=MaterialTheme.colorScheme.onSurface.copy(alpha=if(doc.template==t.id).055f else .025f),border=if(doc.template==t.id)BorderStroke(1.8f.dp,MaterialTheme.colorScheme.primary)else null){
                    Column{
                        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp),contentAlignment=Alignment.Center){
                            if(thumbnail!=null)Image(thumbnail!!.asImageBitmap(),uiLabel(t.name),contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize())
                            else Icon(Icons.Outlined.CropSquare,uiLabel(t.name),modifier=Modifier.size(36.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.5f))
                            IconButton(onClick={vm.favorite(t.id)},modifier=Modifier.size(48.dp).align(Alignment.TopEnd).offset(x=8.dp,y=(-8).dp)){
                                Icon(if(t.id in vm.favorites)Icons.Outlined.Star else Icons.Outlined.StarBorder,appText(R.string.ui_favorite_1_s_200108, uiLabel(t.name)),tint=if(t.id in vm.favorites)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.size(16.dp))
                            }
                        }
                        Text(uiLabel(t.name),style=MaterialTheme.typography.labelMedium,color=if(doc.template==t.id)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,modifier=Modifier.fillMaxWidth().padding(bottom=9.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable private fun CanvasPanel(vm:EditorViewModel,relink:()->Unit){val d=vm.document;val t=d.currentTransform
    var advanced by remember{mutableStateOf(false)}
    Panel{
        Choices(listOf("original" to appText(R.string.ui_original_ratio_4905be),"1:1" to "1:1","4:5" to "4:5","3:4" to "3:4","2:3" to "2:3","9:16" to "9:16","16:9" to "16:9","custom" to appText(R.string.ui_custom_c49333)),d.ratio){vm.change(vm.document.copy(ratio=it))}
        if(d.ratio=="custom")Control("画布宽高比",d.customRatio,.2f..3f,vm){vm.change(vm.document.copy(customRatio=it),true)}
        if(!d.textOnly){
        ActionRow{
            OutlinedButton(onClick={vm.updateTransform(t.copy(rotation=(t.rotation+90+180)%360-180))},enabled=!t.locked){Icon(Icons.Outlined.Rotate90DegreesCcw,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(appText(R.string.ui_rotate_a69e51))}
            OutlinedButton(onClick={vm.updateTransform(t.copy(mirror=!t.mirror))},enabled=!t.locked){Icon(Icons.Outlined.Flip,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(appText(R.string.ui_flip_e64cd6))}
            TextButton(onClick={vm.updateTransform(PhotoTransform())},enabled=!t.locked){Text(appText(R.string.ui_reset_a19193))}
        }
        Control("照片大小",t.zoom,.2f..4f,vm,enabled=!t.locked){vm.updateTransform(vm.document.currentTransform.copy(zoom=it),true)}
        TextButton(onClick={advanced=!advanced}){Text(appText(R.string.ui_position_crop_7f3f4c));Icon(if(advanced)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)}
        if(advanced){
            Toggle(appText(R.string.ui_lock_photo_position_9f70a7),t.locked){vm.updateTransform(t.copy(locked=it))}
            if(!t.locked){
                Toggle(appText(R.string.ui_fill_frame_e60938),t.cover){vm.updateTransform(vm.document.currentTransform.copy(cover=it))}
                Control("旋转角度",t.rotation.coerceIn(-180f,180f),-180f..180f,vm){vm.updateTransform(vm.document.currentTransform.copy(rotation=it),true)}
                if(abs(t.zoom-1f)>.01f||t.cover)Control("水平位置",t.x,0f..1f,vm){vm.updateTransform(vm.document.currentTransform.copy(x=it),true)}
                if(abs(t.zoom-1f)>.01f||t.cover)Control("垂直位置",t.y,0f..1f,vm){vm.updateTransform(vm.document.currentTransform.copy(y=it),true)}
                Control("左边界",t.cropLeft,0f..min(.9f,t.cropRight-.05f),vm){vm.updateTransform(vm.document.currentTransform.copy(cropLeft=it),true)}
                Control("上边界",t.cropTop,0f..min(.9f,t.cropBottom-.05f),vm){vm.updateTransform(vm.document.currentTransform.copy(cropTop=it),true)}
                Control("右边界",t.cropRight,max(.1f,t.cropLeft+.05f)..1f,vm){vm.updateTransform(vm.document.currentTransform.copy(cropRight=it),true)}
                Control("下边界",t.cropBottom,max(.1f,t.cropTop+.05f)..1f,vm){vm.updateTransform(vm.document.currentTransform.copy(cropBottom=it),true)}
            }
            if(d.current!=null)TextButton(onClick=relink){Text(appText(R.string.ui_relink_original_3ec83f))}
        }
        }
    }
}

@Composable private fun FramePanel(vm:EditorViewModel,color:(Int,(Int)->Unit)->Unit,pipette:()->Unit){val f=vm.document.frame
    Panel{
        var independent by remember(vm.document.id,vm.document.template,vm.frameResetGeneration){mutableStateOf(setOf(f.left,f.top,f.right,f.bottom).size>1)}
        if(!independent)Control("四边留白",f.left,0f..0.5f,vm){v->vm.change(vm.document.copy(frame=vm.document.frame.copy(left=v,top=v,right=v,bottom=v)),true)}
        Toggle(appText(R.string.ui_separate_margins_3dd123),independent){value->independent=value;if(!value){val current=vm.document.frame;vm.change(vm.document.copy(frame=current.copy(top=current.left,right=current.left,bottom=current.left)))}}
        if(independent){
            Control("左边",f.left,0f..0.5f,vm){vm.change(vm.document.copy(frame=vm.document.frame.copy(left=it)),true)}
            Control("上边",f.top,0f..0.5f,vm){vm.change(vm.document.copy(frame=vm.document.frame.copy(top=it)),true)}
            Control("右边",f.right,0f..0.5f,vm){vm.change(vm.document.copy(frame=vm.document.frame.copy(right=it)),true)}
            Control("下边",f.bottom,0f..0.5f,vm){vm.change(vm.document.copy(frame=vm.document.frame.copy(bottom=it)),true)}
        }
        val template=vm.app.renderer.template(vm.document.template)
        if(template.family!=1)Control("底栏留白",f.footer,0f..0.6f,vm){vm.change(vm.document.copy(frame=vm.document.frame.copy(footer=it)),true)}
        if(!vm.document.textOnly)Control("圆角",f.radius,0f..0.3f,vm){vm.change(vm.document.copy(frame=vm.document.frame.copy(radius=it)),true)}
        if(!vm.document.textOnly)Control("外描边",f.stroke,0f..0.012f,vm){vm.change(vm.document.copy(frame=vm.document.frame.copy(stroke=it)),true)}
        if(!vm.document.textOnly)Control("内描边",f.innerStroke,0f..0.012f,vm){vm.change(vm.document.copy(frame=vm.document.frame.copy(innerStroke=it)),true)}
        if(!vm.document.textOnly)Control("投影",f.shadow,0f..0.08f,vm){vm.change(vm.document.copy(frame=vm.document.frame.copy(shadow=it)),true)}
        if(!vm.document.textOnly){
        Title(appText(R.string.ui_photo_palette_dae030))
        Toggle(appText(R.string.ui_match_photo_colors_1382d0),f.autoColor){vm.change(vm.document.copy(frame=vm.document.frame.copy(autoColor=it)))}
        val palette=vm.preview?.palette?:listOf(AColor.GRAY,AColor.DKGRAY)
        val main=palette.first();val hsv=FloatArray(3);AColor.colorToHSV(main,hsv)
        fun mix(c:Int,t:Float)=AColor.rgb((AColor.red(main)*(1-t)+AColor.red(c)*t).toInt(),(AColor.green(main)*(1-t)+AColor.green(c)*t).toInt(),(AColor.blue(main)*(1-t)+AColor.blue(c)*t).toInt())
        val suggestions=listOf(appText(R.string.ui_dominant_00eee0) to main,appText(R.string.ui_accent_b95519) to palette.maxBy{val color=FloatArray(3);AColor.colorToHSV(it,color);color[1]},appText(R.string.ui_light_80ec9e) to mix(AColor.WHITE,.78f),appText(R.string.ui_dark_30b2c9) to mix(AColor.BLACK,.65f),appText(R.string.ui_complement_269154) to AColor.HSVToColor(floatArrayOf((hsv[0]+180)%360,hsv[1],hsv[2])))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)){
            for((name,c)in suggestions)Column(horizontalAlignment=Alignment.CenterHorizontally){
                Surface(onClick={vm.change(vm.document.copy(frame=vm.document.frame.copy(color=c,autoColor=false)))},color=Color(c),shape=RoundedCornerShape(12.dp),border=BorderStroke(if(!f.autoColor&&f.color==c)2.dp else 1.dp,if(!f.autoColor&&f.color==c)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.size(48.dp).semantics{contentDescription=name}){}
                Text(name,style=MaterialTheme.typography.labelSmall)
            }
        }
        }
        ActionRow{TextButton(onClick={color(f.color){c->vm.change(vm.document.copy(frame=vm.document.frame.copy(color=c,autoColor=false)))}}){Text(appText(R.string.ui_color_8ef488))};TextButton(onClick=pipette){Text(appText(R.string.ui_eyedropper_168a76))}}
        if(!vm.document.textOnly&&vm.app.renderer.template(vm.document.template).family==3){
            Title(appText(R.string.ui_glass_effect_a7dad1))
            Control("模糊",f.blur,0f..1f,vm){vm.change(vm.document.copy(frame=vm.document.frame.copy(blur=it)),true)}
            Control("玻璃不透明度",f.glassOpacity,0f..1f,vm){vm.change(vm.document.copy(frame=vm.document.frame.copy(glassOpacity=it)),true)}
            Control("照片染色",f.tint,0f..1f,vm){vm.change(vm.document.copy(frame=vm.document.frame.copy(tint=it)),true)}
            Control("悬浮高度",f.lift,0f..0.1f,vm){vm.change(vm.document.copy(frame=vm.document.frame.copy(lift=it)),true)}
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun Panel(modifier:Modifier=Modifier.fillMaxSize(),content:@Composable ColumnScope.()->Unit){Column(modifier.testTag("tool-options").semantics{testTagsAsResourceId=true}.verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)}
@Composable internal fun Title(text:String){Text(text,style=MaterialTheme.typography.titleSmall,modifier=Modifier.padding(top=8.dp))}
@Composable internal fun Toggle(label:String,value:Boolean,onChange:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value,role=Role.Switch,onValueChange=onChange),verticalAlignment=Alignment.CenterVertically){
        Text(label,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
        Switch(value,null,colors=SwitchDefaults.colors(checkedThumbColor=Color.White,checkedTrackColor=LiquidGreen,uncheckedThumbColor=Color.White,uncheckedTrackColor=MaterialTheme.colorScheme.onSurface.copy(alpha=.20f),uncheckedBorderColor=Color.Transparent))
    }
}
@Composable internal fun liquidChipColors()=FilterChipDefaults.filterChipColors(containerColor=Color.Transparent,labelColor=MaterialTheme.colorScheme.onSurfaceVariant,selectedContainerColor=MaterialTheme.colorScheme.primary.copy(alpha=.12f),selectedLabelColor=MaterialTheme.colorScheme.primary)
@Composable internal fun Choices(values:List<Pair<String,String>>,selected:String,wrap:Boolean=false,onSelect:(String)->Unit){
    @Composable fun Items(){values.forEach{(key,label)->FilterChip(selected=selected==key,onClick={onSelect(key)},shape=RoundedCornerShape(22.dp),border=null,colors=liquidChipColors(),label={Text(label,style=MaterialTheme.typography.labelLarge,maxLines=1,softWrap=false)})}}
    if(wrap)ActionRow{Items()}else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)){Items()}
}
@Composable internal fun Control(label:String,value:Float,range:ClosedFloatingPointRange<Float>,vm:EditorViewModel,enabled:Boolean=true,displayLabel:String?=null,pixelScale:Float?=null,change:(Float)->Unit){
    val d=vm.document
    val unit=remember(d.current,d.currentTransform,d.template,d.collageAssets,d.textOnly){vm.app.renderer.layout(d).unit}
    val parameter=numericParameter(label,pixelScale?:unit)
    LiquidSliderRow(label,value,range,enabled,{vm.endGesture();vm.beginGesture()},vm::endGesture,displayLabel=displayLabel,parameter=parameter,onChange=change)
}

@Composable internal fun EditorText(value:String,label:String,vm:EditorViewModel,modifier:Modifier=Modifier.fillMaxWidth(),multi:Boolean=false,showLabel:Boolean=true,change:(String)->Unit){
    var wasFocused by remember{mutableStateOf(false)}
    LiquidTextField(value,label,change,modifier.onFocusChanged{state->if(state.isFocused&&!wasFocused)vm.beginGesture();if(!state.isFocused&&wasFocused)vm.endGesture();wasFocused=state.isFocused},multi,showLabel)
}
@Composable private fun ColorDialog(initial:Int,palette:List<Int>,dismiss:()->Unit,choose:(Int)->Unit){
    var text by remember{mutableStateOf("#%08X".format(initial))};val parsed=runCatching{AColor.parseColor(text)}.getOrNull()
    AlertDialog(onDismissRequest=dismiss,title={Text(appText(R.string.ui_color_8ef488))},text={Column{
        LiquidTextField(text,appText(R.string.ui_color_value_3def4d),{text=it})
        Spacer(Modifier.height(10.dp));Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){for((c,name) in listOf(AColor.WHITE to appText(R.string.ui_white_2d80ae),AColor.BLACK to appText(R.string.ui_black_5568c1),0xfff4efe4.toInt() to appText(R.string.ui_warm_white_519902),0xff738b7b.toInt() to appText(R.string.ui_sage_green_9672fc),AColor.TRANSPARENT to appText(R.string.ui_transparent_b1a302))){
            Column(horizontalAlignment=Alignment.CenterHorizontally){Surface(onClick={text="#%08X".format(c)},color=if(c==0)Color.LightGray else Color(c),shape=RoundedCornerShape(8.dp),border=BorderStroke(1.dp,Color.Gray),modifier=Modifier.size(48.dp).semantics{contentDescription=name}){if(c==0)Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Icon(Icons.Outlined.LayersClear,null,Modifier.size(22.dp),tint=Color.DarkGray)}}
            Text(name,style=MaterialTheme.typography.labelSmall,maxLines=1,modifier=Modifier.padding(top=4.dp))}
        }}
        if(palette.isNotEmpty()){
            Text(appText(R.string.ui_photo_palette_dae030),style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(top=12.dp,bottom=6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){for(c in palette.distinct())Surface(onClick={text="#%08X".format(c)},color=Color(c),shape=RoundedCornerShape(8.dp),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.size(48.dp).semantics{contentDescription=appText(R.string.ui_photo_color_06x_f3a6ce).format(c and 0xffffff)}){}}
        }
    }},confirmButton={TextButton(onClick={parsed?.let(choose)},enabled=parsed!=null){Text(appText(R.string.ui_ok_f526c8))}},dismissButton={TextButton(onClick=dismiss){Text(appText(R.string.ui_cancel_4d0b46))}})
}
private fun share(context:android.content.Context,uris:List<String>){
    if(uris.isEmpty())return
    val list=ArrayList(uris.map(Uri::parse));val intent=Intent(if(list.size==1)Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply{
        type="image/*";if(list.size==1)putExtra(Intent.EXTRA_STREAM,list[0])else putParcelableArrayListExtra(Intent.EXTRA_STREAM,list)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);clipData=ClipData.newRawUri(appText(R.string.ui_label_662b75),list.first()).also{clip->list.drop(1).forEach{clip.addItem(ClipData.Item(it))}}
    };context.startActivity(Intent.createChooser(intent,appText(R.string.ui_share_image_df80b4)))
}

private fun jobStatus(status:String)=when(status){"waiting"->appText(R.string.ui_waiting_b24635);"running"->appText(R.string.ui_exporting_e30445);"paused"->appText(R.string.ui_paused_fcbae4);"failed"->appText(R.string.ui_some_exports_failed_791204);"done"->appText(R.string.ui_completed_e99b48);else->appText(R.string.ui_resume_available_06ea04)}

private fun shortColorSpace(name:String)=when{ name.contains("P3",true)->"Display P3";name.contains("Adobe",true)->"Adobe RGB";name.contains("sRGB",true)->"sRGB";else->name.take(18)}
