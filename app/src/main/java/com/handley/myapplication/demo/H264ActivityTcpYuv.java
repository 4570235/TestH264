package com.handley.myapplication.demo;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import com.handley.myapplication.common.Utils;
import java.io.File;

// 演示 MyVideoClient 向 MyVideoServer 发送 dump.h264(含私有协议头) 文件数据流。解码成 yuv420 数据保存成 jpg 文件。
public class H264ActivityTcpYuv extends H264ActivityTcpBase {

    private int imageAvailableIndex = 0;
    private int saveFileIndex = 0;
    private ImageReader imageReader;
    private HandlerThread imageThread;

    @Override
    protected Surface getSurface(int width, int height) {
        // 创建ImageReader获取YUV数据
        imageThread = new HandlerThread("ImageThread");
        imageThread.start();
        Handler imageThreadHandler = new Handler(imageThread.getLooper());
        imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4);
        imageReader.setOnImageAvailableListener(reader -> {
            Log.i(TAG, "onImageAvailable() frameIndex=" + (++imageAvailableIndex));
            try (Image image = imageReader.acquireLatestImage()) { // 自动关闭
                if (image == null) {
                    Log.w(TAG, "onImageAvailable() image == null");
                    return;
                }
                if (imageAvailableIndex % 20 == 0 && saveFileIndex < 50) {
                    File file = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                            "frame_" + (++saveFileIndex) + ".jpg");
                    Utils.saveImageAsJpeg(image, file);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, imageThreadHandler);
        return imageReader.getSurface();
    }

    @Override
    protected synchronized void release() {
        super.release();

        // 停止ImageReader
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (imageThread != null) {
            imageThread.quitSafely();
        }
    }
}