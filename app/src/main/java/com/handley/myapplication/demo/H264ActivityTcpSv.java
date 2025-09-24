package com.handley.myapplication.demo;

import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import com.handley.myapplication.R;

// 演示 MyVideoClient 向 MyVideoServer 发送(含私有协议头的)文件数据流，解码播放。渲染到 SurfaceView 上。
public class H264ActivityTcpSv extends H264ActivityTcpBase implements SurfaceHolder.Callback {

    private Surface surface;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SurfaceView surfaceView = findViewById(R.id.surface_view);
        surfaceView.getHolder().addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        Log.i(TAG, "surfaceCreated");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged: " + width + "x" + height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "surfaceDestroyed");
    }

    @Override
    protected Surface getSurface(int width, int height) {
        return surface;
    }
}