package com.handley.myapplication;

// 起始码信息类
public class StartCodeInfo {

    public final int position; // 起始码在缓冲区中的位置（相对于bufferPos）
    public final int length;   // 起始码长度（3或4）

    public StartCodeInfo(int position, int length) {
        this.position = position;
        this.length = length;
    }
}
