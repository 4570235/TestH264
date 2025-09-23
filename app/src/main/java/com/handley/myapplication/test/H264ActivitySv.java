package com.handley.myapplication.test;

import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import com.handley.myapplication.R;

// 使用 MediaCodec 解码 h264 文件，渲染到 SurfaceView 上
public class H264ActivitySv extends H264ActivityBase implements SurfaceHolder.Callback {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        SurfaceView surfaceView = findViewById(R.id.surface_view);
        surfaceView.getHolder().addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        startDecoder(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopDecoder();
    }
}