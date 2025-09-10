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
import java.util.ArrayList;
import java.util.List;

public class H264PlayerActivity2 extends AppCompatActivity implements SurfaceHolder.Callback {

    private static final String TAG = "H264PlayerActivity2";

    private static final String MIME_TYPE = "video/avc";
    private static final int FRAME_RATE = 25; // 假设帧率
    private static final long FRAME_INTERVAL_US = 1000000 / FRAME_RATE;

    private MediaCodec mediaCodec;
    private SurfaceView surfaceView;
    private Thread decoderThread;
    private volatile boolean isRunning = false;

    // 提取SPS和PPS
    private static byte[][] extractSpsPps(File file) throws IOException {
        try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[(int) file.length()];
            int bytesRead = is.read(buffer);

            if (bytesRead <= 0) {
                throw new IOException("Failed to read file");
            }

            List<byte[]> nalUnits = splitNalUnits2(buffer, bytesRead);

            byte[] sps = null;
            byte[] pps = null;

            for (byte[] nal : nalUnits) {
                if (nal.length < 1) {
                    continue;
                }

                int nalType = nal[0] & 0x1F;
                if (nalType == 7) { // SPS
                    sps = nal;
                } else if (nalType == 8) { // PPS
                    pps = nal;
                }

                if (sps != null && pps != null) {
                    break;
                }
            }

            if (sps == null || pps == null) {
                throw new IOException("SPS or PPS not found");
            }

            return new byte[][]{sps, pps};
        }
    }

    private static List<byte[]> splitNalUnits2(byte[] data, int length) {
        List<byte[]> nalUnits = new ArrayList<>();
        int start = 0;

        for (int i = 0; i < length - 4; i++) {
            // 检测 4字节起始码 (0x00000001)
            if (i <= length - 4 && data[i] == 0x00 && data[i + 1] == 0x00 && data[i + 2] == 0x00
                    && data[i + 3] == 0x01) {

                if (i > start) {
                    // 提取前一个NAL单元
                    byte[] nal = new byte[i - start];
                    System.arraycopy(data, start, nal, 0, i - start);
                    nalUnits.add(nal);
                }

                start = i + 4; // 跳过4字节起始码
                i += 3; // 跳过已检查的字节
                continue;
            }

            // 检测 3字节起始码 (0x000001)
            if (i <= length - 3 && data[i] == 0x00 && data[i + 1] == 0x00 && data[i + 2] == 0x01) {

                if (i > start) {
                    // 提取前一个NAL单元
                    byte[] nal = new byte[i - start];
                    System.arraycopy(data, start, nal, 0, i - start);
                    nalUnits.add(nal);
                }

                start = i + 3; // 跳过3字节起始码
                i += 2; // 跳过已检查的字节
            }
        }

        // 添加最后一个NAL单元
        if (start < length) {
            byte[] nal = new byte[length - start];
            System.arraycopy(data, start, nal, 0, length - start);
            nalUnits.add(nal);
        }

        return nalUnits;
    }

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

    // 解码器工作线程
    private class DecoderRunnable implements Runnable {

        private final File h264File;

        public DecoderRunnable(File h264File) {
            this.h264File = h264File;
        }

        @Override
        public void run() {
            try (InputStream is = new BufferedInputStream(new FileInputStream(h264File))) {
                byte[] fileData = new byte[(int) h264File.length()];
                int bytesRead = is.read(fileData);

                if (bytesRead <= 0) {
                    return;
                }

                // 分割NAL单元
                List<byte[]> nalUnits = splitNalUnits2(fileData, bytesRead);

                long presentationTimeUs = 0;
                boolean isWaitingForIDR = false;
                ByteArrayOutputStream currentFrame = new ByteArrayOutputStream();

                for (byte[] nal : nalUnits) {
                    if (!isRunning) {
                        break;
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
                }

                // 提交结束标志
                signalEndOfStream();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void submitSingleFrame(byte[] nal, long pts) {
            ByteBuffer frameData = ByteBuffer.allocate(nal.length + 4);
            frameData.putInt(0x00000001);
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

                // 处理输出
                drainOutput();
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