package com.parker.imageborder.editor
import android.graphics.Bitmap
import android.graphics.ColorSpace
import java.util.function.DoubleUnaryOperator

object NativeCodec {
    init { System.loadLibrary("imagecodecs") }
    external fun start(path:String,width:Int,height:Int,png:Boolean,quality:Int,profile:ByteArray?):Long
    external fun write(handle:Long,bitmap:Bitmap,rows:Int)
    external fun writeRegion(handle:Long,bitmap:Bitmap,left:Int,top:Int,rows:Int)
    external fun finish(handle:Long,commit:Boolean)
    external fun decodeHdr(path:String,maxEdge:Int):Bitmap
    external fun encodeHdr(base:Bitmap,hdr:Bitmap,path:String,quality:Int,gamut:Int,exif:ByteArray?)
    external fun adjustPixels(bitmap:Bitmap,values:FloatArray,exposure:FloatArray,filter:Int)
    external fun applyLook(bitmap:Bitmap,lut:FloatArray,strength:Float)
    external fun reconstructHdr(base:Bitmap,gainmap:Bitmap,output:Bitmap,metadata:FloatArray,transfer:FloatArray,matrices:FloatArray)
    fun gamut(space:ColorSpace):Int {
        val d50=floatArrayOf(.964212f,1f,.825188f)
        val matrix=(ColorSpace.adapt(space,d50) as ColorSpace.Rgb).transform
        return listOf(ColorSpace.Named.SRGB,ColorSpace.Named.DISPLAY_P3,ColorSpace.Named.BT2020).indices.minBy{index->
            val candidate=(ColorSpace.adapt(ColorSpace.get(listOf(ColorSpace.Named.SRGB,ColorSpace.Named.DISPLAY_P3,ColorSpace.Named.BT2020)[index]),d50) as ColorSpace.Rgb).transform
            matrix.indices.sumOf{kotlin.math.abs(matrix[it]-candidate[it]).toDouble()}
        }
    }
    @JvmStatic fun hdrBitmap(width:Int,height:Int,gamut:Int):Bitmap {
        val space=when(gamut){
            1->ColorSpace.Rgb("Linear Display P3",floatArrayOf(.68f,.32f,.265f,.69f,.15f,.06f),floatArrayOf(.3127f,.3290f),1.0)
            2->ColorSpace.Rgb("Linear BT2020",floatArrayOf(.708f,.292f,.17f,.797f,.131f,.046f),floatArrayOf(.3127f,.3290f),1.0)
            else->ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)
        }
        return Bitmap.createBitmap(width,height,Bitmap.Config.RGBA_F16,true,space)
    }
}
