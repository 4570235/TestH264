package com.handley.myapplication;

import java.io.IOException;
import java.io.InputStream;

// 流式NAL单元读取器（支持3字节和4字节起始码）
public class H264StreamReader {
    private static final int BUFFER_SIZE = 1024 * 1024; // 1MB 缓冲区

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
