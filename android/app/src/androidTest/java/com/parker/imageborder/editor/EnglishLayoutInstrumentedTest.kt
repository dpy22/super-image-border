package com.parker.imageborder.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import org.junit.Before
import org.junit.After
import com.parker.imageborder.R
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Checks real Compose line layout and actions, not just screenshot dimensions. No photo/DB writes. */
class EnglishLayoutInstrumentedTest {
    val rule=createEmptyComposeRule()
    @get:Rule val environment:org.junit.rules.TestRule=org.junit.rules.RuleChain.outerRule(object:org.junit.rules.ExternalResource(){
        override fun before(){useExistingAppWindow()}
        override fun after(){restoreEditor()}
    }).around(rule)
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private lateinit var activity:ComponentActivity
    private var priorLanguage="system"
    private var changedLanguage=false
    private fun resumed():ComponentActivity? {
        var result:ComponentActivity?=null
        instrumentation.runOnMainSync{result=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull{it.javaClass.name=="com.parker.imageborder.MainActivity"} as? ComponentActivity}
        return result
    }
    private fun waitResumed(previous:ComponentActivity?=null):ComponentActivity {
        val end=SystemClock.elapsedRealtime()+10000
        while(SystemClock.elapsedRealtime()<end){val current=resumed();if(current!=null&&current!==previous&&!current.isFinishing&&!current.isDestroyed)return current;SystemClock.sleep(40)}
        error("App window did not resume")
    }
    fun useExistingAppWindow(){
        val context=instrumentation.targetContext
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){"Unlock phone before UI checks"}
        UiDevice.getInstance(instrumentation).executeShellCommand("am start -n com.parker.imageborder/.MainActivity")
        activity=waitResumed()
        priorLanguage=AppLanguage.selection()
        if(appText(R.string.settings_title)!="Settings"){
            changedLanguage=true;val previous=activity
            instrumentation.runOnMainSync{AppLanguage.set("en")}
            activity=waitResumed(previous)
        }
        assertEquals("English layout fixture", "Settings",appText(R.string.settings_title))
    }
    fun restoreEditor(){
        if(::activity.isInitialized){
            val previous=activity
            instrumentation.runOnMainSync{if(changedLanguage)AppLanguage.set(priorLanguage)else activity.recreate()}
            activity=waitResumed(previous)
        }
    }
    private fun render(content:@Composable ()->Unit){instrumentation.runOnMainSync{
        activity.setContentView(androidx.compose.ui.platform.ComposeView(activity).apply{setContent(content=content)})
    }}
    private data class Size(val width:Int,val scale:Float)
    private val sizes=listOf(Size(320,1f),Size(320,1.5f),Size(320,2f),Size(360,1.5f),Size(402,1f))
    private fun noClippedText(){
        rule.waitForIdle()
        val matcher=SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and !SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)
        for(node in rule.onAllNodes(matcher,useUnmergedTree=true).fetchSemanticsNodes()){
            val layouts=mutableListOf<TextLayoutResult>()
            rule.runOnIdle{node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)}
            for(layout in layouts){
                val message="Clipped label '${layout.layoutInput.text}': size=${layout.size}, paragraph=${layout.multiParagraph.width} x ${layout.multiParagraph.height}, lines=${layout.lineCount}, edges=${(0 until layout.lineCount).map{layout.getLineLeft(it) to layout.getLineRight(it)}}"
                // Compose rounds its IntSize; allow only a one physical pixel rounding difference.
                assertFalse(message,layout.multiParagraph.didExceedMaxLines)
                assertTrue(message,layout.multiParagraph.height<=layout.size.height+1f)
                repeat(layout.lineCount){line->assertTrue(message,layout.getLineRight(line)<=layout.size.width+1f&&layout.getLineLeft(line)>=-1f)}
            }
        }
    }
    @Composable private fun Frame(size:Size,content:@Composable ()->Unit){
        val density=LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density,size.scale)){
            LiquidEditorTheme(false){Surface(Modifier.width(size.width.dp).height(740.dp)){Column(Modifier.fillMaxSize().padding(16.dp)){content()}}}
        }
    }
    @Test fun alignmentLabelsStayWholeAndEveryChoiceIsReachable(){
        assertEquals("Settings",appText(R.string.settings_title))
        var size by mutableStateOf(sizes.first());var selected by mutableStateOf("left")
        render{Frame(size){TextAlignment(selected,true){selected=it}}}
        for(next in sizes){
            rule.runOnIdle{size=next};noClippedText()
            for((key,label)in listOf("left" to "Left","center" to "Center","right" to "Right","justify" to "Justify","distribute" to "Spread")){
                rule.onNodeWithContentDescription(appText(R.string.ui_body_alignment_1_s_90ead4,label)).performScrollTo().performClick()
                rule.runOnIdle{assertEquals(key,selected)};noClippedText()
            }
        }
    }
    @Test fun metadataInputKeepsUsefulWidthAndClearRestoreWork(){
        var size by mutableStateOf(sizes.first());var value by mutableStateOf("EOS R50")
        render{Frame(size){MetadataInputRow(value,"Camera model",true,true,{}, {value=it},{value=""},{value="EOS R50"})}}
        for(next in sizes){
            rule.runOnIdle{size=next};noClippedText()
            val input=rule.onNode(hasSetTextAction())
            assertTrue("Input squeezed at $next",input.getUnclippedBoundsInRoot().width>=160.dp)
            rule.onNodeWithContentDescription(appText(R.string.ui_clear_1_s_c7e3ae,"Camera model")).performClick()
            rule.runOnIdle{assertEquals("",value)}
            input.performTextInput("Camera");rule.runOnIdle{assertEquals("Camera",value)}
            rule.onNodeWithContentDescription(appText(R.string.ui_restore_automatic_1_s_f8e14d,"Camera model")).performClick()
            rule.runOnIdle{assertEquals("EOS R50",value)}
        }
    }
    @Test fun hundredPhotoCountAndActionsStayVisible(){
        var size by mutableStateOf(sizes.first());var count by mutableIntStateOf(100);var adds=0
        render{Frame(size){GallerySelectionActions(count,100,{count=0},{adds++})}}
        for(next in sizes){
            rule.runOnIdle{size=next;count=100};noClippedText()
            rule.onNodeWithText("100 / 100").assertIsDisplayed()
            rule.onNodeWithText("Add (100)").assertIsDisplayed().performClick()
            rule.onNodeWithText("Clear").performClick();rule.runOnIdle{assertEquals(0,count)};noClippedText()
        }
        rule.runOnIdle{assertEquals(sizes.size,adds)}
    }
    @Test fun independentActionsWrapWithoutBreakingTheirLabels(){
        var size by mutableStateOf(sizes.first())
        render{Frame(size){ActionRow{TextButton({}){Text("Text color")};TextButton({}){Text("Text background")};TextButton({}){Text("Import font")}}}}
        for(next in sizes){rule.runOnIdle{size=next};noClippedText();rule.onNodeWithText("Text background").assertIsDisplayed()}
    }
    @Test fun settingsTitleAndValuesFitAtLargeText(){
        var size by mutableStateOf(sizes.first())
        render{Frame(size){SettingsScreen(false,{},{},{},"system",{})}}
        for(next in sizes){rule.runOnIdle{size=next};noClippedText()}
    }
    @Test fun exportModesAreVisibleWithoutHorizontalScrolling(){
        var size by mutableStateOf(sizes.first());var selected by mutableStateOf("share")
        render{Frame(size){Choices(listOf("original" to appText(R.string.ui_original_photo_pixels_7c0346),"share" to "Sharing","custom" to "Custom size"),selected,wrap=true){selected=it}}}
        for(next in sizes){
            rule.runOnIdle{size=next};noClippedText()
            rule.onNodeWithText("Full resolution").assertIsDisplayed().performClick();rule.runOnIdle{assertEquals("original",selected)}
            rule.onNodeWithText("Custom size").assertIsDisplayed().performClick();rule.runOnIdle{assertEquals("custom",selected)}
        }
    }

    @Test fun settingsRowsShareGridAndCenterAccessories(){
        var size by mutableStateOf(sizes.first())
        render{Frame(size){Column{
            SettingsItem("language","Language","English",action={})
            SettingsItem("version","Version","2.0.9")
            SettingsItem("privacy","Privacy",action={})
            SettingsItem("transparency","Reduce transparency",checked=false,changeChecked={})
        }}}
        for(next in sizes){
            rule.runOnIdle{size=next};noClippedText()
            val ids=listOf("language","version","privacy","transparency")
            val titles=ids.map{rule.onNodeWithTag("settings-title:$it",useUnmergedTree=true).getUnclippedBoundsInRoot()}
            titles.forEach{assertEquals("Common text grid at $next",titles.first().left.value,it.left.value,.5f)}
            for(id in listOf("language","version","privacy")){
                val row=rule.onNodeWithTag("settings-row:$id",useUnmergedTree=true).getUnclippedBoundsInRoot()
                val accessory=rule.onNodeWithTag("settings-accessory:$id",useUnmergedTree=true).getUnclippedBoundsInRoot()
                assertTrue("Minimum touch row",row.height>=64.dp)
                assertEquals("Accessory vertical centre at $next",((row.top+row.bottom)/2).value,((accessory.top+accessory.bottom)/2).value,.5f)
            }
            val language=rule.onNodeWithTag("settings-value:language",useUnmergedTree=true).getUnclippedBoundsInRoot()
            val version=rule.onNodeWithTag("settings-value:version",useUnmergedTree=true).getUnclippedBoundsInRoot()
            assertEquals("Values share trailing column",language.right.value,version.right.value,.5f)
            if(next.scale==1f){
                fun baseline(tag:String):Float {
                    val node=rule.onNodeWithTag(tag,useUnmergedTree=true)
                    val result=mutableListOf<TextLayoutResult>();node.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(result)}
                    return node.getUnclippedBoundsInRoot().top.value+with(rule.density){result.first().firstBaseline.toDp().value}
                }
                assertEquals("Title/value text baseline",baseline("settings-title:language"),baseline("settings-value:language"),.5f)
            }
        }
    }
    @Test fun settingsActionsAndWholeSwitchRowWork(){
        var checked by mutableStateOf(false);var opens=0
        render{Frame(sizes.first()){SettingsItem("language","Language","English",action={opens++});SettingsItem("transparency","Reduce transparency",checked=checked,changeChecked={checked=it})}}
        rule.onNodeWithTag("settings-row:language").performClick();rule.runOnIdle{assertEquals(1,opens)}
        rule.onNodeWithTag("settings-row:transparency").assertIsOff().performClick().assertIsOn()
        rule.runOnIdle{assertTrue(checked)}
    }


    @Test fun preciseNumbersPreviewCancelAndConfirmWithoutLosingInput(){
        var value by mutableFloatStateOf(.0997f);var starts=0;var finishes=0;var changes=0
        val parameter=numericParameter("字号",1000f)
        render{Frame(sizes.first()){LiquidSliderRow("字号",value,0f.. .5f,onStart={starts++},onFinish={finishes++},parameter=parameter){value=it;changes++}}}
        rule.runOnIdle{assertEquals(0,changes)}
        rule.onNodeWithTag("number:字号").performClick()
        rule.onNodeWithTag("numeric-input").performTextReplacement("137")
        rule.runOnIdle{assertEquals(.137f,value,.000001f)}
        rule.onNodeWithTag("numeric-input").performTextInput("5")
        rule.onNodeWithTag("numeric-input").assertTextContains("1375")
        rule.runOnIdle{assertEquals(.137f,value,.000001f)}
        rule.onNodeWithText("Done").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle{assertEquals(.0997f,value,0f);assertEquals(1,starts);assertEquals(1,finishes)}
        rule.onNodeWithTag("number:字号").performClick()
        rule.onNodeWithTag("numeric-input").performTextReplacement("140")
        rule.onNodeWithText("Done").performClick()
        rule.runOnIdle{assertEquals(.14f,value,.000001f);assertEquals(2,starts);assertEquals(2,finishes)}
    }
    @Test fun numericSteppersAndAccessibilityUseDisplayedUnits(){
        var value by mutableFloatStateOf(.1f);var starts=0;var finishes=0
        render{Frame(sizes.first()){LiquidSliderRow("字号",value,0f.. .5f,onStart={starts++},onFinish={finishes++},parameter=numericParameter("字号",1000f)){value=it}}}
        rule.onNodeWithContentDescription("Increase Font size").performClick()
        rule.runOnIdle{assertEquals(.101f,value,.000001f)}
        rule.onNodeWithContentDescription("Decrease Font size").performClick()
        rule.runOnIdle{assertEquals(.1f,value,.000001f)}
        rule.onNodeWithTag("slider:字号").performSemanticsAction(SemanticsActions.SetProgress){assertTrue(it(128f))}
        rule.runOnIdle{assertEquals(.128f,value,.000001f);assertEquals(starts,finishes)}
    }
    @Test fun lineAndParagraphPresetsAreExactAndDisabledControlsStayDisabled(){
        var value by mutableFloatStateOf(1.3f)
        render{Frame(sizes.first()){LiquidSliderRow("行距",value,1f..3f){value=it};LiquidSliderRow("照片大小",1f,.2f..4f,enabled=false){error("Disabled control changed")}}}
        rule.onNodeWithTag("number:照片大小").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Increase Photo scale").assertIsNotEnabled()
        rule.onNodeWithTag("number:行距").performClick()
        rule.onNodeWithText("1.15 ×").performClick()
        rule.onNodeWithText("Done").performClick()
        rule.runOnIdle{assertEquals(1.15f,value,0f)}
    }
    @Test fun numericLabelsFitNarrowWindowsAndLargeText(){
        var size by mutableStateOf(sizes.first())
        render{Frame(size){LiquidSliderRow("玻璃不透明度",.45f,0f..1f,onChange={});LiquidSliderRow("参数栏高度",.12f,.12f.. .6f,parameter=numericParameter("参数栏高度",6000f),onChange={})}}
        for(next in sizes){rule.runOnIdle{size=next};noClippedText()}
    }

    @Test fun heldStepperUsesOneUndoGroupAndRetainsFractionalAngles(){
        var value by mutableFloatStateOf(-12.5f);var starts=0;var finishes=0
        render{Frame(sizes.first()){LiquidSliderRow("旋转角度",value,-180f..180f,onStart={starts++},onFinish={finishes++}){value=it}}}
        val plus=rule.onNodeWithContentDescription(appText(R.string.ui_numeric_increase,uiLabel("旋转角度")))
        plus.performTouchInput{down(center)}
        rule.mainClock.advanceTimeBy(750)
        plus.performTouchInput{up()}
        rule.runOnIdle{assertTrue(value>-11.5f);assertEquals(.5f,kotlin.math.abs(value%1f),.0001f);assertEquals(1,starts);assertEquals(1,finishes)}
        plus.performClick()
        rule.runOnIdle{assertEquals(2,starts);assertEquals(2,finishes)}
        plus.performTouchInput{down(center)};rule.mainClock.advanceTimeBy(650);plus.performTouchInput{cancel()}
        rule.runOnIdle{assertEquals(3,starts);assertEquals(3,finishes)}
        plus.performClick();rule.runOnIdle{assertEquals(4,starts);assertEquals(4,finishes)}
    }
    @Test fun negativeValuesHaveAnExplicitSignControl(){
        var value by mutableFloatStateOf(.5f)
        render{Frame(sizes.first()){LiquidSliderRow("曝光",value,-2f..2f){value=it}}}
        rule.onNodeWithTag("number:曝光").performClick()
        rule.onNodeWithContentDescription("Change sign").performClick()
        rule.runOnIdle{assertEquals(-.5f,value,0f)}
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithTag("number:曝光").assertTextContains("-0.5 EV")
    }

    @Test fun metadataPositionAndArrangementLabelsFitAndRespond(){
        var size by mutableStateOf(sizes.first());var style by mutableStateOf(MetadataStyle())
        val template=TemplateDefinition("plate","",1,0,"")
        render{Frame(size){MetadataLayoutChoices(style,template,1.5f){style=it}}}
        for(next in sizes){rule.runOnIdle{size=next};noClippedText()
            for((key,label)in listOf("top" to "Top","bottom" to "Bottom","left" to "Left","right" to "Right")){
                rule.onAllNodesWithText(label).onFirst().performClick();rule.runOnIdle{assertEquals(key,style.position)}
            }
            rule.onNodeWithText("Two columns").performClick();rule.runOnIdle{assertEquals("split",style.arrangement)}
            rule.onNodeWithText("Stacked").performClick();rule.runOnIdle{assertEquals("stacked",style.arrangement)}
            rule.onNodeWithContentDescription(appText(R.string.ui_metadata_alignment_value,"Center")).performScrollTo().performClick()
            rule.runOnIdle{assertEquals("center",style.alignment)};noClippedText()
        }
    }

}
