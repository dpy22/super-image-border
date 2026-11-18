@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.parker.imageborder.editor

import com.parker.imageborder.R

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*

internal fun NumericParameter.unitText():String=when(unit){
    NumericUnit.PIXELS->"px";NumericUnit.PERCENT->"%";NumericUnit.MULTIPLIER->"×";NumericUnit.EM->"em"
    NumericUnit.NUMBER->"";NumericUnit.EV->"EV";NumericUnit.DEGREES->"°";NumericUnit.RATIO->": 1";NumericUnit.COUNT->appText(R.string.ui_numeric_characters)
}
internal fun NumericParameter.valueText(value:Float)="${number(value)} ${unitText()}".trimEnd()

@Composable internal fun LiquidSliderRow(label:String,value:Float,range:ClosedFloatingPointRange<Float>,enabled:Boolean=true,onStart:()->Unit={},onFinish:()->Unit={},valueText:String?=null,displayLabel:String?=null,parameter:NumericParameter=numericParameter(label),onChange:(Float)->Unit){
    val interaction=remember{MutableInteractionSource()};val dragging by interaction.collectIsDraggedAsState();val pressed by interaction.collectIsPressedAsState()
    val diameter by animateDpAsState(if(dragging||pressed)25.dp else 21.dp,spring(stiffness=650f),label="slider-knob")
    val colors=SliderDefaults.colors(thumbColor=Color.White,activeTrackColor=MaterialTheme.colorScheme.primary,inactiveTrackColor=MaterialTheme.colorScheme.onSurface.copy(alpha=.13f),disabledActiveTrackColor=MaterialTheme.colorScheme.onSurface.copy(alpha=.2f),disabledInactiveTrackColor=MaterialTheme.colorScheme.onSurface.copy(alpha=.08f))
    var editing by remember{mutableStateOf(false)};var sliderActive by remember{mutableStateOf(false)}
    val limits=parameter.bounds(range);val title=displayLabel?:uiLabel(label)
    val text=valueText?:parameter.valueText(value)
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    val liveValue by rememberUpdatedState(value)
    val currentFinish by rememberUpdatedState(onFinish)
    DisposableEffect(Unit){onDispose{if(sliderActive)currentFinish()}}
    fun start(){focus.clearFocus();keyboard?.hide();onStart()}
    fun once(next:Float){start();onChange(next);onFinish()}
    Column(verticalArrangement=Arrangement.spacedBy(0.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text(title,modifier=Modifier.weight(1f).padding(end=8.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurface.copy(alpha=if(enabled)1f else .38f))
            Surface(onClick={start();editing=true},enabled=enabled,shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.primary.copy(alpha=if(enabled).08f else .025f),
                modifier=Modifier.widthIn(min=76.dp).heightIn(min=48.dp).testTag("number:$label").semantics{contentDescription=appText(R.string.ui_numeric_edit,title);stateDescription=text;testTagsAsResourceId=true}){
                Box(Modifier.padding(horizontal=12.dp,vertical=8.dp),contentAlignment=Alignment.Center){Text(text,style=MaterialTheme.typography.labelMedium.copy(fontFeatureSettings="tnum"),color=if(enabled)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha=.38f))}
            }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            NumericStepButton(false,title,enabled&&parameter.stepped(value,-1,range)<value,onStart=::start,onFinish=onFinish){onChange(parameter.stepped(liveValue,-1,range))}
            Slider(value=parameter.displayed(value).coerceIn(limits.start,limits.endInclusive),onValueChange={v->
                if(!sliderActive){start();sliderActive=true};onChange(parameter.snap(v,range,drag=true))
            },onValueChangeFinished={if(sliderActive){sliderActive=false;onFinish()}},enabled=enabled,valueRange=limits,interactionSource=interaction,colors=colors,
                modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("slider:$label").semantics{contentDescription=title;stateDescription=text;testTagsAsResourceId=true
                    setProgress{target->if(enabled){once(parameter.snap(target,range,drag=true));true}else false}},
                thumb={Box(Modifier.size(diameter).shadow(4.dp,CircleShape).background(Color.White,CircleShape).border(.5f.dp,Color.Black.copy(alpha=.06f),CircleShape))},
                track={state->SliderDefaults.Track(sliderState=state,colors=colors,enabled=enabled,modifier=Modifier.height(4.dp),thumbTrackGapSize=0.dp,drawStopIndicator=null)})
            NumericStepButton(true,title,enabled&&parameter.stepped(value,1,range)>value,onStart=::start,onFinish=onFinish){onChange(parameter.stepped(liveValue,1,range))}
        }
    }
    if(editing)NumericValueDialog(title,value,range,parameter,onChange,onFinish){editing=false}
}

/** Repeat uses one undo group; accessibility clicks also perform a complete step. */
@Composable private fun NumericStepButton(increase:Boolean,label:String,enabled:Boolean,onStart:()->Unit,onFinish:()->Unit,onStep:()->Unit){
    val interaction=remember{MutableInteractionSource()};val pressed by interaction.collectIsPressedAsState()
    var repeating by remember{mutableStateOf(false)}
    val liveStep by rememberUpdatedState(onStep);val liveStart by rememberUpdatedState(onStart);val liveFinish by rememberUpdatedState(onFinish)
    LaunchedEffect(pressed){if(pressed){repeating=false;kotlinx.coroutines.delay(400);repeating=true;liveStart()
        try{while(true){liveStep();kotlinx.coroutines.delay(75)}}finally{liveFinish()}}else repeating=false}
    IconButton(onClick={if(!repeating){onStart();onStep();onFinish()};repeating=false},enabled=enabled,interactionSource=interaction,modifier=Modifier.size(48.dp)){
        Icon(if(increase)androidx.compose.material.icons.Icons.Outlined.Add else androidx.compose.material.icons.Icons.Outlined.Remove,
            appText(if(increase)R.string.ui_numeric_increase else R.string.ui_numeric_decrease,label),Modifier.size(18.dp))
    }
}

@Composable private fun NumericValueDialog(title:String,value:Float,range:ClosedFloatingPointRange<Float>,parameter:NumericParameter,onChange:(Float)->Unit,onFinish:()->Unit,dismiss:()->Unit){
    val original=remember{value};var text by remember{mutableStateOf(TextFieldValue(parameter.input(value),TextRange(0,parameter.input(value).length)))}
    val request=remember{androidx.compose.ui.focus.FocusRequester()};val keyboard=LocalSoftwareKeyboardController.current
    val parsed=parameter.parse(text.text,range);val limits=parameter.bounds(range)
    var completed by remember{mutableStateOf(false)}
    val liveChange by rememberUpdatedState(onChange);val liveFinish by rememberUpdatedState(onFinish)
    fun close(accept:Boolean){if(!completed){completed=true;if(accept){parsed?.let(onChange)}else onChange(original);onFinish()};keyboard?.hide();dismiss()}
    DisposableEffect(Unit){onDispose{if(!completed){liveChange(original);liveFinish()}}}
    LaunchedEffect(Unit){request.requestFocus()}
    AlertDialog(onDismissRequest={close(false)},title={Text(title)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(value=text,onValueChange={text=it;parameter.parse(it.text,range)?.let(onChange)},singleLine=true,
                modifier=Modifier.fillMaxWidth().focusRequester(request).testTag("numeric-input"),suffix={Text(parameter.unitText())},
                leadingIcon=if(limits.start<0f){{TextButton(onClick={
                    val changed=if(text.text.startsWith("-")||text.text.startsWith("−"))text.text.drop(1)else "-"+text.text.removePrefix("+")
                    text=TextFieldValue(changed,TextRange(changed.length));parameter.parse(changed,range)?.let(onChange)
                },modifier=Modifier.semantics{contentDescription=appText(R.string.ui_numeric_sign)}){Text("±")}}}else null,
                isError=text.text.isNotEmpty()&&parsed==null,
                supportingText=if(text.text.isNotEmpty()&&parsed==null){{Text(when(parameter.problem(text.text,range)){
                    NumericInputProblem.PRECISION->if(parameter.decimals==0)appText(R.string.ui_numeric_whole)else appText(R.string.ui_numeric_decimals,parameter.decimals)
                    NumericInputProblem.RANGE->appText(R.string.ui_numeric_range,parameter.input(parameter.raw(limits.start,range)),parameter.input(parameter.raw(limits.endInclusive,range)))
                    else->appText(R.string.ui_numeric_invalid)
                })}}else null,
                keyboardOptions=KeyboardOptions(keyboardType=if(parameter.decimals==0)androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Decimal,imeAction=ImeAction.Done),
                keyboardActions=KeyboardActions(onDone={if(parsed!=null)close(true)}))
            if(parameter.presets.isNotEmpty())ActionRow{
                for(preset in parameter.presets.filter{it in limits}){
                    val raw=parameter.raw(preset,range)
                    FilterChip(selected=parsed!=null&&kotlin.math.abs(parameter.displayed(parsed)-preset)<.0001f,
                        onClick={text=TextFieldValue(parameter.input(raw));onChange(raw)},label={Text(parameter.valueText(raw))},colors=liquidChipColors(),border=null)
                }
            }
        }
    },confirmButton={TextButton(onClick={close(true)},enabled=parsed!=null){Text(appText(R.string.ui_done_33246f))}},dismissButton={TextButton(onClick={close(false)}){Text(appText(R.string.ui_cancel_4d0b46))}})
}

@Composable fun LiquidTextField(value:String,label:String,onChange:(String)->Unit,modifier:Modifier=Modifier,multi:Boolean=false,showLabel:Boolean=true){
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    var focused by remember{mutableStateOf(false)}
    var input by remember{mutableStateOf(TextFieldValue(value))}
    if(input.text!=value)SideEffect{input=input.copy(text=value,selection=TextRange(input.selection.start.coerceAtMost(value.length),input.selection.end.coerceAtMost(value.length)),composition=null)}
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)){
    if(showLabel)Text(uiLabel(label),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    TextField(value=input,onValueChange={input=it;onChange(it.text)},
        modifier=Modifier.fillMaxWidth().onFocusChanged{focused=it.isFocused}.border(.8f.dp,if(focused)MaterialTheme.colorScheme.primary.copy(alpha=.5f)else MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f),RoundedCornerShape(14.dp)).testTag("input:$label").semantics{contentDescription=label;testTagsAsResourceId=true},minLines=if(multi)2 else 1,maxLines=if(multi)6 else 1,singleLine=!multi,
        textStyle=MaterialTheme.typography.bodyMedium,shape=RoundedCornerShape(14.dp),
        keyboardOptions=KeyboardOptions(imeAction=if(multi)ImeAction.Default else ImeAction.Done),keyboardActions=KeyboardActions(onDone={focus.clearFocus();keyboard?.hide()}),
        colors=TextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.onSurface.copy(alpha=.045f),unfocusedContainerColor=MaterialTheme.colorScheme.onSurface.copy(alpha=.035f),
            focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent,disabledIndicatorColor=Color.Transparent,
            focusedLabelColor=MaterialTheme.colorScheme.onSurfaceVariant,unfocusedLabelColor=MaterialTheme.colorScheme.onSurfaceVariant))
    }
}
