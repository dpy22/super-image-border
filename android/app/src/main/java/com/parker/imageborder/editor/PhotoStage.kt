package com.parker.imageborder.editor

import com.parker.imageborder.R

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import kotlin.math.*

data class PreviewViewport(val width:Int,val height:Int,val sceneWidth:Float,val sceneHeight:Float,val zoom:Float=1f,val pan:Offset=Offset.Zero){
    val scale get()=min(width/sceneWidth,height/sceneHeight)*zoom
    val left get()=(width-sceneWidth*scale)/2+pan.x
    val top get()=(height-sceneHeight*scale)/2+pan.y
    fun scenePoint(p:Offset):Offset? {
        if(width<=0||height<=0)return null
        val x=(p.x-left)/scale;val y=(p.y-top)/scale
        return if(x in 0f..sceneWidth&&y in 0f..sceneHeight)Offset(x,y)else null
    }
    fun rect(r:RectF)=RectF(left+r.left*scale,top+r.top*scale,left+r.right*scale,top+r.bottom*scale)
}

@Composable internal fun PhotoStage(vm:EditorViewModel,frame:PreviewFrame,pickColor:Boolean,editPhoto:Boolean,onSelectLayer:(String)->Unit,onColor:(Int)->Unit){
    val id=vm.document.current?.id?:vm.document.id
    var stage by remember{mutableStateOf(IntSize.Zero)}
    var zoom by remember(id){mutableFloatStateOf(1f)};var pan by remember(id){mutableStateOf(Offset.Zero)}
    var showOriginal by remember(id){mutableStateOf(false)};var manipulating by remember{mutableStateOf(false)}
    var original by remember(id){mutableStateOf<Bitmap?>(null)};var detail by remember{mutableStateOf<Bitmap?>(null)}
    val liveFrame by rememberUpdatedState(frame);val liveEditPhoto by rememberUpdatedState(editPhoto)
    val chooseLayer by rememberUpdatedState(onSelectLayer);val chooseColor by rememberUpdatedState(onColor)
    val configuration=LocalViewConfiguration.current;val timeout=configuration.longPressTimeoutMillis
    val doc=vm.document
    val viewport=PreviewViewport(stage.width,stage.height,frame.layout.width,frame.layout.height,zoom,pan)
    val currentViewport by rememberUpdatedState(viewport)
    val paint=remember{android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)}
    LaunchedEffect(id,vm.hdrPreview){
        original=null
        val asset=vm.document.current
        if(asset!=null)original=withContext(Dispatchers.Default){runCatching{
            val plain=EditDocument(assets=listOf(asset),frame=FrameStyle(left=0f,top=0f,right=0f,bottom=0f,footer=0f))
            if(vm.hdrPreview)RenderExporter(vm.app).hdrPreview(plain,1536).bitmap else vm.app.renderer.preview(plain,1536).bitmap
        }.getOrNull()}
    }
    LaunchedEffect(doc,frame.layout,zoom,pan,stage,vm.hdrPreview){
        detail=null
        // Refine at physical screen resolution even at fit-to-screen. A long
        // caption must not consume the photo's entire preview pixel budget.
        if(stage.width>0&&stage.height>0&&!vm.hdrPreview){
            delay(180);val view=currentViewport
            val result=withContext(Dispatchers.Default){runCatching{vm.app.renderer.viewport(doc,stage.width,stage.height,view.scale,view.left,view.top)}.getOrNull()}
            ensureActive();detail=result
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged{stage=it}
        .pointerInput(id,pickColor){
            detectTapGestures(onTap={point->
                val hit=currentViewport.scenePoint(point)
                if(pickColor&&hit!=null){val f=liveFrame;chooseColor(f.bitmap.getPixel((hit.x/f.layout.width*(f.bitmap.width-1)).roundToInt().coerceIn(0,f.bitmap.width-1),(hit.y/f.layout.height*(f.bitmap.height-1)).roundToInt().coerceIn(0,f.bitmap.height-1)))}
                else if(hit!=null){
                    val layer=vm.document.layers.asReversed().firstOrNull{l->!l.tiled&&runCatching{vm.app.renderer.layerShape(l,liveFrame.layout)?.bounds?.contains(hit.x,hit.y)==true}.getOrDefault(false)}
                    if(layer!=null)chooseLayer(layer.id)else vm.deselectLayer()
                }
            },onDoubleTap={zoom=if(zoom>1f)1f else 2f;pan=Offset.Zero},onPress={
                if(!pickColor)coroutineScope{
                    val hold=launch{delay(timeout);showOriginal=true}
                    try{tryAwaitRelease()}finally{hold.cancel();showOriginal=false}
                }
            })
        }.pointerInput(id,pickColor){
            awaitEachGesture{
                awaitFirstDown(requireUnconsumed=false);var editing=false;var travel=Offset.Zero
                do{
                    val event=awaitPointerEvent();val count=event.changes.count{it.pressed};val delta=event.calculatePan();val scale=event.calculateZoom();val rotation=event.calculateRotation()
                    if(pickColor)continue
                    travel+=delta
                    if(!editing&&travel.getDistance()<viewConfiguration.touchSlop&&count<2)continue
                    if(delta.getDistance()<.2f&&abs(scale-1f)<.001f&&abs(rotation)<.1f)continue
                    editing=true;manipulating=true;showOriginal=false
                    val layer=vm.document.layers.firstOrNull{it.id==vm.selectedLayer};val view=currentViewport
                    if(layer!=null&&!layer.locked){
                        val shape=runCatching{vm.app.renderer.layerShape(layer,liveFrame.layout)}.getOrNull()
                        if(shape!=null){vm.beginGesture()
                            val rangeX=(shape.reference.width()-shape.bounds.width()).coerceAtLeast(1f);val rangeY=(shape.reference.height()-shape.bounds.height()).coerceAtLeast(1f)
                            val x=(layer.x+delta.x/view.scale/rangeX).coerceIn(0f,1f);val y=(layer.y+delta.y/view.scale/rangeY).coerceIn(0f,1f)
                            vm.updateLayer(layer.copy(x=if(abs(x-.5f)<.012f).5f else x,y=if(abs(y-.5f)<.012f).5f else y,scale=(layer.scale*scale).coerceIn(.1f,5f),angle=(layer.angle+rotation).coerceIn(-180f,180f)),true)
                        }
                    }else if(count>1){zoom=(zoom*scale).coerceIn(1f,5f);pan+=delta}
                    else if(liveEditPhoto&&!vm.document.currentTransform.locked){
                        val node=liveFrame.layout.photos.firstOrNull{it.asset.id==vm.document.current?.id}
                        if(node!=null){
                            val t=vm.document.currentTransform;val a=floatArrayOf(0f,0f);val b=floatArrayOf(0f,0f)
                            vm.app.renderer.photoMatrix(node.copy(transform=t.copy(x=0f,y=0f))).mapPoints(a)
                            vm.app.renderer.photoMatrix(node.copy(transform=t.copy(x=1f,y=1f))).mapPoints(b)
                            val dx=b[0]-a[0];val dy=b[1]-a[1]
                            if(abs(dx)>1f||abs(dy)>1f){vm.beginGesture();vm.updateTransform(t.copy(x=if(abs(dx)>1f)(t.x+delta.x/view.scale/dx).coerceIn(0f,1f)else t.x,y=if(abs(dy)>1f)(t.y+delta.y/view.scale/dy).coerceIn(0f,1f)else t.y),true)}
                        }
                    }else if(zoom>1f)pan+=delta
                    event.changes.forEach{it.consume()}
                }while(event.changes.any{it.pressed})
                manipulating=false;if(editing)vm.endGesture()
            }
        }){
        Canvas(Modifier.fillMaxSize().semantics{contentDescription=if(showOriginal)appText(R.string.ui_original_cd0f3a)else appText(R.string.ui_editing_preview_260fc3);stateDescription=if(vm.hdrPreview)"HDR"else "SDR"}){
            val display=if(showOriginal)original?:frame.bitmap else detail?:frame.bitmap
            val rect=if(showOriginal){val factor=min(size.width/display.width,size.height/display.height)*zoom
                RectF((size.width-display.width*factor)/2+pan.x,(size.height-display.height*factor)/2+pan.y,(size.width+display.width*factor)/2+pan.x,(size.height+display.height*factor)/2+pan.y)
            }else if(detail!=null)RectF(0f,0f,size.width,size.height)else viewport.rect(RectF(0f,0f,frame.layout.width,frame.layout.height))
            drawIntoCanvas{it.nativeCanvas.drawBitmap(display,null,rect,paint)}
            val layer=vm.document.layers.firstOrNull{it.id==vm.selectedLayer}
            if(layer!=null&&!showOriginal&&!layer.tiled){
                val shape=runCatching{vm.app.renderer.layerShape(layer,frame.layout)}.getOrNull()
                if(shape!=null){val r=viewport.rect(shape.bounds);drawRect(Color(0xff53bf86),Offset(r.left-3,r.top-3),androidx.compose.ui.geometry.Size(r.width()+6,r.height()+6),style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(),4.dp.toPx()))))
                    if(manipulating){
                        if(abs(layer.x-.5f)<.012f)drawLine(Color(0xff53bf86),Offset(r.centerX(),viewport.top),Offset(r.centerX(),viewport.top+frame.layout.height*viewport.scale),1.dp.toPx())
                        if(abs(layer.y-.5f)<.012f)drawLine(Color(0xff53bf86),Offset(viewport.left,r.centerY()),Offset(viewport.left+frame.layout.width*viewport.scale,r.centerY()),1.dp.toPx())
                    }
                }
            }
        }
        if(zoom>1f||pan!=Offset.Zero)LiquidSurface(Modifier.align(Alignment.BottomStart).padding(8.dp),clear=true,shape=RoundedCornerShape(24.dp)){
            TextButton(onClick={zoom=1f;pan=Offset.Zero},modifier=Modifier.height(48.dp)){Icon(Icons.Outlined.FitScreen,appText(R.string.ui_fit_to_screen_47ba68),Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("${(zoom*100).roundToInt()}%",style=MaterialTheme.typography.labelMedium)}
        }
        if(showOriginal)LiquidSurface(Modifier.align(Alignment.TopStart).padding(8.dp),clear=true){Text(appText(R.string.ui_original_cd0f3a),Modifier.padding(horizontal=14.dp,vertical=8.dp),style=MaterialTheme.typography.labelMedium)}
    }
}
