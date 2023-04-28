package com.biansemao.stackcardlayoutmanager

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.support.annotation.FloatRange
import android.support.annotation.IntRange
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import java.lang.reflect.Method


/**
 * 自定义RecyclerView.LayoutManager，卡片式层叠效果
 */
class StackCardLayoutManager : RecyclerView.LayoutManager {

    /* 每个item宽高 */
    private var mItemWidth: Int = 0
    private var mItemHeight: Int = 0

    private var mTotalOffset: Int = 0
    private var initialOffset: Int = 0

    private var animator: ObjectAnimator? = null
    private var animateValue: Int = 0
    private var lastAnimateValue: Int = 0
    private val duration = 300

    private var mRecyclerView: RecyclerView? = null
    private var mRecycler: RecyclerView.Recycler? = null

    private var initialFlag: Boolean = false
    private var stopAutoCycleFlag: Boolean = false

    private var mMinVelocity: Int = 0
    private val mVelocityTracker = VelocityTracker.obtain()
    private var pointerId: Int = 0

    private var sSetScrollState: Method? = null
    private var mPendingScrollPosition = RecyclerView.NO_POSITION

    private var mOnPositionChangeListener: OnPositionChangeListener? = null

    private val mTouchListener = View.OnTouchListener { v, event ->
        mVelocityTracker.addMovement(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                animator?.let {
                    if (it.isRunning) {
                        it.cancel()
                    }
                }
                pointerId = event.getPointerId(0)

                stopAutoCycle()
            }
            MotionEvent.ACTION_MOVE -> {
                animator?.let {
                    if (it.isRunning) {
                        it.cancel()
                    }
                }

                stopAutoCycle()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                if (v.isPressed) {
                    v.performClick()
                }
                mVelocityTracker.computeCurrentVelocity(1000, 14000f)
                when (stackConfig.direction) {
                    StackDirection.LEFT, StackDirection.RIGHT -> {
                        if (mItemHeight > 0) {
                            val o = mTotalOffset % mItemWidth
                            val scrollX: Int
                            if (Math.abs(mVelocityTracker.getXVelocity(pointerId)) < mMinVelocity && o != 0) {
                                scrollX = if (mTotalOffset >= 0) {
                                    if (o >= mItemWidth / 2) {
                                        mItemWidth - o
                                    } else {
                                        -o
                                    }
                                } else {
                                    if (o <= -mItemWidth / 2) {
                                        -mItemWidth - o
                                    } else {
                                        -o
                                    }
                                }
                                val dur = (Math.abs((scrollX + 0f) / mItemWidth) * duration).toInt()
                                brewAndStartAnimator(dur, scrollX)
                            }
                        }
                    }
                }

                startAutoCycle()
            }
        }
        false
    }

    private val mOnFlingListener = object : RecyclerView.OnFlingListener() {
        override fun onFling(velocityX: Int, velocityY: Int): Boolean {
            stopAutoCycle()
            stopAutoCycleFlag = true

            val vel = absMax(velocityX, velocityY)
            when (stackConfig.direction) {
                StackDirection.LEFT, StackDirection.RIGHT -> {
                    if (mItemWidth > 0) {
                        val o = mTotalOffset % mItemWidth
                        val scroll = if (mTotalOffset >= 0) {
                            val s = mItemWidth - o
                            if (vel * stackConfig.direction.layoutDirection > 0) {
                                s
                            } else {
                                -o
                            }
                        } else {
                            val s = -mItemWidth - o
                            if (vel * stackConfig.direction.layoutDirection < 0) {
                                s
                            } else {
                                -o
                            }
                        }
                        val dur = computeHorizontalSettleDuration(Math.abs(scroll), Math.abs(vel).toFloat())
                        brewAndStartAnimator(dur, scroll)
                    }
                }
            }
            setScrollStateIdle()

            stopAutoCycleFlag = false
            startAutoCycle()
            return true
        }
    }

    private val mAutoCycleRunnable = Runnable {
        if (!stopAutoCycleFlag) {
            when (stackConfig.direction) {
                StackDirection.LEFT, StackDirection.RIGHT -> {
                    val dur = computeHorizontalSettleDuration(Math.abs(mItemWidth), 0f)
                    brewAndStartAnimator(dur, mItemWidth)
                }
            }
            startAutoCycle()
        }
    }

    private var stackConfig: StackConfig = StackConfig()

    constructor(config: StackConfig) {
        stackConfig = config
    }

    /**
     * 必须为true，否则RecyclerView为warp_content时，无法显示画面
     */
    override fun isAutoMeasureEnabled(): Boolean {
        return true
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (itemCount <= 0) {
            return
        }
        this.mRecycler = recycler
        detachAndScrapAttachedViews(recycler)

        /* 获取第一个item，所有item具有相同尺寸 */
        val anchorView = recycler.getViewForPosition(0)
        if (stackConfig.isAdjustSize) {
            if (mItemWidth <= 0 || mItemHeight <= 0) {
                measureChildWithMargins(anchorView, 0, 0)
                mItemWidth = anchorView.measuredWidth
                mItemHeight = anchorView.measuredHeight
            }
        } else {
            measureChildWithMargins(anchorView, 0, 0)
            mItemWidth = anchorView.measuredWidth
            mItemHeight = anchorView.measuredHeight
        }


        initialOffset = resolveInitialOffset()
        mMinVelocity = ViewConfiguration.get(anchorView.context).scaledMinimumFlingVelocity
        fillItemView(recycler, 0)
    }

    private fun resolveInitialOffset(): Int {
        var position = stackConfig.stackPosition
        if (position >= itemCount) {
            position = itemCount - 1
        }
        var offset = when (stackConfig.direction) {
            StackDirection.LEFT, StackDirection.RIGHT -> {
                position * mItemWidth
            }
        }
        if (mPendingScrollPosition != RecyclerView.NO_POSITION) {
            offset = when (stackConfig.direction) {
                StackDirection.LEFT, StackDirection.RIGHT -> {
                    mPendingScrollPosition * mItemWidth
                }
            }
            mPendingScrollPosition = RecyclerView.NO_POSITION
        }

        return stackConfig.direction.layoutDirection * offset
    }

    override fun onLayoutCompleted(state: RecyclerView.State?) {
        super.onLayoutCompleted(state)
        if (itemCount <= 0) {
            return
        }
        if (!initialFlag) {
            mRecycler?.let {
                fillItemView(it, initialOffset, false)
            }
            initialFlag = true
            startAutoCycle()
        }
    }

    override fun onAdapterChanged(oldAdapter: RecyclerView.Adapter<*>?, newAdapter: RecyclerView.Adapter<*>?) {
        initialFlag = false
        mItemWidth = 0
        mItemHeight = 0
        mTotalOffset = 0
    }

    /**
     * 填充控件
     */
    private fun fillItemView(recycler: RecyclerView.Recycler, dy: Int): Int {
        return fillItemView(recycler, dy, true)
    }

    /**
     * 填充控件
     */
    private fun fillItemView(recycler: RecyclerView.Recycler, dy: Int, apply: Boolean): Int {
        var delta = stackConfig.direction.layoutDirection * dy
        if (apply) {
            delta = (delta * stackConfig.parallex).toInt()
        }
        return if (stackConfig.isCycle && itemCount > 1) {
            when (stackConfig.direction) {
                StackDirection.LEFT -> {
                    fillHorizontalCycleItemView(recycler, delta, true)
                }
                StackDirection.RIGHT -> {
                    fillHorizontalCycleItemView(recycler, delta, false)
                }
            }
        } else {
            when (stackConfig.direction) {
                StackDirection.LEFT -> {
                    fillHorizontalItemView(recycler, delta, true)
                }
                StackDirection.RIGHT -> {
                    fillHorizontalItemView(recycler, delta, false)
                }
            }
        }
    }

    /**
     * 填充水平方向的控件
     */
    private fun fillHorizontalItemView(recycler: RecyclerView.Recycler, dx: Int, isLeftFlag: Boolean): Int {
        if (mTotalOffset + dx < 0 || (mTotalOffset.toFloat() + dx.toFloat() + 0f) / mItemWidth > itemCount - 1) {
            return 0
        }
        detachAndScrapAttachedViews(recycler)
        mTotalOffset += dx

        for (i in 0 until childCount) {
            getChildAt(i)?.let {
                if (recycleHorizontally(it, dx)) {
                    removeAndRecycleView(it, recycler)
                }
            }
        }

        if (mItemWidth <= 0) {
            return dx
        }
        //根据滑动的位置来计算当前的选中的curPosition
        var curPosition = mTotalOffset / mItemWidth
        curPosition = when {
            curPosition > itemCount - 1 -> itemCount - 1
            curPosition < 0 -> 0
            else -> curPosition
        }

        val start = if (curPosition + stackConfig.stackCount < itemCount - 1) {
            curPosition + stackConfig.stackCount
        } else {
            itemCount - 1
        }
        val end = if (curPosition > 0) {
            curPosition - 1
        } else {
            0
        }

        val firstScale = getHorizontalFirstScale()
        val topOffset = getHorizontalItemOffset(firstScale)
        for (i in start downTo end) {
            fillHorizontalBaseItemView(recycler.getViewForPosition(i), firstScale, topOffset, curPosition, i, isLeftFlag)
        }

        return dx
    }

    /**
     * 填充水平方向的控件--循环
     */
    private fun fillHorizontalCycleItemView(recycler: RecyclerView.Recycler, dx: Int, isLeftFlag: Boolean): Int {
        detachAndScrapAttachedViews(recycler)
        mTotalOffset += dx
        for (i in 0 until childCount) {
            getChildAt(i)?.let {
                if (recycleHorizontally(it, dx)) {
                    removeAndRecycleView(it, recycler)
                }
            }
        }

        if (mItemWidth <= 0) {
            return dx
        }
        log("fillHorizontalCycleItemView mTotalOffset = ${mTotalOffset}   dx = ${dx} isLeftFlag = ${isLeftFlag}")
        //这里循环的逻辑
        when {
            mTotalOffset >= itemCount * mItemWidth -> {
                mTotalOffset -= itemCount * mItemWidth
            }
            mTotalOffset <= -itemCount * mItemWidth -> {
                mTotalOffset += itemCount * mItemWidth
            }
        }

        val curPosition = mTotalOffset / mItemWidth
        val start = curPosition - 1
        val end = curPosition + stackConfig.stackCount
        log("fillHorizontalCycleItemView curPosition = ${curPosition}   start = ${start} end = ${end}")
        val tempList = ArrayList<View>()
        for (i in start..end) {
            when {
                i < -itemCount -> {
                    tempList.add(recycler.getViewForPosition(2 * itemCount + i))
                }
                i < 0 -> {
                    tempList.add(recycler.getViewForPosition(itemCount + i))
                }
                i >= itemCount -> {
                    tempList.add(recycler.getViewForPosition(i - itemCount))
                }
                else -> {
                    tempList.add(recycler.getViewForPosition(i))
                }
            }
        }

        val firstScale = getHorizontalFirstScale()
        val topOffset = getHorizontalItemOffset(firstScale)
        log("fillHorizontalCycleItemView firstScale = ${firstScale}   topOffset = ${topOffset}")
        for (i in tempList.size - 1 downTo 0) {
            fillHorizontalBaseItemView(tempList[i], firstScale, topOffset, 1, i, isLeftFlag)
        }

        return dx
    }

    /**
     * 填充水平方向的控件
     */
    private fun fillHorizontalBaseItemView(view: View, firstScale: Float, topOffset: Float, position: Int, index: Int, isLeftFlag: Boolean) {
        if (stackConfig.isAdjustSize) {
            /* 重设宽高，以防止在RecyclerView嵌套RecyclerView在中出现View显示不全的异常 */
            val layoutParams = view.layoutParams
            layoutParams.width = mItemWidth
            layoutParams.height = mItemHeight
            view.layoutParams = layoutParams
        }

        // 通知测量view的margin值
        measureChildWithMargins(view, 0, 0)
        //获取缩放比例
        val scale = if (mTotalOffset >= 0) {
            calculateHorizontalScale(firstScale, position, index)
        } else {
            calculateHorizontalCycleScale(firstScale, position, index)
        }
        val rotate = if (mTotalOffset >= 0){
            calculateHorizontalRotate(0F, position, index)
        } else{
            calculateHorizontalCycleRotate(0F, position, index)
        }
        if (scale > 0f) {
            // 因为刚刚进行了detach操作，所以现在可以重新添加
            addView(view)
            // 调用这句我们指定了该View的显示区域，并将View显示上去，此时所有区域都用于显示View
            layoutDecoratedWithMargins(view, 0, 0, mItemWidth, mItemHeight)
            view.scaleX = scale
            view.scaleY = scale
            view.rotation = rotate
            view.pivotX = 0f
            view.pivotY = view.height.toFloat() * scale
            log("fillHorizontalBaseItemView rotation = ${rotate} ${  view.height.toFloat() * scale}")
            val offset = if (mTotalOffset >= 0) {
                calculateHorizontalOffset(scale, position, index)
            } else {
                calculateHorizontalCycleOffset(scale, position, index)
            }
            //偏移距离
            if (isLeftFlag) {
                //向右偏移
                view.translationX = -offset
            } else {
                view.translationX = offset
            }
            view.translationY = -topOffset
        }
    }

    private fun absMax(a: Int, b: Int): Int {
        return if (Math.abs(a) > Math.abs(b)) {
            a
        } else {
            b
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onAttachedToWindow(view: RecyclerView?) {
        super.onAttachedToWindow(view)
        mRecyclerView = view
        //check when raise finger and settle to the appropriate item
        view?.setOnTouchListener(mTouchListener)

        view?.onFlingListener = mOnFlingListener

        initialFlag = false
    }

    override fun onDetachedFromWindow(view: RecyclerView?, recycler: RecyclerView.Recycler?) {
        super.onDetachedFromWindow(view, recycler)
        stopAutoCycle()
    }

    private fun brewAndStartAnimator(dur: Int, finalXorY: Int) {
        animator = ObjectAnimator.ofInt(this@StackCardLayoutManager, "animateValue", 0, finalXorY)
        animator?.duration = dur.toLong()
        animator?.start()
        animator?.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                lastAnimateValue = 0
                positionChange()
            }

            override fun onAnimationCancel(animation: Animator) {
                lastAnimateValue = 0
                positionChange()
            }
        })
    }

    private fun positionChange() {
        mOnPositionChangeListener?.let {
            when (stackConfig.direction) {
                StackDirection.LEFT, StackDirection.RIGHT -> {
                    if (mItemWidth > 0) {
                        it.onPositionChange(Math.abs(mTotalOffset) / mItemWidth)
                    }
                }
            }
        }
    }

    @SuppressLint("AnimatorKeep")
    fun setAnimateValue(animateValue: Int) {
        this.animateValue = animateValue
        val distance = this.animateValue - lastAnimateValue
        mRecycler?.let {
            fillItemView(it, stackConfig.direction.layoutDirection * distance, false)
        }
        lastAnimateValue = animateValue
    }

    fun getAnimateValue(): Int {
        return animateValue
    }



    /**
     * 获取水平方向第一个item的缩放比
     */
    private fun getHorizontalFirstScale(): Float {
        return (mItemWidth - (stackConfig.stackCount - 1) * stackConfig.space) * 1f / mItemWidth
    }

    /**
     * 获取水平方向item的偏移量
     * @param firstScale 首个item的缩放比
     */
    private fun getHorizontalItemOffset(firstScale: Float): Float {
        return if (stackConfig.isAdjustSize) {
            (mItemHeight - mItemHeight * firstScale) / 2
        } else {
            0f
        }
    }

    /**
     * 计算水平方向缩放量，StackDirection.LEFT，StackDirection.RIGHT
     * @param firstScale 首个item的缩放比
     * @param position 当前位置
     * @param index 序号
     */
    private fun calculateHorizontalScale(firstScale: Float, position: Int, index: Int): Float {
        log("calculateHorizontalScale firstScale = ${firstScale}  position = ${position} index = ${index}")
        return when {
            index > position -> {
                calculateHorizontalBaseScale(firstScale, position, index)
            }
            index == position -> { // 第一个item，慢慢移出/移入屏幕
                firstScale
            }
            else -> {
                0f
            }
        }
    }

    /**
     * 计算水平方向缩放量(循环)，StackDirection.LEFT，StackDirection.RIGHT
     * @param firstScale 首个item的缩放比
     * @param position 当前位置
     * @param index 序号
     */
    private fun calculateHorizontalCycleScale(firstScale: Float, position: Int, index: Int): Float {
        return when {
            index - position >= stackConfig.stackCount -> {
                0f
            }
            index >= position -> {
                calculateHorizontalBaseScale(firstScale, position, index)
            }
            else -> { // 第一个item，慢慢移出/移入屏幕
                firstScale
            }
        }
    }

    /**
     * 计算水平方向缩放量，StackDirection.LEFT，StackDirection.RIGHT
     * @param firstScale 首个item的缩放比
     * @param position 当前位置
     * @param index 序号
     */
    private fun calculateHorizontalBaseScale(firstScale: Float, position: Int, index: Int): Float {
        // 当前移动的比例
        val offsetRatio = mTotalOffset * 1f / mItemWidth - mTotalOffset / mItemWidth
        /* 计算当前item的缩放比 */
        var scale = firstScale
        for (t in 0 until (index - position)) {
            scale *= stackConfig.stackScale
        }
        /* 计算下一个item的缩放比 */
        var nextScale = firstScale
        for (t in 0 until (index - position + 1)) {
            nextScale *= stackConfig.stackScale
        }
        // 返回当前item的缩放比
        return scale + (scale - nextScale) * offsetRatio
    }

    /**
     * 计算水平方向偏移，StackDirection.LEFT，StackDirection.RIGHT
     * @param scale 当前序号item缩放比
     * @param position 当前位置
     * @param index 序号
     */
    private fun calculateHorizontalOffset(scale: Float, position: Int, index: Int): Float {
        return when {
            index > position -> {
                calculateHorizontalBaseOffset(scale, position, index)
            }
            else -> { // 第一个item
                log("calculateHorizontalOffset 第一个item mItemWidth = ${mItemWidth} scale = ${scale} mTotalOffset = ${mTotalOffset} ${((mTotalOffset * 1f / mItemWidth - mTotalOffset / mItemWidth) * mItemWidth)}")
                //水平位置偏移
                (Math.sin(stackConfig.stackRotateEnd.toDouble() * Math.PI/ 180) * scale * 2 * mItemWidth).toFloat()
            }
        }
    }

    /**
     * 计算水平方向偏移(循环)，StackDirection.LEFT，StackDirection.RIGHT
     * @param scale 当前序号item缩放比
     * @param position 当前位置
     * @param index 序号
     */
    private fun calculateHorizontalCycleOffset(scale: Float, position: Int, index: Int): Float {
        return when {
            index >= position -> {
                calculateHorizontalBaseOffset(scale, position, index)
            }
            else -> { // 第一个item
                (mItemWidth - mItemWidth * scale) / 2 - (stackConfig.stackCount - 1) * stackConfig.space * 1f - mItemWidth - (mTotalOffset * 1f / mItemWidth - mTotalOffset / mItemWidth) * mItemWidth
            }
        }
    }

    /**
     * 计算水平方向偏移(循环)，StackDirection.LEFT，StackDirection.RIGHT
     * @param scale 当前序号item缩放比
     * @param position 当前位置
     * @param index 序号
     */
    private fun calculateHorizontalBaseOffset(scale: Float, position: Int, index: Int): Float {
        log("calculateHorizontalBaseOffset mTotalOffset ${mTotalOffset} position = ${position} index = ${index} mItemWidth = ${mItemWidth} ")
        return if (mTotalOffset % mItemWidth == 0) {
            //stackCount = 3
            if (stackConfig.stackCount - index + position - 1 >= 0) {
                (mItemWidth - mItemWidth * scale) / 2 - (stackConfig.stackCount - index + position - 1) * stackConfig.space * 1f
            } else {
                (mItemWidth - mItemWidth * scale) / 2
            }
        } else {
            val offset = (mTotalOffset * 1f / mItemWidth - mTotalOffset / mItemWidth) * stackConfig.space
            (mItemWidth - mItemWidth * scale) / 2 - (stackConfig.stackCount - index + position - 1) * stackConfig.space * 1f - offset
        }
    }

    private fun calculateHorizontalCycleRotate(firstScale: Float, position: Int, index: Int): Float {
        return when {
            index - position >= stackConfig.stackCount -> {
                0f
            }
            index >= position -> {
                calculateHorizontalBaseRotate(firstScale, position, index)
            }
            else -> { // 第一个item，慢慢移除屏幕
                firstScale
            }
        }
    }

    private fun calculateHorizontalRotate(firstRotate: Float, position: Int, index: Int): Float {
        log("calculateHorizontalRotate firstScale = ${firstRotate}  position = ${position} index = ${index}")
        return when {
            index > position -> {
                calculateHorizontalBaseRotate(firstRotate, position, index)
            }
            index == position -> { // 第一个item，慢慢移出/移入屏幕
                firstRotate
            }
            else -> {
                0f
            }
        }
    }

    private fun calculateHorizontalBaseRotate(firstRotate: Float, position: Int, index: Int): Float {
        // 当前移动的比例
        val offsetRatio = mTotalOffset * 1f / mItemHeight - mTotalOffset / mItemHeight
        /* 计算当前item的缩放比 */
        var rotate = firstRotate
        for (t in 0 until (index - position)) {
            rotate += stackConfig.stackRotate
        }
        /* 计算下一个item的缩放比 */
        var nextRotate = firstRotate
        for (t in 0 until (index - position + 1)) {
            nextRotate += stackConfig.stackRotate
        }
        // 返回当前item的缩放比
        return rotate + (rotate - nextRotate) * offsetRatio
    }

    /**
     * 计算时间水平方向动画时间
     */
    private fun computeHorizontalSettleDuration(distance: Int, xVel: Float): Int {
        val sWeight = 0.5f * distance / mItemWidth
        val velWeight = if (xVel > 0) {
            0.5f * mMinVelocity / xVel
        } else {
            0f
        }

        return ((sWeight + velWeight) * duration).toInt()
    }

    private fun recycleHorizontally(view: View?, dx: Int): Boolean {
        return view != null && (view.left - dx < 0 || view.right - dx > width)
    }

    override fun scrollHorizontallyBy(dx: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        return fillItemView(recycler, dx)
    }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        return fillItemView(recycler, dy)
    }

    override fun canScrollHorizontally(): Boolean {
        return stackConfig.direction == StackDirection.LEFT || stackConfig.direction == StackDirection.RIGHT
    }

    override fun canScrollVertically(): Boolean {
        return false
    }

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams {
        return RecyclerView.LayoutParams(RecyclerView.LayoutParams.WRAP_CONTENT, RecyclerView.LayoutParams.WRAP_CONTENT)
    }

    /**
     * we need to set scrollstate to [RecyclerView.SCROLL_STATE_IDLE] idle
     * stop RecyclerView from intercepting the touch event which block the item click
     */
    private fun setScrollStateIdle() {
        try {
            if (sSetScrollState == null) {
                sSetScrollState = RecyclerView::class.java.getDeclaredMethod("setScrollState", Int::class.javaPrimitiveType!!)
            }
            sSetScrollState?.isAccessible = true
            sSetScrollState?.invoke(mRecyclerView, RecyclerView.SCROLL_STATE_IDLE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun scrollToPosition(position: Int) {
        if (position > itemCount - 1) {
            return
        }
        when (stackConfig.direction) {
            StackDirection.LEFT, StackDirection.RIGHT -> {
                if (mItemWidth > 0) {
                    val currPosition = mTotalOffset / mItemWidth
                    val distance = (position - currPosition) * mItemWidth
                    val dur = computeHorizontalSettleDuration(Math.abs(distance), 0f)
                    brewAndStartAnimator(dur, distance)
                }
            }
        }
    }

    /**
     * 重设宽高
     */
    override fun setMeasuredDimension(widthSize: Int, heightSize: Int) {
        if (stackConfig.isAdjustSize && mItemWidth > 0 && mItemHeight > 0) {
            when (stackConfig.direction) {
                StackDirection.LEFT, StackDirection.RIGHT -> {
                    val height = mItemHeight * getHorizontalFirstScale()
                    super.setMeasuredDimension(mItemWidth, height.toInt())

                }
            }
        } else {
            super.setMeasuredDimension(widthSize, heightSize)
        }
    }

    override fun requestLayout() {
        super.requestLayout()
        initialFlag = false
    }

    /**
     * 开始自动循环
     */
    private fun startAutoCycle() {
        if (stackConfig.isCycle && itemCount > 1 && stackConfig.isAutoCycle) {
            mRecyclerView?.postDelayed(mAutoCycleRunnable, (stackConfig.autoCycleTime + duration) * 1L)
        }
    }

    /**
     * 开始暂停自动循环
     */
    private fun stopAutoCycle() {
        if (stackConfig.isCycle && itemCount > 1 && stackConfig.isAutoCycle) {
            mRecyclerView?.removeCallbacks(mAutoCycleRunnable)
        }
    }

    /**
     * 设置位置改变监听
     */
    fun setOnPositionChangeListener(action: (position: Int) -> Unit) {
        this.mOnPositionChangeListener = object : OnPositionChangeListener {
            override fun onPositionChange(position: Int) {
                action(position)
            }
        }
    }

    /**
     * 位置改变监听
     */
    interface OnPositionChangeListener {
        fun onPositionChange(position: Int)
    }

    class StackConfig {

        @IntRange(from = 0)
        var space = 0 // 间距

        @IntRange(from = 1)
        var stackCount = 3 // 可见数

        @IntRange(from = 0)
        var stackPosition = 0 // 初始可见的位置

        @FloatRange(from = 0.0, to = 1.0)
        var stackScale: Float = 0.9f // 缩放比例

        var stackRotate : Float = -10f//旋转比例

        var stackRotateEnd : Float = -24f

        @FloatRange(from = 1.0, to = 2.0)
        var parallex = 1f // 视差因子

        var isCycle = false // 是否能无限循环，若列表数为1不允许无限循环

        var isAutoCycle = false // 若能无限循环，是否自动开始循环

        @IntRange(from = 1000)
        var autoCycleTime = 3000 // 自动循环时间间隔，毫秒

        var isAdjustSize = false // 是否重新校准调整RecyclerView宽高

        var direction: StackDirection = StackDirection.RIGHT // 方向

    }

    enum class StackDirection(val layoutDirection: Int = 0) {
        LEFT(-1),
        RIGHT(1),
    }

    private fun log(msg : String?){
        Log.e("StackCardLayout", "${msg}")
    }

}

