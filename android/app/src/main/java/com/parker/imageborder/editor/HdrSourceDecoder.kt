package com.parker.imageborder.editor

import com.parker.imageborder.R

import android.content.Context
import android.graphics.*
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.*

/** Decode both the SDR raster and its gainmap through the same Android codec. */
object HdrSourceDecoder {
    private fun matrix(from:ColorSpace.Rgb,to:ColorSpace.Rgb):FloatArray {
        val white=floatArrayOf(.964212f,1f,.825188f)
        val a=(ColorSpace.adapt(from,white) as ColorSpace.Rgb).transform
        val b=(ColorSpace.adapt(to,white) as ColorSpace.Rgb).inverseTransform
        return FloatArray(9){i->val row=i%3;val column=i/3;(0..2).sumOf{k->(b[row+k*3]*a[k+column*3]).toDouble()}.toFloat()}
    }
    @RequiresApi(34)
    fun decode(context:Context,asset:PhotoAsset,maxEdge:Int):Bitmap {
        val file=Assets.cacheFile(context,asset.uri)
        var sample=1;while(maxEdge>0&&max(asset.width,asset.height)/sample>maxEdge*2)sample*=2
        val decoded=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})?:error(appText(R.string.ui_cannot_read_hdr_photo_66345c))
        var base:Bitmap?=null;var output:Bitmap?=null;var gainBitmap:Bitmap?=null
        try{
            val gain=decoded.gainmap?:error(appText(R.string.ui_this_photo_has_no_supported_hdr_gain_map_ed095c))
            if(Build.VERSION.SDK_INT>=36)require(gain.gainmapDirection==Gainmap.GAINMAP_DIRECTION_SDR_TO_HDR){appText(R.string.ui_editing_this_hdr_base_format_is_not_supported_0906eb)}
            val encodedSpace=decoded.colorSpace as? ColorSpace.Rgb?:error(appText(R.string.ui_cannot_read_hdr_base_color_space_9c0c4e))
            val mathSpace=(if(Build.VERSION.SDK_INT>=36)gain.alternativeImagePrimaries as? ColorSpace.Rgb else null)?:encodedSpace
            val target=ColorSpace.get(ColorSpace.Named.DISPLAY_P3) as ColorSpace.Rgb
            val metadata=gain.ratioMin+gain.ratioMax+gain.gamma+gain.epsilonSdr+gain.epsilonHdr
            require(metadata.all{it.isFinite()}&&gain.ratioMin.all{it>0f}&&gain.ratioMax.all{it>0f&&it<=1024f}&&gain.gamma.all{it>0f}){appText(R.string.ui_invalid_hdr_gain_map_parameters_e9f861)}
            val map=gain.gainmapContents
            gainBitmap=map
            decoded.gainmap=null
            val ratio=if(maxEdge>0)min(1f,maxEdge.toFloat()/max(decoded.width,decoded.height))else 1f
            base=if(ratio<1f)Bitmap.createScaledBitmap(decoded,max(1,(decoded.width*ratio).roundToInt()),max(1,(decoded.height*ratio).roundToInt()),true)else decoded
            output=NativeCodec.hdrBitmap(base.width,base.height,1)
            val transfer=FloatArray(256){encodedSpace.eotf.applyAsDouble(it/255.0).toFloat()}
            NativeCodec.reconstructHdr(base,map,output,metadata,transfer,matrix(encodedSpace,mathSpace)+matrix(mathSpace,target))
            val result=output;output=null;return result
        }finally{if(base!==decoded)base?.recycle();gainBitmap?.takeIf{it!==decoded&&it!==base}?.recycle();decoded.recycle();output?.recycle()}
    }
}
