package dev.lumenberg.glass;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/** Rounded, refracted wallpaper plus a dark readability layer and specular edge. */
final class GlassSurface extends FrameLayout {
    private final GlassBackdrop backdrop;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();
    private final RectF bounds = new RectF();
    private final int[] position = new int[2], origin = new int[2];
    private final float radius, density;
    private final boolean dense;
    private boolean observeChildHold, holdTriggered;
    private float touchX, touchY;
    private final Runnable hold = () -> {
        if (performLongClick()) {
            holdTriggered=true;
            MotionEvent cancel=MotionEvent.obtain(0,android.os.SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,touchX,touchY,0);
            super.dispatchTouchEvent(cancel);cancel.recycle();
        }
    };

    GlassSurface(Context context, GlassBackdrop backdrop, float corners, boolean dense) {
        super(context); this.backdrop = backdrop; this.dense = dense;
        density=getResources().getDisplayMetrics().density; radius=corners*density;
        setWillNotDraw(false);
        setElevation(10*density);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),radius);
            }
        });
        setClipToOutline(true);
    }

    void watchProviderLongPress(boolean watch) { observeChildHold=watch; }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (observeChildHold) {
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchX=event.getX();touchY=event.getY();holdTriggered=false;
                    postDelayed(hold,ViewConfiguration.getLongPressTimeout());break;
                case MotionEvent.ACTION_MOVE:
                    float slop=ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if(Math.abs(event.getX()-touchX)>slop||Math.abs(event.getY()-touchY)>slop) removeCallbacks(hold);break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(hold);if(holdTriggered) return true;break;
                case MotionEvent.ACTION_POINTER_DOWN:removeCallbacks(hold);break;
            }
        }
        return holdTriggered||super.dispatchTouchEvent(event);
    }
    @Override protected void onDetachedFromWindow() {removeCallbacks(hold);super.onDetachedFromWindow();}

    @Override protected void onDraw(Canvas canvas) {
        int w=getWidth(),h=getHeight();
        bounds.set(0,0,w,h); clip.reset(); clip.addRoundRect(bounds,radius,radius,Path.Direction.CW);
        canvas.save(); canvas.clipPath(clip);
        if (backdrop.frosted != null && backdrop.full != null) {
            getLocationInWindow(position); backdrop.getLocationInWindow(origin);
            float x=position[0]-origin[0], y=position[1]-origin[1];
            // Sample the same region as the background, with a restrained optical magnification.
            canvas.save(); canvas.scale(1.018f,1.018f,w*.5f,h*.5f);
            canvas.drawBitmap(backdrop.frosted,null,
                    new RectF(-x,-y,backdrop.getWidth()-x,backdrop.getHeight()-y),paint);
            canvas.restore();
        }
        paint.setColor(dense ? 0xBD1A1729 : 0x721C172D);
        canvas.drawRect(bounds,paint);
        paint.setShader(new LinearGradient(0,0,w*.4f,h,
                new int[]{0x2DEEEAF8,0x0FFFFFFF,0x03FFFFFF,0x18D4CFEA},
                new float[]{0,.20f,.70f,1},Shader.TileMode.CLAMP));
        canvas.drawRect(bounds,paint); paint.setShader(null);
        canvas.restore();
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density);
        paint.setShader(new LinearGradient(0,0,w,h,
                new int[]{0xA3FFFFFF,0x32FFFFFF,0x08FFFFFF,0x46E6DFFA},null,Shader.TileMode.CLAMP));
        bounds.inset(density*.5f,density*.5f);
        canvas.drawRoundRect(bounds,radius,radius,paint);
        paint.setShader(null); paint.setStyle(Paint.Style.FILL);
    }
}
