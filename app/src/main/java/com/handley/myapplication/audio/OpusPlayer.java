package com.handley.myapplication.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import com.handley.myapplication.Utils;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

public class OpusPlayer {

    private static final String TAG = Utils.TAG + "OpusPlayer";

    private static final String MIME_TYPE = "audio/opus";
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_COUNT = 2;
    private static final int BUFFER_SIZE = 64 * 1024; // 64KB buffer

    private MediaCodec codec;
    private AudioTrack audioTrack;
    private HandlerThread decoderThread;
    private Handler decoderHandler;
    private InputStream inputStream;
    private boolean isPlaying;

    // 简化的Ogg页面解析
    private static byte[] readNextOggPage(InputStream is) throws IOException {
        // 1. 读取Ogg页头 (27字节)
        byte[] header = new byte[27];
        if (is.read(header) != 27) {
            return null;
        }

        // 2. 读取段表
        int segmentCount = header[26] & 0xFF;
        byte[] segmentTable = new byte[segmentCount];
        if (is.read(segmentTable) != segmentCount) {
            return null;
        }

        // 3. 计算数据长度
        int dataLength = 0;
        for (byte b : segmentTable) {
            dataLength += (b & 0xFF);
        }

        // 4. 读取数据
        byte[] pageData = new byte[27 + segmentCount + dataLength];
        System.arraycopy(header, 0, pageData, 0, 27);
        System.arraycopy(segmentTable, 0, pageData, 27, segmentCount);
        if (is.read(pageData, 27 + segmentCount, dataLength) != dataLength) {
            return null;
        }

        return pageData;
    }

    public void prepare(String filePath) throws IOException {
        // 1. 创建并配置MediaCodec
        codec = MediaCodec.createDecoderByType(MIME_TYPE);

        // 手动构造MediaFormat (关键步骤)
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, MIME_TYPE);
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, CHANNEL_COUNT);

        // Opus需要设置以下特殊参数
        format.setInteger(MediaFormat.KEY_IS_ADTS, 0); // 非ADTS流
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);

        // 设置CSD-0 (Codec Specific Data)
        byte[] opusHeader = createOpusHeader();
        format.setByteBuffer("csd-0", ByteBuffer.wrap(opusHeader));

        codec.configure(format, null, null, 0);

        // 2. 创建AudioTrack
        int channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
        int minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT);

        audioTrack = new AudioTrack(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(channelConfig)
                        .build(),
                Math.max(minBufferSize, BUFFER_SIZE),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE);

        // 3. 准备解码线程
        decoderThread = new HandlerThread("OpusDecoder");
        decoderThread.start();
        decoderHandler = new Handler(decoderThread.getLooper());

        // 4. 打开文件流
        inputStream = new BufferedInputStream(new FileInputStream(filePath));
    }

    // 创建Opus头部信息 (模拟Ogg封装)
    private byte[] createOpusHeader() {
        // 简化的Opus头部 (实际实现需要完整解析Ogg封装)
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        try {
            // Ogg页头标识
            header.write("OggS".getBytes());

            // 版本和标志位
            header.write(0); // version
            header.write(0); // header type

            // Granule位置 (8字节，设为0)
            for (int i = 0; i < 8; i++) {
                header.write(0);
            }

            // Serial number和page sequence
            for (int i = 0; i < 4; i++) {
                header.write(0);
            }

            // Checksum (4字节，设为0)
            for (int i = 0; i < 4; i++) {
                header.write(0);
            }

            // Page segments (设为1个段)
            header.write(1);

            // Segment table (段长度)
            header.write(19); // OpusHead长度

            // OpusHead标识
            header.write("OpusHead".getBytes());

            // 版本号 (1)
            header.write(1);

            // 通道数
            header.write(CHANNEL_COUNT);

            // 预跳过 (2字节小端)
            header.write(0x58); // 跳过样本数: 312 (0x138)
            header.write(0x01);

            // 输入采样率 (4字节小端)
            header.write(SAMPLE_RATE & 0xFF);
            header.write((SAMPLE_RATE >> 8) & 0xFF);
            header.write((SAMPLE_RATE >> 16) & 0xFF);
            header.write((SAMPLE_RATE >> 24) & 0xFF);

            // 输出增益 (2字节，设为0)
            header.write(0);
            header.write(0);

            // 映射族 (0=立体声)
            header.write(0);
        } catch (IOException e) {
            // 不会发生
        }
        return header.toByteArray();
    }

    public void start() {
        if (isPlaying) {
            return;
        }

        isPlaying = true;
        codec.start();
        audioTrack.play();

        decoderHandler.post(() -> {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            ByteBuffer[] inputBuffers = codec.getInputBuffers();

            while (isPlaying) {
                // 1. 填充输入缓冲区
                int inputIndex = codec.dequeueInputBuffer(10000);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = inputBuffers[inputIndex];
                    inputBuffer.clear();

                    try {
//                        byte[] chunk = new byte[inputBuffer.remaining()];
//                        int bytesRead = inputStream.read(chunk);

                        byte[] oggPage = readNextOggPage(inputStream);
                        byte[] chunk = oggPage;
                        int bytesRead = chunk.length;
                        
                        if (bytesRead == -1) { // EOF
                            codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            inputBuffer.put(chunk, 0, bytesRead);
                            codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    bytesRead,
                                    0,
                                    0);
                        }
                    } catch (IOException e) {
                        codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                }

                // 2. 处理输出缓冲区
                int outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000);
                if (outputIndex >= 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);

                    if (outputBuffer != null && bufferInfo.size > 0) {
                        byte[] pcmData = new byte[bufferInfo.size];
                        outputBuffer.get(pcmData);
                        audioTrack.write(pcmData, 0, pcmData.length);
                    }

                    codec.releaseOutputBuffer(outputIndex, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 处理格式变化
                }
            }
            stop();
        });
    }

    public void stop() {
        isPlaying = false;
        if (decoderThread != null) {
            decoderThread.quitSafely();
        }

        if (codec != null) {
            codec.stop();
            codec.release();
            codec = null;
        }

        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }

        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (IOException e) {
            // 忽略
        }
    }
}