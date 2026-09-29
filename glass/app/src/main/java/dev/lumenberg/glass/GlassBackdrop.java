package dev.lumenberg.glass;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** A cached backdrop: glass samples the wallpaper, never its own text or icons. */
final class GlassBackdrop extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    Bitmap full, frosted, photo;
    private int builtWidth, builtHeight;

    GlassBackdrop(Context context) { super(context); }

    void setPhoto(Bitmap bitmap) {
        photo = bitmap;
        builtWidth = 0;
        rebuild(getWidth(), getHeight());
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { rebuild(w, h); }

    private void rebuild(int w, int h) {
        if (w <= 0 || h <= 0 || (w == builtWidth && h == builtHeight)) return;
        builtWidth = w; builtHeight = h;
        Bitmap previous = full, oldFrosted = frosted;
        full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(full);
        if (photo != null) {
            float scale = Math.max((float) w / photo.getWidth(), (float) h / photo.getHeight());
            float pw = photo.getWidth() * scale, ph = photo.getHeight() * scale;
            c.drawBitmap(photo, null, new RectF((w-pw)/2, (h-ph)/2, (w+pw)/2, (h+ph)/2), paint);
            paint.setColor(0x350A0819); c.drawRect(0, 0, w, h, paint);
        } else {
            paint.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{0xFF8985AD, 0xFF645A89, 0xFF302B4C, 0xFF181A2D}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(new RadialGradient(w*.08f, h*.16f, w*.92f,
                    new int[]{0xAEC4B9D5, 0x004A3D64}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
            // A quiet sculpted ribbon; generated once, with no continuous animation.
            Path ribbon = new Path();
            ribbon.moveTo(w*1.2f, h*.13f);
            ribbon.cubicTo(w*.30f, h*.14f, w*.39f, h*.42f, w*.97f, h*.52f);
            ribbon.cubicTo(w*1.23f, h*.56f, w*.64f, h*.73f, w*.17f, h*.98f);
            ribbon.lineTo(w*.51f, h*1.1f);
            ribbon.cubicTo(w*.76f, h*.75f, w*1.68f, h*.61f, w*1.25f, h*.40f);
            ribbon.cubicTo(w*.77f, h*.27f, w*.62f, h*.27f, w*1.3f, h*.19f);
            ribbon.close();
            paint.setShader(new LinearGradient(w*.32f, h*.15f, w*1.1f, h*.8f,
                    new int[]{0xFFC3B5CE, 0xFF7B718F, 0xFF44395E, 0xFF8C7B9D}, null, Shader.TileMode.CLAMP));
            c.drawPath(ribbon, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f);
            paint.setColor(0x50EDE4F3); c.drawPath(ribbon, paint); paint.setStyle(Paint.Style.FILL);
        }
        paint.setShader(null);
        // Blur at reduced resolution once. No per-frame CPU readback or GPU blur dependency.
        int bw = Math.max(24, w / 7), bh = Math.max(24, h / 7);
        frosted = Bitmap.createScaledBitmap(full, bw, bh, true);
        frosted = blur(frosted, 4);
        if (previous != null) previous.recycle();
        if (oldFrosted != null) oldFrosted.recycle();
    }

    private static Bitmap blur(Bitmap input, int radius) {
        int w = input.getWidth(), h = input.getHeight();
        int[] a = new int[w*h], b = new int[w*h], result = new int[w*h];
        input.getPixels(a, 0, w, 0, 0, w, h);
        int count = radius*2+1;
        for (int y=0; y<h; y++) for (int x=0; x<w; x++) {
            int r=0,g=0,bl=0;
            for (int k=-radius; k<=radius; k++) {
                int color=a[y*w+Math.max(0,Math.min(w-1,x+k))];
                r+=(color>>16)&255; g+=(color>>8)&255; bl+=color&255;
            }
            b[y*w+x]=0xFF000000|(r/count<<16)|(g/count<<8)|(bl/count);
        }
        for (int y=0; y<h; y++) for (int x=0; x<w; x++) {
            int r=0,g=0,bl=0;
            for (int k=-radius; k<=radius; k++) {
                int color=b[Math.max(0,Math.min(h-1,y+k))*w+x];
                r+=(color>>16)&255; g+=(color>>8)&255; bl+=color&255;
            }
            result[y*w+x]=0xFF000000|(r/count<<16)|(g/count<<8)|(bl/count);
        }
        input.setPixels(result,0,w,0,0,w,h);
        return input;
    }

    @Override protected void onDraw(Canvas c) {
        if (full != null) c.drawBitmap(full, null, new Rect(0,0,getWidth(),getHeight()), paint);
    }
}
