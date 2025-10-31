package com.handley.myapplication.demo;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.handley.myapplication.R;
import com.handley.myapplication.common.MediaMessageHeader;
import com.handley.myapplication.common.MyFrame;
import com.handley.myapplication.common.Utils;
import com.handley.myapplication.tcp.MyClient;
import com.handley.myapplication.tcp.MyServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

// 演示 MyVideoClient 向 MyVideoServer 发送(含私有协议头的)文件数据流，解码播放。
public abstract class H264ActivityTcpBase extends AppCompatActivity {

    protected static final String TAG = Utils.TAG + "H264ActivityTcpBase";
    private final BlockingQueue<MyFrame> frameQueue = new LinkedBlockingQueue<>(25); // 帧缓冲队列
    private Button videoBtn, audioBtn;
    private MyServer myServer;
    private MyClient myClient;
    private MediaCodec mediaCodec;
    private long startTime = Long.MIN_VALUE; // 播放开始时间（毫秒）
    private Thread decodeThread;
    private volatile boolean decodeThreadRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        videoBtn = findViewById(R.id.video_btn);
        audioBtn = findViewById(R.id.audio_btn);
        videoBtn.setVisibility(View.VISIBLE);
        audioBtn.setVisibility(View.GONE);

        initTcp();

        startDecodeThread();

        Log.i(TAG, "onCreate()");
    }

    private void initTcp() {
        final int port = 23334;

        // 点击启动客户端发送文件
        videoBtn.setOnClickListener(v -> {
            myClient = new MyClient(this, "dump.h264", port);
            myClient.start();
            videoBtn.setEnabled(false);// 防止重复点击
        });

        // 创建并启动服务器
        myServer = new MyServer((frame) -> {
            // 处理接收到的帧数据

            if (frame.header.type != MediaMessageHeader.H264) {
                Log.e(TAG, "onFrameReceived() frame type not H264");
                return;
            }
            // 注意：收到的 frame.header.timestamp 单位是毫秒

            if (startTime == Long.MIN_VALUE) {
                long currentTime = System.nanoTime() / 1000000;
                startTime = currentTime - frame.header.timestamp;
                Log.i(TAG, "onFrameReceived() init currentTime=" + currentTime + " pts=" + frame.header.timestamp
                        + " startTime=" + startTime);
            }

            // 将帧存入队列，视频帧不能丢失，否则后续解不出来。要丢就得一直丢到下一个i帧。
            try {
                frameQueue.put(frame);
            } catch (InterruptedException e) {
                Log.e(TAG, "onFrameReceived() frameQueue.put() interrupted");
            }
        }, port);
        myServer.start();
    }

    // 启动解码播放线程
    private void startDecodeThread() {
        decodeThreadRunning = true;
        decodeThread = new Thread(() -> {
            while (decodeThreadRunning && !Thread.interrupted()) {
                try {
                    MyFrame frame = frameQueue.poll(50, TimeUnit.MILLISECONDS);
                    if (frame == null) {
                        continue;
                    }

                    // 1. 控制解码时机
                    controlSpeed(frame.header.timestamp, 30);

                    // 2. 处理H264数据
                    decodeData(frame.frameData, frame.header.timestamp);
                    //Log.v(TAG, "decode pts=" + frame.header.timestamp);

                    // 3. 处理解码输出
                    drainOutput();
                } catch (Exception e) {
                    Log.e(TAG, "DecodeThread ex=" + e.getMessage());
                }
            }
        }, "DecodeThread");
        decodeThread.start();
    }

    // 处理H264数据
    private synchronized void decodeData(byte[] data, long pts) {
        byte[] sps = null, pps = null;
        try (InputStream is = new ByteArrayInputStream(data)) {
            H264StreamReader streamReader = new H264StreamReader(is);
            ByteArrayOutputStream currentFrame = new ByteArrayOutputStream();
            while (true) {
                byte[] nal = streamReader.readNextNalUnit();
                if (nal == null) {
                    break; // 没有更多数据
                }
                if (nal.length < 1) {
                    continue;
                }
                int nalType = nal[0] & 0x1F;
                Log.d(TAG, "decodeData() nalType=" + nalType + " len=" + nal.length + " pts=" + pts);
                switch (nalType) {
                    case 7: // SPS
                        sps = nal;
                        break;
                    case 8: // PPS
                        pps = nal;
                        break;
                    case 6: // SEI
                        currentFrame.write(new byte[]{0, 0, 0, 1});
                        currentFrame.write(nal);
                        break;
                    case 5: // IDR
                        if (sps != null && pps != null) {
                            // 更新配置
                            configMediaCodec(sps, pps);
                        }
                        currentFrame.write(new byte[]{0, 0, 0, 1});
                        currentFrame.write(nal);
                        submitFrame(currentFrame.toByteArray(), pts, MediaCodec.BUFFER_FLAG_KEY_FRAME);
                        currentFrame.reset();
                        break;
                    case 1: // 非IDR Slice
                        currentFrame.write(new byte[]{0, 0, 0, 1});
                        currentFrame.write(nal);
                        submitFrame(currentFrame.toByteArray(), pts, 0);
                        currentFrame.reset();
                        break;
                    default:
                        // 其他NAL类型
                        break;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "H264 processing error", e);
        }
    }

    private void submitFrame(byte[] frameData, long presentationTimeUs, int flag) {
        if (mediaCodec == null) {
            Log.e(TAG, "submitFrame() mediaCodec = null");
            return;
        }
        try {
            int inputBufferIndex;
            while ((inputBufferIndex = mediaCodec.dequeueInputBuffer(10000)) == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Thread.sleep(2);
            }
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(frameData);
                    Log.v(TAG, "submitFrame() queueInputBuffer inputBufferIndex=" + inputBufferIndex + " size="
                            + frameData.length + " pts=" + presentationTimeUs + " flag=" + flag);
                    mediaCodec.queueInputBuffer(inputBufferIndex, 0, frameData.length, presentationTimeUs, flag);
                }
            } else {
                Log.e(TAG, "submitFrame() queueInputBuffer inputBufferIndex=" + inputBufferIndex + " pts="
                        + presentationTimeUs + " flag=" + flag);
            }
        } catch (Exception e) {
            Log.e(TAG, "drainOutput() error=", e);
        }
    }

    private synchronized void drainOutput() {
        if (mediaCodec == null) {
            Log.e(TAG, "drainOutput() mediaCodec = null");
            return;
        }
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int outputBufferIndex;
        while (true) {
            outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // 没有可用的输出缓冲区，稍后再试
                break;
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // 处理输出格式变化
                handleOutputFormatChanged();
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // 输出缓冲区已更改，通常不需要特殊处理
                Log.d(TAG, "drainOutput() Output buffers changed");
            } else if (outputBufferIndex >= 0) {
                // 控制渲染时机
                controlSpeed(bufferInfo.presentationTimeUs, 2);

                // 渲染帧
                Log.v(TAG, "drainOutput() releaseOutputBuffer pts=" + bufferInfo.presentationTimeUs);
                mediaCodec.releaseOutputBuffer(outputBufferIndex, true);

                // 检查是否结束
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.i(TAG, "drainOutput() Output EOS");
                    break;
                }
            } else {
                // 处理其他未知返回值
                Log.w(TAG, "drainOutput() Unexpected outputBufferIndex: " + outputBufferIndex);
            }
        }
    }

    // 处理输出格式变化
    private void handleOutputFormatChanged() {
        // 获取新的输出格式
        MediaFormat newFormat = mediaCodec.getOutputFormat();

        // 记录新的视频参数
        int width = newFormat.getInteger(MediaFormat.KEY_WIDTH);
        int height = newFormat.getInteger(MediaFormat.KEY_HEIGHT);

        // 获取颜色空间信息
        int colorStandard = newFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD, 0);
        int colorRange = newFormat.getInteger(MediaFormat.KEY_COLOR_RANGE, 0);

        Log.i(TAG,
                "handleOutputFormatChanged() wxh=" + width + "x" + height + " colorStandard=" + colorStandardToString(
                        colorStandard) + " colorRange=" + colorRangeToString(colorRange));
    }

    // 颜色标准转换方法
    private String colorStandardToString(int standard) {
        switch (standard) {
            case MediaFormat.COLOR_STANDARD_BT709:
                return "BT.709";
            case MediaFormat.COLOR_STANDARD_BT601_PAL:
                return "BT.601 PAL";
            case MediaFormat.COLOR_STANDARD_BT601_NTSC:
                return "BT.601 NTSC";
            case MediaFormat.COLOR_STANDARD_BT2020:
                return "BT.2020";
            default:
                return "Unspecified";
        }
    }

    // 颜色范围转换方法
    private String colorRangeToString(int range) {
        switch (range) {
            case MediaFormat.COLOR_RANGE_LIMITED:
                return "Limited (TV)";
            case MediaFormat.COLOR_RANGE_FULL:
                return "Full";
            default:
                return "Unspecified";
        }
    }

    private void configMediaCodec(@NonNull byte[] sps, @NonNull byte[] pps) {
        // 检查是否需要重新配置
        boolean needReconfig = false;
        if (mediaCodec != null) {
            // 这里可以添加更精确的判断：比较新旧SPS/PPS是否相同
            // 简单起见，只要有新的SPS/PPS就重新配置
            Log.i(TAG, "configMediaCodec() 检测到新的SPS/PPS，准备重新配置解码器");
            needReconfig = true;

            // 释放旧的解码器
            try {
                mediaCodec.stop();
                mediaCodec.release();
                mediaCodec = null;
                Log.i(TAG, "configMediaCodec() 旧解码器已释放");
            } catch (Exception e) {
                Log.e(TAG, "configMediaCodec() 释放旧解码器失败: ", e);
                mediaCodec = null;
            }
        }

        final boolean software = false; // 是否使用软件解码器
        final String MIME = "video/avc";
        try {
            mediaCodec = software ? Utils.findSoftwareDecoder(MIME) : MediaCodec.createDecoderByType(MIME);
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "configMediaCodec() failed: ", e);
            return;
        }

        // 从SPS中解析视频宽高
        int[] dimensions = Utils.parseSps(sps);
        int width = dimensions[0];
        int height = dimensions[1];
        Log.i(TAG, "configMediaCodec() soft=" + software + " dimensions=" + width + "x" + height
                + " reconfig=" + needReconfig);

        // 创建并配置MediaFormat
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(Utils.addStartCode(sps)));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(Utils.addStartCode(pps)));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
        mediaCodec.configure(format, getSurface(width, height), null, 0);
        try {
            mediaCodec.start();
            Log.i(TAG, "configMediaCodec() 解码器启动成功");
        } catch (IllegalStateException e) {
            Log.e(TAG, "configMediaCodec() start() failed: ", e);
            return;
        }

        // 提交 sps pps 配置帧
        ByteArrayOutputStream currentFrame = new ByteArrayOutputStream();
        try {
            currentFrame.write(new byte[]{0, 0, 0, 1});
            currentFrame.write(sps);
            currentFrame.write(new byte[]{0, 0, 0, 1});
            currentFrame.write(pps);
            submitFrame(currentFrame.toByteArray(), 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
        } catch (IOException e) {
            Log.e(TAG, "configMediaCodec() write config frame failed: ", e);
        }

        // 如果是重新配置，可能需要重置播放时间
        if (needReconfig) {
            startTime = Long.MIN_VALUE;
            Log.i(TAG, "configMediaCodec() 播放时间已重置");
        }
    }

    protected abstract Surface getSurface(int width, int height);

    // 控制速度(pts 时间戳ms，ahead 提前多少ms)
    private void controlSpeed(long pts, long ahead) {
        long targetTime = startTime + pts;
        long currentTime = System.nanoTime() / 1000000;
        long sleepTime = targetTime - currentTime - ahead;
        Log.v(TAG, "controlSpeed pts=" + pts + " ahead=" + ahead + " targetTime=" + targetTime + " currentTime="
                + currentTime + " sleepTime=" + sleepTime);

        // 如果太快，等待一段时间
        if (sleepTime > 1) {
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        release();
        finish();//此类只为了演示解码渲染，不考虑 ui 交互。
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        release();
    }

    protected synchronized void release() {
        // 停止 tcp
        if (myServer != null) {
            myServer.stop();
            myServer = null;
        }
        if (myClient != null) {
            myClient.stop();
            myClient = null;
        }

        // 释放解码器
        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
        }

        // 停止线程
        decodeThreadRunning = false;
        if (decodeThread != null) {
            decodeThread.interrupt();
            try {
                decodeThread.join(200);
            } catch (InterruptedException ignored) {
            }
        }

        Log.i(TAG, "release()");
    }
}