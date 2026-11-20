package com.ppporia.nsdgames.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream

/**
 * 头像的"选图 → 居中裁正方形 → 至多 512px → WebP"。
 *
 * 用户只管选图，裁切和缩放都在这里做（**不做放大**：小图保持原尺寸）。
 * 用 `ImageDecoder` 而不是 `BitmapFactory`：它会按 EXIF 自己转正，竖拍的照片不会躺倒；
 * 解码时先按 2 的幂降采样，几十兆的原图也不会把内存吃满。
 *
 * 产物是一段字节（≤ 512px 的 WebP，一般 20–60KB），直接丢给
 * [com.poria.netnsd.room.RoomSession.uploadAvatar] 或随 Intent 带进房间。
 */
object Avatars {

    /** 头像最长边。压缩到至多 512px；512 的 WebP q80 一般 20–60KB。 */
    const val MAX_SIDE = 512

    private const val QUALITY = 80

    /**
     * 读一张图并编码成头像。
     *
     * @return 编码好的字节；读不出来（损坏、权限、不是图片）返回 `null`
     */
    fun encode(context: Context, uri: Uri): ByteArray? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // 先降到"裁完还剩 512~1024"的量级，再精确缩放，避免解一张 4000px 的原图
            val minSide = minOf(info.size.width, info.size.height)
            var sample = 1
            while (minSide / (sample * 2) >= MAX_SIDE) sample *= 2
            decoder.setTargetSampleSize(sample)
            // 硬件位图不能 compress()，必须软件分配
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

        val square = centerSquare(decoded)
        val side = minOf(MAX_SIDE, square.width)
        val scaled = if (square.width == side) square else square.scale(side, side)

        ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out)
            out.toByteArray()
        }
    }.getOrNull()

    /** 把编好的头像字节解成小图（预览、座位卡都用它），失败返回 `null`。 */
    fun decode(bytes: ByteArray): Bitmap? =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()

    /** 居中的最大正方形，不放大。 */
    private fun centerSquare(bitmap: Bitmap): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        if (bitmap.width == side && bitmap.height == side) return bitmap
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        return Bitmap.createBitmap(bitmap, left, top, side, side)
    }
}
