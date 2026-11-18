@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.parker.imageborder.editor

import com.parker.imageborder.R

import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.*
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow

val LiquidGreen=Color(0xff198651)
data class LiquidAppearance(val dark:Boolean=false,val reduceTransparency:Boolean=false)
val LocalLiquidAppearance=staticCompositionLocalOf{LiquidAppearance()}
val LocalLiquidBackdrop=staticCompositionLocalOf<Backdrop?>{null}

@Composable fun LiquidEditorTheme(dark:Boolean,reduceTransparency:Boolean=false,content:@Composable ()->Unit){
    AppText.previewContext(androidx.compose.ui.platform.LocalContext.current)
    val accent=if(dark)Color(0xff8be0ad)else LiquidGreen
    val surface=if(dark)Color(0xff191b20)else Color(0xfff5f5f7)
    val ink=if(dark)Color(0xfff5f5f7)else Color(0xff19191c)
    val secondary=if(dark)Color(0xffaaaab1)else Color(0xff6e6e76)
    val palette=(if(dark)darkColorScheme()else lightColorScheme()).copy(
        primary=accent,onPrimary=if(dark)Color(0xff10291b)else Color.White,primaryContainer=accent.copy(alpha=.16f),onPrimaryContainer=accent,
        secondary=secondary,onSecondary=surface,secondaryContainer=if(dark)Color(0xff30353a)else Color(0xffe9eeea),onSecondaryContainer=ink,
        background=surface,onBackground=ink,surface=surface,onSurface=ink,onSurfaceVariant=secondary,
        surfaceVariant=if(dark)Color(0xff2b2d33)else Color(0xffececf0),surfaceContainer=surface,surfaceContainerLow=surface,
        surfaceContainerHigh=if(dark)Color(0xff292b31)else Color(0xfffafafc),surfaceContainerHighest=if(dark)Color(0xff33353b)else Color.White,
        surfaceTint=Color.Transparent,outline=secondary.copy(alpha=.45f),outlineVariant=secondary.copy(alpha=.20f),inversePrimary=Color(0xff8be0ad))
    val typography=Typography(
        titleLarge=TextStyle(fontSize=22.sp,lineHeight=28.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-.4).sp),
        titleMedium=TextStyle(fontSize=17.sp,lineHeight=23.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-.25).sp),
        titleSmall=TextStyle(fontSize=15.sp,lineHeight=21.sp,fontWeight=FontWeight.SemiBold),
        bodyLarge=TextStyle(fontSize=17.sp,lineHeight=24.sp,letterSpacing=(-.2).sp),
        bodyMedium=TextStyle(fontSize=15.sp,lineHeight=21.sp),bodySmall=TextStyle(fontSize=13.sp,lineHeight=18.sp),
        labelLarge=TextStyle(fontSize=15.sp,lineHeight=21.sp,fontWeight=FontWeight.Medium),
        labelMedium=TextStyle(fontSize=12.sp,lineHeight=17.sp,fontWeight=FontWeight.Medium),
        labelSmall=TextStyle(fontSize=11.sp,lineHeight=15.sp,fontWeight=FontWeight.Medium))
    CompositionLocalProvider(LocalLiquidAppearance provides LiquidAppearance(dark,reduceTransparency)){
        MaterialTheme(colorScheme=palette,typography=typography,shapes=Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(18.dp),large=RoundedCornerShape(26.dp),extraLarge=RoundedCornerShape(32.dp))){CompositionLocalProvider(LocalContentColor provides ink,content=content)}
    }
}

/** Navigation/control material only. The photo pixels and export renderer never use this effect. */
@Composable fun LiquidSurface(modifier:Modifier=Modifier,clear:Boolean=false,shape:Shape=RoundedCornerShape(28.dp),content:@Composable BoxScope.()->Unit){
    val appearance=LocalLiquidAppearance.current;val backdrop=LocalLiquidBackdrop.current
    val inspection=LocalInspectionMode.current
    val tint=when{appearance.reduceTransparency->if(appearance.dark)Color(0xff23252b)else Color(0xfff7f7fa)
        appearance.dark->if(clear)Color(0xff111318).copy(alpha=.52f)else Color(0xff24262d).copy(alpha=.78f)
        else->if(clear)Color.White.copy(alpha=.48f)else Color(0xfffafafd).copy(alpha=.80f)}
    val effect=if(backdrop!=null&&!appearance.reduceTransparency&&!inspection&&Build.VERSION.SDK_INT>=31){
        Modifier.drawBackdrop(backdrop=backdrop,shape={shape},effects={
            vibrancy();blur((if(clear)5f else 18f).dp.toPx())
            if(Build.VERSION.SDK_INT>=33)lens(12.dp.toPx(),(if(clear)22f else 12f).dp.toPx(),depthEffect=true)
        },highlight={Highlight(width=.65f.dp,alpha=if(appearance.dark).70f else .95f,style=HighlightStyle.Default(intensity=if(appearance.dark).30f else .65f,angle=35f))},
            shadow={Shadow(radius=16.dp,color=Color.Black.copy(alpha=if(appearance.dark).22f else .10f),offset=DpOffset(0.dp,5.dp))},
            onDrawSurface={drawRect(tint)})
    }else Modifier.shadow(12.dp,shape,ambientColor=Color.Black.copy(alpha=.09f),spotColor=Color.Black.copy(alpha=.12f)).background(tint,shape)
        .border(.65f.dp,if(appearance.dark)Color.White.copy(alpha=.13f)else Color.White.copy(alpha=.85f),shape)
    Box(modifier.then(effect).clip(shape),content=content)
}

@Composable fun LiquidIconButton(icon:ImageVector,label:String,onClick:()->Unit,enabled:Boolean=true,modifier:Modifier=Modifier,tint:Color=MaterialTheme.colorScheme.onSurface){
    val interaction=remember{MutableInteractionSource()};val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed).94f else 1f,spring(dampingRatio=.8f,stiffness=650f),label="press")
    IconButton(onClick=onClick,enabled=enabled,interactionSource=interaction,modifier=modifier.size(48.dp).graphicsLayer{scaleX=scale;scaleY=scale}){
        Icon(icon,label,tint=if(enabled)tint else tint.copy(alpha=.26f),modifier=Modifier.size(22.dp))
    }
}

object EditorTools {const val Template=0;const val Canvas=1;const val Frame=2;const val Text=3;const val Metadata=4;const val Watermark=5;const val Adjust=6}
private val ToolKeys=listOf("template","canvas","frame","text","metadata","watermark","adjust")
private val ToolNames=listOf("模板","画幅","边框","文字","参数","水印","调色")
private val ToolIcons=listOf(Icons.Outlined.AutoAwesomeMosaic,Icons.Outlined.Crop,Icons.Outlined.CropSquare,Icons.Outlined.Notes,Icons.Outlined.CameraAlt,Icons.Outlined.Layers,Icons.Outlined.Tune)

@Composable fun LiquidToolDock(selected:Int,onSelect:(Int)->Unit,modifier:Modifier=Modifier,textOnly:Boolean=false){
    val available=if(textOnly)listOf(0,1,2,3,5)else ToolNames.indices.toList()
    LiquidSurface(modifier.fillMaxWidth(),clear=true,shape=RoundedCornerShape(36.dp)){
        BoxWithConstraints{
        val density=LocalDensity.current;val measurer=rememberTextMeasurer()
        val itemWidth=(available.maxOf{index->with(density){measurer.measure(AnnotatedString(uiLabel(ToolNames[index])),style=MaterialTheme.typography.labelSmall).size.width.toDp()}}+12.dp).coerceAtLeast(52.dp)
        val scrollable=maxWidth<(available.size*itemWidth.value+12).dp;val scroll=rememberScrollState()
        LaunchedEffect(selected,scrollable){if(scrollable)scroll.animateScrollTo((with(density){(available.indexOf(selected).coerceAtLeast(0)*itemWidth.value+itemWidth.value/2).dp.toPx()}-with(density){maxWidth.toPx()}/2).toInt().coerceAtLeast(0))}
        Row(Modifier.fillMaxWidth().then(if(scrollable)Modifier.horizontalScroll(scroll)else Modifier).testTag("editor-tool-dock").semantics{testTagsAsResourceId=true}.padding(6.dp)){
            available.forEach{i->val name=ToolNames[i]
                val active=selected==i
                val color by animateColorAsState(if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,label="tool")
                Column((if(scrollable)Modifier.width(itemWidth)else Modifier.weight(1f)).heightIn(min=58.dp).clip(RoundedCornerShape(28.dp))
                    .background(if(active)MaterialTheme.colorScheme.primary.copy(alpha=.13f)else Color.Transparent)
                    .selectable(active,role=Role.Tab,onClick={onSelect(i)}).testTag("editor-tool:${ToolKeys[i]}").semantics{testTagsAsResourceId=true}.padding(vertical=7.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(3.dp)){
                    Icon(ToolIcons[i],null,tint=color,modifier=Modifier.size(22.dp));Text(uiLabel(name),color=color,style=MaterialTheme.typography.labelSmall,maxLines=1)
                }
            }
        }
        }
    }
}

@Composable fun LiquidEditorWorkspace(
    backdrop:LayerBackdrop,hasPhoto:Boolean,hdr:Boolean,panel:Int,expanded:Boolean,onExpanded:()->Unit,onPanel:(Int)->Unit,
    onLibrary:()->Unit,onExport:()->Unit,canExport:Boolean,canUndo:Boolean,canRedo:Boolean,onUndo:()->Unit,onRedo:()->Unit,
    sourceRibbon:@Composable ()->Unit,sourceInfo:@Composable ()->Unit,photo:@Composable BoxScope.()->Unit,controls:@Composable ()->Unit,
    progress:@Composable ()->Unit={},hasSources:Boolean=hasPhoto,hasProgress:Boolean=false,requestedControlHeight:Dp?=null,textOnly:Boolean=false,
    notice:@Composable ()->Unit={},hasNotice:Boolean=false,onReset:(()->Unit)?=null,onSettings:(()->Unit)?=null
){
    val density=LocalDensity.current
    val keyboardVisible=WindowInsets.ime.getBottom(density)>0
    val showSources=hasSources&&!keyboardVisible
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()){
        val compact=maxHeight<560.dp
        val narrowHeader=maxWidth<390.dp||density.fontScale>1.15f
        val libraryWidth=48.dp+if(onSettings==null)0.dp else if(narrowHeader)48.dp else labelWidth(appText(R.string.settings_title),MaterialTheme.typography.labelLarge)+24.dp
        val stackedHeader=libraryWidth+96.dp+labelWidth(appText(R.string.ui_export_188896),MaterialTheme.typography.labelLarge)+56.dp>maxWidth-32.dp
        val controlHeight=if(keyboardVisible){if(panel==EditorTools.Watermark)160.dp else 112.dp}else requestedControlHeight?:if(compact)144.dp else if(panel==0)186.dp else 224.dp
        val dockHeight=maxOf(70f,51f+15f*density.fontScale).dp
        val footerHeight=(if(showSources)92.dp else 0.dp)+(if(expanded)controlHeight+58.dp else 0.dp)+(if(hasProgress)58.dp else 0.dp)+(if(hasNotice)80.dp else 0.dp)+(if(keyboardVisible)0.dp else dockHeight)+8.dp+WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val topInset=WindowInsets.statusBars.asPaddingValues().calculateTopPadding()+74.dp+if(stackedHeader)56.dp else 0.dp
        val bottomInset=footerHeight.coerceAtMost((maxHeight-170.dp).coerceAtLeast(120.dp))+8.dp
        val viewport=Modifier.fillMaxSize().padding(top=topInset,bottom=bottomInset)
        // Exclude the live HDR plane from backdrop capture. Glass samples an SDR scene only.
        val capture=if(LocalInspectionMode.current||LocalLiquidAppearance.current.reduceTransparency)Modifier else Modifier.layerBackdrop(backdrop)
        Box(Modifier.matchParentSize().then(capture).background(MaterialTheme.colorScheme.background))
        // Keep one photo node and one drawing path across HDR mode changes.
        // The live image is never captured by the glass material recorder.
        Box(viewport,content=photo)
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(start=16.dp,end=16.dp,top=10.dp),verticalAlignment=Alignment.CenterVertically){
            LiquidSurface(clear=true,shape=RoundedCornerShape(26.dp)){Row{LiquidIconButton(Icons.Outlined.PhotoLibrary,appText(R.string.settings_library),onLibrary);if(onSettings!=null){if(narrowHeader)LiquidIconButton(Icons.Outlined.Settings,appText(R.string.settings_title),onSettings)else TextButton(onClick=onSettings,modifier=Modifier.heightIn(min=48.dp)){Text(appText(R.string.settings_title))}}}}
            Spacer(Modifier.weight(1f))
            if(!stackedHeader)LiquidSurface(clear=true,shape=RoundedCornerShape(25.dp)){
                Row{LiquidIconButton(Icons.Outlined.Undo,appText(R.string.ui_undo_9fcefd),onUndo,canUndo);LiquidIconButton(Icons.Outlined.Redo,appText(R.string.ui_redo_1238f0),onRedo,canRedo)}
            }
            Spacer(Modifier.weight(1f))
            LiquidSurface(shape=RoundedCornerShape(26.dp)){
                Button(onClick=onExport,enabled=canExport,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary),
                    contentPadding=PaddingValues(horizontal=16.dp,vertical=0.dp),modifier=Modifier.heightIn(min=48.dp)){
                    Icon(Icons.Outlined.IosShare,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(appText(R.string.ui_export_188896))
                }
            }
        }
        if(stackedHeader)LiquidSurface(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start=16.dp,top=66.dp),clear=true,shape=RoundedCornerShape(25.dp)){
            Row{LiquidIconButton(Icons.Outlined.Undo,appText(R.string.ui_undo_9fcefd),onUndo,canUndo);LiquidIconButton(Icons.Outlined.Redo,appText(R.string.ui_redo_1238f0),onRedo,canRedo)}
        }
        if(hasPhoto)Box(Modifier.align(Alignment.TopEnd).padding(top=topInset+4.dp,end=20.dp)){sourceInfo()}
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal=12.dp).padding(bottom=8.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(hasNotice)notice()
            if(showSources)sourceRibbon()
            if(hasProgress)progress()
            AnimatedVisibility(expanded,enter=fadeIn(tween(150))+expandVertically(spring(stiffness=650f)),exit=fadeOut(tween(100))+shrinkVertically(tween(180))){
                LiquidSurface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(28.dp)){
                    Column{
                        Row(Modifier.fillMaxWidth().padding(start=16.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically){
                            Text(uiLabel(ToolNames[panel]),style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                            if(onReset!=null)TextButton(onClick=onReset,modifier=Modifier.height(48.dp).semantics{contentDescription=appText(R.string.ui_reset_1_s_3fbe5d, uiLabel(ToolNames[panel]))}){Text(appText(R.string.ui_reset_a19193),style=MaterialTheme.typography.labelMedium)}
                            LiquidIconButton(Icons.Outlined.KeyboardArrowDown,appText(R.string.ui_collapse_panel_1553bf),onExpanded)
                        }
                        Box(Modifier.fillMaxWidth().height(controlHeight)){controls()}
                    }
                }
            }
            if(!keyboardVisible)LiquidToolDock(panel,{if(it==panel&&!expanded)onExpanded()else onPanel(it)},textOnly=textOnly)
        }
    }
}
