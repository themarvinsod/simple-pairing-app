package com.example.pairingapp.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.pairingapp.engine.Standing
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Draws a standings table as a PNG (no views involved) and saves it to the Pictures folder. */
object ResultImage {
    private const val W = 1080
    private const val PAD = 40f
    private const val ROW_H = 100f // = DeckImages.BANNER_H so a stored picture matches the row
    private const val GAP = 12f
    private const val DECK_MAX_W = 380f // widest the deck name on the right may get

    private const val BG = 0xFF0E1128.toInt()
    private const val CARD = 0xFF1C2040.toInt()
    private const val TEXT = 0xFFEDEFFF.toInt()
    private const val MUTED = 0xFF9DA1C4.toInt()
    private const val GOLD = 0xFFFFC857.toInt()
    private const val VIOLET = 0xFF8B6CFF.toInt()
    private val MEDAL = intArrayOf(0xFFFFC857.toInt(), 0xFFC4CAD8.toInt(), 0xFFD8925B.toInt())

    private fun paint(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            textAlign = align
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    /** Shortens [s] with "..." so it fits in [maxWidth] pixels. */
    private fun fit(s: String, p: Paint, maxWidth: Float): String {
        if (p.measureText(s) <= maxWidth) return s
        val n = p.breakText(s, true, maxWidth - p.measureText("..."), null)
        return s.take(n).trimEnd() + "..."
    }

    /** The part of [pic] that fills [dst] without stretching (centre crop). */
    private fun coverRect(pic: Bitmap, dst: RectF): android.graphics.Rect {
        val want = dst.width() / dst.height()
        val have = pic.width.toFloat() / pic.height
        return if (have > want) {
            val w = (pic.height * want).toInt()
            android.graphics.Rect((pic.width - w) / 2, 0, (pic.width + w) / 2, pic.height)
        } else {
            val h = (pic.width / want).toInt()
            android.graphics.Rect(0, (pic.height - h) / 2, pic.width, (pic.height + h) / 2)
        }
    }

    /** [rows] is one page; [totalPlayers] and [pageLabel] describe the whole result it belongs to. */
    fun render(
        tournamentName: String, stageLabel: String?, rows: List<Standing>, totalPlayers: Int, pageLabel: String?,
        deckImages: Map<String, Bitmap> = emptyMap() // player id -> cropped deck picture
    ): Bitmap {
        val headH = 250f
        val tableTop = headH + 20f
        val colHeadH = 10f
        val height = (tableTop + colHeadH + rows.size * (ROW_H + GAP) + 70f).toInt()
        val bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)

        // Title banner
        val banner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), headH, 0xFF4B2FC4.toInt(), VIOLET, Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(RectF(0f, -60f, W.toFloat(), headH), 60f, 60f, banner)
        c.drawText(fit(tournamentName, paint(64f, 0xFFFFFFFF.toInt(), true), W - 2 * PAD), PAD, 120f, paint(64f, 0xFFFFFFFF.toInt(), true))
        val sub = listOfNotNull(stageLabel, "$totalPlayers players", pageLabel).joinToString("  -  ")
        c.drawText(fit(sub, paint(34f, 0xFFE6DEFF.toInt()), W - 2 * PAD), PAD, 185f, paint(34f, 0xFFE6DEFF.toInt()))
        val nameX = PAD + 80f
        val nameW = W - PAD - 40f - nameX

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x808B6CFF.toInt()
        }
        val goldStroke = Paint(strokePaint).apply { color = GOLD; strokeWidth = 4f }
        val picPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val namePaint = paint(40f, TEXT, true)
        val deckPaint = paint(32f, MUTED, false, Paint.Align.RIGHT)
        val badgeText = paint(28f, 0xFF000000.toInt(), true, Paint.Align.CENTER)
        val badgeTextLight = paint(28f, TEXT, true, Paint.Align.CENTER)

        val shadow = 0xFF000000.toInt()
        fun onPicture(p: Paint, color: Int = p.color) = Paint(p).apply { setShadowLayer(6f, 0f, 2f, shadow); this.color = color }
        val nameOn = onPicture(namePaint, 0xFFFFFFFF.toInt())
        val deckOn = onPicture(deckPaint, 0xFFE6E8F7.toInt())
        val badgeOn = onPicture(badgeTextLight)
        val scrim = Paint().apply { color = 0x66000000 }

        var y = tableTop + colHeadH
        for (s in rows) {
            val rect = RectF(PAD, y, W - PAD, y + ROW_H)
            // A deck picture fills the whole row; the text is drawn over it on a light dark veil.
            val pic = deckImages[s.player.id]
            if (pic != null) {
                c.save()
                c.clipPath(Path().apply { addRoundRect(rect, 26f, 26f, Path.Direction.CW) })
                c.drawBitmap(pic, coverRect(pic, rect), rect, picPaint)
                c.drawRect(rect, scrim)
                c.restore()
            } else c.drawRoundRect(rect, 26f, 26f, cardPaint)
            c.drawRoundRect(rect, 26f, 26f, if (s.note == "Champion") goldStroke else strokePaint)

            val cy = y + ROW_H / 2
            val top3 = s.rank <= 3
            val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (top3) MEDAL[s.rank - 1] else if (pic != null) 0xCC2A2358.toInt() else 0x338B6CFF }
            c.drawCircle(PAD + 40f, cy, 26f, badge)
            c.drawText(s.rank.toString(), PAD + 40f, cy + 10f, if (top3) badgeText else if (pic != null) badgeOn else badgeTextLight)

            // Only the player name (left), deck name (right) and, as the rank badge, the final standing are shown.
            val np = if (pic != null) nameOn else namePaint
            val dp = if (pic != null) deckOn else deckPaint
            val deck = s.deck.takeIf { it.isNotBlank() }?.let { fit(it, dp, DECK_MAX_W) }
            val deckW = deck?.let { dp.measureText(it) + 30f } ?: 0f
            c.drawText(fit(s.player.name, np, nameW - deckW), nameX, cy + 14f, np)
            if (deck != null) c.drawText(deck, nameX + nameW, cy + 11f, dp)
            y += ROW_H + GAP
        }

        val foot = paint(24f, MUTED, false, Paint.Align.CENTER)
        val stamp = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date())
        c.drawText("Pairing App  -  $stamp", W / 2f, y + 40f, foot)
        return bmp
    }

    /** Saves to Pictures/PairingApp. Needs WRITE_EXTERNAL_STORAGE only on API 28 and below. */
    fun save(ctx: Context, bmp: Bitmap, displayName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PairingApp")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            try {
                val out = resolver.openOutputStream(uri) ?: error("no output stream")
                out.use { check(bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                check(resolver.update(uri, done, null, null) == 1) { "could not publish image" }
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) } // never leave a hidden half-written row
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PairingApp")
            check(dir.exists() || dir.mkdirs()) { "cannot create $dir" }
            val file = File(dir, "$displayName.png")
            try {
                FileOutputStream(file).use { check(bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } catch (e: Throwable) {
                runCatching { file.delete() }
                throw e
            }
            MediaScannerConnection.scanFile(ctx, arrayOf(file.path), arrayOf("image/png"), null)
        }
    }.onFailure { Log.w("ResultImage", "save failed for $displayName", it) }.isSuccess
}
