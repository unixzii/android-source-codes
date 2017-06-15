# SwipeRefreshLayout 源码分析

> `SwipeRefreshLayout` 是 Android Support 包中的一个控件，旨在为用户提供通用的下拉刷新体验。

对于这个控件的功能和外观这里就不做过多的赘述了，我想大家肯定都用过。所以本文就直接切入正题来分析整个控件的实现了。

整篇文章我打算分为两部分：
1. **Nested Scrolling** 简单回顾
2. `CircleImageView` 类的实现分析 

## Nested Scrolling 简单回顾

**Nested Scrolling** 是于 API Level xx 加入的一个新特性，在 support 包中也有相关的兼容性实现，顾名思义，它的作用就是处理一些复杂的嵌套滚动。在用户界面中，我们通常会遇到两种嵌套滚动：一种是不同轴的，例如 `ViewPager` 中嵌套 `RecyclerView`，它的处理方式相对简单，就是 `onInterceptTouchEvent` 的应用；另一种就是同轴的，就是本文要着重介绍的 Nested Scrolling。

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