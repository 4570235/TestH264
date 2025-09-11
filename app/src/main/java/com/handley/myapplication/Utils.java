package com.handley.myapplication;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class Utils {

    public static final String TAG = "[Test]";

    // 提取SPS和PPS（流式版本）
    public static byte[][] extractSpsPps(File file) throws IOException {
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
    public static MediaCodec findSoftwareDecoder(String mimeType) {
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
                            Log.e(TAG, "findSoftwareDecoder() Failed: " + codecInfo.getName(), e);
                        }
                    }
                }
            }
        }
        return null;
    }
}
