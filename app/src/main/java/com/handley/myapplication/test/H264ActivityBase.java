package com.handley.myapplication.test;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import androidx.appcompat.app.AppCompatActivity;
import com.handley.myapplication.common.AssetsFileCopier;
import com.handley.myapplication.common.Utils;
import com.handley.myapplication.demo.H264StreamReader;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

// 使用 MediaCodec 解码 h264 文件并渲染的基类
public class H264ActivityBase extends AppCompatActivity {

    private static final String TAG = Utils.TAG + "H264ActivityTv";
    private static final String MIME_TYPE = "video/avc";
    private static final int FRAME_RATE = 25;
    private static final long FRAME_INTERVAL_US = 1000000 / FRAME_RATE;

    private MediaCodec mediaCodec;
    private Thread decoderThread;
    private File h264File;
    private volatile boolean isRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        h264File = AssetsFileCopier.copyAssetToExternalFilesDir(this, "test2.h264");
    }

    protected void startDecoder(Surface surface) {
        try {
            // 从文件中提取SPS和PPS
            byte[][] spsPps = Utils.extractSpsPps(h264File);
            byte[] sps = spsPps[0];
            byte[] pps = spsPps[1];

            // 从SPS中解析视频宽高
            int[] dimensions = Utils.parseSps(sps);

            // 创建并配置MediaFormat
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, dimensions[0], dimensions[1]);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(Utils.addStartCode(sps)));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(Utils.addStartCode(pps)));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);

            // 初始化MediaCodec
            final boolean software = false; // 是否使用软件解码器
            mediaCodec = software ? Utils.findSoftwareDecoder(MIME_TYPE) : MediaCodec.createDecoderByType(MIME_TYPE);
            mediaCodec.configure(format, surface, null, 0);
            mediaCodec.start();

            // 启动解码线程
            isRunning = true;
            decoderThread = new Thread(new DecoderRunnable(h264File));
            decoderThread.start();
            Log.i(TAG, "startDecoder() software=" + software + " dimensions=" + dimensions[0] + "x" + dimensions[1]);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    protected void stopDecoder() {
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
        private long frameIndex = 0; // 帧计数器

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
                    Log.d(TAG, "nalType=" + nalType + " nalLen=" + nal.length + " frameIndex=" + frameIndex
                            + " pts=" + presentationTimeUs);
                    switch (nalType) {
                        // 和 H264ActivityTvMe 相同的处理方式，首帧为 SEI+IDR，不带 SPS 和 PPS。
                        case 6: // SEI
                            currentFrame.write(new byte[]{0, 0, 0, 1});
                            currentFrame.write(nal);
                            isWaitingForIDR = true;
                            break;
                        case 5: // IDR
                            if (isWaitingForIDR) {
                                currentFrame.write(new byte[]{0, 0, 0, 1});
                                currentFrame.write(nal);
                                submitFrame(currentFrame.toByteArray(), presentationTimeUs, true);
                                currentFrame.reset();
                                isWaitingForIDR = false;
                                frameIndex++;
                            } else {
                                // 提交单个NAL单元
                                submitSingleFrame(nal, presentationTimeUs, true);
                                frameIndex++;
                            }
                            break;
                        case 1: // 非IDR Slice
                            submitSingleFrame(nal, presentationTimeUs, false);
                            frameIndex++;
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
            return frameIndex * FRAME_INTERVAL_US;
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

        private void submitSingleFrame(byte[] nal, long pts, boolean isKeyFrame) {
            ByteBuffer frameData = ByteBuffer.allocate(nal.length + 4);
            frameData.putInt(0x00000001); // 统一使用4字节起始码
            frameData.put(nal);
            frameData.flip();
            submitFrame(frameData.array(), pts, isKeyFrame);
        }

        private void submitFrame(byte[] frameData, long presentationTimeUs, boolean isKeyFrame) {
            try {
                int inputBufferIndex;
                while ((inputBufferIndex = mediaCodec.dequeueInputBuffer(10000)) == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    Thread.sleep(2);
                }
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);
                    inputBuffer.clear();
                    inputBuffer.put(frameData);
                    Log.d(TAG, "queueInputBuffer inputBufferIndex=" + inputBufferIndex + " size=" + frameData.length
                            + " pts=" + presentationTimeUs + " flag=" + (isKeyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME
                            : 0));
                    mediaCodec.queueInputBuffer(inputBufferIndex, 0, frameData.length, presentationTimeUs,
                            isKeyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                } else {
                    Log.e(TAG, "queueInputBuffer inputBufferIndex=" + inputBufferIndex + " pts=" + presentationTimeUs
                            + " flag=" + (isKeyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void drainOutput() {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex;
            //Log.v(TAG, "drainOutput() begin");
            while ((outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000)) >= 0) {
                // 检查渲染时间
                long renderTimeNs = startTimeNs + bufferInfo.presentationTimeUs * 1000;
                long currentTimeNs = System.nanoTime();
                //Log.v(TAG, "drainOutput() renderTimeNs=" + renderTimeNs + " currentTimeNs=" + currentTimeNs);

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
                Log.v(TAG, "drainOutput() outputBufferIndex=" + outputBufferIndex + " presentationTimeUs="
                        + bufferInfo.presentationTimeUs);
                // 渲染帧
                mediaCodec.releaseOutputBuffer(outputBufferIndex, true);
            }
        }

        private void signalEndOfStream() {
            int inputBufferIndex;
            while ((inputBufferIndex = mediaCodec.dequeueInputBuffer(10000)) == MediaCodec.INFO_TRY_AGAIN_LATER) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    //throw new RuntimeException(e);
                }
            }
            if (inputBufferIndex >= 0) {
                mediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            } else {
                Log.e(TAG, "signalEndOfStream() queueInputBuffer inputBufferIndex=" + inputBufferIndex);
            }

            // 等待所有输出处理完成
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            while (isRunning) {
                int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);
                if (outputBufferIndex >= 0) {
                    mediaCodec.releaseOutputBuffer(outputBufferIndex, true);
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 格式变化处理
                    Log.w(TAG, "signalEndOfStream INFO_OUTPUT_FORMAT_CHANGED");
                } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    Log.w(TAG, "signalEndOfStream INFO_TRY_AGAIN_LATER");
                    break;
                }

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.w(TAG, "signalEndOfStream BUFFER_FLAG_END_OF_STREAM");
                    break;
                }
            }
        }
    }
}