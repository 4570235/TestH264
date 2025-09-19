package com.handley.myapplication.audio;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import com.handley.myapplication.R;
import com.handley.myapplication.common.MediaMessageHeader;
import com.handley.myapplication.common.MyFrame;
import com.handley.myapplication.common.Utils;
import com.handley.myapplication.tcp.MyClient;
import com.handley.myapplication.tcp.MyServer;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

// 演示 MyAudioClient 向 MyAudioServer 发送(含私有协议头的)文件数据流，解码播放。
public class OpusActivityTcp extends AppCompatActivity {

    private static final String TAG = Utils.TAG + "OpusActivityTcp";
    private final BlockingQueue<MyFrame> frameQueue = new LinkedBlockingQueue<>(50); // 帧缓冲队列
    private Button videoBtn, audioBtn;
    private MyServer myServer;
    private MyClient myClient;
    private long startTime = Long.MIN_VALUE; // 播放开始时间（毫秒）
    private Thread decodeThread;
    private volatile boolean decodeThreadRunning = false;
    private AudioTrack audioTrack;
    private MediaCodec mediaCodec;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        videoBtn = findViewById(R.id.video_btn);
        audioBtn = findViewById(R.id.audio_btn);
        videoBtn.setVisibility(View.GONE);
        audioBtn.setVisibility(View.VISIBLE);

        initMedia();

        initTcp();

        startDecodeThread();

        Log.i(TAG, "onCreate()");
    }


    // 创建并配置解码器和播放器
    private void initMedia() {
        try {
            MediaFormat format = Utils.createAudioFormat();
            String mime = format.getString(MediaFormat.KEY_MIME);
            mediaCodec = MediaCodec.createDecoderByType(mime);
            mediaCodec.configure(format, null, null, 0);
            mediaCodec.start();

            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int channelConfig = (channelCount == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
            audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);
            audioTrack.play();
        } catch (Exception e) {
            throw new RuntimeException("initMedia() failed", e);
        }
    }

    private void initTcp() {
        final int port = 23333;

        // 创建并启动客户端
        audioBtn.setOnClickListener(v -> {
            myClient = new MyClient(this, "fake-dump.opus", port);
            myClient.start();
            audioBtn.setEnabled(false);// 防止重复点击
        });

        // 创建并启动服务器
        myServer = new MyServer((frame) -> {
            // 处理接收到的帧数据
            Log.d(TAG, "Received frame: type=" + frame.header.type + ", size=" + frame.header.dataLen + ", timestamp=" + frame.header.timestamp);
            if (frame.header.type != MediaMessageHeader.OPUS) return;

            frame.header.timestamp /= 1000;//转换为毫秒
            if (startTime == Long.MIN_VALUE) {
                long currentTime = System.nanoTime() / 1000000;
                startTime = currentTime - frame.header.timestamp;
                Log.i(TAG, "onFrameReceived init currentTime=" + currentTime + " pts=" + frame.header.timestamp + " startTime=" + startTime);
            }

            // 控制速度：一帧 20ms，队列 50 个数据，1000ms。控制一半水位。
            controlSpeed(frame.header.timestamp, 500);

            // 快速将帧存入队列（非阻塞）
            boolean offer = frameQueue.offer(frame);
            if (!offer) {
                Log.w(TAG, "frameQueue.offer() failed");
            }
        }, port, null, this);
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

                    // 1. 控制解码时机（输入速度）
                    controlSpeed(frame.header.timestamp, 10);

                    // 2. 解码Opus数据
                    byte[] pcmData = decodeData(frame.frameData, frame.header.timestamp);
                    if (pcmData.length == 0) {
                        continue;
                    }

                    // 3. 控制播放时机（输出速度）
                    controlSpeed(frame.header.timestamp, 1);

                    // 4. 播放音频（示例：AudioTrack）
                    audioTrack.write(pcmData, 0, pcmData.length);
                } catch (Exception e) {
                    Log.e(TAG, "Playback error: " + e.getMessage());
                }
            }
        }, "DecodeThread");
        decodeThread.start();
    }

    // 播放线程中的解码方法
    private byte[] decodeData(byte[] data, long pts) {
        if (mediaCodec == null) {
            return new byte[0];
        }

        ByteBuffer[] inputBuffers = mediaCodec.getInputBuffers();
        ByteBuffer[] outputBuffers = mediaCodec.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int inputBufferIndex = mediaCodec.dequeueInputBuffer(10000);
        if (inputBufferIndex >= 0) {
            ByteBuffer buffer = inputBuffers[inputBufferIndex];
            buffer.put(data);
            mediaCodec.queueInputBuffer(inputBufferIndex, 0, data.length, pts, 0);
        }

        // 从解码器获取输出
        int outputBufferIndex = mediaCodec.dequeueOutputBuffer(info, 10000);
        if (outputBufferIndex >= 0) {
            ByteBuffer buffer = outputBuffers[outputBufferIndex];
            byte[] pcmData = new byte[info.size];
            buffer.get(pcmData);
            buffer.clear();
            mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
            return pcmData;
        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            // 处理缓冲区变化
            outputBuffers = mediaCodec.getOutputBuffers();
            Log.d(TAG, "Output buffers changed");
        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            // 处理格式变化
            MediaFormat newFormat = mediaCodec.getOutputFormat();
            Log.d(TAG, "Output format changed: " + newFormat);
        }
        return new byte[0];
    }

    // 控制速度(pts 时间戳ms，ahead 提前多少ms)
    private void controlSpeed(long pts, long ahead) {
        long targetTime = startTime + pts;
        long currentTime = System.nanoTime() / 1000000;
        long sleepTime = targetTime - currentTime - ahead;
        Log.v(TAG, "controlSpeed pts=" + pts + " ahead=" + ahead + " targetTime=" + targetTime + " currentTime=" + currentTime + " sleepTime=" + sleepTime);

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
    protected void onDestroy() {
        super.onDestroy();

        // 停止 tcp
        myServer.stop();
        myClient.stop();


        // 释放解码器
        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
        }

        // 释放AudioTrack
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
                audioTrack = null;
            } catch (Exception e) {
                Log.w(TAG, "Error releasing AudioTrack: " + e.getMessage());
            }
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

        Log.i(TAG, "onDestroy()");
    }

}