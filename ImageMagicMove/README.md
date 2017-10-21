# 使用自定义动画实现 ImageView 的神奇移动效果

> 图片的“神奇移动”效果在很多 app 中都很常见，这个效果说白了就是图片由一个缩略图变成一个全屏显示的完整图片时的动画。

这里为了直观，我们先直接上最终的效果图：

![Preview](https://github.com/unixzii/android-source-codes/raw/master/ImageMagicMove/assets/demo.gif)

这个效果看似很简单，但实现起来也不是非常轻松，为什么这么说呢？我们可以简单分析一下这个动画效果。

一般来讲，预览图和全屏图片并不处于同一个 Activity，所以我们需要在启动新的 Activity 之前将原始缩略图的位置和大小传递过去，这一点其实还是很简单的，利用 extras 就能搞定。

接下来是位置和大小的变换，我们通常可以用 translate 和 scale 来实现，但这里这个方法显然不可用，大家可以仔细观察上面的效果图，注意动画过程中“小熊猫耳朵”附近的变化，可以发现，整个动画过程中，ImageView 并不是在做等比例的缩放，而是会根据大小动态调整内容的裁剪方式。

我们都知道，`ImageView` 的 `scaleType` 属性可以规定图片的缩放方式，这里缩略图我们为了美观性一般都会采用 `centerCrop`，从而使图片填满整个 ImageView，而大图为了完整地查看整个图片，我们一般又都会去用 `fitCenter`。（有关各个 Scale Type 的区别本文不再赘述，请读者提前弄清楚）

## 分析 `ImageView` 缩放和裁剪图片的实现

要实现 Scale Type 的动画，我们首先要弄明白它是怎么在 `ImageView` 中被实现的。这里涉及了一个比较重要的方法：`ImageView#configureBounds()`，它会在 `ImageView` 尺寸发生变化时被调用，在这个方法中，`ImageView` 主要会去调整 drawable 的 bounds 和 **`mDrawMatrix`**。这个 matrix 非常重要，我们之后的动画效果也要很大程度上依赖于这个它。

简单来说，只要 `ImageView` 的 `scaleType` 不是 `FIT_XY`（这种方式采用的是 drawable bounds 实现的），matrix 就会在绘制过程中被使用到。

而这里我们用到的两种方式分别是 `CENTER_CROP` 和 `FIT_CENTER`，那我们就看看这两种方式下，matrix 是如何被配置的。

首先是 `CENTER_CROP`：

```java
...
} else if (ScaleType.CENTER_CROP == mScaleType) {
    mDrawMatrix = mMatrix;

    float scale;
    float dx = 0, dy = 0;

    if (dwidth * vheight > vwidth * dheight) {
        scale = (float) vheight / (float) dheight;
        dx = (vwidth - dwidth * scale) * 0.5f;
    } else {
        scale = (float) vwidth / (float) dwidth;
        dy = (vheight - dheight * scale) * 0.5f;
    }

    mDrawMatrix.setScale(scale, scale);
    mDrawMatrix.postTranslate(Math.round(dx), Math.round(dy));
} ...
```

**dwidth * vheight > vwidth * dheight** 

可以化为：

**dwidth / vwidth > dheight / vheight**

那么这个逻辑就是，如果宽度明显宽的话，就让缩放后的图片高度和 ImageView 一致，这样水平方向上的内容就可以通过平移来裁剪掉一部分，反之...

然后是 `FIT_CENTER`：

```java
} else {
    mTempSrc.set(0, 0, dwidth, dheight);
    mTempDst.set(0, 0, vwidth, vheight);

    mDrawMatrix = mMatrix;
    mDrawMatrix.setRectToRect(mTempSrc, mTempDst, scaleTypeToScaleToFit(mScaleType));
}
```

这个就比较简单了，直接使用的 `Matrix` 的方法。

这些 matrix 最终都会被赋值给 `mDrawMatrix`，然后在 `onDraw` 时被应用到 Canvas 上就能实现变换了。同时使用 **Find Usages** 可以发现，有名为 `getImageMatrix` 的公开方法可以拿到这个 matrix。至此 matrix 就分析完毕了，我们只需要拿到起始和结束的 matrix，然后通过 `Animator` 就可以实现动画了。

## 在 Animator 中使用自定义属性和类型

在这个例子中，我们需要对矩阵进行动画变化，这里就涉及 `Property` 和 `TypeEvaluator` 这两个东西了。前者是给 `ObjectAnimator` 用的属性访问器，由于 `ImageView` 并没有自带一个可以设置 `imageMatrix` 的 `Property`，所以我们要自己实现一个，实现方法也是十分简单：

```java
private final static Property<ImageView, Matrix> IMAGE_MATRIX =
        new Property<ImageView, Matrix>(Matrix.class, "imageMatrix") {
            @Override
            public void set(ImageView object, Matrix value) {
                object.setImageMatrix(value);
            }

            @Override
            public Matrix get(ImageView object) {
                return object.getImageMatrix();
            }
        };
```

然后由于我们要动画的值类型是 `Matrix`，`Animator` 默认也不能对这种类型进行估值处理。注意和插值器的区别，所谓估值，就是给了起始值、结束值和一个进度，然后计算出当前进度的值是多少。`Animator` 对于简单数值类型可以直接估值，而对于除此以外的 `Object` 派生类型，则需要我们提供估值的方法，就是实现 `TypeEvaluator` 接口。

对矩阵的估值也很简单，我们知道对浮点数的估值可以采用以下公式：

```
V = S + (E - S) * P
```

其中 V 就是我们要计算的值，S、E、P 分别表示起始值、结束值和进度。对于矩阵，直接代入实际就相当于矩阵的加法和数乘运算，不涉及复杂的矩阵计算方式，我们只需要对矩阵的 9 个元分别进行上述计算就可以了。

简单看一下实现：

```java
private static class MatrixEvaluator implements TypeEvaluator<Matrix> {
    private float[] mTmpStartValues = new float[9];
    private float[] mTmpEndValues = new float[9];
    private Matrix mTmpMatrix = new Matrix();

    @Override
    public Matrix evaluate(float fraction, Matrix startValue, Matrix endValue) {
        startValue.getValues(mTmpStartValues);
        endValue.getValues(mTmpEndValues);
        for (int i = 0; i < 9; i++) {
            float diff = mTmpEndValues[i] - mTmpStartValues[i];
            mTmpEndValues[i] = mTmpStartValues[i] + (fraction * diff);
        }
        mTmpMatrix.setValues(mTmpEndValues);

        return mTmpMatrix;
    }
}
```

为了性能，我们在估值器内部缓存一个 matrix，每次把结果都存放于这个 matrix 中返回。

## 还没结束...

至此，我们已经能实现矩阵的变换了，但还不够，`ImageView` 的位置和大小还没变化呢。关于 View 的位置和大小，Android 与 iOS 最大的一个不同就是 iOS 可以非常方便地设置 `UIView` 的 `frame` 和 `bounds`（对应 `CALayer` 的属性，`CALayer` 是 `UIView` 的实际体现），而 Android 中 View 的位置和大小一般都是由父容器在 `onLayout` 时调用 `View#layout` 方法设置好的。但是我们仍然可以随时调用 `layout` 方法重新设置 `View` 的位置和大小。

为什么不能设置 `LayoutParams` 呢？虽然可以实现相同的效果，但是这会使父容器重新执行 Layout Passes，需要重新 measure 和 layout，如果视图层级比较复杂就很产生很大的性能开销，尤其是这个动作是以 60fps 的频率执行的。而 `layout` 方法则可以在“不惊动”父容器的情况下完成对位置和大小的设置（硬件加速的情况下只影响 `RenderNode`，它包含了对 `View` 各个属性的描述）。

这里为了方便，我们把 View 的位置和大小称作 bounds。对于 bounds 的变化还有一个需要注意的点就是参考系。

一般的 app，缩略图所在的视图层级一般较深（比如可能位于 `RecyclerView` 下的列表项 Layout 下 `LinearLayout`），那此时这个 View 的 bounds 原点和大图 View 的 bounds 原点参考系就不同了。

这里我的处理方式是，将两个 View 的 bounds 归到相同的参考系下（用 `View#getLocationInWindow` 化为相对于 Window 的位置），然后再比较两个 bounds 的原点差异，用这个差异去 offset 大图的 bounds，就能得到缩略图在大图参考系下的位置了。感觉说起来比较绕口，直接贴出代码来吧：

```java
int[] thumbnailOrigin = new int[2];
int[] fullOrigin = new int[2];
mThumbnailImageView.getLocationInWindow(thumbnailOrigin);
mFullImageView.getLocationInWindow(fullOrigin);

int thumbnailLeft = mFullImageView.getLeft() + (thumbnailOrigin[0] - fullOrigin[0]);
int thumbnailTop = mFullImageView.getTop() + (thumbnailOrigin[1] - fullOrigin[1]);

Rect thumbnailBounds = new Rect(thumbnailLeft, thumbnailTop,
        thumbnailLeft + mThumbnailImageView.getWidth(),
        thumbnailTop + mThumbnailImageView.getHeight());
Rect fullBounds = new Rect(mFullImageView.getLeft(), mFullImageView.getTop(),
        mFullImageView.getRight(), mFullImageView.getBottom());
```

这里我为了演示方便，两个 ImageView 都在同一个 Activity 下，真实情况中这些数据就需要通过 Intent extras 来传递了。

由于 bounds 属性也不存在，`Animator` 默认也不能对 `Rect` 进行估值，所以用上文的方法自己实现相关接口就可以了。

## 总结

到这里，我们总共用了两个 Animator，一个用来变换 bounds，另一个用来变换 matrix。因为 ImageView 是通过 matrix 来缩放和裁剪内容图片的，直接变换 bounds 或 transform 是不能达到本效果的。

另外，如果你想使用 [PhotoView](https://github.com/chrisbanes/PhotoView) 也是没有问题的，因为它也是通过 matrix 实现的，本文仅提供了一个思路，具体实现就看大家的需求了。

文章开头效果图的核心代码可以参考：[ImageTransitionActivity](https://github.com/unixzii/android-source-codes/blob/master/ImageMagicMove/demo/ImageTransitionActivity.java)

## 推广信息

如果你对我的 Android 源码分析系列文章感兴趣，可以点个 star 哦，我会持续不定期更新文章。