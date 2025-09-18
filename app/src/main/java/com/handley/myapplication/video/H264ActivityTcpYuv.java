package com.handley.myapplication.video;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import com.handley.myapplication.R;
import com.handley.myapplication.common.AssetsFileCopier;
import com.handley.myapplication.common.Utils;
import com.handley.myapplication.video.MyVideoServer.OnH264DataListener;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

// 演示 MyVideoClient 向 MyVideoServer 发送 dump.h264(含私有协议头) 文件数据流。解码成 yuv420 数据保存成 jpg 文件。
public class H264ActivityTcpYuv extends AppCompatActivity implements OnH264DataListener {

    private static final String TAG = Utils.TAG + "H264ActivityTcpYuv";
    private static final String MIME_TYPE = "video/avc";
    private static final int FRAME_RATE = 25; // 假设帧率
    private final MyVideoServer myVideoServer = new MyVideoServer(this);
    private int outputFrameIndex = 0;
    private long startTime = Long.MIN_VALUE; // 播放开始时间（毫秒）
    private MediaCodec mediaCodec;
    private ImageReader imageReader;
    private HandlerThread imageThread;
    private byte[] sps = null;
    private byte[] pps = null;
    private volatile boolean hasStop = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button videoBtn = findViewById(R.id.video_btn);
        Button audioBtn = findViewById(R.id.audio_btn);
        videoBtn.setVisibility(View.VISIBLE);
        audioBtn.setVisibility(View.GONE);

        // 将Client要发送的文件复制到外部存储目录
        AssetsFileCopier.copyAssetToExternalFilesDir(this, "dump.h264");

        // 点击启动客户端发送文件。
        videoBtn.setOnClickListener(v -> new MyVideoClient(H264ActivityTcpYuv.this).sendH264File());

        // 启动服务器。
        myVideoServer.start();
        Log.i(TAG, "onCreate");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");
        myVideoServer.stop();
        stopDecoder();
    }

    @Override
    public void onDataReceived(byte[] data, long pts, int rotation) {
        if (startTime == Long.MIN_VALUE) {
            long currentTime = System.nanoTime() / 1000000;
            startTime = currentTime - pts;
            Log.d(TAG, "onDataReceived init currentTime=" + currentTime + " pts=" + pts + " startTime=" + startTime);
        }

        // 控制播放速度
        controlInputSpeed(pts);

        // 将字节数组包装成 InputStream，以便复用 H264StreamReader 的逻辑
        try (InputStream is = new ByteArrayInputStream(data)) {
            H264StreamReader streamReader = new H264StreamReader(is);
            boolean isWaitingForIDR = false;
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
                //Log.v(TAG, "onDataReceived nalType=" + nalType);
                switch (nalType) {
                    case 7: // SPS
                        isWaitingForIDR = true;
                        currentFrame.write(new byte[]{0, 0, 0, 1});
                        currentFrame.write(nal);
                        sps = nal;
                        break;

                    case 8: // PPS
                        if (isWaitingForIDR) {
                            currentFrame.write(new byte[]{0, 0, 0, 1});
                            currentFrame.write(nal);
                            pps = nal;
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
                            submitFrame(currentFrame.toByteArray(), pts);
                            //Log.d(TAG, "submitFrame");
                            currentFrame.reset();
                            isWaitingForIDR = false;
                        } else {
                            // 提交单个NAL单元
                            submitSingleFrame(nal, pts);
                            //Log.d(TAG, "submitSingleFrame");
                        }
                        break;

                    case 1: // 非IDR Slice
                        if (isWaitingForIDR) {
                            // 错误处理：预期IDR但遇到普通帧
                            currentFrame.reset();
                            isWaitingForIDR = false;
                        }
                        submitSingleFrame(nal, pts);
                        //Log.d(TAG, "submitSingleFrame");
                        break;

                    default:
                        // 其他NAL类型（可选处理）
                        break;
                }

                // 处理输出
                drainOutput();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 控制输入速度
    private void controlInputSpeed(long pts) {
        // 计算当前帧应该显示的时间点
        long targetTime = startTime + pts;
        // 计算当前系统时间
        long currentTime = System.nanoTime() / 1000000;
        // 计算需要等待的时间
        long sleepTime = targetTime - currentTime - 16; // 考虑解码时间，提前一点时间送入
        Log.v(TAG,
                "controlPlaybackSpeed pts=" + pts + " targetTime=" + targetTime + " currentTime=" + currentTime
                        + " sleepTime=" + sleepTime);

        // 如果输入太快，等待一段时间
        if (sleepTime > 1) {
            try {
                Thread.sleep(sleepTime);
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

    private synchronized void submitFrame(byte[] frameData, long presentationTimeUs) {
        if (hasStop) {
            return;
        }
        if (mediaCodec == null) {
            startDecoder();
        }
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

    private synchronized void drainOutput() {
        if (hasStop) {
            return;
        }
        if (mediaCodec == null) {
            return;
        }
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int outputBufferIndex;

        while ((outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000)) >= 0) {
            controlOutputSpeed(bufferInfo.presentationTimeUs);

            // 渲染帧
            mediaCodec.releaseOutputBuffer(outputBufferIndex, true);
        }
    }

    // 控制输出速度
    private void controlOutputSpeed(long pts) {
        // 检查渲染时间
        long renderTime = startTime + pts;
        long currentTime = System.nanoTime() / 1000000;
        long sleepTime = renderTime - currentTime - 2; // 考虑处理时间，提前一点时间输出
        Log.d(TAG,
                "drainOutput() pts=" + pts + " renderTime=" + renderTime + " currentTime=" + currentTime + " sleepTime="
                        + sleepTime);

        // 如果输出太快，等待一段时间
        if (sleepTime > 1) {
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private synchronized void startDecoder() {
        try {
            // 从SPS中解析视频宽高
            int[] dimensions = Utils.parseSps(sps);

            // 创建ImageReader获取YUV数据
            imageThread = new HandlerThread("ImageThread");
            imageThread.start();
            Handler imageThreadHandler = new Handler(imageThread.getLooper());
            imageReader = ImageReader.newInstance(dimensions[0], dimensions[1], ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Log.i(TAG, "onImageAvailable frameIndex=" + outputFrameIndex);
                // 获取ImageReader中的图像
                Image image = imageReader.acquireLatestImage();
                if (image != null) {
                    if (outputFrameIndex++ < 30) {
                        File file = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                                "frame_" + outputFrameIndex + ".jpg");
                        Utils.saveImageAsJpeg(image, file);
                    }
                    image.close();
                }
            }, imageThreadHandler);

            // 创建并配置MediaFormat
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, dimensions[0], dimensions[1]);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(Utils.addStartCode(sps)));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(Utils.addStartCode(pps)));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);

            // 初始化MediaCodec
            final boolean software = false; // 是否使用软件解码器
            mediaCodec = software ? Utils.findSoftwareDecoder(MIME_TYPE) : MediaCodec.createDecoderByType(MIME_TYPE);
            mediaCodec.configure(format, imageReader.getSurface(), null, 0);
            mediaCodec.start();

            Log.i(TAG, "startDecoder() soft=" + software + " dimensions=" + dimensions[0] + "x" + dimensions[1]);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private synchronized void stopDecoder() {
        Log.i(TAG, "stopDecoder()");
        hasStop = true;
        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (imageThread != null) {
            imageThread.quitSafely();
        }
    }
}