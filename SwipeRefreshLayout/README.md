# SwipeRefreshLayout 源码分析

> `SwipeRefreshLayout` 是 Android Support 包中的一个控件，旨在为用户提供通用的下拉刷新体验。

对于这个控件的功能和外观这里就不做过多的赘述了，我想大家肯定都用过。所以本文就直接切入正题来分析整个控件的实现了。

整篇文章我打算分为两部分：
1. **Nested Scrolling** 机制的运用
2. 动画效果的实现分析

为了大家参考方便，我将组件的核心类 [SwipeRefreshLayout.java](https://github.com/unixzii/android-source-codes/blob/master/SwipeRefreshLayout/SwipeRefreshLayout.java) 也一并提交了上来。

## Nested Scrolling 机制的运用

**Nested Scrolling** 是于 API level 21 (Android 5.0 Lollipop) 加入的一个新特性，在 support 包中也有相关的兼容性实现，顾名思义，它的作用就是处理一些复杂的嵌套滚动。在用户界面中，我们通常会遇到两种嵌套滚动：一种是不同轴的，例如 `ViewPager` 中嵌套 `RecyclerView`，它的处理方式相对简单，就是 `onInterceptTouchEvent` 的应用；另一种就是同轴的，就是本文要着重介绍的 Nested Scrolling。

Nested Scrolling 主要由两个接口和两个 Helper 类来实现。对于 Nested Scrolling 中各个类的实现分析我不打算在这篇文章中展开了，日后会再开一篇文章来讲述（*未来文章的 Placeholder*）。

`SwipeRefreshLayout` 实现了两个接口：`NestedScrollingParent`、`NestedScrollingChild`，也就是说，这个控件既可以作为一个嵌套滚动容器的子视图，也可以作为嵌套滚动的容器。一般来讲，我们都会把 `RecyclerView` 塞进它里面，这样就能轻松实现下拉刷新了。然而有的时候，我们需要再将 `SwipeRefreshLayout` 塞入一个 `CoordinatorLayout` 中来实现一些更复杂的效果，由于 `CoordinatorLayout` 也是依靠 Nested Scrolling 实现的，那他就要求子视图实现 `NestedScrollingChild` 才能接收到它的滚动事件，以便于拦截处理。

首先看它作为 Parent 部分的实现。

总的来说就需要分析下面几个方法：
* `onStartNestedScroll`
* `onNestedScrollAccepted`
* `onNestedPreScroll`
* `onNestedScroll`
* `onStopNestedScroll`

首先来看 `onStartNestedScroll`：
```java
@Override
public boolean onStartNestedScroll(View child, View target, int nestedScrollAxes) {
    return isEnabled() && !mReturningToStart && !mRefreshing
            && (nestedScrollAxes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
}
```

这个方法的调用时机就是当用户刚开始滑动内嵌视图时，嵌套滚动机制会询问这个方法，是否要当做嵌套滚动处理，通常我们在这里判断一下滑动的轴向和其他条件，然后返回一个布尔值表示是否处理。SRL 的实现很简单，判断控件是否启用，返回动画是否未结束，滑动轴向是否为纵向。如果这些条件都满足，那么就对这次滑动全程实施嵌套滚动处理。

下面是 `onNestedScrollAccepted`：
```java
@Override
public void onNestedScrollAccepted(View child, View target, int axes) {
    // Reset the counter of how much leftover scroll needs to be consumed.
    mNestedScrollingParentHelper.onNestedScrollAccepted(child, target, axes);
    // Dispatch up to the nested parent
    startNestedScroll(axes & ViewCompat.SCROLL_AXIS_VERTICAL);
    mTotalUnconsumed = 0;
    mNestedScrollInProgress = true;
}
```

这里其实就是初始化了几个状态变量，但注意，`startNestedScroll` 这个方法很有意思，我们跟踪：
```java
@Override
public boolean startNestedScroll(int axes) {
    return mNestedScrollingChildHelper.startNestedScroll(axes);
}
```

可以看到，由于 SRL 也可以作为嵌套滚动的子视图，所以这个方法的作用就是告知 SRL 的父视图，有一个可以嵌套滚动的子视图开始滚动了，那 SRL 的父视图（可能是 `CoordinatorLayout`）就可以做和 SRL 类似的准备工作了。

接下来就是比较重要的几个方法了，首先是 `onNestedPreScroll`，代码篇幅较长，我就把分析写进注释里了：
```java
@Override
public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
    // 这个方法在子视图滚动之前被调用。
    // 这里处理了用户将 Spinner 下拉的半路又上划回去的情况。
    if (dy > 0 && mTotalUnconsumed > 0) {
        if (dy > mTotalUnconsumed) {
            consumed[1] = dy - (int) mTotalUnconsumed;
            mTotalUnconsumed = 0;
        } else {
            mTotalUnconsumed -= dy;
            consumed[1] = dy;
        }
        // 移动 Spinner，下文会分析相关实现。
        moveSpinner(mTotalUnconsumed);
    }

    // 到这里 consumed 的值可能被修改，如果出现上面的情况，那么这个值就不为 0，表示该滑动已经被 SRL 消费了，子视图你就别再滚动消费的那部分了。

    // 这里处理了用户自定义 Spinner 位置的情况，让它在该出现的位置之前隐藏。
    if (mUsingCustomStart && dy > 0 && mTotalUnconsumed == 0
            && Math.abs(dy - consumed[1]) > 0) {
        mCircleView.setVisibility(View.GONE);
    }

    // 同样的，作为子视图时要通知父视图。
    final int[] parentConsumed = mParentScrollConsumed;
    if (dispatchNestedPreScroll(dx - consumed[0], dy - consumed[1], parentConsumed, null)) {
        consumed[0] += parentConsumed[0];
        consumed[1] += parentConsumed[1];
    }
}
```

然后是 `onNestedScroll`：
```java
@Override
public void onNestedScroll(final View target, final int dxConsumed, final int dyConsumed,
        final int dxUnconsumed, final int dyUnconsumed) {
    // onNestedPreScroll 调用后子视图会滑动，然后这个方法就会被调用，如果子视图滑动到边界，也会由这几个参数表现出来。
    // 先将事件派发给父视图，注意这里有一个很重要的细节！
    // 我们将 SRL 放进 CoordinatorLayout 时，CoordinatorLayout 是
    // 优先于 SRL 处理滑动越界行为的（比如展开折叠的 Toolbar），最后才
    // 轮到 SRL 显示 Spinner，这是怎么做到的呢？
    // 秘密就在于下面这个函数的 mParentOffsetInWindow 参数。
    dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed,
            mParentOffsetInWindow);

    // 这里将滚动事件派发给父视图后，父视图一定会有所动作，但是
    // 嵌套滚动机制并不知晓父视图做了什么操作，是否消耗了滚动，
    // 但是最后一个参数却能传递 SRL 本身在这个事件发生之后
    // 的位移，从而推断出父视图消耗的滚动距离。

    // 如果 SRL 滚动了，mParentOffsetInWindow[1] 肯定就不是 0，父视图消耗了滚动，dy 就不是一个负数了。
    final int dy = dyUnconsumed + mParentOffsetInWindow[1];
    if (dy < 0 && !canChildScrollUp()) {
        mTotalUnconsumed += Math.abs(dy);
        moveSpinner(mTotalUnconsumed);
    }
}
```

最后是 `onStopNestedScroll`，这个就比较简单了，主要是做一些动画和状态设置，代码我就不贴了。

作为 Child 部分的实现就相对简单了，基本都是使用 `NestedScrollingChildHelper` 这个助手类来去实现的。这里也不再赘述了。

一点补充，如果大家看源码的话可以发现，SRL 中实际也使用了传统的重写 `onInterceptTouchEvent` 的方法来处理嵌套滚动，这是为了适应子视图未实现现代 Nested Scrolling 的情况，如果子视图是 `RecyclerView` 或其他支持 Nested Scrolling 的视图，就不会引起后续的传统处理方式：
```java
@Override
public boolean onInterceptTouchEvent(MotionEvent ev) {
    ensureTarget();

    final int action = MotionEventCompat.getActionMasked(ev);
    int pointerIndex;

    if (mReturningToStart && action == MotionEvent.ACTION_DOWN) {
        mReturningToStart = false;
    }

    if (!isEnabled() || mReturningToStart || canChildScrollUp()
            || mRefreshing || mNestedScrollInProgress) {
        // Fail fast if we're not in a state where a swipe is possible
        return false;
    }

    ...
}
```

`mNestedScrollInProgress` 变量就可以用于判断子视图是否支持 Nested Scrolling，因为在 Nested Scrolling 开始后这个值就被设置为 `true` 了。

## 动画效果的实现分析

整个控件的视觉元素很少，就包含一个圆形的刷新指示器，这个是由 `CircleImageView` 和 `MaterialProgressDrawable` 这两个类来实现的，并由该方法添加到 SRL 视图中：
```java
private void createProgressView() {
    mCircleView = new CircleImageView(getContext(), CIRCLE_BG_LIGHT);
    mProgress = new MaterialProgressDrawable(getContext(), this);
    mProgress.setBackgroundColor(CIRCLE_BG_LIGHT);
    mCircleView.setImageDrawable(mProgress);
    mCircleView.setVisibility(View.GONE);
    addView(mCircleView);
}
```

其中 `MaterialProgressDrawable` 就绘制了那个 **Material Design** 风格的 Spinner，它也被用于 `ProgressBar` 等控件中，而 `CircleImageView` 只是一个含圆形底图的一个 `ImageView`。后者其实没有什么值得深入分析的，因为 API level 21 之后就可以直接通过 `setElevation` 来设置视图的阴影效果了，这个类主要通过自定义绘制处理了老版本的兼容问题。

另一个有意思的点就是 SRL 处理动画的方式，举一个例子，`animateOffsetToCorrectPosition` 这个方法中使用到了一个名为 `mAnimateToCorrectPosition` 的 `Animation` 对象，它的定义如下：
```java
private final Animation mAnimateToCorrectPosition = new Animation() {
    @Override
    public void applyTransformation(float interpolatedTime, Transformation t) {
        int targetTop = 0;
        int endTarget = 0;
        if (!mUsingCustomStart) {
            endTarget = mSpinnerOffsetEnd - Math.abs(mOriginalOffsetTop);
        } else {
            endTarget = mSpinnerOffsetEnd;
        }
        targetTop = (mFrom + (int) ((endTarget - mFrom) * interpolatedTime));
        int offset = targetTop - mCircleView.getTop();
        setTargetOffsetTopAndBottom(offset, false /* requires update */);
        mProgress.setArrowScale(1 - interpolatedTime);
    }
};
```

这里同时控制了 `CircleImageView` 的位置和 `MaterialProgressDrawable` 的箭头大小，没有使用 `ValueAnimator` 等 `Animator` 来实现动画的目的是什么就不得而知了（个人猜测是为了兼容 Android 3.0 下的版本，毕竟 support 包的东西在 2.x 下都可以正常使用），大家如果知道的话也可以来提 Issue。

另外，SRL 作为一个 ViewGroup 也有自己的布局逻辑，我们也可以简单看一下：
```java
@Override
protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    final int width = getMeasuredWidth();
    final int height = getMeasuredHeight();
    if (getChildCount() == 0) {
        return;
    }
    if (mTarget == null) {
        ensureTarget();
    }
    if (mTarget == null) {
        return;
    }
    final View child = mTarget;
    final int childLeft = getPaddingLeft();
    final int childTop = getPaddingTop();
    final int childWidth = width - getPaddingLeft() - getPaddingRight();
    final int childHeight = height - getPaddingTop() - getPaddingBottom();
    child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
    int circleWidth = mCircleView.getMeasuredWidth();
    int circleHeight = mCircleView.getMeasuredHeight();
    mCircleView.layout((width / 2 - circleWidth / 2), mCurrentTargetOffsetTop,
            (width / 2 + circleWidth / 2), mCurrentTargetOffsetTop + circleHeight);
}

@Override
public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    if (mTarget == null) {
        ensureTarget();
    }
    if (mTarget == null) {
        return;
    }
    mTarget.measure(MeasureSpec.makeMeasureSpec(
            getMeasuredWidth() - getPaddingLeft() - getPaddingRight(),
            MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(
            getMeasuredHeight() - getPaddingTop() - getPaddingBottom(), MeasureSpec.EXACTLY));
    mCircleView.measure(MeasureSpec.makeMeasureSpec(mCircleDiameter, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(mCircleDiameter, MeasureSpec.EXACTLY));
    mCircleViewIndex = -1;
    // Get the index of the circleview.
    for (int index = 0; index < getChildCount(); index++) {
        if (getChildAt(index) == mCircleView) {
            mCircleViewIndex = index;
            break;
        }
    }
}
```

概括地说就是，对于内容子视图，就让它填满整个 SRL，这也就是说 SRL 会自动填满它的父视图，不管它的尺寸是 `MATCH_PARENT` 还是 `WRAP_CONTENT`，然后让它的子视图也填满父视图。对于 Spinner，SRL 就 hardcode 了它的大小，位置则由 `mCurrentTargetOffsetTop` 决定，并始终水平居中。

为了让 Spinner 始终显示在最上面，SRL 也自定义了绘制顺序：
```java
@Override
protected int getChildDrawingOrder(int childCount, int i) {
    if (mCircleViewIndex < 0) {
        // Spinner 的 index 还未被计算出来（可能还没 measure），fallback 到 super 的处理方式。
        // super 的处理方式实际就是直接返回 i。
        return i;
    } else if (i == childCount - 1) {
        // Draw the selected child last
        return mCircleViewIndex;
    } else if (i >= mCircleViewIndex) {
        // Move the children after the selected child earlier one
        return i + 1;
    } else {
        // Keep the children before the selected child the same
        return i;
    }
}
```

简单讲解一下这个方法的作用，i 这个参数代表了当前应该绘制第几个的视图，返回值则是选中视图的 index，举个简单的例子：

我依次向一个 ViewGroup 中加入了 A、B、C、D 四个视图，按理说 D 是在最上面的，但如果我想让 D 始终显示在最下面，怎么做呢？我们只要让 `getChildDrawingOrder` 能达到下面的这个函数映射就可以了：

| i | 返回值 |
| - | :-: |
| 0 | 3 |
| 1 | 0 |
| 2 | 1 |
| 3 | 2 |

在 SRL 中，最后绘制的视图始终是 Spinner，所以当 `i == childCount - 1` 的时候，就直接返回 Spinner 视图的 index，但其它的视图顺序也需要处理，所以如果当正在绘制的不是最后一个视图，然而 Spinner 却出现了，那就需要跳过 Spinner，先绘制下一个视图，也就是第 `i + 1` 个视图；对于 Spinner 之前的视图就采用默认顺序就可以了。这块可能比较抽象，大家列列表格就能推出来了。

到这里我们基本就分析完 SRL 里面几个比较核心的方法了，其它的细节本文就不再赘述了，相信大家通过阅读源码都能理解。