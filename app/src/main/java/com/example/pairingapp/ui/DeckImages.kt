package com.example.pairingapp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/** Deck pictures live in the app's private storage, one cropped JPEG per player per tournament. */
object DeckImages {
    /** Size of one result-image row in px; the crop frame uses the same aspect ratio so a stored picture fills the row. */
    const val BANNER_W = 1000f
    const val BANNER_H = 100f
    private const val OUT_SCALE = 1 // stored bitmap is exactly the size of a result-image row
    private const val MAX_SIDE = 1600

    private fun dir(ctx: Context) = File(ctx.filesDir, "deck_images")

    fun file(ctx: Context, tournamentId: String, playerId: String) =
        File(dir(ctx), "${tournamentId}_$playerId.jpg".replace(Regex("[^A-Za-z0-9._-]"), "_"))

    fun load(ctx: Context, tournamentId: String, playerId: String, maxW: Int = Int.MAX_VALUE): Bitmap? =
        decodeFile(file(ctx, tournamentId, playerId), maxW)

    /** Decodes [f], at most about [maxW] px wide (power-of-two downsampling). Null when missing or unreadable. */
    private fun decodeFile(f: File, maxW: Int = Int.MAX_VALUE): Bitmap? {
        if (!f.exists()) return null
        return runCatching { // also catches OutOfMemoryError
            var sample = 1
            if (maxW != Int.MAX_VALUE) {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, b)
                while (b.outWidth / (sample * 2) >= maxW) sample *= 2
            }
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    fun has(ctx: Context, tournamentId: String, playerId: String) = file(ctx, tournamentId, playerId).exists()

    fun remove(ctx: Context, tournamentId: String, playerId: String) {
        file(ctx, tournamentId, playerId).delete()
    }

    fun removeAll(ctx: Context, tournamentId: String) {
        dir(ctx).listFiles { f -> f.name.startsWith("${tournamentId}_") }?.forEach { it.delete() }
    }

    /** Writes to a temp file and renames it into place, so a failed save never damages an existing picture. */
    private fun writeJpeg(f: File, bmp: Bitmap): Boolean {
        val d = f.parentFile!!
        val tmp = File(d, f.name + ".tmp")
        return runCatching {
            check(d.exists() || d.mkdirs()) { "cannot create $d" }
            FileOutputStream(tmp).use { check(bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            check(tmp.renameTo(f)) { "cannot replace $f" }
        }.onFailure { Log.w("DeckImages", "save failed", it) }.also { tmp.delete() }.isSuccess
    }

    /** Picture for one player in one tournament (used while they have no deck name). */
    fun save(ctx: Context, tournamentId: String, playerId: String, bmp: Bitmap): Boolean =
        writeJpeg(file(ctx, tournamentId, playerId), bmp)

    // ---- deck library: one picture per deck name, shared by every player and tournament ----

    private fun libFile(ctx: Context, deck: String): File {
        val k = deck.trim().lowercase()
        val safe = k.replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40)
        return File(File(ctx.filesDir, "deck_library"), "${safe}_${Integer.toHexString(k.hashCode())}.jpg")
    }


    /** The file a player's picture comes from: the deck's library picture, else their own from this tournament. */
    fun resolve(ctx: Context, tournamentId: String, playerId: String, deck: String): File? {
        if (deck.isNotBlank()) libFile(ctx, deck).takeIf { it.exists() }?.let { return it }
        return file(ctx, tournamentId, playerId).takeIf { it.exists() }
    }

    fun loadFor(ctx: Context, tournamentId: String, playerId: String, deck: String, maxW: Int = Int.MAX_VALUE): Bitmap? =
        resolve(ctx, tournamentId, playerId, deck)?.let { decodeFile(it, maxW) }

    /** Saves under the deck name when there is one (so it is reused), else for this player only. */
    fun saveFor(ctx: Context, tournamentId: String, playerId: String, deck: String, bmp: Bitmap): Boolean =
        if (deck.isBlank()) save(ctx, tournamentId, playerId, bmp)
        else writeJpeg(libFile(ctx, deck), bmp).also { if (it) remove(ctx, tournamentId, playerId) }

    /** Removes the deck's library picture (for every player using it) and this player's own one. True if a library picture was deleted. */
    fun removeFor(ctx: Context, tournamentId: String, playerId: String, deck: String): Boolean {
        val lib = deck.isNotBlank() && libFile(ctx, deck).delete()
        remove(ctx, tournamentId, playerId)
        return lib
    }

    /** A picture added before the deck name was typed moves into the library once the name is known. */
    fun promote(ctx: Context, tournamentId: String, playerId: String, deck: String) {
        if (deck.isBlank()) return
        val own = file(ctx, tournamentId, playerId)
        if (!own.exists()) return
        val lib = libFile(ctx, deck)
        if (lib.exists()) { own.delete(); return } // the library picture wins anyway
        runCatching {
            lib.parentFile!!.mkdirs()
            if (!own.renameTo(lib)) { own.copyTo(lib); own.delete() }
        }
    }

    /** Decodes a picked image, downsampled to [MAX_SIDE] and rotated upright. Null if it cannot be read. */
    fun decode(ctx: Context, uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val degrees = runCatching { // a failed orientation read must not discard a good decode
            ctx.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (degrees == 0f) raw
        else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees) }, true).also { raw.recycle() }
    }.onFailure { Log.w("DeckImages", "decode failed", it) }.getOrNull()

    val outW get() = (BANNER_W * OUT_SCALE).toInt()
    val outH get() = (BANNER_H * OUT_SCALE).toInt()
}

/** Pan with one finger, zoom with two; the highlighted frame is what gets kept. */
class CropView(ctx: Context, private val bmp: Bitmap) : View(ctx) {
    private val aspect = DeckImages.BANNER_W / DeckImages.BANNER_H
    private val frame = RectF()
    private val m = Matrix()
    private var scale = 1f
    private var minScale = 1f
    private var tx = 0f
    private var ty = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint().apply { color = 0xB3000000.toInt() }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat(); color = 0xFFFFC857.toInt()
    }
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val next = (scale * d.scaleFactor).coerceIn(minScale, minScale * 6f)
            val k = next / scale
            tx = d.focusX - (d.focusX - tx) * k
            ty = d.focusY - (d.focusY - ty) * k
            scale = next
            clamp(); invalidate()
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val margin = context.dp(8).toFloat()
        val fw = w - 2 * margin
        val fh = fw / aspect
        frame.set(margin, (h - fh) / 2, margin + fw, (h + fh) / 2)
        minScale = maxOf(fw / bmp.width, fh / bmp.height)
        scale = minScale
        tx = frame.centerX() - bmp.width * scale / 2
        ty = frame.centerY() - bmp.height * scale / 2
        clamp()
    }

    /** Keeps the picture covering the whole frame. */
    private fun clamp() {
        // Rounding can leave the picture a hair smaller than the frame; then pin it instead of coercing into an empty range.
        val minTx = frame.right - bmp.width * scale
        val minTy = frame.bottom - bmp.height * scale
        tx = if (minTx >= frame.left) frame.left else tx.coerceIn(minTx, frame.left)
        ty = if (minTy >= frame.top) frame.top else ty.coerceIn(minTy, frame.top)
    }

    override fun onDraw(c: Canvas) {
        m.reset(); m.postScale(scale, scale); m.postTranslate(tx, ty)
        c.drawBitmap(bmp, m, paint)
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, frame.top, dim)
        c.drawRect(0f, frame.bottom, w, h, dim)
        c.drawRect(0f, frame.top, frame.left, frame.bottom, dim)
        c.drawRect(frame.right, frame.top, w, frame.bottom, dim)
        c.drawRect(frame, stroke)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; dragging = true }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false
            MotionEvent.ACTION_POINTER_UP -> {
                val i = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(i); lastY = e.getY(i); dragging = e.pointerCount > 1
            }
            MotionEvent.ACTION_MOVE -> if (dragging && e.pointerCount == 1 && !scaler.isInProgress) {
                tx += e.x - lastX; ty += e.y - lastY
                lastX = e.x; lastY = e.y
                clamp(); invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    /** The part of the picture inside the frame, scaled to the stored thumbnail size. */
    fun cropped(): Bitmap {
        val src = RectF(
            (frame.left - tx) / scale, (frame.top - ty) / scale,
            (frame.right - tx) / scale, (frame.bottom - ty) / scale
        )
        val out = Bitmap.createBitmap(DeckImages.outW, DeckImages.outH, Bitmap.Config.ARGB_8888)
        val srcRect = android.graphics.Rect(
            kotlin.math.floor(src.left).toInt().coerceIn(0, bmp.width - 1), kotlin.math.floor(src.top).toInt().coerceIn(0, bmp.height - 1),
            kotlin.math.ceil(src.right).toInt().coerceIn(1, bmp.width), kotlin.math.ceil(src.bottom).toInt().coerceIn(1, bmp.height)
        )
        Canvas(out).drawBitmap(bmp, srcRect, RectF(0f, 0f, out.width.toFloat(), out.height.toFloat()), paint)
        return out
    }
}
