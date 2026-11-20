/*
 * Copyright 2026, IOSChair
 *
 * Licensed under the GNU General Public License v3.0 (same as Lawnchair).
 */

package app.lawnchair.ioschair.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Shader
import kotlin.random.Random

/**
 * Fine monochrome film grain for frosted glass. A tiling texture of white and black specks with
 * random low alpha, so it both lightens and darkens without needing blend modes (works on API 26+).
 */
object GlassNoise {

    private const val SIZE = 192

    private val bitmap: Bitmap by lazy {
        val rnd = Random(0x10C4A1)
        val pixels = IntArray(SIZE * SIZE) {
            val a = (rnd.nextFloat() * rnd.nextFloat() * 255).toInt()
            if (rnd.nextBoolean()) Color.argb(a, 255, 255, 255) else Color.argb(a, 0, 0, 0)
        }
        Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }

    /** Returns a new repeating shader; texels are ~[texelPx] screen pixels wide. */
    @JvmStatic
    fun shader(texelPx: Float): BitmapShader =
        BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setScale(texelPx, texelPx) })
        }
}
