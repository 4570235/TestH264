package com.handley.myapplication.audio;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import com.handley.myapplication.R;
import com.handley.myapplication.common.AssetsFileCopier;
import com.handley.myapplication.common.MediaMessageHeader;
import com.handley.myapplication.common.Utils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

// 使用 MediaExtractor + MediaCodec 解码 test.opus 文件，播放
public class OpusActivityMe extends AppCompatActivity {

    private static final String TAG = Utils.TAG + "OpusActivityMe";
    private static final boolean FAKE_DUMP_FILE = false; // 是否输出fake-dump.opus文件
    private FileOutputStream dumpOutputStream; // 输出fake-dump.opus的文件流
    private Thread playbackThread;
    private volatile boolean isPlaying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        startPlayback();
    }

    private void startPlayback() {
        if (playbackThread != null) {
            isPlaying = false;
            try {
                playbackThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping previous thread", e);
            }
        }

        isPlaying = true;
        playbackThread = new Thread(new Runnable() {
            @Override
            public void run() {
                playOpusAudio();
            }
        });
        playbackThread.start();
    }

    private void playOpusAudio() {
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        AudioTrack audioTrack = null;

        try {
            // 1. 设置数据源
            File h264File = AssetsFileCopier.copyAssetToExternalFilesDir(this, "test.opus");
            extractor = new MediaExtractor();
            extractor.setDataSource(h264File.getAbsolutePath());

            // 2. 选择音频轨道
            int audioTrackIndex = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    break;
                }
            }

            if (audioTrackIndex == -1) {
                Log.e(TAG, "No audio track found");
                return;
            }

            // 3. 配置解码器
            extractor.selectTrack(audioTrackIndex);
            MediaFormat format = extractor.getTrackFormat(audioTrackIndex);
            //format = createAudioFormat(); 等价构造
            String mime = format.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            // 4. 配置AudioTrack
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int channelConfig = (channelCount == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;

            int bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);

            audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);
            audioTrack.play();

            // 5. 解码循环
            ByteBuffer[] inputBuffers = codec.getInputBuffers();
            ByteBuffer[] outputBuffers = codec.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean sawInputEOS = false;
            boolean sawOutputEOS = false;

            while (!sawOutputEOS && isPlaying) {
                // 输入数据到解码器
                if (!sawInputEOS) {
                    int inputBufferIndex = codec.dequeueInputBuffer(10000);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer buffer = inputBuffers[inputBufferIndex];
                        int sampleSize = extractor.readSampleData(buffer, 0);

                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sawInputEOS = true;
                        } else {
                            long presentationTimeUs = extractor.getSampleTime();

                            if (FAKE_DUMP_FILE) {
                                // ===== 写入帧头和帧数据到dump文件 =====
                                if (dumpOutputStream == null) {
                                    // 创建dump文件输出流
                                    File dumpFile = new File(getExternalFilesDir(null), "fake-dump.opus");
                                    dumpOutputStream = new FileOutputStream(dumpFile);
                                }
                                // 创建帧头
                                MediaMessageHeader header = Utils.createMediaMessageHeader(sampleSize,
                                        presentationTimeUs);
                                // 序列化帧头
                                byte[] headerBytes = header.toBytes();
                                // 写入帧头
                                dumpOutputStream.write(headerBytes);
                                // 写入帧数据
                                byte[] frameData = new byte[sampleSize];
                                buffer.position(0); // 重置buffer位置
                                buffer.get(frameData);
                                dumpOutputStream.write(frameData);
                            }

                            codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0);
                            extractor.advance();
                        }
                    }
                }

                // 从解码器获取输出
                int outputBufferIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outputBufferIndex >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true;
                    }

                    ByteBuffer buffer = outputBuffers[outputBufferIndex];
                    byte[] pcmData = new byte[info.size];
                    buffer.get(pcmData);
                    buffer.clear();

                    // 写入AudioTrack
                    audioTrack.write(pcmData, 0, info.size);
                    codec.releaseOutputBuffer(outputBufferIndex, false);
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = codec.getOutputBuffers();
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 可以获取更新后的格式
                    MediaFormat newFormat = codec.getOutputFormat();
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error during playback", e);
        } finally {
            // 6. 释放资源
            if (dumpOutputStream != null) {
                try {
                    dumpOutputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing dump file", e);
                }
            }
            if (extractor != null) {
                extractor.release();
            }
            if (codec != null) {
                codec.stop();
                codec.release();
            }
            if (audioTrack != null) {
                audioTrack.stop();
                audioTrack.release();
            }
            Log.i(TAG, "playOpusAudio() finish");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isPlaying = false;
        if (playbackThread != null) {
            try {
                playbackThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping playback thread", e);
            }
        }
    }
}