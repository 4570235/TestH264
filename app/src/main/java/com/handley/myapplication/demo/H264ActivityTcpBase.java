package com.handley.myapplication.demo;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
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
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

// 演示 MyVideoClient 向 MyVideoServer 发送(含私有协议头的)文件数据流，解码播放。
public abstract class H264ActivityTcpBase extends AppCompatActivity {

    protected static final String TAG = Utils.TAG + "H264ActivityTcpBase";
    private final BlockingQueue<MyFrame> frameQueue = new LinkedBlockingQueue<>(25); // 帧缓冲队列
    private Button videoBtn, audioBtn;
    private TextView tvPacketStats, tvFrameStats, tvQueueStats;
    private MyServer myServer;
    private MyClient myClient;
    private MediaCodec mediaCodec;
    private long startTime = Long.MIN_VALUE; // 播放开始时间（毫秒）
    private Thread decodeThread;
    private volatile boolean decodeThreadRunning = false;
    private Handler uiHandler;
    
    // 视频帧统计
    private long totalFrames = 0;
    private long iFrameCount = 0;
    private long pFrameCount = 0;
    private long lastFrameTime = 0;
    private float currentFps = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        videoBtn = findViewById(R.id.video_btn);
        audioBtn = findViewById(R.id.audio_btn);
        tvPacketStats = findViewById(R.id.tv_packet_stats);
        tvFrameStats = findViewById(R.id.tv_frame_stats);
        tvQueueStats = findViewById(R.id.tv_queue_stats);
        
        videoBtn.setVisibility(View.VISIBLE);
        audioBtn.setVisibility(View.GONE);
        
        uiHandler = new Handler(Looper.getMainLooper());

        initTcp();

        startDecodeThread();
        
        startQueueMonitor();

        Log.i(TAG, "onCreate()");
    }

    private void initTcp() {
        final int port = 23334;

        // 点击启动客户端发送文件
        videoBtn.setOnClickListener(v -> {
            myClient = new MyClient(this, "received_packets_20251031_051814.raw", port);
            myClient.start();
            videoBtn.setEnabled(false);// 防止重复点击
        });

        // 创建并启动服务器，传入保存目录
        String saveDirectory = getExternalFilesDir(null).getAbsolutePath();
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
        }, port, saveDirectory);
        
        // 设置数据包统计回调
        myServer.setStatsCallback((packets, bytes, bytesPerSecond) -> {
            updatePacketStats(packets, bytes, bytesPerSecond);
        });
        
        myServer.start();
    }
    
    // 更新数据包统计信息
    private void updatePacketStats(long packets, long bytes, float bytesPerSecond) {
        uiHandler.post(() -> {
            String stats = String.format(Locale.getDefault(),
                    "数据包: %d个 | 总量: %.2f MB | 速率: %.2f KB/s",
                    packets, bytes / 1024.0 / 1024.0, bytesPerSecond / 1024.0);
            tvPacketStats.setText(stats);
        });
    }
    
    // 更新视频帧统计信息
    private void updateFrameStats(boolean isKeyFrame) {
        totalFrames++;
        if (isKeyFrame) {
            iFrameCount++;
        } else {
            pFrameCount++;
        }
        
        // 计算帧率
        long currentTime = System.currentTimeMillis();
        if (lastFrameTime > 0) {
            long timeDiff = currentTime - lastFrameTime;
            if (timeDiff > 0) {
                currentFps = currentFps * 0.9f + (1000.0f / timeDiff) * 0.1f; // 平滑处理
            }
        }
        lastFrameTime = currentTime;
        
        uiHandler.post(() -> {
            String stats = String.format(Locale.getDefault(),
                    "视频帧: %d个 (I:%d P:%d) | 帧率: %.1f fps",
                    totalFrames, iFrameCount, pFrameCount, currentFps);
            tvFrameStats.setText(stats);
        });
    }
    
    // 启动队列监控线程
    private void startQueueMonitor() {
        new Thread(() -> {
            while (!Thread.interrupted()) {
                try {
                    Thread.sleep(100); // 每100ms更新一次
                    int queueSize = frameQueue.size();
                    uiHandler.post(() -> {
                        String stats = String.format(Locale.getDefault(),
                                "队列状态: %d/25 (%.0f%%)",
                                queueSize, queueSize * 100.0 / 25);
                        tvQueueStats.setText(stats);
                        
                        // 根据队列状态改变颜色
                        if (queueSize > 20) {
                            tvQueueStats.setTextColor(0xFFFF0000); // 红色：队列快满
                        } else if (queueSize > 15) {
                            tvQueueStats.setTextColor(0xFFFFFF00); // 黄色：队列较满
                        } else {
                            tvQueueStats.setTextColor(0xFF00FF00); // 绿色：正常
                        }
                    });
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "QueueMonitor").start();
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
                        updateFrameStats(true); // I帧
                        currentFrame.reset();
                        break;
                    case 1: // 非IDR Slice
                        currentFrame.write(new byte[]{0, 0, 0, 1});
                        currentFrame.write(nal);
                        submitFrame(currentFrame.toByteArray(), pts, 0);
                        updateFrameStats(false); // P帧
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

    private byte[] currentSps = null;
    private byte[] currentPps = null;

    private void configMediaCodec(@NonNull byte[] sps, @NonNull byte[] pps) {
        // 检查是否需要重新配置
        boolean needReconfig = false;
        if (mediaCodec != null) {
            // 比较新旧SPS和PPS是否相同
            if (!java.util.Arrays.equals(currentSps, sps) || !java.util.Arrays.equals(currentPps, pps)) {
                Log.i(TAG, "configMediaCodec() 检测到SPS/PPS变化，需要重新配置解码器");
                needReconfig = true;
                
                // 释放旧的解码器
                try {
                    mediaCodec.stop();
                    mediaCodec.release();
                    mediaCodec = null;
                    Log.i(TAG, "configMediaCodec() 已释放旧解码器");
                } catch (Exception e) {
                    Log.e(TAG, "configMediaCodec() 释放旧解码器失败: ", e);
                    mediaCodec = null;
                }
                
                // 清空帧队列，避免旧数据干扰
                frameQueue.clear();
                Log.i(TAG, "configMediaCodec() 已清空帧队列");
                
                // 重置播放时间
                startTime = Long.MIN_VALUE;
            } else {
                Log.d(TAG, "configMediaCodec() SPS/PPS未变化，跳过重新配置");
                return;
            }
        }

        // 保存当前的SPS和PPS
        currentSps = sps.clone();
        currentPps = pps.clone();

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
        String configType = needReconfig ? "重新配置" : "初始配置";
        Log.i(TAG, "configMediaCodec() " + configType + " soft=" + software + " dimensions=" + width + "x" + height);

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