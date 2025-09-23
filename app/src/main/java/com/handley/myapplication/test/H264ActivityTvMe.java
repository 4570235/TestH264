package com.handley.myapplication.test;

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
import com.handley.myapplication.common.MediaMessageHeader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

// 使用 MediaExtractor + MediaCodec 解码 test.mp4 文件，渲染到 TextureView 上
public class H264ActivityTvMe extends AppCompatActivity implements TextureView.SurfaceTextureListener {

    private static final String TAG = "H264ActivityTvMe";
    // dump 码流到文件
    private static final int MAX_DUMP_FRAME_COUNT = 0;
    private FileOutputStream outputStream;
    private FileChannel outputChannel;

    private TextureView textureView;
    private MediaCodec mediaCodec;
    private MediaExtractor mediaExtractor;
    private Surface outputSurface;
    private HandlerThread decoderThread;
    private volatile boolean isDecoding = false;
    private File h264File;


    private static int findVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_textureview_main);
        textureView = findViewById(R.id.texture_view);
        textureView.setSurfaceTextureListener(this);
        h264File = AssetsFileCopier.copyAssetToExternalFilesDir(this, "test2.mp4");
        Log.i(TAG, "onCreate()");
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

    private void decodeFrames() {
        if (MAX_DUMP_FRAME_COUNT > 0) {
            try {
                File outputFile = new File(getExternalFilesDir(null), "fake-dump2.h264");
                outputStream = new FileOutputStream(outputFile);
                outputChannel = outputStream.getChannel();
            } catch (FileNotFoundException e) {
                Log.e(TAG, "Failed to create output file", e);
            }
        }
        int dumpFrameCount = 0;

        final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (isDecoding) {
            // 将数据送入解码器输入缓冲区
            int inputBufferIndex = mediaCodec.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    int sampleSize = mediaExtractor.readSampleData(inputBuffer, 0);
                    if (sampleSize > 0) {

                        // ==== 新增：复制Buffer数据到文件 ====
                        if (outputChannel != null && dumpFrameCount < MAX_DUMP_FRAME_COUNT) {
                            try {
                                // 0. 写入头部
                                ByteBuffer header = ByteBuffer.allocate(MediaMessageHeader.SIZE);
                                header.order(ByteOrder.LITTLE_ENDIAN);
                                header.putInt(MediaMessageHeader.MAGIC);
                                header.put(MediaMessageHeader.H264);
                                header.putLong(mediaExtractor.getSampleTime());
                                header.putInt(0);
                                header.putInt(sampleSize);
                                header.position(0);
                                int w1 = outputChannel.write(header);

                                // 1. 保存原始Buffer的位置状态
                                int originalPosition = inputBuffer.position();
                                int originalLimit = inputBuffer.limit();

                                // 2. 设置Buffer范围（仅包含有效数据）
                                inputBuffer.position(0);
                                inputBuffer.limit(sampleSize);

                                // 3. 复制数据到文件
                                int w2 = outputChannel.write(inputBuffer); // 自动从position写到limit

                                // 4. 恢复原始Buffer状态
                                inputBuffer.position(originalPosition);
                                inputBuffer.limit(originalLimit);

                                Log.d(TAG, "w1=" + w1 + " w2=" + w2 + " sampleSize=" + sampleSize + " pts="
                                        + mediaExtractor.getSampleTime());
                                dumpFrameCount++;
                            } catch (IOException e) {
                                Log.e(TAG, "Error writing to file", e);
                            }
                        }
                        // ==== 结束新增 ====

                        Log.d(TAG, "queueInputBuffer inputBufferIndex=" + inputBufferIndex + " sampleSize=" + sampleSize
                                + " pts=" + mediaExtractor.getSampleTime() + " flag="
                                + mediaExtractor.getSampleFlags());
                        mediaCodec.queueInputBuffer(inputBufferIndex, 0, sampleSize, mediaExtractor.getSampleTime(),
                                mediaExtractor.getSampleFlags());
                        mediaExtractor.advance();
                    } else {
                        // 文件结束
                        mediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        isDecoding = false;

                        // ==== 新增：关闭输出流 ====
                        try {
                            if (outputChannel != null) {
                                outputChannel.close();
                            }
                            if (outputStream != null) {
                                outputStream.close();
                            }
                        } catch (IOException e) {
                            Log.e(TAG, "Error closing stream", e);
                        }
                        // ==== 结束新增 ====
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
        Log.i(TAG, "onDestroy()");
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