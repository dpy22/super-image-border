package com.parker.imageborder.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.*
import com.android.tools.screenshot.PreviewTest
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.parker.imageborder.R
import org.json.JSONObject
import kotlin.math.*

@PreviewTest
@Preview(name="Liquid light",widthDp=402,heightDp=874,showSystemUi=true,locale="zh-rCN")
@Composable fun LiquidLightPreview(){EditorPreview(false,0)}
@PreviewTest
@Preview(name="Liquid dark",widthDp=402,heightDp=874,showSystemUi=true,locale="zh-rCN")
@Composable fun LiquidDarkPreview(){EditorPreview(true,0)}
@PreviewTest
@Preview(name="Watermark editing",widthDp=402,heightDp=874,showSystemUi=true,locale="zh-rCN")
@Composable fun LiquidWatermarkPreview(){EditorPreview(true,5)}
@PreviewTest
@Preview(name="Small screen large text",widthDp=360,heightDp=780,fontScale=1.5f,showSystemUi=true,locale="zh-rCN")
@Composable fun LiquidLargeTextPreview(){EditorPreview(false,5)}

@PreviewTest @Preview(name="English editor",widthDp=402,heightDp=874,locale="en")
@Composable fun EnglishEditorPreview(){EditorPreview(false,0)}
@PreviewTest @Preview(name="English large editor",widthDp=360,heightDp=780,fontScale=1.5f,locale="en")
@Preview(name="English narrow 2x editor",widthDp=320,heightDp=780,fontScale=2f,locale="en")
@Composable fun EnglishLargeEditorPreview(){EditorPreview(false,5)}

@Composable private fun EditorPreview(dark:Boolean,panel:Int){
    val context=LocalContext.current;AppText.previewContext(context)
    val photo=remember{BitmapFactory.decodeResource(context.resources,R.drawable.preview_photo)}
    val templates=remember{JSONObject(context.assets.open("templates.json").bufferedReader().use{it.readText()}).getJSONArray("templates").objects().map{TemplateDefinition(it.getString("id"),it.getString("name"),it.getInt("family"),it.getInt("variant"),it.getString("category"))}}
    val renderer=remember{FrameRenderer(context,templates)}
    val doc=remember{templates.first{it.id=="plate"}.apply(EditDocument(assets=listOf(PhotoAsset(uri="preview://sample",name="示例照片",width=photo.width,height=photo.height,exif=mapOf("Make" to "CAMERA","Model" to "FULL FRAME","FocalLengthIn35mmFilm" to "35","FNumber" to "2.8","ExposureTime" to "1/250","PhotographicSensitivity" to "100"))),layers=if(panel==5)listOf(WatermarkLayer(text="© Parker",x=.87f,y=.78f,size=.022f))else emptyList()))}
    val framed=remember{renderPreview(renderer,doc,photo,1000)}
    val backdrop=rememberLayerBackdrop()
    LiquidEditorTheme(dark){CompositionLocalProvider(LocalLiquidBackdrop provides backdrop){
        LiquidEditorWorkspace(backdrop,true,false,panel,true,{}, {},{}, {},true,true,false,{}, {},
            sourceRibbon={Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
                LiquidPhotoThumbnail(photo,"示例照片",true,{},{});LiquidPhotoThumbnail(photo,"示例照片二",false,{},{});LiquidSurface(clear=true,shape=RoundedCornerShape(24.dp)){LiquidIconButton(Icons.Outlined.Add,"添加照片",{})}
            }},sourceInfo={LiquidSurface(clear=true){Text("35 mm",style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(horizontal=14.dp,vertical=12.dp))}},
            photo={Image(framed.asImageBitmap(),"照片预览",contentScale=ContentScale.Fit,modifier=Modifier.fillMaxSize().padding(horizontal=20.dp))},
            controls={
                if(panel==0)Column{
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                        for(name in listOf("极简","铭牌","配色","玻璃","相纸"))FilterChip(name=="铭牌",{},label={Text(uiLabel(name))},border=null,colors=liquidChipColors())
                    }
                    LazyRow(contentPadding=PaddingValues(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                        for(t in templates.filter{it.family==1})item{
                            val bitmap=remember(t.id){renderPreview(renderer,t.apply(doc),photo,220)}
                            Surface(color=MaterialTheme.colorScheme.onSurface.copy(alpha=.035f),shape=RoundedCornerShape(16.dp),border=if(t.id=="plate")BorderStroke(1.8f.dp,MaterialTheme.colorScheme.primary)else null,modifier=Modifier.size(104.dp,128.dp)){
                                Column(Modifier.padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally){Image(bitmap.asImageBitmap(),t.name,modifier=Modifier.fillMaxWidth().weight(1f),contentScale=ContentScale.Fit);Spacer(Modifier.height(7.dp));Text(uiLabel(t.name),style=MaterialTheme.typography.labelMedium,maxLines=1)}
                            }
                        }
                    }
                }else Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal=18.dp,vertical=4.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    LiquidTextField("© Parker","水印文字",{},multi=false)
                    ActionRow{TextButton({}){Text(uiLabel("署名"))};TextButton({}){Text(uiLabel("标签"))};TextButton({}){Text(uiLabel("平铺"))}}
                    LiquidSliderRow("不透明度",.85f,0f..1f,onChange={})
                    LiquidSliderRow("大小",.035f,0f..0.15f,onChange={})
                }
            },onSettings={})
    }}
}

private fun renderPreview(renderer:FrameRenderer,doc:EditDocument,photo:Bitmap,edge:Int):Bitmap{
    val scene=renderer.layout(doc);val scale=min(1f,edge/max(scene.width,scene.height))
    val bitmap=Bitmap.createBitmap(max(1,(scene.width*scale).roundToInt()),max(1,(scene.height*scale).roundToInt()),Bitmap.Config.ARGB_8888)
    BitmapPixels(mapOf(doc.current!!.id to photo)).use{renderer.draw(Canvas(bitmap),doc,scene,it,scale,photo to ImageEffects.palette(photo))}
    return bitmap
}
