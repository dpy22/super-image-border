package com.parker.imageborder.editor

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalContext
import com.android.tools.screenshot.PreviewTest
import com.parker.imageborder.R

@PreviewTest @Preview(name="Gallery light",widthDp=402,heightDp=874,locale="zh-rCN")
@Composable fun GalleryLightPreview(){GalleryPreview(false,GalleryAccess.Full)}
@PreviewTest @Preview(name="Gallery limited dark",widthDp=402,heightDp=874,locale="zh-rCN")
@Composable fun GalleryLimitedPreview(){GalleryPreview(true,GalleryAccess.Selected)}
@PreviewTest @Preview(name="Gallery first permission",widthDp=402,heightDp=874,locale="zh-rCN")
@Composable fun GalleryPermissionPreview(){GalleryPreview(false,GalleryAccess.None)}
@PreviewTest @Preview(name="Gallery large text 100 selection",widthDp=360,heightDp=780,fontScale=1.5f,locale="zh-rCN")
@Composable fun GalleryLargeTextPreview(){GalleryPreview(false,GalleryAccess.Full,true)}

@PreviewTest @Preview(name="Gallery English large",widthDp=320,heightDp=780,fontScale=1.5f,locale="en")
@Preview(name="Gallery English narrow 2x",widthDp=320,heightDp=780,fontScale=2f,locale="en")
@Composable fun GalleryEnglishLargePreview(){GalleryPreview(false,GalleryAccess.Selected,true)}
@PreviewTest @Preview(name="Gallery English permission",widthDp=320,heightDp=780,fontScale=1.5f,locale="en")
@Composable fun GalleryEnglishPermissionPreview(){GalleryPreview(false,GalleryAccess.None)}

@Composable private fun GalleryPreview(dark:Boolean,access:GalleryAccess,large:Boolean=false){
    val context=LocalContext.current;AppText.previewContext(context);val bitmap=remember{BitmapFactory.decodeResource(context.resources,R.drawable.preview_photo)}
    val photos=remember{List(if(large)100 else 18){GalleryPhoto("preview://gallery/$it","示例照片 ${it+1}",1200,1600,0,"1","相机")}}
    val selected=if(access==GalleryAccess.None)emptyList()else if(large)photos.map{it.uri}else listOf(photos[0].uri,photos[4].uri)
    val grid=rememberLazyGridState(initialFirstVisibleItemIndex=if(large)96 else 0)
    LiquidEditorTheme(dark){Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
        GalleryPickerBody(access,access!=GalleryAccess.Selected,100,photos,listOf(GalleryAlbum("1","相机",photos.size),GalleryAlbum("2","旅行",12)),null,selected,false,"",false,grid,
            dismiss={},authorize={},authorizeOriginal={},albumChange={},pick={},files={},systemPicker={},retry={},thumbnail={bitmap},confirm={})
    }}
}
