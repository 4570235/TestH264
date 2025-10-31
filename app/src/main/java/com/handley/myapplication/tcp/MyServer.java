package com.handley.myapplication.tcp;


import android.util.Log;
import androidx.annotation.NonNull;
import com.handley.myapplication.common.MediaMessageHeader;
import com.handley.myapplication.common.MyFrame;
import com.handley.myapplication.common.MyFrameCallback;
import com.handley.myapplication.common.Utils;
import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MyServer {

    private static final String TAG = Utils.TAG + "MyServer";
    @NonNull
    private final MyFrameCallback myFrameCallback;
    private final int port;
    private final String saveDirectory; // 保存目录
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean isRunning = false;

    // 统计信息
    private long totalPackets = 0;        // 总数据包数
    private long totalBytes = 0;          // 总字节数
    private long lastStatsTime = 0;       // 上次统计时间
    private long lastStatsBytes = 0;      // 上次统计的字节数
    private PacketStatsCallback statsCallback;

    // 数据包保存功能
    private FileOutputStream packetFileOutputStream;     // 数据包文件输出流（仅payload）
    private FileOutputStream rawPacketFileOutputStream;   // 原始数据包文件输出流（帧头+payload）
    private String saveFilePath;                        // 保存文件路径
    private String rawSaveFilePath;                     // 原始数据包保存文件路径

    // 统计信息回调接口
    public interface PacketStatsCallback {
        void onStatsUpdate(long packets, long bytes, float bytesPerSecond);
    }

    public MyServer(@NonNull MyFrameCallback callback, int port, String saveDirectory) {
        this.myFrameCallback = callback;
        this.port = port;
        this.saveDirectory = saveDirectory;
    }

    public void setStatsCallback(PacketStatsCallback callback) {
        this.statsCallback = callback;
    }

    // 初始化保存文件
    private synchronized void initSaveFiles() {
        try {
            // 生成带时间戳的文件名
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
            String timestamp = sdf.format(new Date());
            
            // 创建payload文件
            saveFilePath = saveDirectory + "/received_packets_" + timestamp + ".bin";
            packetFileOutputStream = new FileOutputStream(saveFilePath);
            
            // 创建原始数据包文件
            rawSaveFilePath = saveDirectory + "/received_packets_" + timestamp + ".raw";
            rawPacketFileOutputStream = new FileOutputStream(rawSaveFilePath);
            
            Log.i(TAG, "Initialized save files:");
            Log.i(TAG, "  Payload file: " + saveFilePath);
            Log.i(TAG, "  Raw packet file: " + rawSaveFilePath);
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize save files: " + e.getMessage());
            closeSaveFiles();
        }
    }

    // 关闭保存文件
    private synchronized void closeSaveFiles() {
        // 关闭payload文件
        if (packetFileOutputStream != null) {
            try {
                packetFileOutputStream.flush();
                packetFileOutputStream.close();
                Log.i(TAG, "Closed payload file: " + saveFilePath);
            } catch (IOException e) {
                Log.e(TAG, "Error closing packet file: " + e.getMessage());
            } finally {
                packetFileOutputStream = null;
            }
        }
        
        // 关闭原始数据包文件
        if (rawPacketFileOutputStream != null) {
            try {
                rawPacketFileOutputStream.flush();
                rawPacketFileOutputStream.close();
                Log.i(TAG, "Closed raw packet file: " + rawSaveFilePath);
            } catch (IOException e) {
                Log.e(TAG, "Error closing raw packet file: " + e.getMessage());
            } finally {
                rawPacketFileOutputStream = null;
            }
        }
    }

    public void start() {
        if (isRunning) {
            Log.w(TAG, "Server already running");
            return;
        }

        isRunning = true;
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(this.port);
                Log.i(TAG, "start() Server started on port " + this.port);

                while (isRunning) {
                    try (Socket clientSocket = serverSocket.accept();
                            InputStream inputStream = clientSocket.getInputStream();
                            BufferedInputStream bis = new BufferedInputStream(inputStream)) {

                        Log.i(TAG, "start() Client connected: " + clientSocket.getInetAddress());
                        
                        // 客户端连接时初始化保存文件
                        initSaveFiles();
                        
                        processClientData(bis);
                        
                        // 客户端断开时关闭保存文件
                        closeSaveFiles();
                        
                        Log.i(TAG, "start() finish Client data: " + clientSocket.getInetAddress());
                    } catch (IOException e) {
                        if (isRunning) {
                            Log.e(TAG, "start() Client connection error: " + e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "start() Server error: " + e.getMessage());
            } finally {
                closeServerSocket();
            }
        });

        serverThread.start();
    }

    private void processClientData(BufferedInputStream bis) throws IOException {
        byte[] headerBuffer = new byte[MediaMessageHeader.SIZE];
        int bytesRead;

        Log.i(TAG, "processClientData() started, waiting for data...");
        
        while (isRunning) {
            // 1. 读取帧头
            int totalHeaderBytesRead = 0;
            Log.i(TAG, "Waiting to read header (" + MediaMessageHeader.SIZE + " bytes)...");
            
            while (totalHeaderBytesRead < MediaMessageHeader.SIZE && isRunning) {
                
                Log.i(TAG, "Available bytes before read: " + bis.available());

                bytesRead = bis.read(headerBuffer, totalHeaderBytesRead,
                        MediaMessageHeader.SIZE - totalHeaderBytesRead);
                
                Log.i(TAG, "Header read attempt: bytesRead=" + bytesRead + ", totalHeaderBytesRead=" + totalHeaderBytesRead);
                
                if (bytesRead == -1) {
                    Log.i(TAG, "End of stream reached during header read");
                    break;
                }
                totalHeaderBytesRead += bytesRead;
            }
            
            if (totalHeaderBytesRead != MediaMessageHeader.SIZE) {
                Log.w(TAG, "Incomplete header: expected " + MediaMessageHeader.SIZE + ", got " + totalHeaderBytesRead);
                break;
            }

            Log.i(TAG, "Header read complete, parsing...");
            
            // 2. 解析帧头
            MediaMessageHeader header = MediaMessageHeader.parse(headerBuffer);
            if (header.magic != MediaMessageHeader.MAGIC) {
                Log.e(TAG, "Invalid magic number: 0x" + Integer.toHexString(header.magic));
                break;
            }

            Log.i(TAG, "Header parsed successfully, dataLen=" + header.dataLen);
            
            // 3. 读取帧数据
            byte[] frameData = new byte[header.dataLen];
            int totalBytesRead = 0;
            while (totalBytesRead < header.dataLen && isRunning) {
                bytesRead = bis.read(frameData, totalBytesRead, header.dataLen - totalBytesRead);
                if (bytesRead == -1) {
                    Log.e(TAG, "Unexpected end of stream while reading frame data");
                    break;
                }
                
                totalBytesRead += bytesRead;
            }
            if (totalBytesRead != header.dataLen) {
                Log.e(TAG, "Incomplete frame data: expected " + header.dataLen + ", got " + totalBytesRead);
                break;
            }

            // 自动保存数据包
            if (packetFileOutputStream != null && rawPacketFileOutputStream != null) {
                try {
                    // 保存payload
                    packetFileOutputStream.write(frameData, 0, totalBytesRead);
                    
                    // 保存原始TCP包（帧头 + payload）
                    rawPacketFileOutputStream.write(headerBuffer, 0, MediaMessageHeader.SIZE);
                    rawPacketFileOutputStream.write(frameData, 0, totalBytesRead);
                } catch (IOException e) {
                    Log.e(TAG, "Error saving packet to file: " + e.getMessage());
                    closeSaveFiles();
                }
            }

            // 更新统计信息
            totalPackets++;
            totalBytes += MediaMessageHeader.SIZE + header.dataLen;
            updateStats();

            // 4. 回调帧数据
            Log.i(TAG, "Received frame: type=" + header.type + ", length=" + header.dataLen + ", timestamp="
                    + header.timestamp);
            myFrameCallback.onFrameReceived(new MyFrame(header, frameData));
        }
        
        Log.i(TAG, "processClientData() finished");
    }

    private void updateStats() {
        long currentTime = System.currentTimeMillis();
        if (lastStatsTime == 0) {
            lastStatsTime = currentTime;
            lastStatsBytes = totalBytes;
            return;
        }

        // 每秒更新一次统计
        if (currentTime - lastStatsTime >= 1000) {
            long bytesDiff = totalBytes - lastStatsBytes;
            float bytesPerSecond = bytesDiff * 1000.0f / (currentTime - lastStatsTime);

            if (statsCallback != null) {
                statsCallback.onStatsUpdate(totalPackets, totalBytes, bytesPerSecond);
            }

            lastStatsTime = currentTime;
            lastStatsBytes = totalBytes;
        }
    }

    public void stop() {
        isRunning = false;

        // 关闭保存文件
        closeSaveFiles();

        closeServerSocket();

        if (serverThread != null && serverThread.isAlive()) {
            serverThread.interrupt();
            try {
                serverThread.join(500);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while stopping server thread");
            }
        }
        Log.i(TAG, "stop() finish");
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
}