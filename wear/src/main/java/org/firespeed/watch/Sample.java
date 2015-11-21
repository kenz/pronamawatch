/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.firespeed.watch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.SurfaceHolder;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class Sample extends CanvasWatchFaceService {
    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<Sample.Engine> mWeakReference;

        public EngineHandler(Sample.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            Sample.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBitmapPaint;
        Paint mDrawPaint;
        Paint mDrawStrokePaint;
        boolean mAmbient;
        private Bitmap mBackground;
        private Bitmap mHour;
        private Bitmap mMinute;
        private int mWidth;
        private int mHeight;
        private float mCenterX;
        private float mCenterY;
        private float mSecLength;
        private float mBackgroundTop;
        private float mBackgroundLeft;
        private float mHourLeft;
        private float mHourTop;
        private float mMinuteLeft;
        private float mMinuteTop;
        private float mHoleRadius;
        private float mScale;
        private Matrix mMatrix;


        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(Sample.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(true)
                    .setAcceptsTapEvents(true)
                    .build());

            Resources resources = Sample.this.getResources();

            mBitmapPaint = new Paint();
            mBitmapPaint.setFilterBitmap(true);

            mDrawPaint = new Paint();
            mDrawPaint.setColor(resources.getColor(R.color.stroke));

            mDrawStrokePaint = new Paint();
//            mDrawStrokePaint.setStrokeWidth(resources.getDimension(R.dimen.analog_hand_stroke));
            mDrawStrokePaint.setAntiAlias(true);
//            mDrawStrokePaint.setStyle(Paint.Style.STROKE);
            mDrawStrokePaint.setColor(resources.getColor(R.color.analog_hands));

            mDrawPaint.setAntiAlias(true);
            mDrawPaint.setStrokeCap(Paint.Cap.ROUND);
            mMatrix = new Matrix();
            mTime = new Time();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mDrawPaint.setAntiAlias(!inAmbientMode);
                    mDrawStrokePaint.setAntiAlias(!inAmbientMode);
                    mBitmapPaint.setFilterBitmap(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = Sample.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    break;
            }
            invalidate();
        }

        private static final float DESIGNED_SIZE = 512f;

        private Bitmap createScaledBitmap(Resources resources, int id){
            Bitmap original = ((BitmapDrawable)(resources.getDrawable(id))).getBitmap();
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(original, (int)(original.getWidth() * mScale),(int)(original.getHeight() * mScale), true);
            original.recycle();
            return scaledBitmap;
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mTime.setToNow();
            boolean isSizeChanged = canvas.getWidth() != mWidth || canvas.getHeight() != mHeight;
            if(isSizeChanged){
                mWidth = canvas.getWidth();
                mHeight  = canvas.getHeight();
                mCenterX = canvas.getWidth() / 2f;
                mCenterY = canvas.getHeight() / 2f;
                int longSize = Math.max(canvas.getWidth(), canvas.getHeight());
                mScale = longSize / DESIGNED_SIZE;
                mBackgroundLeft = (mWidth - longSize) / 2f;
                mBackgroundTop = (mHeight - longSize) / 2f;

                mSecLength = (int)(200f * mScale);

                mHoleRadius = mScale * 12f;

                mHourLeft = 158f * mScale + mBackgroundLeft;
                mHourTop = 40f * mScale + mBackgroundTop;
                mMinuteLeft = 234f * mScale + mBackgroundLeft;
                mMinuteTop = 34f * mScale + mBackgroundTop;
                mDrawPaint.setTextSize(40f);
                mDrawStrokePaint.setTextSize(40f);

            }
            if(isSizeChanged
                    || mBackground == null || mBackground.isRecycled()
                    || mHour== null || mHour.isRecycled()
                    || mMinute== null || mMinute.isRecycled()
                    ){
                Resources resouces = getResources();
                mBackground = createScaledBitmap(resouces, R.drawable.background);
                mHour= createScaledBitmap(resouces, R.drawable.hour);
                mMinute= createScaledBitmap(resouces, R.drawable.minute);
            }

            // Draw the background.
                canvas.drawBitmap(mBackground, mBackgroundLeft, mBackgroundTop, mBitmapPaint);

            float hourRotate = (mTime.hour+ mTime.minute / 60f ) * 30;
            mMatrix.setTranslate(mHourLeft, mHourTop);
            mMatrix.postRotate(hourRotate, mCenterX, mCenterY);
            canvas.drawBitmap(mHour, mMatrix, mBitmapPaint);

            float minuteRotate = mTime.minute * 6;
            mMatrix.setTranslate(mMinuteLeft, mMinuteTop);
            mMatrix.postRotate(minuteRotate, mCenterX, mCenterY);
            canvas.drawBitmap(mMinute, mMatrix, mBitmapPaint);

            if (!mAmbient) {
                float secRot = mTime.second / 30f * (float) Math.PI;
                float secX = (float) Math.sin(secRot) * mSecLength;
                float secY = (float) -Math.cos(secRot) * mSecLength;
                //canvas.drawLine(mCenterX, mCenterY, mCenterX + secX, mCenterY + secY, mDrawPaint);
                mDrawPaint.setStrokeWidth(1f);
                canvas.drawText("ぷ", mCenterX + secX, mCenterY + secY, mDrawPaint);
            }

        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            Sample.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            Sample.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
