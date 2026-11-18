package com.parker.imageborder.editor

import org.junit.Assert.*
import org.junit.Test

class NumericParameterTest {
    @Test fun pixelEntryAndSteppingUseDisplayedUnits(){
        for(unit in listOf(640f,3000f,4032f)){
            val p=numericParameter("字号",unit);val range=.008f.. .1f
            val raw=p.parse("60",range)!!
            assertEquals(60f,p.displayed(raw),.0001f)
            assertEquals(61f,p.displayed(p.stepped(raw,1,range)),.0001f)
            assertEquals(60f,p.displayed(p.snap(60.3f,range)),.0001f)
            assertEquals("60",p.input(raw));assertNull(p.parse("60.5",range))
        }
    }
    @Test fun signedDecimalAndLocalizedInputAreValidatedWithoutClamping(){
        val line=numericParameter("行距")
        assertEquals(1.15f,line.parse("1,15",1f..3f)!!,0f)
        assertEquals(1.15f,line.parse("１．１５",1f..3f)!!,0f)
        assertEquals(1.15f,line.parse("١.١٥",1f..3f)!!,0f)
        assertEquals(1280f,numericParameter("输出最长边").parse("1,280",640f..12000f)!!,0f)
        for(text in listOf("",".","-","NaN","Infinity","1e2","1.154","4","0.9"))assertNull(text,line.parse(text,1f..3f))
        val angle=numericParameter("旋转角度")
        assertEquals(-12.5f,angle.parse("−12.5",-180f..180f)!!,0f)
        assertEquals(-11f,angle.stepped(-12f,1,-180f..180f),0f)
        assertEquals(-11.5f,angle.stepped(-12.5f,1,-180f..180f),0f)
        val ev=numericParameter("曝光")
        assertEquals(0f,ev.snap(.049f,-2f..2f),0f)
        assertEquals("0.0",ev.input(-.000001f))
    }
    @Test fun endpointsAndSmallRangesRemainReachable(){
        val p=numericParameter("图文间距",4032f);val range=.008f.. .1f
        assertEquals(33f,p.bounds(range).start,0f);assertEquals(403f,p.bounds(range).endInclusive,0f)
        assertNull(p.parse("32",range));assertNull(p.parse("404",range))
        assertEquals(403f,p.displayed(p.stepped(p.parse("403",range)!!,1,range)),.0001f)
        val crop=numericParameter("左边界")
        assertEquals(6f,crop.bounds(.053f.. .459f).start,0f)
        assertEquals(45f,crop.bounds(.053f.. .459f).endInclusive,0f)
    }
    @Test fun parameterUnitsDoNotDependOnRangeOrTranslation(){
        assertEquals(NumericUnit.RATIO,numericParameter("画布宽高比").unit)
        assertEquals(NumericUnit.EV,numericParameter("曝光").unit)
        assertEquals(NumericUnit.COUNT,numericParameter("每行字数").unit)
        assertEquals(NumericUnit.PIXELS,numericParameter("输出最长边").unit)
        assertEquals(100f,numericParameter("照片大小").scale,0f)
        assertEquals(95f,numericParameter("JPG 质量").displayed(95f),0f)
        assertEquals(1.15f,numericParameter("行距").presets[1],0f)
        assertEquals(.5f,numericParameter("段间距").presets[2],0f)
    }
    @Test fun existingDocumentValuesAreNotRoundedOnLoad(){
        val doc=EditDocument(frame=FrameStyle(left=.017123f),textStyle=TextBlockStyle(size=.03567f,paragraphSpacing=.237f))
        assertEquals(doc,EditDocument.read(doc.json()))
    }
}
