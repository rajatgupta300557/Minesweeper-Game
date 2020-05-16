package me.stuhlmeier.minesweeper.ui.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.GestureDetectorCompat
import me.stuhlmeier.minesweeper.Point
import me.stuhlmeier.minesweeper.R
import me.stuhlmeier.minesweeper.eightNeighbors
import me.stuhlmeier.minesweeper.game.Board
import me.stuhlmeier.minesweeper.game.GameSettings
import me.stuhlmeier.minesweeper.game.moves.ChordMove
import me.stuhlmeier.minesweeper.game.moves.FloodRevealMove
import me.stuhlmeier.minesweeper.game.moves.ToggleFlagMove
import me.stuhlmeier.minesweeper.roundUp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class BoardView(
    context: Context,
    attrs: AttributeSet?,
    board: Board?,
    var settings: GameSettings?
) : View(context, attrs) {
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, null, null)
    constructor(context: Context, board: Board, settings: GameSettings) : this(
        context,
        null,
        board,
        settings
    )

    constructor(context: Context) : this(context, null)

    @FunctionalInterface
    interface OnMoveListener {
        fun onMove(board: Board, state: Board.State)
    }

    private var _board = board

    var board
        get() = _board
        set(value) {
            _board = value
            recalculate()
            invalidate()
        }

    private val dp by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            1.0f,
            resources.displayMetrics
        )
    }
    private val sp by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            1.0f,
            resources.displayMetrics
        )
    }
    private val iconSize = 0.7f

    private var boardWidth = 0f
    private var boardHeight = 0f

    private var viewportX = 0f
    private var viewportY = 0f
    private val viewportMaxX get() = max(0f, boardWidth - width)
    private val viewportMaxY get() = max(0f, boardHeight - height)

    private var doubleDividerSize = 0f
    private var dividerSize = 0f
    private var halfDividerSize = 0f
    private var totalCellSize = 0f
    private var cellSize = 0f

    // TODO: This deprecation should be ignored (higher API level)
    @Suppress("DEPRECATION")
    private val flag = resources.getDrawable(R.drawable.ic_flag)

    @Suppress("DEPRECATION")
    private val mine = resources.getDrawable(R.drawable.ic_mine)

    private var flagBitmap: Bitmap
    private var mineBitmap: Bitmap

    val basePaint = Paint().apply {
        style = Paint.Style.FILL
        flags = Paint.ANTI_ALIAS_FLAG
    }

    val textPaint = Paint().apply {
        color = Color.BLACK
        typeface = Typeface.MONOSPACE
    }

    val cellPaint = Paint(basePaint).apply { color = Color.WHITE }
    val uncoveredPaint = Paint(basePaint).apply { color = Color.WHITE }
    val coveredPaint = Paint(basePaint).apply { color = Color.parseColor("#dfe4ea") }
    val minePaint = Paint(basePaint).apply { color = Color.parseColor("#ffc7cc") }
    val flagPaint = Paint(basePaint).apply { color = Color.parseColor("#f8a5c2") }

    val dividerPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.LTGRAY
    }

    private val detector =
        GestureDetectorCompat(context, GestureListener()).apply { setIsLongpressEnabled(true) }
    private val scroller = OverScroller(context)

    var moveListener: OnMoveListener? = null

    private fun recalculate() {
        dividerSize = 3 * dp
        doubleDividerSize = 2 * dividerSize
        halfDividerSize = dividerSize / 2

        dividerPaint.strokeWidth = dividerSize
        textPaint.textSize = 22 * sp

        cellSize = 50 * dp
        totalCellSize = doubleDividerSize + cellSize

        viewportX = 0f
        viewportY = 0f

        val board = board

        if (board == null) {
            Log.i("Minesweeper", "No board")
            boardWidth = 0f
            boardHeight = 0f
        } else {
            boardWidth =
                if (board.columns == 0) 0f
                else (board.columns * cellSize + (board.columns + 1) * dividerSize)

            boardHeight =
                if (board.rows == 0) 0f
                else (board.rows * cellSize + (board.rows + 1) * dividerSize)
        }
    }

    init {
        recalculate()

        val size = (cellSize * iconSize).toInt()
        flagBitmap = flag.toBitmap(size, size, null)
        mineBitmap = mine.toBitmap(size, size, null)
    }

    private fun scrollTo(x: Float, y: Float) {
        viewportX = max(0f, min(viewportMaxX, x))
        viewportY = max(0f, min(viewportMaxY, y))
    }

    private inner class GestureListener
        : GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener {
        override fun onShowPress(e: MotionEvent) = Unit
        override fun onDoubleTapEvent(e: MotionEvent) = true
        override fun onDown(e: MotionEvent) = true

        private fun locate(e: MotionEvent): Point? {
            return board?.let { board ->
                val totalX = viewportX + e.x
                val totalY = viewportY + e.y

                val effectiveX =
                    max(0f, min(totalX - halfDividerSize, boardWidth - halfDividerSize))
                val effectiveY =
                    max(0f, min(totalY - halfDividerSize, boardHeight - halfDividerSize))

                val column = (effectiveX / (dividerSize + cellSize)).toInt()
                val row = (effectiveY / (dividerSize + cellSize)).toInt()

                if (row !in 0 until board.rows || column !in 0 until board.columns) null
                else Point(row, column)
            }
        }

        private fun moveReveal(board: Board, row: Int, column: Int) {
            if (!board.started && settings?.safe == true) board.ensureSafe(row, column)
            if (board.isFlagged(row, column)) return

            if (board.isRevealed(row, column)) {
                // Allow chord if all flags have been assigned to neighbors
                if (board.eightNeighbors(row, column)
                        .count { board.isFlagged(it.first, it.second) } ==
                    board.getAdjacentMines(row, column)
                ) board.push(ChordMove(row, column))
                else return
            } else board.push(FloodRevealMove(row, column))
        }

        private fun moveFlag(board: Board, row: Int, column: Int) {
            if (board.isRevealed(row, column)) return
            board.push(ToggleFlagMove(row, column))
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (settings?.chordEnabled == true) {
                board?.let { board ->
                    if (board.state != Board.State.Neutral) return true

                    val (row, column) = locate(e) ?: return true

                    if (settings?.invertControls == true) moveFlag(board, row, column)
                    else moveReveal(board, row, column)

                    invalidate()
                    moveListener?.onMove(board, board.state)
                }
            }
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (settings?.chordEnabled != true) {
                board?.let { board ->
                    if (board.state != Board.State.Neutral) return true

                    val (row, column) = locate(e) ?: return true

                    if (settings?.invertControls == true) moveFlag(board, row, column)
                    else moveReveal(board, row, column)

                    invalidate()
                    moveListener?.onMove(board, board.state)
                }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            board?.let { board ->
                if (board.state != Board.State.Neutral) return

                val (row, column) = locate(e) ?: return

                if (settings?.invertControls == true) moveReveal(board, row, column)
                else moveFlag(board, row, column)

                invalidate()
                moveListener?.onMove(board, board.state)
            }
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (settings?.chordEnabled == true) {
                board?.let { board ->
                    if (board.state != Board.State.Neutral) return true

                    val (row, column) = locate(e) ?: return true

                    board.push(ChordMove(row, column))

                    invalidate()
                    moveListener?.onMove(board, board.state)
                }
            }
            return true
        }

        override fun onScroll(
            e1: MotionEvent,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            scrollTo(viewportX + distanceX, viewportY + distanceY)
            invalidate()
            return true
        }

        override fun onFling(
            e1: MotionEvent,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            scroller.forceFinished(true)
            scroller.fling(
                viewportX.toInt(), viewportY.toInt(),
                -velocityX.toInt(), -velocityY.toInt(),
                0, viewportMaxX.roundUp(), 0, viewportMaxY.roundUp()
            )

            post(FlingRunnable(scroller))

            return true
        }
    }

    private inner class FlingRunnable(private val scroller: OverScroller) : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                scrollTo(scroller.currX.toFloat(), scroller.currY.toFloat())
                invalidate()
                post(this)
            }
        }
    }

    fun undo(): Boolean {
        board?.let { board ->
            if (!board.pop()) return false
            invalidate()
            moveListener?.onMove(board, Board.State.Neutral)
        } ?: return false
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        return true
    }

    private val textBounds = Rect()

    override fun onDraw(canvas: Canvas) {
        board?.let { board ->
            with(canvas) {
                val startRow =
                    max(0f, floor((viewportY - dividerSize) / (dividerSize + cellSize))).toInt()
                val endRow =
                    min(
                        board.rows.toFloat(),
                        ceil((viewportY + height - dividerSize) / (dividerSize + cellSize)) + 1
                    ).toInt()

                val startColumn =
                    max(0f, floor((viewportX - dividerSize) / (dividerSize + cellSize))).toInt()
                val endColumn =
                    min(
                        board.columns.toFloat(),
                        ceil((viewportX + width - dividerSize) / (dividerSize + cellSize)) + 1
                    ).toInt()


                for (row in startRow until endRow) {
                    for (column in startColumn until endColumn) {
                        val cell = board[row, column]

                        val offsetX = column * (dividerSize + cellSize) - viewportX
                        val offsetY = row * (dividerSize + cellSize) - viewportY

                        run {
                            val rectX = offsetX + halfDividerSize
                            val rectY = offsetY + halfDividerSize
                            val rectW = cellSize + dividerSize
                            val rectH = cellSize + dividerSize

                            drawRect(rectX, rectY, rectX + rectW, rectY + rectH, dividerPaint)
                        }

                        run {
                            val rectX = offsetX + dividerSize
                            val rectY = offsetY + dividerSize
                            val rectW = cellSize
                            val rectH = cellSize

                            val paint: Paint
                            var bitmap: Bitmap? = null
                            var text: String? = null
                            when {
                                cell.isRevealed -> {
                                    if (cell.isMine) {
                                        paint = minePaint
                                        bitmap = mineBitmap
                                    } else {
                                        paint = uncoveredPaint
                                        text =
                                            cell.adjacentMines.let { if (it == 0) null else it.toString() }
                                    }
                                }
                                cell.isFlagged -> {
                                    paint = flagPaint
                                    bitmap = flagBitmap
                                }
                                else -> paint = coveredPaint
                            }

                            drawRect(rectX, rectY, rectX + rectW, rectY + rectH, paint)

                            bitmap?.let {
                                drawBitmap(
                                    it,
                                    rectX + (rectW - bitmap.width) / 2,
                                    rectY + (rectH - bitmap.height) / 2,
                                    null
                                )
                            }

                            text?.let {
                                textPaint.getTextBounds(it, 0, it.length, textBounds)
                                val width = textPaint.measureText(it, 0, it.length)
                                drawText(
                                    it,
                                    rectX + (rectW - width) / 2,
                                    rectY + (rectH + textBounds.height()) / 2,
                                    textPaint
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val width = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> min(boardWidth.roundUp(), widthSize)
            else -> boardWidth.roundUp()
        }

        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> min(boardHeight.roundUp(), heightSize)
            else -> boardHeight.roundUp()
        }

        setMeasuredDimension(width, height)
    }
}
