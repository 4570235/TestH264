package com.handley.myapplication;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

// 使用 MediaCodec 解码 test.h264 文件，渲染到 SurfaceView 上
public class H264ActivitySv extends AppCompatActivity implements SurfaceHolder.Callback {

    private static final String TAG = "H264ActivitySv";
    private static final String MIME_TYPE = "video/avc";
    private static final int FRAME_RATE = 25; // 假设帧率
    private static final long FRAME_INTERVAL_US = 1000000 / FRAME_RATE;

    private MediaCodec mediaCodec;
    private SurfaceView surfaceView;
    private Thread decoderThread;
    private File h264File;
    private volatile boolean isRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        surfaceView = findViewById(R.id.surface_view);
        surfaceView.getHolder().addCallback(this);
        h264File = AssetsFileCopier.copyAssetToExternalFilesDir(this, "test.h264");
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

    private void startDecoder(Surface surface) {
        try {
            // 1. 初始化MediaCodec
            final boolean soft = false;
            mediaCodec = soft ? Utils.findSoftwareDecoder(MIME_TYPE) : MediaCodec.createDecoderByType(MIME_TYPE);

            // 2. 从文件中提取SPS和PPS
            byte[][] spsPps = Utils.extractSpsPps(h264File);
            byte[] sps = spsPps[0];
            byte[] pps = spsPps[1];

            // 从SPS中解析视频宽度
            int[] dimensions = Utils.parseSps(sps);

            // 3. 创建并配置MediaFormat
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, dimensions[0], dimensions[1]);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);

            mediaCodec.configure(format, surface, null, 0);
            mediaCodec.start();

            // 4. 启动解码线程
            isRunning = true;
            decoderThread = new Thread(new DecoderRunnable(h264File));
            //decoderThread.setPriority(Thread.MAX_PRIORITY); // 设置高优先级
            decoderThread.start();
            Log.i(TAG, "startDecoder() soft=" + soft + " dimensions=" + dimensions[0] + "x" + dimensions[1]);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void stopDecoder() {
        isRunning = false;
        if (decoderThread != null) {
            try {
                decoderThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
        }
    }

    // 解码器工作线程
    private class DecoderRunnable implements Runnable {

        private final File h264File;
        private long startTimeNs = -1; // 播放开始时间（纳秒）
        private long frameCounter = 0; // 帧计数器

        public DecoderRunnable(File h264File) {
            this.h264File = h264File;
        }

        @Override
        public void run() {
            try (InputStream is = new BufferedInputStream(new FileInputStream(h264File))) {
                H264StreamReader streamReader = new H264StreamReader(is);

                boolean isWaitingForIDR = false;
                ByteArrayOutputStream currentFrame = new ByteArrayOutputStream();

                while (isRunning) {
                    // 计算当前帧应该显示的时间（微秒）
                    long presentationTimeUs = calculatePresentationTime();

                    byte[] nal = streamReader.readNextNalUnit();
                    if (nal == null) {
                        break; // 没有更多数据
                    }

                    if (nal.length < 1) {
                        continue;
                    }

                    int nalType = nal[0] & 0x1F;
                    //Log.v(TAG, "nalType=" + nalType + " isWaitingForIDR=" + isWaitingForIDR + " frameCounter=" + frameCounter + " presentationTimeUs=" + presentationTimeUs);
                    switch (nalType) {
                        case 7: // SPS
                            isWaitingForIDR = true;
                            currentFrame.write(new byte[]{0, 0, 0, 1});
                            currentFrame.write(nal);
                            break;

                        case 8: // PPS
                            if (isWaitingForIDR) {
                                currentFrame.write(new byte[]{0, 0, 0, 1});
                                currentFrame.write(nal);
                            }
                            break;

                        case 6: // SEI
                            if (isWaitingForIDR) {
                                currentFrame.write(new byte[]{0, 0, 0, 1});
                                currentFrame.write(nal);
                            }
                            break;

                        case 5: // IDR
                            if (isWaitingForIDR) {
                                currentFrame.write(new byte[]{0, 0, 0, 1});
                                currentFrame.write(nal);
                                submitFrame(currentFrame.toByteArray(), presentationTimeUs);
                                currentFrame.reset();
                                isWaitingForIDR = false;
                                frameCounter++;
                            } else {
                                // 提交单个NAL单元
                                submitSingleFrame(nal, presentationTimeUs);
                                frameCounter++;
                            }
                            break;

                        case 1: // 非IDR Slice
                            if (isWaitingForIDR) {
                                // 错误处理：预期IDR但遇到普通帧
                                currentFrame.reset();
                                isWaitingForIDR = false;
                            }
                            submitSingleFrame(nal, presentationTimeUs);
                            frameCounter++;
                            break;

                        default:
                            // 其他NAL类型（可选处理）
                            break;
                    }

                    // 控制播放速度
                    controlPlaybackSpeed(presentationTimeUs);

                    // 处理输出
                    drainOutput();
                }

                // 提交结束标志
                signalEndOfStream();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // 计算当前帧的呈现时间
        private long calculatePresentationTime() {
            if (startTimeNs == -1) {
                startTimeNs = System.nanoTime();
                return 0;
            }

            // 计算基于帧计数的时间
            return frameCounter * FRAME_INTERVAL_US;
        }

        // 控制播放速度
        private void controlPlaybackSpeed(long presentationTimeUs) {
            // 计算当前帧应该显示的时间点（纳秒）
            long targetTimeNs = startTimeNs + presentationTimeUs * 1000;

            // 计算当前系统时间
            long currentTimeNs = System.nanoTime();

            // 计算需要等待的时间（纳秒）
            long sleepTimeNs = targetTimeNs - currentTimeNs;

            // 如果播放太快，等待一段时间
            if (sleepTimeNs > 1000) { // 差异大于1微秒才等待
                try {
                    // 将纳秒转换为毫秒和纳秒
                    long sleepMs = sleepTimeNs / 1000000;
                    int sleepNs = (int) (sleepTimeNs % 1000000);

                    Thread.sleep(sleepMs, sleepNs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void submitSingleFrame(byte[] nal, long pts) {
            ByteBuffer frameData = ByteBuffer.allocate(nal.length + 4);
            frameData.putInt(0x00000001); // 统一使用4字节起始码
            frameData.put(nal);
            frameData.flip();
            submitFrame(frameData.array(), pts);
        }

        private void submitFrame(byte[] frameData, long presentationTimeUs) {
            try {
                int inputBufferIndex = mediaCodec.dequeueInputBuffer(10000);
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);
                    inputBuffer.clear();
                    inputBuffer.put(frameData);

                    mediaCodec.queueInputBuffer(inputBufferIndex, 0, frameData.length, presentationTimeUs, 0);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void drainOutput() {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex;

            while ((outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0)) >= 0) {
                // 检查渲染时间
                long renderTimeNs = startTimeNs + bufferInfo.presentationTimeUs * 1000;
                long currentTimeNs = System.nanoTime();

                // 如果渲染时间还没到，等待
                if (renderTimeNs > currentTimeNs) {
                    long sleepTimeNs = renderTimeNs - currentTimeNs;
                    if (sleepTimeNs > 1000) {
                        try {
                            Thread.sleep(sleepTimeNs / 1000000, (int) (sleepTimeNs % 1000000));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }

                // 渲染帧
                mediaCodec.releaseOutputBuffer(outputBufferIndex, true);
            }
        }

        private void signalEndOfStream() {
            int inputBufferIndex = mediaCodec.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                mediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }

            // 等待所有输出处理完成
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            while (isRunning) {
                int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);
                if (outputBufferIndex >= 0) {
                    mediaCodec.releaseOutputBuffer(outputBufferIndex, true);
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 格式变化处理
                } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break;
                }

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
    }
}