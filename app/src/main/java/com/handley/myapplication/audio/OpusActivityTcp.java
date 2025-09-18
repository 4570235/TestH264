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
import com.handley.myapplication.common.Utils;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

// 演示 MyAudioClient 向 MyAudioServer 发送(含私有协议头的)文件数据流，解码播放。

public class OpusActivityTcp extends AppCompatActivity {

    private static final String TAG = Utils.TAG + "OpusActivityTcp";
    private final BlockingQueue<AudioFrame> frameQueue = new LinkedBlockingQueue<>(50); // 缓冲队列
    private Button videoBtn, audioBtn;
    private MyAudioServer audioServer;
    private MyAudioClient audioClient;
    private long startTime = Long.MIN_VALUE; // 播放开始时间（毫秒）
    private Thread playbackThread;
    private volatile boolean isPlaying = false;
    private AudioTrack audioTrack;
    private MediaCodec codec; // Opus解码器实例

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

        startPlaybackThread();

        Log.i(TAG, "onCreate()");
    }


    // 创建并配置解码器和播放器
    private void initMedia() {
        try {
            MediaFormat format = Utils.createAudioFormat();
            String mime = format.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int channelConfig = (channelCount == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
            audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);
            audioTrack.play();
        } catch (Exception e) {
            throw new RuntimeException("initMedia() failed", e);
        }
    }

    private void initTcp() {
        // 创建并启动客户端
        audioBtn.setOnClickListener(v -> {
            audioClient = new MyAudioClient(this);
            audioClient.start();
            audioBtn.setEnabled(false);// 防止重复点击
        });

        // 创建并启动服务器
        audioServer = new MyAudioServer((header, frameData) -> {
            // 处理接收到的帧数据
            Log.d(TAG, "Received frame: type=" + header.type + ", size=" + frameData.length + ", timestamp="
                    + header.timestamp);

            if (startTime == Long.MIN_VALUE) {
                long currentTime = System.nanoTime() / 1000000;
                startTime = currentTime - header.timestamp;
                Log.i(TAG, "onFrameReceived init currentTime=" + currentTime + " pts=" + header.timestamp
                        + " startTime=" + startTime);
            }

            controlSpeed(header.timestamp, 500);// 一帧 20ms，队列 50 个数据，1000ms。控制一半水位。

            // 快速将帧存入队列（非阻塞）
            boolean offer = frameQueue.offer(new AudioFrame(header, frameData));
            if (!offer) {
                Log.w(TAG, "frameQueue.offer() failed");
            }
        });
        audioServer.start();
    }

    // 启动播放线程
    private void startPlaybackThread() {
        isPlaying = true;
        playbackThread = new Thread(() -> {
            while (isPlaying && !Thread.interrupted()) {
                try {
                    AudioFrame frame = frameQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (frame == null) {
                        continue;
                    }

                    // 1. 控制解码时机（输入速度）
                    controlSpeed(frame.header.timestamp, 2);

                    // 2. 解码Opus数据
                    byte[] pcmData = decodeOpus(frame.frameData, frame.header.timestamp);
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
        }, "AudioPlaybackThread");
        playbackThread.start();
    }

    // 播放线程中的解码方法
    private byte[] decodeOpus(byte[] opusData, long pts) {
        if (codec == null) {
            return new byte[0];
        }

        ByteBuffer[] inputBuffers = codec.getInputBuffers();
        ByteBuffer[] outputBuffers = codec.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int inputBufferIndex = codec.dequeueInputBuffer(10000);
        if (inputBufferIndex >= 0) {
            ByteBuffer buffer = inputBuffers[inputBufferIndex];
            buffer.put(opusData);
            codec.queueInputBuffer(inputBufferIndex, 0, opusData.length, pts, 0);
        }

        // 从解码器获取输出
        int outputBufferIndex = codec.dequeueOutputBuffer(info, 10000);
        if (outputBufferIndex >= 0) {
            ByteBuffer buffer = outputBuffers[outputBufferIndex];
            byte[] pcmData = new byte[info.size];
            buffer.get(pcmData);
            buffer.clear();
            codec.releaseOutputBuffer(outputBufferIndex, false);
            return pcmData;
        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            // 处理缓冲区变化
            outputBuffers = codec.getOutputBuffers();
            Log.d(TAG, "Output buffers changed");
        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            // 处理格式变化
            MediaFormat newFormat = codec.getOutputFormat();
            Log.d(TAG, "Output format changed: " + newFormat);
        }
        return new byte[0];
    }

    private void releaseAudioResources() {
        // 停止播放线程
        isPlaying = false;
        if (playbackThread != null) {
            playbackThread.interrupt();
            try {
                playbackThread.join(200);
            } catch (InterruptedException ignored) {
            }
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

        // 释放Opus解码器
        if (codec != null) {
            codec.stop();
            codec.release();
            codec = null;
        }

        Log.i(TAG, "releaseAudioResources()");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        audioServer.stop();
        audioClient.stop();
        isPlaying = false; // 停止播放线程
        if (playbackThread != null) {
            playbackThread.interrupt();
            try {
                playbackThread.join(200);
            } catch (InterruptedException ignored) {
            }
        }
        releaseAudioResources();
        Log.i(TAG, "onDestroy()");
    }

    // 控制输入速度
    private void controlSpeed(long pts, long workTime) {
        long targetTime = startTime + pts;
        long currentTime = System.nanoTime() / 1000000;
        long sleepTime = targetTime - currentTime - workTime;
        Log.v(TAG, "controlSpeed pts=" + pts + " workTime=" + workTime + " targetTime=" + targetTime + " currentTime="
                + currentTime + " sleepTime=" + sleepTime);

        // 如果输入太快，等待一段时间
        if (sleepTime > 1) {
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // 音频帧封装类
    private static class AudioFrame {

        final MediaMessageHeader header;
        final byte[] frameData;

        AudioFrame(MediaMessageHeader header, byte[] frameData) {
            this.header = header;
            this.frameData = frameData;
        }
    }
}