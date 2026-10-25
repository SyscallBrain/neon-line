package pt.tiago.neonline.ui.art

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import androidx.compose.ui.graphics.asImageBitmap
import pt.tiago.neonline.core.model.Piece
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player

/**
 * Pre-renders every piece sprite once: theme x kind x state x cell size, cached as an
 * [androidx.compose.ui.graphics.ImageBitmap]. Halos are drawn with [BlurMaskFilter] on a software
 * canvas, so this works from API 26 without RenderEffect.
 */
object PieceSprites {

    private data class Key(val skin: BoardSkin, val palette: ColorPalette, val owner: Player?, val kind: PieceKind, val diameter: Int, val symbols: Boolean)

    private val cache = HashMap<Key, androidx.compose.ui.graphics.ImageBitmap>()

    fun skinPalette(skin: BoardSkin, colorPalette: String = "normal"): SkinPalette = BoardStyles.palette(skin, colorPalette)

    fun colorFor(skin: BoardSkin, owner: Player?, colorPalette: String = "normal"): Int = when (owner) {
        Player.P1 -> skinPalette(skin, colorPalette).p1
        Player.P2 -> skinPalette(skin, colorPalette).p2
        null -> 0xFFFFFFFF.toInt()
    }

    /** The sprite for a piece; [diameterPx] is the cell size minus padding. */
    fun sprite(
        owner: Player?,
        kind: PieceKind,
        diameterPx: Float,
        skin: BoardSkin,
        symbols: Boolean,
        lit: Boolean = false,
        colorPalette: String = "normal",
    ): androidx.compose.ui.graphics.ImageBitmap {
        val renderedOwner = owner.takeUnless { kind == PieceKind.WILD }
        val palette = ColorPalette.resolve(colorPalette)
        val key = Key(skin, palette, renderedOwner, kind, diameterPx.toInt(), symbols)
        cache[key]?.let { return it }
        val size = diameterPx.toInt().coerceAtLeast(4)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawPiece(canvas, size, renderedOwner, kind, skin, symbols, palette.key)
        val image = bitmap.asImageBitmap()
        cache[key] = image
        return image
    }

    private fun drawPiece(
        canvas: Canvas,
        size: Int,
        owner: Player?,
        kind: PieceKind,
        skin: BoardSkin,
        symbols: Boolean,
        colorPalette: String,
    ) {
        val radius = size / 2f
        val center = radius
        val base = BoardStyles.pieceColor(skin, owner, kind, colorPalette)

        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = base
            alpha = 90
            maskFilter = BlurMaskFilter(radius * 0.35f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(center, center, radius * 0.86f, halo)

        if (skin != BoardSkin.NEON_ROAD) {
            val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(center - radius * .25f, center - radius * .3f, radius * 1.2f,
                    intArrayOf(Color.WHITE, base, base), floatArrayOf(0f, .18f, 1f), android.graphics.Shader.TileMode.CLAMP)
                alpha = if (skin == BoardSkin.AURORA) 145 else 255
            }
            canvas.drawCircle(center, center, radius * .72f, disc)
            val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = base; style = Paint.Style.STROKE; strokeWidth = radius * .06f }
            canvas.drawCircle(center, center, radius * .72f, rim)
            val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 155; style = Paint.Style.STROKE; strokeWidth = radius * .06f }
            canvas.drawArc(center - radius * .56f, center - radius * .56f, center + radius * .56f, center + radius * .56f, 205f, 95f, false, shine)
            if (skin == BoardSkin.CIRCUIT) {
                val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 180 }
                canvas.drawCircle(center, center, radius * .38f, core)
            }
        } else {
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    center, center, radius,
                    intArrayOf(Color.TRANSPARENT, base, base, Color.TRANSPARENT),
                    floatArrayOf(0f, 0.46f, 0.62f, 0.66f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
                style = Paint.Style.STROKE
                strokeWidth = radius * 0.22f
            }
            canvas.drawCircle(center, center, radius * 0.72f, ring)

            val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = base
                alpha = 120
                maskFilter = BlurMaskFilter(radius * 0.2f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawCircle(center, center, radius * 0.55f, inner)

        }
        if (kind == PieceKind.WILD) {
            val neutralRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB1BDCC.toInt(); style = Paint.Style.STROKE; strokeWidth = radius * .04f }
            canvas.drawCircle(center, center, radius * .5f, neutralRing)
        }
        if (symbols || kind != PieceKind.NORMAL) {
            val symbol = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (skin == BoardSkin.NEON_ROAD) 0xFF2A0614.toInt() else 0xFF160C20.toInt()
                style = Paint.Style.FILL
            }
            when (kind) {
                PieceKind.WILD -> drawStar(canvas, center, center, radius * 0.34f, symbol)
                PieceKind.LOCK -> drawLock(canvas, center, center, radius * 0.3f, symbol)
                PieceKind.BOMB -> drawBomb(canvas, center, center, radius * 0.3f, symbol)
                PieceKind.NORMAL -> when (owner) {
                    Player.P1 -> canvas.drawCircle(center, center, radius * 0.16f, symbol)
                    Player.P2 -> {
                        val path = Path().apply {
                            moveTo(center, center - radius * 0.22f)
                            lineTo(center + radius * 0.22f, center)
                            lineTo(center, center + radius * 0.22f)
                            lineTo(center - radius * 0.22f, center)
                            close()
                        }
                        canvas.drawPath(path, symbol)
                    }
                    null -> Unit
                }
            }
        }
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
        val path = Path()
        for (i in 0 until 8) {
            val angle = Math.PI / 4 * i - Math.PI / 2
            val radius = if (i % 2 == 0) r else r * 0.42f
            val x = cx + (radius * kotlin.math.cos(angle)).toFloat()
            val y = cy + (radius * kotlin.math.sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawLock(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
        canvas.drawRect(cx - r * 0.8f, cy - r * 0.1f, cx + r * 0.8f, cy + r * 0.9f, paint)
        val shackle = Paint(paint).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.22f }
        canvas.drawArc(cx - r * 0.5f, cy - r * 1.1f, cx + r * 0.5f, cy + r * 0.3f, 180f, 180f, false, shackle)
    }

    private fun drawBomb(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
        canvas.drawCircle(cx, cy + r * 0.2f, r * 0.6f, paint)
        val fuse = Paint(paint).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.18f }
        canvas.drawLine(cx + r * 0.3f, cy - r * 0.3f, cx + r * 0.8f, cy - r * 0.8f, fuse)
    }

    /** Drops the cache, e.g. when the theme changes. */
    fun clear() = cache.clear()
}
