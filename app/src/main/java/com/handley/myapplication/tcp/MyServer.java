package com.handley.myapplication.tcp;


import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.handley.myapplication.common.MediaMessageHeader;
import com.handley.myapplication.common.MyFrame;
import com.handley.myapplication.common.MyFrameCallback;
import com.handley.myapplication.common.Utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class MyServer {
    private static final String TAG = Utils.TAG + "MyServer";
    private final MyFrameCallback myFrameCallback;
    private final int port;
    private final String outputFileName;  // 新增：输出文件路径
    private final Context context;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean isRunning = false;
    private OutputStream fileOutputStream;  // 新增：文件输出流

    // 修改构造方法，增加文件路径参数
    public MyServer(MyFrameCallback callback, int port, String outputFileName, Context context) {
        this.myFrameCallback = callback;
        this.port = port;
        this.outputFileName = outputFileName;
        this.context = context.getApplicationContext();
    }

    public void start() {
        if (isRunning) {
            Log.w(TAG, "Server already running");
            return;
        }

        // 初始化文件输出流
        if (!TextUtils.isEmpty(outputFileName)) {
            File dumpFile = new File(this.context.getExternalFilesDir(null), outputFileName);
            try {
                fileOutputStream = new BufferedOutputStream(new FileOutputStream(dumpFile.getAbsolutePath()));
                Log.i(TAG, "Output file opened: " + dumpFile.getAbsolutePath());
            } catch (IOException e) {
                Log.e(TAG, "Error opening output file: " + e.getMessage());
                fileOutputStream = null; // 确保为null避免后续操作
            }
        }

        isRunning = true;
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(this.port);
                Log.i(TAG, "Server started on port " + this.port);

                while (isRunning) {
                    try (Socket clientSocket = serverSocket.accept();
                         InputStream inputStream = clientSocket.getInputStream();
                         BufferedInputStream bis = new BufferedInputStream(inputStream)) {

                        Log.i(TAG, "Client connected: " + clientSocket.getInetAddress());
                        processClientData(bis);
                    } catch (IOException e) {
                        if (isRunning) {
                            Log.e(TAG, "Client connection error: " + e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Server error: " + e.getMessage());
            } finally {
                closeServerSocket();
                closeFileOutputStream(); // 确保关闭文件流
            }
        });

        serverThread.start();
    }

    private void processClientData(BufferedInputStream bis) throws IOException {
        byte[] headerBuffer = new byte[MediaMessageHeader.SIZE];
        int bytesRead;

        while (isRunning) {
            // 1. 读取帧头
            bytesRead = bis.read(headerBuffer, 0, MediaMessageHeader.SIZE);
            if (bytesRead != MediaMessageHeader.SIZE) {
                if (bytesRead == -1) {
                    Log.i(TAG, "End of stream reached");
                } else {
                    Log.w(TAG, "Incomplete header: " + bytesRead + " bytes");
                }
                break;
            }

            // 2. 解析帧头
            MediaMessageHeader header = MediaMessageHeader.parse(headerBuffer);
            if (header.magic != MediaMessageHeader.MAGIC) {
                Log.e(TAG, "Invalid magic number: 0x" + Integer.toHexString(header.magic));
                break;
            }

            // 3. 读取帧数据
            byte[] frameData = new byte[header.dataLen];
            bytesRead = bis.read(frameData, 0, header.dataLen);
            if (bytesRead != header.dataLen) {
                Log.e(TAG, "Incomplete frame data: expected " + header.dataLen + ", got " + bytesRead);
                break;
            }

            // 4. 保存原始二进制数据（帧头+帧数据）
            if (fileOutputStream != null) {
                try {
                    //fileOutputStream.write(headerBuffer); // 写入帧头
                    fileOutputStream.write(frameData);    // 写入帧数据
                    fileOutputStream.flush(); // 确保数据写入磁盘
                } catch (IOException e) {
                    Log.e(TAG, "File write error: " + e.getMessage());
                    closeFileOutputStream(); // 出错时关闭流
                }
            }

            // 5. 回调帧数据（原有功能保留）
            if (myFrameCallback != null) {
                myFrameCallback.onFrameReceived(new MyFrame(header, frameData));
            }
        }
    }

    public void stop() {
        isRunning = false;

        closeServerSocket();
        closeFileOutputStream(); // 停止时关闭文件流

        if (serverThread != null && serverThread.isAlive()) {
            serverThread.interrupt();
            try {
                serverThread.join(500);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while stopping server thread");
            }
        }
    }

    private void closeServerSocket() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                Log.i(TAG, "closeServerSocket()");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing server socket: " + e.getMessage());
        }
    }

    // 新增：安全关闭文件输出流
    private void closeFileOutputStream() {
        if (fileOutputStream != null) {
            try {
                fileOutputStream.flush();
                fileOutputStream.close();
                Log.i(TAG, "Output file closed");
            } catch (IOException e) {
                Log.e(TAG, "Error closing file stream: " + e.getMessage());
            } finally {
                fileOutputStream = null;
            }
        }
    }
}