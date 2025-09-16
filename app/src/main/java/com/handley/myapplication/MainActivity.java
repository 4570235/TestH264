package com.handley.myapplication;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import com.handley.myapplication.MyServer.OnH264DataListener;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

// 演示 TCP Client MyServer 发送 dump.h264(含私有协议头) 文件
public class MainActivity extends AppCompatActivity implements OnH264DataListener, Callback {

    private static final String TAG = Utils.TAG + "MainActivity";
    private static final String MIME_TYPE = "video/avc";
    private static final int FRAME_RATE = 25; // 假设帧率
    private final MyServer myServer = new MyServer(this);
    private MediaCodec mediaCodec;
    private SurfaceView surfaceView;
    private Surface surface;
    private byte[] sps = null;
    private byte[] pps = null;
    private volatile boolean hasStop = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        surfaceView = findViewById(R.id.surface_view);
        surfaceView.getHolder().addCallback(this);

        // 将 dump.h264 文件复制到外部存储目录
        AssetsFileCopier.copyAssetToExternalFilesDir(this, "dump.h264");

        Button btn = findViewById(R.id.btn);
        btn.setVisibility(View.VISIBLE);
        // 点击启动客户端发送 dump.h264 文件。
        btn.setOnClickListener(v -> new MyClient(MainActivity.this).sendH264File());

        // 启动服务器。
        myServer.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        myServer.stop();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopDecoder();
    }


    @Override
    public void onDataReceived(byte[] data, long presentationTimeUs, int rotation) {
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
                Log.v(TAG, "parseH264Data nalType=" + nalType);
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
                            submitFrame(currentFrame.toByteArray(), presentationTimeUs);
                            Log.d(TAG, "submitFrame");
                            currentFrame.reset();
                            isWaitingForIDR = false;
                        } else {
                            // 提交单个NAL单元
                            submitSingleFrame(nal, presentationTimeUs);
                            Log.d(TAG, "submitSingleFrame");
                        }
                        break;

                    case 1: // 非IDR Slice
                        if (isWaitingForIDR) {
                            // 错误处理：预期IDR但遇到普通帧
                            currentFrame.reset();
                            isWaitingForIDR = false;
                        }
                        submitSingleFrame(nal, presentationTimeUs);
                        Log.d(TAG, "submitSingleFrame");
                        break;

                    default:
                        // 其他NAL类型（可选处理）
                        break;
                }

                // 处理输出
                drainOutput();
                Log.d(TAG, "drainOutput");
            }
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

    private void drainOutput() {
        if (hasStop) {
            return;
        }
        if (mediaCodec == null) {
            return;
        }
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int outputBufferIndex;

        while ((outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0)) >= 0) {
            // 渲染帧
            mediaCodec.releaseOutputBuffer(outputBufferIndex, true);
        }
    }

    private void startDecoder() {
        try {
            Log.i(TAG, "startDecoder()");
            // 1. 初始化MediaCodec
            final boolean soft = false;
            mediaCodec = soft ? Utils.findSoftwareDecoder(MIME_TYPE) : MediaCodec.createDecoderByType(MIME_TYPE);

            // 从SPS中解析视频宽度
            int[] dimensions = SpsParser.parseSps(sps);

            // 3. 创建并配置MediaFormat
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, dimensions[0], dimensions[1]);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);

            mediaCodec.configure(format, surface, null, 0);
            mediaCodec.start();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void stopDecoder() {
        Log.i(TAG, "stopDecoder()");
        hasStop = true;
        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
            mediaCodec = null;
        }
    }
}