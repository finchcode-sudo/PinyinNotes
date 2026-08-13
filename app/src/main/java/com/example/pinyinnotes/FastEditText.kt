package com.example.pinyinnotes

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.widget.OverScroller
import androidx.appcompat.widget.AppCompatEditText
import kotlin.math.abs

/**
 * 编辑模式专用 EditText：
 * 1. fling 惯性滚动速度放大，快速划一下能滚很远（与阅读模式 ScrollView 手感一致）
 * 2. 提供按比例快速滚动接口，供右侧快速滚动条 / 跳转按钮使用
 */
class FastEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /** 惯性滚动速度放大倍数：越大，划一下滚得越远。2.5 约为阅读模式 ScrollView 的力度 */
    var flingMultiplier: Float = 2.5f

    /** 手指跟手放大倍数：>1 时手指滑动距离被放大（可选，默认 1 保持原生跟手） */
    var scrollMultiplier: Float = 1.0f

    private val customScroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private var lastTouchY = 0f
    private var customFlingActive = false

    /** 总可滚动范围（文本高度 - 可视高度） */
    private fun scrollRange(): Int {
        val layout = layout ?: return 0
        return maxOf(0, layout.height - (height - paddingTop - paddingBottom))
    }

    /** 当前滚动比例 0..1 */
    fun getScrollFraction(): Float {
        val range = scrollRange()
        return if (range <= 0) 0f else (scrollY.toFloat() / range).coerceIn(0f, 1f)
    }

    /** 按比例平滑滚动（0..1），供快速滚动条使用 */
    fun smoothScrollToFraction(fraction: Float) {
        val range = scrollRange()
        if (range <= 0) return
        smoothScrollTo(0, (range * fraction.coerceIn(0f, 1f)).toInt())
    }

    /** 直接跳到底部 */
    fun jumpToBottom() {
        val range = scrollRange()
        if (range > 0) scrollTo(0, range)
    }

    /** 跳到顶部 */
    fun jumpToTop() {
        scrollTo(0, 0)
    }

    override fun computeScroll() {
        // 自定义增强 fling 优先
        if (customScroller.computeScrollOffset()) {
            scrollTo(customScroller.currX, customScroller.currY)
            postInvalidateOnAnimation()
        } else {
            if (customFlingActive) {
                customFlingActive = false
                customScroller.forceFinished(true)
            }
            super.computeScroll()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 用户再次触摸，立即停止自定义 fling
                customScroller.forceFinished(true)
                customFlingActive = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                lastTouchY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dy = event.y - lastTouchY
                lastTouchY = event.y
                // 可选：放大跟手距离（默认 1.0 不启用，避免干扰光标/选择）
                if (scrollMultiplier > 1f && canScrollVertically(1)) {
                    scrollBy(0, (dy * (scrollMultiplier - 1f)).toInt())
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val vy = velocityTracker?.yVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null

                // 先交给系统收尾（光标定位/点击/系统 fling）
                val handled = super.onTouchEvent(event)

                // 再用放大后的速度接管 fling，实现"划一下滚很远"
                val range = scrollRange()
                if (range > 0 && abs(vy) > 300f && !customFlingActive) {
                    customScroller.fling(
                        0, scrollY,
                        0, (vy * flingMultiplier).toInt(),
                        0, 0,
                        0, range,
                        0, 0
                    )
                    customFlingActive = true
                    postInvalidateOnAnimation()
                }
                return handled
            }
        }
        return super.onTouchEvent(event)
    }
}
