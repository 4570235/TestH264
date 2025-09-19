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
import com.handley.myapplication.common.MediaMessageHeader;
import com.handley.myapplication.common.MyFrame;
import com.handley.myapplication.common.Utils;
import com.handley.myapplication.tcp.MyClient;
import com.handley.myapplication.tcp.MyServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

// 演示 MyVideoClient 向 MyVideoServer 发送 dump.h264(含私有协议头) 文件数据流。解码成 yuv420 数据保存成 jpg 文件。
public class H264ActivityTcpYuv extends AppCompatActivity {

    private static final String TAG = Utils.TAG + "H264ActivityTcpYuv";
    private final BlockingQueue<MyFrame> frameQueue = new LinkedBlockingQueue<>(25); // 帧缓冲队列
    private Button videoBtn, audioBtn;
    private MyServer myServer;
    private MyClient myClient;
    private MediaCodec mediaCodec;
    private long startTime = Long.MIN_VALUE; // 播放开始时间（毫秒）
    private Thread decodeThread;
    private volatile boolean decodeThreadRunning = false;
    private int outputFrameIndex = 0;
    private ImageReader imageReader;
    private HandlerThread imageThread;

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
        final int port = 23333;

        // 点击启动客户端发送文件
        videoBtn.setOnClickListener(v -> {
            myClient = new MyClient(this, "dump.h264", port);
            myClient.start();
            videoBtn.setEnabled(false);// 防止重复点击
        });

        // 创建并启动服务器
        myServer = new MyServer((frame) -> {
            // 处理接收到的帧数据
            Log.d(TAG, "Received frame: type=" + frame.header.type + ", length=" + frame.header.dataLen + ", timestamp=" + frame.header.timestamp);
            if (frame.header.type != MediaMessageHeader.H264) return;

            if (startTime == Long.MIN_VALUE) {
                long currentTime = System.nanoTime() / 1000000;
                startTime = currentTime - frame.header.timestamp;
                Log.i(TAG, "onFrameReceived init currentTime=" + currentTime + " pts=" + frame.header.timestamp + " startTime=" + startTime);
            }

            // 将帧存入队列，视频帧不能丢失，否则后续解不出来。要丢就得一直丢到下一个i帧。
            try {
                frameQueue.put(frame);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
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
                    Log.v(TAG, "decode pts=" + frame.header.timestamp);

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
    private void decodeData(byte[] data, long pts) {
        byte[] sps = null, pps = null;
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
                Log.v(TAG, "decodeData() nalType=" + nalType + " len=" + nal.length + " pts=" + pts);
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
                            configMediaCodec(sps, pps);
                            submitFrame(currentFrame.toByteArray(), pts);//带 sps pps 的I帧，直接提交
                            currentFrame.reset();
                            isWaitingForIDR = false;
                        } else {
                            submitSingleFrame(nal, pts);//不带 sps pps 的I帧，补起始码后提交
                        }
                        break;

                    case 1: // 非IDR Slice
                        if (isWaitingForIDR) {
                            currentFrame.reset();
                            isWaitingForIDR = false;
                        }
                        submitSingleFrame(nal, pts);//P帧，补起始码后提交
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

    private synchronized void submitSingleFrame(byte[] nal, long pts) {
        if (mediaCodec == null) {
            Log.e(TAG, "submitSingleFrame: mediaCodec is null");
            return;
        }
        ByteBuffer frameData = ByteBuffer.allocate(nal.length + 4);
        frameData.putInt(0x00000001); // 统一使用4字节起始码
        frameData.put(nal);
        frameData.flip();
        submitFrame(frameData.array(), pts);
    }

    private synchronized void submitFrame(byte[] frameData, long presentationTimeUs) {
        if (mediaCodec == null) {
            return;
        }
        try {
            int inputBufferIndex = mediaCodec.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(frameData);
                    mediaCodec.queueInputBuffer(inputBufferIndex, 0, frameData.length, presentationTimeUs, 0);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "submitFrame error", e);
        }
    }

    private synchronized void drainOutput() {
        if (mediaCodec == null) {
            return;
        }
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int outputBufferIndex;

        while ((outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000)) >= 0) {
            // 控制渲染时机
            controlSpeed(bufferInfo.presentationTimeUs, 2);

            // 渲染帧
            Log.v(TAG, "releaseOutputBuffer pts=" + bufferInfo.presentationTimeUs);
            mediaCodec.releaseOutputBuffer(outputBufferIndex, true);
        }
    }

    private synchronized void configMediaCodec(byte[] sps, byte[] pps) {
        if (mediaCodec != null) {
            return;
        }
        try {
            if (sps == null || pps == null) {
                Log.w(TAG, "initMediaCodecIfNeeded: SPS/PPS not available");
                return;
            }

            // 从SPS中解析视频宽高
            int[] dimensions = Utils.parseSps(sps);

            // 创建ImageReader获取YUV数据
            imageThread = new HandlerThread("ImageThread");
            imageThread.start();
            Handler imageThreadHandler = new Handler(imageThread.getLooper());
            imageReader = ImageReader.newInstance(dimensions[0], dimensions[1], ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Log.i(TAG, "onImageAvailable frameIndex=" + outputFrameIndex);
                try (Image image = imageReader.acquireLatestImage()) { // 自动关闭
                    if (image == null) {
                        return;
                    }
                    if (outputFrameIndex++ < 30) {
                        File file = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                                "frame_" + outputFrameIndex + ".jpg");
                        Utils.saveImageAsJpeg(image, file);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, imageThreadHandler);

            // 创建并配置MediaFormat
            String MIME_TYPE = "video/avc";
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, dimensions[0], dimensions[1]);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(Utils.addStartCode(sps)));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(Utils.addStartCode(pps)));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 25);

            // 初始化MediaCodec
            final boolean software = false; // 是否使用软件解码器
            mediaCodec = software ? Utils.findSoftwareDecoder(MIME_TYPE) : MediaCodec.createDecoderByType(MIME_TYPE);
            mediaCodec.configure(format, imageReader.getSurface(), null, 0);
            mediaCodec.start();

            Log.i(TAG, "initMediaCodecIfNeeded() soft=" + software + " dimensions=" + dimensions[0] + "x" + dimensions[1]);
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "initMediaCodecIfNeeded failed", e);
        }
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
        release();
    }

    private synchronized void release() {
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

        // 停止ImageReader
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (imageThread != null) {
            imageThread.quitSafely();
        }

        Log.i(TAG, "release()");
    }
}