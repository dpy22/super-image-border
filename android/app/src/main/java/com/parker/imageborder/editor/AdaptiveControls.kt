package com.parker.imageborder.editor

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*

/** Measure the localized label at the user's actual font scale, never shrink the type. */
@Composable internal fun labelWidth(text:String,style:TextStyle):Dp {
    val measurer=rememberTextMeasurer()
    return with(LocalDensity.current){measurer.measure(AnnotatedString(text),style=style,maxLines=1,softWrap=false).size.width.toDp()}
}

/** Independent actions wrap as whole buttons rather than squeezing each other's labels. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ActionRow(modifier:Modifier=Modifier,content:@Composable FlowRowScope.()->Unit){
    FlowRow(modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(0.dp),content=content)
}
