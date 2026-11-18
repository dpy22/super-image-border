package com.parker.imageborder.editor

import com.parker.imageborder.R

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*

@Composable internal fun AdjustPanel(vm:EditorViewModel){
    val a=vm.document.adjustments
    var category by remember{mutableStateOf(if(a.filter=="自然")"胶片"else FilmLooks.group(a.filter))}
    var source by remember(vm.document.current?.uri){mutableStateOf<Bitmap?>(null)}
    val list=rememberLazyListState()
    LaunchedEffect(vm.document.current?.uri){source=vm.document.current?.let{asset->withContext(Dispatchers.IO){runCatching{Assets.preview(vm.app,asset,180)}.getOrNull()}}}
    LaunchedEffect(category){list.scrollToItem(0)}
    Panel{
        Row(verticalAlignment=Alignment.CenterVertically){
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(2.dp)){
                for(group in listOf("胶片","电影","人像","黑白","基础","全部"))FilterChip(category==group,{category=group},label={Text(uiLabel(group),style=MaterialTheme.typography.labelMedium)},colors=liquidChipColors(),border=null)
            }
        }
        val styles=listOf("自然")+Adjustments.filters.filter{it!="自然"&&(category=="全部"||FilmLooks.group(it)==category)}
        val cardWidth=maxOf(104f,76f*LocalDensity.current.fontScale).dp
        LazyRow(state=list,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            items(styles,key={it}){style->
                val photo=source
                val thumbnail by produceState<Bitmap?>(null,photo,style){value=photo?.let{withContext(Dispatchers.Default){ImageEffects.adjust(it,Adjustments(filter=style))}}}
                Column(Modifier.width(cardWidth).clickable{vm.change(vm.document.copy(adjustments=vm.document.adjustments.copy(filter=style,intensity=1f)))}
                    .semantics{selected=style==a.filter;contentDescription=appText(R.string.ui_filter_1_s_490839, uiLabel(style))},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)){
                    Box(Modifier.width(cardWidth).height(76.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(if(style==a.filter)2.dp else .5f.dp,if(style==a.filter)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(13.dp))){
                        thumbnail?.let{Image(it.asImageBitmap(),null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize().padding(if(style==a.filter)3.dp else 1.dp).clip(RoundedCornerShape(10.dp)))}
                    }
                    Text(uiLabel(style),style=MaterialTheme.typography.labelMedium,color=if(style==a.filter)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.fillMaxWidth())
                }
            }
        }
        if(a.filter!="自然")Control("滤镜强度",a.intensity,0f..1f,vm,displayLabel=uiLabel(a.filter)){vm.change(vm.document.copy(adjustments=vm.document.adjustments.copy(intensity=it)),true)}
        Control("曝光",a.exposure,-2f..2f,vm){vm.change(vm.document.copy(adjustments=vm.document.adjustments.copy(exposure=it)),true)}
        Control("对比度",a.contrast,-1f..1f,vm){vm.change(vm.document.copy(adjustments=vm.document.adjustments.copy(contrast=it)),true)}
        Control("饱和度",a.saturation,-1f..1f,vm){vm.change(vm.document.copy(adjustments=vm.document.adjustments.copy(saturation=it)),true)}
        Control("色温",a.warmth,-1f..1f,vm){vm.change(vm.document.copy(adjustments=vm.document.adjustments.copy(warmth=it)),true)}
        Control("色调",a.tint,-1f..1f,vm){vm.change(vm.document.copy(adjustments=vm.document.adjustments.copy(tint=it)),true)}
        Control("高光",a.highlights,-1f..1f,vm){vm.change(vm.document.copy(adjustments=vm.document.adjustments.copy(highlights=it)),true)}
        Control("阴影",a.shadows,-1f..1f,vm){vm.change(vm.document.copy(adjustments=vm.document.adjustments.copy(shadows=it)),true)}
        Control("锐化",a.sharpness,0f..1f,vm){vm.change(vm.document.copy(adjustments=vm.document.adjustments.copy(sharpness=it)),true)}
        Control("暗角",a.vignette,0f..1f,vm){vm.change(vm.document.copy(adjustments=vm.document.adjustments.copy(vignette=it)),true)}
    }
}
