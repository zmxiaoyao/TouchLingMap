package com.touchling.mapper

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * v0.7.0 背屏互动玩具（纯本地绘制 + 触摸交互，不注入主屏）
 * 1 幸运转盘 / 2 真心话大冒险 / 3 电子木鱼（敲盅+功德）/ 4 骰子
 */
object Toys {
    val names = listOf("关闭", "幸运转盘", "真心话大冒险", "电子木鱼", "骰子")

    fun build(ctx: Context, id: Int): View? = when (id) {
        1 -> WheelView(ctx)
        2 -> TruthDareView(ctx)
        3 -> MeritView(ctx)
        4 -> DiceView(ctx)
        else -> null
    }
}

internal fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

/** 简单音效（无需权限） */
internal object Fx {
    fun tone(type: Int) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 75)
            tg.startTone(type, 120)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    tg.release()
                } catch (_: Throwable) {
                }
            }, 400)
        } catch (_: Throwable) {
        }
    }
}

// ---------------------------------------------------------------- 幸运转盘

class WheelView(private val ctx: Context) : View(ctx) {

    private val items = listOf("吃火锅", "吃烧烤", "吃日料", "吃快餐", "吃面", "随便吃")
    private val colors = listOf(
        0xFFEF5350, 0xFFAB47BC, 0xFF5C6BC0, 0xFF26A69A, 0xFFFFA726, 0xFF66BB6A
    ).map { it.toInt() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var angle = 0f
    private var spinning = false
    private var result: String? = null

    init {
        setBackgroundColor(0xFF111318.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(w, h) * 0.34f
        val cx = w / 2f
        val cy = h / 2f
        val n = items.size
        val sweep = 360f / n
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)

        // 扇区
        canvas.save()
        canvas.rotate(angle, cx, cy)
        p.style = Paint.Style.FILL
        for (i in 0 until n) {
            p.color = colors[i % colors.size]
            canvas.drawArc(rect, -90f + i * sweep, sweep, true, p)
        }
        canvas.restore()

        // 外圈
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(ctx, 4f)
        p.color = 0xFFFFC107.toInt()
        canvas.drawCircle(cx, cy, r, p)

        // 指针
        val ptr = Path().apply {
            moveTo(cx, cy - r - dp(ctx, 2f))
            lineTo(cx - dp(ctx, 16f), cy - r - dp(ctx, 26f))
            lineTo(cx + dp(ctx, 16f), cy - r - dp(ctx, 26f))
            close()
        }
        p.style = Paint.Style.FILL
        p.color = 0xFFFFEB3B.toInt()
        canvas.drawPath(ptr, p)

        // 文字
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = dp(ctx, 14f)
        tp.color = Color.WHITE
        for (i in 0 until n) {
            val mid = Math.toRadians((-90f + i * sweep + sweep / 2f + angle).toDouble())
            val tx = cx + (r * 0.62f * cos(mid)).toFloat()
            val ty = cy + (r * 0.62f * sin(mid)).toFloat() + dp(ctx, 5f)
            canvas.drawText(items[i], tx, ty, tp)
        }

        // 中心
        p.style = Paint.Style.FILL
        p.color = 0xFF111318.toInt()
        canvas.drawCircle(cx, cy, r * 0.16f, p)
        tp.textSize = dp(ctx, 13f)
        canvas.drawText("转", cx, cy + dp(ctx, 5f), tp)

        // 顶部提示
        tp.textSize = dp(ctx, 14f)
        tp.color = 0x99FFFFFF.toInt()
        canvas.drawText(if (spinning) "转盘旋转中…" else "轻触转盘，让命运来决定", cx, dp(ctx, 34f), tp)

        // 结果
        result?.let {
            tp.textSize = dp(ctx, 24f)
            tp.color = 0xFFFFEB3B.toInt()
            canvas.drawText("👉 $it", cx, cy + r + dp(ctx, 56f), tp)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && !spinning) {
            if (result == null) spin() else {
                result = null
                spin()
            }
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun spin() {
        spinning = true
        result = null
        val n = items.size
        val sweep = 360f / n
        val idx = Random.nextInt(n)
        val start = angle
        // 目标：让第 idx 个扇区中心停在正上方
        val need = (-(idx * sweep + sweep / 2f) - start) % 360f
        val end = start + 360f * 5 + ((need % 360f) + 360f) % 360f
        val va = ValueAnimator.ofFloat(start, end)
        va.duration = 2600
        va.interpolator = DecelerateInterpolator()
        va.addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
        va.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                spinning = false
                result = items[idx]
                invalidate()
                Fx.tone(ToneGenerator.TONE_PROP_ACK)
                Diag.log("转盘结果=${items[idx]}")
            }
        })
        va.start()
        Fx.tone(ToneGenerator.TONE_PROP_BEEP)
    }
}

// ------------------------------------------------------- 真心话大冒险

class TruthDareView(private val ctx: Context) : View(ctx) {

    private val truths = listOf(
        "说一件最近让你心动的事",
        "手机相册第 3 张照片是什么？给大家看看",
        "你最近一次哭是因为什么",
        "说出在场每个人一个优点",
        "你做过最社死的事是什么",
        "你手机里最舍不得删的一张照片",
        "你最近偷偷搜索过什么",
        "说出你的一个怪癖",
        "你最想删掉的黑历史是什么",
        "你对谁有过好感但一直没说"
    )
    private val dares = listOf(
        "模仿一个动物叫 3 声",
        "给通讯录第 5 个人发一句「在吗」",
        "用奇怪姿势自拍一张",
        "唱一首歌的副歌",
        "对空气深情表白 10 秒",
        "原地转 5 圈再走直线",
        "模仿一位朋友的口头禅",
        "用方言说一句土味情话",
        "做个鬼脸坚持 5 秒",
        "给最近联系的 3 个人各发一个表情"
    )

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var type = -1
    private var current: String? = null
    private var count = 0

    init {
        setBackgroundColor(0xFF111318.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cardW = w * 0.86f
        val cardH = h * 0.60f
        val rect = RectF((w - cardW) / 2f, (h - cardH) / 2f, (w + cardW) / 2f, (h + cardH) / 2f)
        val rr = dp(ctx, 24f)

        // 卡片
        p.style = Paint.Style.FILL
        p.color = when (type) {
            0 -> 0xFF1E88E5.toInt()
            1 -> 0xFFE53935.toInt()
            else -> 0xFF37474F.toInt()
        }
        canvas.drawRoundRect(rect, rr, rr, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(ctx, 3f)
        p.color = 0x66FFFFFF
        canvas.drawRoundRect(rect, rr, rr, p)

        tp.textAlign = Paint.Align.CENTER
        tp.color = Color.WHITE
        tp.textSize = dp(ctx, 26f)
        val title = when (type) {
            0 -> "真心话"
            1 -> "大冒险"
            else -> "真心话 OR 大冒险"
        }
        canvas.drawText(title, w / 2f, rect.top + dp(ctx, 54f), tp)

        // 正文（自动换行）
        tp.textSize = dp(ctx, 19f)
        val text = current ?: "轻触卡片，抽一张"
        val sl = StaticLayout.Builder
            .obtain(text, 0, text.length, tp, (cardW - dp(ctx, 48f)).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        canvas.save()
        canvas.translate(rect.left + dp(ctx, 24f), rect.top + dp(ctx, 96f))
        sl.draw(canvas)
        canvas.restore()

        // 底部提示
        tp.textSize = dp(ctx, 14f)
        tp.color = 0x99FFFFFF.toInt()
        canvas.drawText(
            if (count == 0) "轻触卡片抽签" else "已抽 $count 次 · 再点一次继续",
            w / 2f, h - dp(ctx, 40f), tp
        )
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            type = Random.nextInt(2)
            current = if (type == 0) truths.random() else dares.random()
            count++
            invalidate()
            Fx.tone(if (type == 0) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_ACK)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

// ------------------------------------------------- 电子木鱼（敲盅 + 功德）

class MeritView(private val ctx: Context) : View(ctx) {

    private val blessings = listOf(
        "功德 +1 · 心静自然凉",
        "功德 +1 · 事事顺遂",
        "功德 +1 · 平安喜乐",
        "功德 +1 · 烦恼退散",
        "功德 +1 · 好运连连",
        "功德 +1 · 一夜好眠"
    )

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var merit = 0
    private var blessing = blessings[0]
    private var floatY = 0f
    private var floatAlpha = 0
    private val ripples = mutableListOf<FloatArray>() // cx, cy, r

    init {
        setBackgroundColor(0xFF14100C.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * 0.56f
        val r = min(w, h) * 0.26f

        // 木鱼本体
        p.style = Paint.Style.FILL
        p.color = 0xFF6D4C41.toInt()
        canvas.drawCircle(cx, cy, r, p)
        p.color = 0xFF8D6E63.toInt()
        canvas.drawCircle(cx, cy - r * 0.12f, r * 0.86f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(ctx, 3f)
        p.color = 0xFF3E2723.toInt()
        canvas.drawCircle(cx, cy, r, p)
        // 敲击口
        p.style = Paint.Style.FILL
        p.color = 0xFF2E1A12.toInt()
        canvas.drawCircle(cx, cy + r * 0.34f, r * 0.18f, p)
        // 木鱼"眼"
        p.color = 0xFF3E2723.toInt()
        canvas.drawCircle(cx - r * 0.42f, cy - r * 0.24f, r * 0.11f, p)
        canvas.drawCircle(cx + r * 0.42f, cy - r * 0.24f, r * 0.11f, p)

        // 涟漪
        ripples.forEach {
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(ctx, 2f)
            val a = (255 * (1f - it[2] / dp(ctx, 150f))).toInt().coerceIn(0, 255)
            p.color = (a shl 24) or 0xFFD54F
            canvas.drawCircle(it[0], it[1], it[2], p)
        }

        // 计数
        tp.textAlign = Paint.Align.CENTER
        tp.color = 0xFFFFD54F.toInt()
        tp.textSize = dp(ctx, 46f)
        canvas.drawText("功德 $merit", cx, dp(ctx, 70f), tp)

        // 飘字
        if (floatAlpha > 0) {
            tp.textSize = dp(ctx, 22f)
            tp.color = (floatAlpha shl 24) or 0xFFEB3B
            canvas.drawText("+1", cx, cy - r - dp(ctx, 8f) + floatY, tp)
        }

        // 祝福语
        tp.textSize = dp(ctx, 15f)
        tp.color = 0xCCFFFFFF.toInt()
        canvas.drawText(blessing, cx, h - dp(ctx, 44f), tp)
        tp.textSize = dp(ctx, 13f)
        tp.color = 0x66FFFFFF.toInt()
        canvas.drawText("轻触木鱼，积攒功德", cx, h - dp(ctx, 20f), tp)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                merit++
                ripples.add(floatArrayOf(e.x, e.y, dp(ctx, 6f)))
                floatY = 0f
                floatAlpha = 255
                blessing = blessings.random()
                Fx.tone(ToneGenerator.TONE_PROP_BEEP)
                invalidate()
                tick()
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun tick() {
        var alive = false
        val it = ripples.iterator()
        while (it.hasNext()) {
            val r = it.next()
            r[2] += dp(ctx, 4f)
            if (r[2] > dp(ctx, 150f)) it.remove() else alive = true
        }
        floatY -= dp(ctx, 1.6f)
        floatAlpha = (floatAlpha - 8).coerceAtLeast(0)
        if (alive || floatAlpha > 0) postInvalidateDelayed(30)
    }
}

// ---------------------------------------------------------------- 骰子

class DiceView(private val ctx: Context) : View(ctx) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var d1 = 1
    private var d2 = 1
    private var rolling = false

    init {
        setBackgroundColor(0xFF111318.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val size = min(w, h) * 0.30f
        val gap = size * 0.22f
        val cy = h * 0.48f
        val cx1 = w / 2f - size / 2f - gap / 2f
        val cx2 = w / 2f + size / 2f + gap / 2f

        drawDie(canvas, cx1, cy, size, d1)
        drawDie(canvas, cx2, cy, size, d2)

        tp.textAlign = Paint.Align.CENTER
        tp.color = 0xFFFFD54F.toInt()
        tp.textSize = dp(ctx, 34f)
        canvas.drawText("合计 ${d1 + d2}", w / 2f, h * 0.78f, tp)

        tp.textSize = dp(ctx, 15f)
        tp.color = 0x99FFFFFF.toInt()
        canvas.drawText(if (rolling) "摇骰子中…" else "轻触点任意处掷骰子", w / 2f, dp(ctx, 40f), tp)
    }

    private fun drawDie(canvas: Canvas, cx: Float, cy: Float, size: Float, value: Int) {
        val left = cx - size / 2f
        val top = cy - size / 2f
        val rect = RectF(left, top, left + size, top + size)
        p.style = Paint.Style.FILL
        p.color = 0xFFF5F5F5.toInt()
        canvas.drawRoundRect(rect, size * 0.16f, size * 0.16f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(ctx, 2f)
        p.color = 0xFF9E9E9E.toInt()
        canvas.drawRoundRect(rect, size * 0.16f, size * 0.16f, p)

        val r = size * 0.075f
        val a = size * 0.26f
        p.style = Paint.Style.FILL
        p.color = 0xFF212121.toInt()
        fun dot(dx: Float, dy: Float) = canvas.drawCircle(cx + dx * a, cy + dy * a, r, p)
        when (value) {
            1 -> dot(0f, 0f)
            2 -> {
                dot(-1f, -1f); dot(1f, 1f)
            }
            3 -> {
                dot(-1f, -1f); dot(0f, 0f); dot(1f, 1f)
            }
            4 -> {
                dot(-1f, -1f); dot(1f, -1f); dot(-1f, 1f); dot(1f, 1f)
            }
            5 -> {
                dot(-1f, -1f); dot(1f, -1f); dot(0f, 0f); dot(-1f, 1f); dot(1f, 1f)
            }
            else -> {
                dot(-1f, -1f); dot(1f, -1f); dot(-1f, 0f); dot(1f, 0f); dot(-1f, 1f); dot(1f, 1f)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && !rolling) {
            rolling = true
            Fx.tone(ToneGenerator.TONE_PROP_BEEP)
            val handler = Handler(Looper.getMainLooper())
            var ticks = 0
            val task = object : Runnable {
                override fun run() {
                    d1 = Random.nextInt(1, 7)
                    d2 = Random.nextInt(1, 7)
                    invalidate()
                    ticks++
                    if (ticks < 12) {
                        handler.postDelayed(this, 60)
                    } else {
                        rolling = false
                        invalidate()
                        Fx.tone(ToneGenerator.TONE_PROP_ACK)
                        Diag.log("骰子结果=$d1+$d2")
                    }
                }
            }
            handler.post(task)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}