package com.parker.imageborder.editor

import com.parker.imageborder.R

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*

@Composable fun LiquidPhotoRail(vm:EditorViewModel,onAdd:()->Unit){
    val doc=vm.document;val state=rememberLazyListState()
    LaunchedEffect(doc.active,doc.assets.size){if(doc.assets.isNotEmpty())state.animateScrollToItem(doc.active.coerceIn(doc.assets.indices))}
    Row(Modifier.fillMaxWidth().height(82.dp),verticalAlignment=Alignment.CenterVertically){
    LazyRow(state=state,modifier=Modifier.weight(1f).fillMaxHeight(),horizontalArrangement=Arrangement.spacedBy(2.dp),contentPadding=PaddingValues(horizontal=8.dp)){
        itemsIndexed(doc.assets,key={_,a->a.id}){index,asset->
            var thumbnail by remember(asset.uri){mutableStateOf<Bitmap?>(null)}
            LaunchedEffect(asset.uri){thumbnail=withContext(Dispatchers.IO){runCatching{Assets.preview(vm.app,asset,160)}.getOrNull()}}
            LiquidPhotoThumbnail(thumbnail,asset.name,index==doc.active,{vm.activateAsset(index)},{vm.removeAsset(asset.id)})
        }
    }
        Box(Modifier.width(60.dp).fillMaxHeight(),contentAlignment=Alignment.Center){
            LiquidSurface(clear=true,shape=CircleShape){LiquidIconButton(Icons.Outlined.Add,appText(R.string.ui_add_photos_989644),onAdd)}
        }
    }
}

@Composable fun LiquidPhotoThumbnail(bitmap:Bitmap?,name:String,selected:Boolean,onSelect:()->Unit,onRemove:()->Unit){
    Box(Modifier.size(86.dp,82.dp)){
        val border=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        Box(Modifier.padding(top=12.dp,start=2.dp).size(64.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .border(if(selected)2.5f.dp else .7f.dp,border,RoundedCornerShape(13.dp))
            .clickable(onClick=onSelect).semantics{contentDescription=name;this.selected=selected},contentAlignment=Alignment.Center){
            if(bitmap!=null)Image(bitmap.asImageBitmap(),null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize().padding(if(selected)4.dp else 2.dp).clip(RoundedCornerShape(10.dp)))
            else Icon(Icons.Outlined.Image,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick=onRemove,modifier=Modifier.align(Alignment.TopEnd).size(48.dp)){
            Box(Modifier.size(22.dp).background(MaterialTheme.colorScheme.surface.copy(alpha=.94f),CircleShape).border(.6f.dp,MaterialTheme.colorScheme.outlineVariant,CircleShape),contentAlignment=Alignment.Center){
                Icon(Icons.Outlined.Close,appText(R.string.ui_remove_1_s_58c704, name),modifier=Modifier.size(14.dp),tint=MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
