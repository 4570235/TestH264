package com.handley.myapplication.video;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import androidx.appcompat.app.AppCompatActivity;
import com.handley.myapplication.R;
import com.handley.myapplication.common.AssetsFileCopier;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

// 使用 MediaExtractor + MediaCodec 解码 test.mp4 文件，渲染到 TextureView 上
public class H264ActivityTvMe extends AppCompatActivity implements TextureView.SurfaceTextureListener {

    private static final String TAG = "H264ActivityTvMe";

    private TextureView textureView;
    private MediaCodec mediaCodec;
    private MediaExtractor mediaExtractor;
    private Surface outputSurface;
    private HandlerThread decoderThread;
    private volatile boolean isDecoding = false;
    private File h264File;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_textureview_main);
        textureView = findViewById(R.id.texture_view);
        textureView.setSurfaceTextureListener(this);
        h264File = AssetsFileCopier.copyAssetToExternalFilesDir(this, "test.mp4");
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        // 创建Surface用于MediaCodec输出
        outputSurface = new Surface(surfaceTexture);

        // 初始化解码线程
        decoderThread = new HandlerThread("DecoderThread");
        decoderThread.start();
        Handler decoderHandler = new Handler(decoderThread.getLooper());

        // 开始解码
        decoderHandler.post(this::startDecoding);
    }

    private void startDecoding() {
        try {
            // 初始化MediaExtractor
            mediaExtractor = new MediaExtractor();
            mediaExtractor.setDataSource(h264File.getAbsolutePath());

            // 查找视频轨道
            int videoTrackIndex = findVideoTrack(mediaExtractor);
            if (videoTrackIndex < 0) {
                throw new IOException("No video track found");
            }

            // 选择视频轨道
            mediaExtractor.selectTrack(videoTrackIndex);
            MediaFormat format = mediaExtractor.getTrackFormat(videoTrackIndex);

            // 创建H264解码器
            mediaCodec = MediaCodec.createDecoderByType("video/avc");
            mediaCodec.configure(format, outputSurface, null, 0);
            mediaCodec.start();

            isDecoding = true;
            decodeFrames();
        } catch (IOException e) {
            Log.e(TAG, "Decoder initialization failed", e);
        }
    }

    private int findVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                return i;
            }
        }
        return -1;
    }

    private void decodeFrames() {
        final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (isDecoding) {
            // 将数据送入解码器输入缓冲区
            int inputBufferIndex = mediaCodec.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    int sampleSize = mediaExtractor.readSampleData(inputBuffer, 0);
                    if (sampleSize > 0) {
                        mediaCodec.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                sampleSize,
                                mediaExtractor.getSampleTime(),
                                mediaExtractor.getSampleFlags()
                        );
                        mediaExtractor.advance();
                    } else {
                        // 文件结束
                        mediaCodec.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        );
                        isDecoding = false;
                    }
                }
            }

            // 处理解码器输出
            int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);
            if (outputBufferIndex >= 0) {
                // 渲染到Surface
                mediaCodec.releaseOutputBuffer(outputBufferIndex, true);

                // 检查是否结束
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    isDecoding = false;
                }
            } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // 稍后重试
            }
        }

        // 释放资源
        releaseResources();
    }

    private void releaseResources() {
        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
        }
        if (mediaExtractor != null) {
            mediaExtractor.release();
            mediaExtractor = null;
        }
        if (outputSurface != null) {
            outputSurface.release();
            outputSurface = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isDecoding = false;
        if (decoderThread != null) {
            decoderThread.quitSafely();
        }
    }

    // 其他TextureView回调方法
    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }
}