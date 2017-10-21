package com.example.cyandev.androidplayground;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TypeEvaluator;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Property;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

public class ImageTransitionActivity extends AppCompatActivity {

    private ImageView mThumbnailImageView;
    private ImageView mFullImageView;

    private boolean mFullShown = false;

    private final static Property<View, Rect> BOUNDS =
            new Property<View, Rect>(Rect.class, "bounds") {
                @Override
                public void set(View object, Rect value) {
                    object.setLeft(value.left);
                    object.setTop(value.top);
                    object.setRight(value.right);
                    object.setBottom(value.bottom);
                }

                @Override
                public Rect get(View object) {
                    return new Rect(object.getLeft(), object.getTop(),
                            object.getRight(), object.getBottom());
                }
            };

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

    private static class RectEvaluator implements TypeEvaluator<Rect> {
        private Rect mTmpRect = new Rect();

        @Override
        public Rect evaluate(float fraction, Rect startValue, Rect endValue) {
            mTmpRect.left =
                    (int) (startValue.left + (endValue.left - startValue.left) * fraction);
            mTmpRect.top =
                    (int) (startValue.top + (endValue.top - startValue.top) * fraction);
            mTmpRect.right =
                    (int) (startValue.right + (endValue.right - startValue.right) * fraction);
            mTmpRect.bottom =
                    (int) (startValue.bottom + (endValue.bottom - startValue.bottom) * fraction);

            return mTmpRect;
        }
    }

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

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_transition);

        mThumbnailImageView = (ImageView) findViewById(R.id.thumbnail_image);
        mFullImageView = (ImageView) findViewById(R.id.full_image);

        View.OnClickListener onClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mFullShown = !mFullShown;
                createAnimator(mFullShown);
            }
        };

        mThumbnailImageView.setOnClickListener(onClickListener);
        mFullImageView.setOnClickListener(onClickListener);
    }

    private Rect getDrawableIntrinsicBounds(Drawable d) {
        Rect rect = new Rect();
        rect.right = d.getIntrinsicWidth();
        rect.bottom = d.getIntrinsicHeight();

        return rect;
    }

    private void createAnimator(final boolean in) {
        int[] origin = new int[2];
        mThumbnailImageView.getLocationInWindow(origin);

        Rect thumbnailBounds = new Rect(origin[0], origin[1],
                origin[0] + mThumbnailImageView.getWidth(),
                origin[1] + mThumbnailImageView.getHeight());
        Rect fullBounds = new Rect(mFullImageView.getLeft(), mFullImageView.getTop(),
                mFullImageView.getRight(), mFullImageView.getBottom());

        Matrix thumbnailMatrix = mThumbnailImageView.getImageMatrix();
        Matrix fullMatrix = new Matrix();

        fullMatrix.setRectToRect(new RectF(getDrawableIntrinsicBounds(mFullImageView.getDrawable())),
                new RectF(0, 0, fullBounds.width(), fullBounds.height()),
                Matrix.ScaleToFit.CENTER);

        mFullImageView.setScaleType(ImageView.ScaleType.MATRIX);
        mFullImageView.setImageMatrix(in ? thumbnailMatrix : fullMatrix);
        mFullImageView.post(new Runnable() {
            @Override
            public void run() {
                if (in) {
                    mThumbnailImageView.setVisibility(View.INVISIBLE);
                }
                mFullImageView.setVisibility(View.VISIBLE);
            }
        });

        Animator boundsAnimator = ObjectAnimator.ofObject(mFullImageView, BOUNDS,
                new RectEvaluator(),
                in ? thumbnailBounds : fullBounds,
                in ? fullBounds : thumbnailBounds);
        Animator matrixAnimator = ObjectAnimator.ofObject(mFullImageView, IMAGE_MATRIX,
                new MatrixEvaluator(),
                in ? thumbnailMatrix : fullMatrix,
                in ? fullMatrix : thumbnailMatrix);

        final Runnable resetRunnable = new Runnable() {
            @Override
            public void run() {
                if (!in) {
                    mFullImageView.setVisibility(View.INVISIBLE);
                    mThumbnailImageView.setVisibility(View.VISIBLE);
                }
                mFullImageView.requestLayout();
                mFullImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            }
        };

        AnimatorSet animator = new AnimatorSet();
        animator.playTogether(boundsAnimator, matrixAnimator);
        animator.setDuration(800);
        animator.setInterpolator(new DecelerateInterpolator(3.f));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                resetRunnable.run();
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                resetRunnable.run();
            }
        });
        animator.start();
    }

}
