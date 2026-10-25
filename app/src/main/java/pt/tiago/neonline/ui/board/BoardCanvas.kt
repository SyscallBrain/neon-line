package pt.tiago.neonline.ui.board

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import pt.tiago.neonline.game.BoardAccessibility
import pt.tiago.neonline.game.BoardActions
import pt.tiago.neonline.game.PeekVisibility
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.rules.GameEngine
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import pt.tiago.neonline.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import pt.tiago.neonline.core.model.Piece
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.game.AnimationTimeline
import pt.tiago.neonline.game.GameUiState
import pt.tiago.neonline.game.RivalThreatCue
import pt.tiago.neonline.ui.art.BoardStyles
import androidx.compose.ui.graphics.Brush
import pt.tiago.neonline.ui.art.BoardSkin
import pt.tiago.neonline.ui.art.PieceSprites
import pt.tiago.neonline.ui.theme.Amber
import pt.tiago.neonline.ui.theme.Holes
import pt.tiago.neonline.ui.theme.Rail
import pt.tiago.neonline.ui.theme.Shoulder
import pt.tiago.neonline.ui.theme.TubeBlue
import pt.tiago.neonline.ui.theme.Win
import pt.tiago.neonline.ui.theme.Danger
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The board: one Compose [Canvas] that blits the cached piece sprites. Never one composable per
 * piece. Animation timing is driven by the Compose frame clock.
 *
 * Input: tap anywhere in a column drops the piece; dragging shows the preview and releasing
 * confirms. The board exposes per-column TalkBack actions; previews have a localized summary.
 */
@Composable
fun BoardCanvas(
    ui: GameUiState,
    skin: BoardSkin,
    symbolsOnPieces: Boolean,
    showPreview: Boolean = true,
    inputEnabled: Boolean = true,
    turnDescription: String? = null,
    previewColumn: Int?,
    onColumnTap: (Int) -> Unit,
    onColumnDrag: (Int?) -> Unit,
    onBottomPieceTap: (Int) -> Unit = {},
    onAnimationFinished: (Long) -> Unit = {},
    onAnimationPhase: (AnimationTimeline.Phase) -> Unit = {},
    modifier: Modifier = Modifier,
    colorPalette: String = "normal",
    reviewPlayedColumn: Int? = null,
    reviewSuggestedColumn: Int? = null,
    onPeek: (Int) -> Unit = {},
    rivalThreats: List<RivalThreatCue> = emptyList(),
) {
    val latestPeek by rememberUpdatedState(onPeek)
    val latestTap by rememberUpdatedState(onColumnTap)
    val latestBottomTap by rememberUpdatedState(onBottomPieceTap)
    val latestUi by rememberUpdatedState(ui)
    val latestDrag by rememberUpdatedState(onColumnDrag)
    val latestFinished by rememberUpdatedState(onAnimationFinished)
    val latestPhase by rememberUpdatedState(onAnimationPhase)
    val latestInput by rememberUpdatedState(ui.acceptsInput)
    val latestPreview by rememberUpdatedState(previewColumn)
    val state = ui.state
    val animation = ui.animation
    var elapsedMs by remember(animation?.id) { mutableLongStateOf(0L) }
    LaunchedEffect(animation?.id, ui.animating, ui.paused) {
        if (animation == null || !ui.animating || ui.paused) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (elapsedMs < animation.timeline.totalMs) {
            val now = withFrameNanos { it }
            elapsedMs = (elapsedMs + (now - previous) / 1_000_000L).coerceAtMost(animation.timeline.totalMs)
            previous = now
        }
        latestFinished(animation.id)
    }
    val frame = remember(state, animation, elapsedMs, ui.animating) {
        if (animation == null) AnimationTimeline.stillFrame(state, ui.events)
        else AnimationTimeline.frameAt(animation.timeline, if (ui.animating) elapsedMs else animation.timeline.totalMs)
    }
    var playedPhase by remember(animation?.id) { mutableIntStateOf(-1) }
    LaunchedEffect(animation?.id, frame.phaseIndex, ui.paused) {
        if (animation == null || ui.paused) return@LaunchedEffect
        val through = if (frame.finished) animation.timeline.phases.lastIndex else frame.phaseIndex
        for (index in playedPhase + 1..through) latestPhase(animation.timeline.phases[index])
        playedPhase = maxOf(playedPhase, through)
    }
    val tutorial = ui.tutorialStep?.takeIf { ui.acceptsInput }
    val pulse = if (ui.motionEnabled && (tutorial != null || frame.litCells.isNotEmpty())) {
        val value by rememberInfiniteTransition(label = "neon").animateFloat(
            initialValue = 0.35f, targetValue = 0.65f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow",
        )
        value
    } else 0.5f
    val rendered = frame.board
    val description = stringResource(
        R.string.board_description, state.width, state.height, state.pieces.count { it != null },
        turnDescription ?: stringResource(if (ui.finished) R.string.turn_over else if (state.toMove == Player.P1) R.string.turn_you else R.string.board_rival_turn),
    )
    Box(modifier.semantics { isTraversalGroup = true }) {
    Canvas(
        modifier = Modifier.fillMaxSize()
            .then(if (inputEnabled) Modifier.clearAndSetSemantics { } else Modifier.semantics { contentDescription = description })
            .pointerInput(rendered.width, rendered.height, state.toMove, inputEnabled, state.fadeAfter) {
                if (!inputEnabled) return@pointerInput
                detectTapGestures(onLongPress = if (state.fadeAfter == null) null else { offset ->
                    latestDrag(null)
                    columnAt(offset.x, size.width.toFloat(), size.height.toFloat(), rendered.width, rendered.height)?.let { latestPeek(it) }
                }, onTap = { offset ->
                    val col = columnAt(offset.x, size.width.toFloat(), size.height.toFloat(), rendered.width, rendered.height)
                    if (col != null && latestInput) {
                        val current = latestUi
                        val cell = minOf(size.width.toFloat() / (rendered.width + 0.6f), size.height.toFloat() / (rendered.height + 0.6f))
                        val originY = (size.height - cell * rendered.height) / 2f
                        val row = floor((offset.y - originY) / cell).toInt()
                        val bottom = current.state.pieces.indices.lastOrNull { it % current.state.width == col && current.state.pieces[it] != null }
                        if (current.selectedPiece == PieceKind.NORMAL && bottom != null && row == bottom / current.state.width &&
                            BoardAccessibility.canPop(current, col)) {
                            latestBottomTap(col)
                        } else latestTap(col)
                    }
                })
            }
            .pointerInput(rendered.width, rendered.height, inputEnabled) {
                if (!inputEnabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        val col = columnAt(offset.x, size.width.toFloat(), size.height.toFloat(), rendered.width, rendered.height)
                        if (latestInput) latestDrag(col)
                    },
                    onDragEnd = { if (latestInput) latestPreview?.let { latestTap(it) }; latestDrag(null) },
                    onDragCancel = { latestDrag(null) },
                    onDrag = { change, _ ->
                        val col = columnAt(change.position.x, size.width.toFloat(), size.height.toFloat(), rendered.width, rendered.height)
                        if (latestInput) latestDrag(col)
                    },
                )
            },
    ) {
        val width = rendered.width
        val height = rendered.height
        val fitWidth = if (ui.animating) maxOf(width, animation?.timeline?.before?.width ?: width, animation?.timeline?.after?.width ?: width) else width
        val fitHeight = if (ui.animating) maxOf(height, animation?.timeline?.before?.height ?: height, animation?.timeline?.after?.height ?: height) else height
        val cell = minOf(size.width / (fitWidth + 0.6f), size.height / (fitHeight + 0.6f))
        val boardWidth = cell * width
        val boardHeight = cell * height
        val originX = (size.width - boardWidth) / 2f
        val originY = (size.height - boardHeight) / 2f
        rotate(frame.rotationDegrees, center) {
            drawBoardFrame(originX, originY, boardWidth, boardHeight, cell, skin)
            rendered.playable.forEachIndexed { index, playable ->
                if (!playable) drawBlockedCell(originX + index % width * cell, originY + index / width * cell, cell)
            }
            for (target in rendered.targets) {
                if (target !in rendered.pieces.indices) continue
                drawCircle(TubeBlue, cell * 0.36f, Offset(originX + (target % width + 0.5f) * cell, originY + (target / width + 0.5f) * cell), style = Stroke(cell * 0.04f))
            }
            ui.events.filterIsInstance<pt.tiago.neonline.core.model.GameEvent.LineFormed>().forEach { event ->
                if (event.line.cells.all { it in frame.litCells }) {
                    val first = event.line.cells.first()
                    val last = event.line.cells.last()
                    drawLine(Win.copy(alpha = 0.6f),
                        Offset(originX + (first % width + 0.5f) * cell, originY + (first / width + 0.5f) * cell),
                        Offset(originX + (last % width + 0.5f) * cell, originY + (last / width + 0.5f) * cell), cell * 0.045f)
                }
            }
            frame.pieces.forEach { pose ->
                val alpha = PeekVisibility.alpha(ui, pose.piece, pose.col.toInt(), pose.alpha)
                if (!ui.animating && alpha == 0f && PeekVisibility.isHidden(state, pose.piece)) {
                    drawCircle(androidx.compose.ui.graphics.Color.White.copy(alpha = .12f), cell * .38f,
                        Offset(originX + (pose.col + .5f) * cell, originY + (pose.row + .5f) * cell))
                }
                drawPieceSprite(pose.piece, originX + pose.col * cell, originY + pose.row * cell, cell, skin, symbolsOnPieces, alpha, colorPalette)
                if (pose.lit) drawCircle(Win.copy(alpha = pulse), cell * 0.43f,
                    Offset(originX + (pose.col + 0.5f) * cell, originY + (pose.row + 0.5f) * cell), style = Stroke(cell * 0.04f))
            }
            if (inputEnabled && ui.acceptsInput) for (index in rivalThreats.map { it.cell }.toSet()) {
                if (index !in rendered.pieces.indices) continue
                val center = Offset(originX + (index % width + .5f) * cell, originY + (index / width + .5f) * cell)
                drawCircle(Danger.copy(alpha = .5f), cell * .46f, center, style = Stroke(cell * .025f))
                val badge = center + Offset(cell * .3f, -cell * .3f)
                drawCircle(Shoulder, cell * .11f, badge)
                drawLine(Danger, badge + Offset(0f, -cell * .065f), badge + Offset(0f, cell * .015f), cell * .025f)
                drawCircle(Danger, cell * .014f, badge + Offset(0f, cell * .06f))
            }
            frame.explosionCell?.let { index ->
                drawCircle(Amber.copy(alpha = 1f - frame.effectProgress), cell * (0.2f + 1.5f * frame.effectProgress),
                    Offset(originX + (index % width + 0.5f) * cell, originY + (index / width + 0.5f) * cell), style = Stroke(cell * 0.08f))
            }
            ui.pendingPopColumn?.takeIf { ui.acceptsInput }?.let { col ->
                val index = state.pieces.indices.lastOrNull { it % width == col && state.pieces[it] != null }
                if (index != null) drawCircle(TubeBlue, cell * 0.45f,
                    Offset(originX + (col + 0.5f) * cell, originY + (index / width + 0.5f) * cell), style = Stroke(cell * 0.06f))
            }
            // Review outlines are decorative; the dialog labels both actions in text.
            reviewPlayedColumn?.takeIf { it in 0 until width }?.let { col ->
                drawRect(Color(0xffff5e6c), Offset(originX + col * cell + 2.dp.toPx(), originY + 2.dp.toPx()),
                    Size(cell - 4.dp.toPx(), boardHeight - 4.dp.toPx()), style = Stroke(2.dp.toPx()))
            }
            reviewSuggestedColumn?.takeIf { it in 0 until width }?.let { col ->
                val inset = if (col == reviewPlayedColumn) 6.dp.toPx() else 2.dp.toPx()
                drawRect(Win, Offset(originX + col * cell + inset, originY + inset),
                    Size((cell - inset * 2).coerceAtLeast(0f), (boardHeight - inset * 2).coerceAtLeast(0f)), style = Stroke(2.dp.toPx()))
            }
            frame.lockedColumn?.let { col ->
                drawRect(TubeBlue.copy(alpha = 0.15f), Offset(originX + col * cell, originY), Size(cell, boardHeight))
            }
            if (tutorial != null) {
                val highlighted = (tutorial.highlightCols + listOfNotNull(tutorial.forcedCol)).toSet()
                for (col in 0 until width) {
                    if (highlighted.isNotEmpty() && col !in highlighted) {
                        drawRect(Color.Black.copy(alpha = 0.55f), Offset(originX + col * cell, originY), Size(cell, boardHeight))
                    } else if (col in highlighted) {
                        drawRect(TubeBlue.copy(alpha = pulse * 0.25f), Offset(originX + col * cell, originY), Size(cell, boardHeight))
                        val x = originX + (col + 0.5f) * cell
                        val y = originY + cell * (0.1f + pulse * 0.1f)
                        val arrow = Path().apply { moveTo(x - cell * 0.15f, y); lineTo(x + cell * 0.15f, y); lineTo(x, y + cell * 0.2f); close() }
                        drawPath(arrow, TubeBlue)
                    }
                }
            }
            val preview = if (ui.acceptsInput && ui.pendingPopColumn == null) ui.pendingDrop?.col ?: ui.hintColumn ?: previewColumn.takeIf { showPreview } else null
            if (preview != null && preview in 0 until width) {
                BoardActions.landingRow(state, preview, ui.selectedPiece)?.let { row ->
                    drawPieceSprite(Piece(state.toMove, ui.selectedPiece, state.ply), originX + preview * cell, originY + row * cell,
                        cell, skin, symbolsOnPieces, if (preview == ui.hintColumn) 0.6f else 0.28f, colorPalette)
                }
            }
        }
    }
    if (inputEnabled) AccessibleColumns(ui, onColumnTap, onBottomPieceTap, onPeek, rivalThreats)
    }
}

private fun DrawScope.drawBoardFrame(x: Float, y: Float, w: Float, h: Float, cell: Float, skin: BoardSkin) {
    val style = BoardStyles.style(skin)
    val corner = CornerRadius(cell * style.corner)
    drawRoundRect(brush = Brush.verticalGradient(listOf(Color(style.top), Color(style.bottom)), y, y + h),
        topLeft = Offset(x, y), size = Size(w, h), cornerRadius = corner)
    if (skin == BoardSkin.CIRCUIT) {
        val copper = Color(style.edge).copy(alpha = .3f)
        for (col in 0 until (w / cell).roundToInt()) {
            val trackX = x + (col + .5f) * cell
            drawLine(copper, Offset(trackX, y + cell * .1f), Offset(trackX, y + h - cell * .1f), cell * .025f)
        }
        for (row in 0 until (h / cell).roundToInt()) {
            val trackY = y + (row + .5f) * cell
            drawLine(copper, Offset(x + cell * .1f, trackY), Offset(x + w - cell * .1f, trackY), cell * .025f)
        }
    }
    drawRoundRect(Color(style.edge), topLeft = Offset(x, y), size = Size(w, h), cornerRadius = corner,
        style = Stroke(cell * if (skin == BoardSkin.CIRCUIT || skin == BoardSkin.AURORA) .025f else .06f))
    for (row in 0 until (h / cell).roundToInt()) for (col in 0 until (w / cell).roundToInt()) {
        val center = Offset(x + (col + .5f) * cell, y + (row + .5f) * cell)
        if (style.squareHoles) {
            val topLeft = center - Offset(cell * .36f, cell * .36f)
            val size = Size(cell * .72f, cell * .72f)
            val radius = CornerRadius(cell * .72f * .28f)
            drawRoundRect(Color(style.hole), topLeft, size, radius)
            drawRoundRect(Color(style.edge).copy(alpha = .35f), topLeft, size, radius, style = Stroke(cell * .015f))
        } else drawCircle(Color(style.hole), cell * .36f, center)
    }
}

private fun DrawScope.drawBlockedCell(x: Float, y: Float, cell: Float) {
    drawRect(color = Rail, topLeft = Offset(x, y), size = Size(cell, cell))
    drawRect(color = Shoulder, topLeft = Offset(x, y), size = Size(cell * 0.86f, cell * 0.86f))
    val bolt = cell * 0.07f
    listOf(
        Offset(x + cell * 0.12f, y + cell * 0.12f),
        Offset(x + cell * 0.88f, y + cell * 0.12f),
        Offset(x + cell * 0.12f, y + cell * 0.88f),
        Offset(x + cell * 0.88f, y + cell * 0.88f),
    ).forEach { center -> drawCircle(color = Rail, radius = bolt, center = center) }
}

private fun DrawScope.drawPieceSprite(
    piece: Piece,
    left: Float,
    top: Float,
    cell: Float,
    skin: BoardSkin,
    symbols: Boolean,
    alpha: Float,
    colorPalette: String,
) {
    val sprite = PieceSprites.sprite(piece.owner, piece.kind, cell * 0.78f, skin, symbols, colorPalette = colorPalette)
    drawImage(
        image = sprite,
        dstOffset = androidx.compose.ui.unit.IntOffset((left + cell * 0.11f).roundToInt(), (top + cell * 0.11f).roundToInt()),
        dstSize = androidx.compose.ui.unit.IntSize((cell * 0.78f).roundToInt(), (cell * 0.78f).roundToInt()),
        alpha = alpha,
    )
}

private fun columnAt(x: Float, canvasWidth: Float, canvasHeight: Float, columns: Int, rows: Int): Int? {
    if (columns <= 0 || rows <= 0) return null
    val cell = minOf(canvasWidth / (columns + 0.6f), canvasHeight / (rows + 0.6f))
    val originX = (canvasWidth - cell * columns) / 2f
    val col = floor((x - originX) / cell).toInt()
    return col.takeIf { it in 0 until columns }
}
