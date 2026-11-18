@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.parker.imageborder.editor

import com.parker.imageborder.R

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

@Composable internal fun GalleryPicker(maximum:Int,dismiss:()->Unit,files:()->Unit,systemPicker:()->Unit,confirm:(List<Uri>)->Unit){
    val context=LocalContext.current;val owner=LocalLifecycleOwner.current
    var access by remember{mutableStateOf(MediaGallery.access(context))};var revision by remember{mutableIntStateOf(0)}
    var original by remember{mutableStateOf(MediaGallery.originalAccess(context))}
    var album by rememberSaveable{mutableStateOf<String?>(null)}
    var selected by rememberSaveable{mutableStateOf(listOf<String>())}
    var photos by remember{mutableStateOf(emptyList<GalleryPhoto>())};var albums by remember{mutableStateOf(emptyList<GalleryAlbum>())}
    var loading by remember{mutableStateOf(false)};var more by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")}
    var moreRequest by remember{mutableIntStateOf(0)};var limitNotice by remember{mutableStateOf(false)}
    var permissionNeedsSettings by rememberSaveable{mutableStateOf(false)}
    var originalNeedsSettings by rememberSaveable{mutableStateOf(false)}
    val thumbs=remember{GalleryThumbnails(context)};val grid=rememberLazyGridState()
    fun settings(){context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){
        access=MediaGallery.access(context);original=MediaGallery.originalAccess(context);revision++
        if(access==GalleryAccess.None){
            val activity=context as? Activity
            permissionNeedsSettings=activity!=null&&MediaGallery.permissions().filter{it!=Manifest.permission.ACCESS_MEDIA_LOCATION}.all{permission->!androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity,permission)}
        }
        if(it[Manifest.permission.ACCESS_MEDIA_LOCATION]==false){
            val activity=context as? Activity
            originalNeedsSettings=activity!=null&&!androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity,Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }
    DisposableEffect(owner){
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME){
            val current=MediaGallery.access(context)
            if(current!=access){selected=emptyList();thumbs.clear()}
            access=current;original=MediaGallery.originalAccess(context);revision++
        }}
        owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer);thumbs.clear()}
    }
    LaunchedEffect(access,revision,album){
        photos=emptyList();error="";more=false;limitNotice=false
        if(access==GalleryAccess.None){loading=false;selected=emptyList();albums=emptyList();return@LaunchedEffect}
        loading=true
        try{
            val result=MediaGallery.page(context,album)
            ensureActive();photos=result.photos;more=result.hasMore
            albums=MediaGallery.albums(context)
            if(album!=null&&albums.none{it.id==album})album=null
            grid.scrollToItem(0)
        }catch(e:SecurityException){access=MediaGallery.access(context);error=appText(R.string.ui_photo_permissions_changed_715217)}
        catch(e:Exception){if(e is CancellationException)throw e;error=appText(R.string.ui_cannot_open_the_gallery_83f81d)}
        finally{loading=false}
    }
    LaunchedEffect(moreRequest,album,revision,access){
        if(moreRequest==0||loading||!more||access==GalleryAccess.None)return@LaunchedEffect
        val expectedAlbum=album;val expectedRevision=revision
        loading=true
        try{
            val result=MediaGallery.page(context,album,photos.size)
            ensureActive()
            if(expectedAlbum==album&&expectedRevision==revision){
                val extra=result.photos.filterNot{next->photos.any{it.uri==next.uri}}
                photos=photos+extra;more=result.hasMore&&extra.isNotEmpty()
            }
        }catch(e:Exception){if(e is CancellationException)throw e;error=appText(R.string.ui_could_not_load_more_photos_908d6e);more=false}
        finally{loading=false}
    }
    val nearEnd by remember{derivedStateOf{grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let{it>=photos.size-24}==true}}
    LaunchedEffect(nearEnd,loading,more){if(nearEnd&&!loading&&more)moreRequest++}

    fun choose(photo:GalleryPhoto){
        if(maximum==1){selected=if(photo.uri in selected)emptyList()else listOf(photo.uri);return}
        val selection=GallerySelection(maximum,selected)
        if(selection.toggle(photo.uri)){selected=selection.uris;limitNotice=false}else limitNotice=true
    }
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        val view=LocalView.current;val dark=LocalLiquidAppearance.current.dark
        DisposableEffect(view,dark){
            val window=(view.parent as? DialogWindowProvider)?.window
            window?.let{WindowCompat.getInsetsController(it,it.decorView).apply{isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}}
            onDispose{}
        }
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
            GalleryPickerBody(access,original,maximum,photos,albums,album,selected,loading,error,limitNotice,grid,
                dismiss=dismiss,authorize={request.launch(MediaGallery.permissions())},authorizeOriginal={if(originalNeedsSettings)settings()else request.launch(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION))},
                albumChange={album=it},pick=::choose,files=files,systemPicker=systemPicker,retry={revision++},thumbnail={photo->
                    val image by produceState<android.graphics.Bitmap?>(null,photo.uri,revision){value=thumbs.load(photo)}
                    image
                },confirm={confirm(selected.map{MediaGallery.importUri(Uri.parse(it),original)})},permissionNeedsSettings=permissionNeedsSettings,
                settings=::settings,clearSelection={selected=emptyList();limitNotice=false})
        }
    }
}

@Composable internal fun GalleryPickerBody(
    access:GalleryAccess,original:Boolean,maximum:Int,photos:List<GalleryPhoto>,albums:List<GalleryAlbum>,album:String?,selected:List<String>,loading:Boolean,error:String,limitNotice:Boolean,grid:LazyGridState,
    dismiss:()->Unit,authorize:()->Unit,authorizeOriginal:()->Unit,albumChange:(String?)->Unit,pick:(GalleryPhoto)->Unit,files:()->Unit,systemPicker:()->Unit,retry:()->Unit,
    thumbnail:@Composable (GalleryPhoto)->android.graphics.Bitmap?,confirm:()->Unit,permissionNeedsSettings:Boolean=false,settings:()->Unit={},clearSelection:()->Unit={}
){
    var albumMenu by remember{mutableStateOf(false)};var actions by remember{mutableStateOf(false)}
    val backdrop=rememberLayerBackdrop();val appearance=LocalLiquidAppearance.current
    val density=LocalDensity.current;val inspection=LocalInspectionMode.current
    var headerPixels by remember{mutableIntStateOf(with(density){(if(access==GalleryAccess.None)72.dp else if(original)128.dp else 176.dp).roundToPx()})};var footerPixels by remember{mutableIntStateOf(with(density){88.dp.roundToPx()})}
    val top=with(density){headerPixels.toDp()};val bottom=with(density){footerPixels.toDp()}
    CompositionLocalProvider(LocalLiquidBackdrop provides backdrop){
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()){
        val capture=if(inspection||appearance.reduceTransparency)Modifier else Modifier.layerBackdrop(backdrop)
        Box(Modifier.fillMaxSize().then(capture)){
            when{
                access==GalleryAccess.None->Column(Modifier.align(Alignment.Center).padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){
                    Icon(Icons.Outlined.PhotoLibrary,null,Modifier.size(52.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(appText(R.string.ui_choose_your_photos_799d62),style=MaterialTheme.typography.titleMedium)
                    Button(onClick=if(permissionNeedsSettings)settings else authorize){Text(if(permissionNeedsSettings)appText(R.string.ui_open_photo_permissions_fabfe5)else appText(R.string.ui_allow_photo_access_577145))}
                    ActionRow{TextButton(onClick=systemPicker){Text(appText(R.string.ui_system_picker_ba74eb))};TextButton(onClick=files){Text(appText(R.string.ui_import_from_files_58333d))}}
                }
                error.isNotBlank()&&photos.isEmpty()->Column(Modifier.align(Alignment.Center).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(error);TextButton(onClick=retry){Text(appText(R.string.ui_retry_e2d53a))}}
                photos.isEmpty()&&!loading->Column(Modifier.align(Alignment.Center).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(appText(R.string.ui_no_accessible_photos_cbca62));TextButton(onClick=authorize){Text(appText(R.string.ui_manage_photos_5f2a79))}}
                else->LazyVerticalGrid(columns=GridCells.Adaptive(104.dp),state=grid,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(start=8.dp,end=8.dp,top=top+8.dp,bottom=bottom+12.dp),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    items(photos,key={it.uri}){photo->
                        val index=selected.indexOf(photo.uri);val image=thumbnail(photo)
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable{pick(photo)}.semantics{contentDescription=photo.name;this.selected=index>=0;stateDescription=if(index>=0)appText(R.string.ui_selected_photo_1_s_a35786, index+1)else appText(R.string.ui_not_selected_53e2db)}){
                            if(image!=null)Image(image.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                            else Icon(Icons.Outlined.Image,null,Modifier.align(Alignment.Center),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(index>=0)Box(Modifier.matchParentSize().border(3.dp,MaterialTheme.colorScheme.primary,RoundedCornerShape(5.dp)))
                            Surface(color=if(index>=0)MaterialTheme.colorScheme.primary else Color.Black.copy(alpha=.25f),shape=CircleShape,border=BorderStroke(1.5f.dp,Color.White.copy(alpha=.95f)),modifier=Modifier.align(Alignment.TopEnd).padding(8.dp)){
                                Box(Modifier.sizeIn(minWidth=26.dp,minHeight=26.dp).padding(horizontal=5.dp,vertical=3.dp),contentAlignment=Alignment.Center){if(index>=0)Text("${index+1}",color=MaterialTheme.colorScheme.onPrimary,style=MaterialTheme.typography.labelMedium)}
                            }
                        }
                    }
                    if(loading)item(span={GridItemSpan(maxLineSpan)}){Box(Modifier.fillMaxWidth().padding(16.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp)}}
                    if(error.isNotBlank())item(span={GridItemSpan(maxLineSpan)}){TextButton(onClick=retry,modifier=Modifier.fillMaxWidth()){Text(appText(R.string.ui_retry_loading_b647ee))}}
                }
            }
            if(loading&&photos.isEmpty())CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp),strokeWidth=2.dp)
        }
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged{headerPixels=it.height}.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            LiquidSurface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(28.dp)){
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=4.dp)){
                val title=appText(R.string.ui_choose_photos_4a8a30)
                val stacked=labelWidth(title,MaterialTheme.typography.titleMedium)+labelWidth(appText(R.string.ui_cancel_4d0b46),MaterialTheme.typography.labelLarge)+labelWidth(appText(R.string.ui_more_9b0c6c),MaterialTheme.typography.labelLarge)+64.dp>maxWidth
                Column{
                if(stacked)Text(title,Modifier.fillMaxWidth().padding(top=12.dp),style=MaterialTheme.typography.titleMedium,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    TextButton(onClick=dismiss,modifier=Modifier.widthIn(min=64.dp).heightIn(min=48.dp)){Text(appText(R.string.ui_cancel_4d0b46),maxLines=1,softWrap=false)}
                    if(stacked)Spacer(Modifier.weight(1f))else Text(title,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center,maxLines=1,softWrap=false)
                    Box{
                        TextButton(onClick={actions=true},modifier=Modifier.widthIn(min=64.dp).heightIn(min=48.dp)){Text(appText(R.string.ui_more_9b0c6c))}
                        DropdownMenu(actions,onDismissRequest={actions=false}){
                            if(access!=GalleryAccess.Full)DropdownMenuItem(text={Text(appText(R.string.ui_manage_accessible_photos_507639))},onClick={actions=false;authorize()})
                            DropdownMenuItem(text={Text(appText(R.string.ui_photo_permissions_32c013))},onClick={actions=false;settings()})
                            if(!original)DropdownMenuItem(text={Text(appText(R.string.ui_allow_original_metadata_16df39))},onClick={actions=false;authorizeOriginal()})
                            DropdownMenuItem(text={Text(appText(R.string.ui_import_from_files_58333d))},onClick={actions=false;files()})
                            DropdownMenuItem(text={Text(appText(R.string.ui_system_picker_ba74eb))},onClick={actions=false;systemPicker()})
                        }
                    }
                }
                }
                }
            }
            if(access!=GalleryAccess.None){
                LiquidSurface(Modifier.fillMaxWidth(),clear=true,shape=RoundedCornerShape(24.dp)){
                    Column{
                        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=8.dp)){
                        val stackedAlbum=access==GalleryAccess.Selected&&maxWidth-labelWidth(appText(R.string.ui_manage_photos_5f2a79),MaterialTheme.typography.labelMedium)-24.dp<140.dp
                        Column{
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                            Box(Modifier.weight(1f)){
                                TextButton(onClick={albumMenu=true},modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=8.dp)){
                                    Text(albums.firstOrNull{it.id==album}?.name?:appText(R.string.ui_recent_photos_b287b1),modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurface)
                                    Icon(Icons.Outlined.ExpandMore,null,Modifier.size(20.dp))
                                }
                                DropdownMenu(albumMenu,onDismissRequest={albumMenu=false},modifier=Modifier.heightIn(max=420.dp)){
                                    DropdownMenuItem(text={Text(appText(R.string.ui_recent_photos_b287b1))},onClick={albumMenu=false;albumChange(null)})
                                    for(item in albums)DropdownMenuItem(text={Text("${item.name} · ${item.count}",maxLines=1,overflow=TextOverflow.Ellipsis)},onClick={albumMenu=false;albumChange(item.id)})
                                }
                            }
                            if(access==GalleryAccess.Selected&&!stackedAlbum)TextButton(onClick=authorize){Text(appText(R.string.ui_manage_photos_5f2a79),style=MaterialTheme.typography.labelMedium)}
                        }
                        if(stackedAlbum)TextButton(onClick=authorize,modifier=Modifier.align(Alignment.End)){Text(appText(R.string.ui_manage_photos_5f2a79),style=MaterialTheme.typography.labelMedium)}
                        }
                        }
                        if(!original)TextButton(onClick=authorizeOriginal,modifier=Modifier.fillMaxWidth()){Text(appText(R.string.ui_allow_original_metadata_16df39),style=MaterialTheme.typography.labelMedium)}
                    }
                }
            }
        }
        if(access!=GalleryAccess.None)LiquidSurface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged{footerPixels=it.height}.padding(12.dp),shape=RoundedCornerShape(28.dp)){
            Column(Modifier.padding(horizontal=12.dp,vertical=8.dp)){
                if(limitNotice)Text(appText(R.string.ui_selection_limit_1_s_e67a93, maximum),Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.error)
                GallerySelectionActions(selected.size,maximum,clearSelection,confirm)
            }
        }
    }
    }
}


@Composable internal fun GallerySelectionActions(count:Int,maximum:Int,clear:()->Unit,confirm:()->Unit){
    val summary=if(count==0)appText(R.string.ui_not_selected_53e2db)else "$count / $maximum"
    val clearLabel=appText(R.string.ui_clear_84fcd7)
    val addLabel=if(count==0)appText(R.string.ui_add_94191c)else appText(R.string.ui_add_1_s_71bf51,count)
    val required=labelWidth(summary,MaterialTheme.typography.labelMedium)+8.dp+labelWidth(addLabel,MaterialTheme.typography.labelLarge)+40.dp+if(count>0)labelWidth(clearLabel,MaterialTheme.typography.labelLarge)+24.dp else 0.dp
    BoxWithConstraints(Modifier.fillMaxWidth()){
        val stacked=required>maxWidth
        @Composable fun Add(modifier:Modifier=Modifier){Button(onClick=confirm,enabled=count>0,modifier=modifier.heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=20.dp)){Text(addLabel,maxLines=1,softWrap=false)}}
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text(summary,Modifier.weight(1f).padding(start=8.dp),style=MaterialTheme.typography.labelMedium,maxLines=1,softWrap=false)
                if(count>0)TextButton(onClick=clear,modifier=Modifier.heightIn(min=48.dp)){Text(clearLabel,maxLines=1,softWrap=false)}
                if(!stacked)Add()
            }
            if(stacked)Add(Modifier.fillMaxWidth())
        }
    }
}
