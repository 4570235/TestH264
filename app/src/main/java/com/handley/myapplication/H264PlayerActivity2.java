package com.handley.myapplication;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
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

public class H264PlayerActivity2 extends AppCompatActivity implements SurfaceHolder.Callback {

    private static final String TAG = "H264PlayerActivity2";
    private static final String MIME_TYPE = "video/avc";
    private static final int FRAME_RATE = 25; // 假设帧率
    private static final long FRAME_INTERVAL_US = 1000000 / FRAME_RATE;
    private static final int BUFFER_SIZE = 1024 * 1024; // 1MB 缓冲区

    private MediaCodec mediaCodec;
    private SurfaceView surfaceView;
    private Thread decoderThread;
    private volatile boolean isRunning = false;

    // 提取SPS和PPS（流式版本）
    private static byte[][] extractSpsPps(File file) throws IOException {
        try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
            H264StreamReader streamReader = new H264StreamReader(is);

            byte[] sps = null;
            byte[] pps = null;

            while (sps == null || pps == null) {
                byte[] nal = streamReader.readNextNalUnit();
                if (nal == null) {
                    break; // 没有更多数据
                }

                if (nal.length < 1) {
                    continue;
                }

                int nalType = nal[0] & 0x1F;
                if (nalType == 7) { // SPS
                    sps = nal;
                } else if (nalType == 8) { // PPS
                    pps = nal;
                }
            }

            if (sps == null || pps == null) {
                throw new IOException("SPS or PPS not found");
            }

            return new byte[][]{sps, pps};
        }
    }

    // 查找软件解码器
    private static MediaCodec findSoftwareDecoder(String mimeType) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
            if (codecInfo.isEncoder()) {
                continue; // 跳过编码器
            }

            for (String supportedType : codecInfo.getSupportedTypes()) {
                if (supportedType.equalsIgnoreCase(mimeType)) {
                    String codecName = codecInfo.getName().toLowerCase();

                    // 检测软件解码器特征
                    if (codecName.contains("omx.google.") ||   // 传统软件解码器
                            codecName.contains(".sw.") ||         // 通用标记
                            codecName.contains("c2.android.")) {  // Codec2 软件实现
                        try {
                            return MediaCodec.createByCodecName(codecInfo.getName());
                        } catch (IOException e) {
                            Log.e(TAG, "Failed to create software decoder: " + codecInfo.getName(), e);
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        surfaceView = findViewById(R.id.surface_view);
        surfaceView.getHolder().addCallback(this);
        AssetsFileCopier.copyAssetToExternalFilesDir(this, "test.h264");
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
            final boolean soft = true;
            mediaCodec = soft ? findSoftwareDecoder(MIME_TYPE) : MediaCodec.createDecoderByType(MIME_TYPE);

            // 2. 从文件中提取SPS和PPS
            File h264File = new File(getExternalFilesDir(null), "test.h264");
            byte[][] spsPps = extractSpsPps(h264File);
            byte[] sps = spsPps[0];
            byte[] pps = spsPps[1];

            // 从SPS中解析视频宽度
            int[] dimensions = SpsParser.parseSps(sps);

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
            decoderThread.start();
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

    // 流式NAL单元读取器（支持3字节和4字节起始码）
    private static class H264StreamReader {

        private final InputStream inputStream;
        private final byte[] buffer;
        private int bufferPos;
        private int bufferSize;
        private boolean endOfStream;
        private int bytesProcessed;

        public H264StreamReader(InputStream is) {
            this.inputStream = is;
            this.buffer = new byte[BUFFER_SIZE];
            this.bufferPos = 0;
            this.bufferSize = 0;
            this.endOfStream = false;
            this.bytesProcessed = 0;
        }

        // 读取下一个NAL单元
        public byte[] readNextNalUnit() throws IOException {
            if (endOfStream && bufferSize == 0) {
                return null; // 没有更多数据
            }

            // 查找起始码
            StartCodeInfo startCodeInfo = findStartCode();
            if (startCodeInfo == null) {
                return null; // 没有找到起始码
            }

            // 跳过起始码
            bufferPos += startCodeInfo.position + startCodeInfo.length;
            bytesProcessed += startCodeInfo.position + startCodeInfo.length;

            // 查找下一个起始码
            StartCodeInfo nextStartCodeInfo = findStartCode();
            if (nextStartCodeInfo == null) {
                // 如果没有找到下一个起始码，读取剩余数据
                return readRemainingData();
            }

            // 提取NAL单元
            int nalLength = nextStartCodeInfo.position;
            byte[] nal = new byte[nalLength];
            System.arraycopy(buffer, bufferPos, nal, 0, nalLength);
            bufferPos += nalLength;
            bytesProcessed += nalLength;

            return nal;
        }

        // 查找起始码信息
        private StartCodeInfo findStartCode() throws IOException {
            while (true) {
                // 检查缓冲区中是否有足够的数据
                if (bufferSize - bufferPos < 4) {
                    if (!refillBuffer()) {
                        return null; // 没有更多数据
                    }
                }

                // 在缓冲区中查找起始码
                for (int i = bufferPos; i <= bufferSize - 4; i++) {
                    // 检测4字节起始码 (0x00000001)
                    if (buffer[i] == 0x00 && buffer[i + 1] == 0x00 && buffer[i + 2] == 0x00 && buffer[i + 3] == 0x01) {
                        return new StartCodeInfo(i - bufferPos, 4);
                    }

                    // 检测3字节起始码 (0x000001)
                    if (buffer[i] == 0x00 && buffer[i + 1] == 0x00 && buffer[i + 2] == 0x01) {
                        return new StartCodeInfo(i - bufferPos, 3);
                    }
                }

                // 如果没有找到起始码，尝试读取更多数据
                if (!refillBuffer()) {
                    return null; // 没有更多数据
                }
            }
        }

        // 填充缓冲区
        private boolean refillBuffer() throws IOException {
            if (endOfStream) {
                return false;
            }

            // 移动剩余数据到缓冲区开头
            int remaining = bufferSize - bufferPos;
            if (remaining > 0) {
                System.arraycopy(buffer, bufferPos, buffer, 0, remaining);
            }

            bufferPos = 0;
            bufferSize = remaining;

            // 读取新数据
            int bytesRead = inputStream.read(buffer, bufferSize, buffer.length - bufferSize);
            if (bytesRead == -1) {
                endOfStream = true;
                return bufferSize > 0; // 如果还有剩余数据返回true
            }

            bufferSize += bytesRead;
            return true;
        }

        // 读取剩余数据作为最后一个NAL单元
        private byte[] readRemainingData() {
            int nalLength = bufferSize - bufferPos;
            if (nalLength <= 0) {
                return null;
            }

            byte[] nal = new byte[nalLength];
            System.arraycopy(buffer, bufferPos, nal, 0, nalLength);
            bufferPos += nalLength;
            bytesProcessed += nalLength;
            return nal;
        }
    }

    // 起始码信息类
    private static class StartCodeInfo {

        public final int position; // 起始码在缓冲区中的位置（相对于bufferPos）
        public final int length;   // 起始码长度（3或4）

        public StartCodeInfo(int position, int length) {
            this.position = position;
            this.length = length;
        }
    }

    // 解码器工作线程（流式读取版本）
    private class DecoderRunnable implements Runnable {

        private final File h264File;

        public DecoderRunnable(File h264File) {
            this.h264File = h264File;
        }

        @Override
        public void run() {
            try (InputStream is = new BufferedInputStream(new FileInputStream(h264File))) {
                H264StreamReader streamReader = new H264StreamReader(is);

                long presentationTimeUs = 0;
                boolean isWaitingForIDR = false;
                ByteArrayOutputStream currentFrame = new ByteArrayOutputStream();

                while (isRunning) {
                    byte[] nal = streamReader.readNextNalUnit();
                    if (nal == null) {
                        break; // 没有更多数据
                    }

                    if (nal.length < 1) {
                        continue;
                    }

                    int nalType = nal[0] & 0x1F;

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
                                presentationTimeUs += FRAME_INTERVAL_US;
                            } else {
                                // 提交单个NAL单元
                                submitSingleFrame(nal, presentationTimeUs);
                                presentationTimeUs += FRAME_INTERVAL_US;
                            }
                            break;

                        case 1: // 非IDR Slice
                            if (isWaitingForIDR) {
                                // 错误处理：预期IDR但遇到普通帧
                                currentFrame.reset();
                                isWaitingForIDR = false;
                            }
                            submitSingleFrame(nal, presentationTimeUs);
                            presentationTimeUs += FRAME_INTERVAL_US;
                            break;

                        default:
                            // 其他NAL类型（可选处理）
                            break;
                    }

                    // 处理输出
                    drainOutput();
                }

                // 提交结束标志
                signalEndOfStream();
            } catch (IOException e) {
                e.printStackTrace();
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