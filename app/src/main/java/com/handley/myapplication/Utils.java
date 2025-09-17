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

    public static final String TAG = "[TestH264]";

    // 提取SPS和PPS, 返回SPS和PPS的字节数组，不带起始码 0x00 0x00 0x00 0x01
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

            System.out.println("SPS Hex: " + bytesToHex(sps));
            System.out.println("PPS Hex: " + bytesToHex(pps));
            return new byte[][]{sps, pps};
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString();
    }

    public static byte[] addStartCode(byte[] data) {
        byte[] newData = new byte[data.length + 4];
        // 设置起始码：0x00, 0x00, 0x00, 0x01
        newData[0] = 0x00;
        newData[1] = 0x00;
        newData[2] = 0x00;
        newData[3] = 0x01;
        // 复制原始数据到新数组
        System.arraycopy(data, 0, newData, 4, data.length);
        return newData;
    }

    public static int[] parseSps(byte[] sps) {
        // 跳过 NAL 头 (通常为 0x67)
        int offset = 0;
        if (sps.length > 0 && (sps[0] & 0x1F) == 7) {
            offset = 1; // 跳过 NAL 单元类型字节
        }

        // 创建位读取器
        BitReader reader = new BitReader(sps, offset);

        try {
            // 解析 SPS 基本参数
            int profileIdc = reader.readBits(8);
            reader.readBits(1); // constraint_set0_flag
            reader.readBits(1); // constraint_set1_flag
            reader.readBits(1); // constraint_set2_flag
            reader.readBits(1); // constraint_set3_flag
            reader.readBits(4); // reserved_zero_4bits
            int levelIdc = reader.readBits(8);

            // 解析 SPS ID
            int seqParameterSetId = reader.readUE();

            // 处理不同 profile 的扩展参数
            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 ||
                    profileIdc == 244 || profileIdc == 44 || profileIdc == 83 ||
                    profileIdc == 86 || profileIdc == 118 || profileIdc == 128) {

                int chromaFormatIdc = reader.readUE();
                if (chromaFormatIdc == 3) {
                    reader.readBits(1); // separate_colour_plane_flag
                }

                reader.readUE(); // bit_depth_luma_minus8
                reader.readUE(); // bit_depth_chroma_minus8
                reader.readBits(1); // qpprime_y_zero_transform_bypass_flag

                if (reader.readBool()) { // seq_scaling_matrix_present_flag
                    for (int i = 0; i < (chromaFormatIdc != 3 ? 8 : 12); i++) {
                        if (reader.readBool()) { // seq_scaling_list_present_flag
                            // 跳过缩放列表
                            skipScalingList(reader);
                        }
                    }
                }
            }

            // 解析帧相关参数
            reader.readUE(); // log2_max_frame_num_minus4
            int picOrderCntType = reader.readUE();

            if (picOrderCntType == 0) {
                reader.readUE(); // log2_max_pic_order_cnt_lsb_minus4
            } else if (picOrderCntType == 1) {
                reader.readBits(1); // delta_pic_order_always_zero_flag
                reader.readSE(); // offset_for_non_ref_pic
                reader.readSE(); // offset_for_top_to_bottom_field
                int numRefFramesInPicOrderCntCycle = reader.readUE();
                for (int i = 0; i < numRefFramesInPicOrderCntCycle; i++) {
                    reader.readSE(); // offset_for_ref_frame
                }
            }

            reader.readUE(); // max_num_ref_frames
            reader.readBits(1); // gaps_in_frame_num_value_allowed_flag

            // 获取宽高信息
            int picWidthInMbsMinus1 = reader.readUE();
            int picHeightInMapUnitsMinus1 = reader.readUE();

            int frameMbsOnlyFlag = reader.readBits(1);
            if (frameMbsOnlyFlag == 0) {
                reader.readBits(1); // mb_adaptive_frame_field_flag
            }

            reader.readBits(1); // direct_8x8_inference_flag

            // 计算实际宽高
            int width = (picWidthInMbsMinus1 + 1) * 16;
            int height = (picHeightInMapUnitsMinus1 + 1) * 16;

            // 考虑帧/场编码模式
            if (frameMbsOnlyFlag == 0) {
                height *= 2; // 场编码模式下高度加倍
            }

            // 检查裁剪参数
            if (reader.readBool()) { // frame_cropping_flag
                int cropLeft = reader.readUE();
                int cropRight = reader.readUE();
                int cropTop = reader.readUE();
                int cropBottom = reader.readUE();

                // 应用裁剪
                width -= (cropLeft + cropRight) * 2;
                height -= (cropTop + cropBottom) * 2;
            }

            return new int[]{width, height};
        } catch (Exception e) {
            return new int[]{0, 0}; // 解析失败返回默认值
        }
    }

    // 跳过缩放列表数据
    private static void skipScalingList(BitReader reader) {
        int lastScale = 8;
        int nextScale = 8;
        int size = (reader.readBool() ? 16 : 64); // 缩放列表大小

        for (int j = 0; j < size; j++) {
            if (nextScale != 0) {
                int deltaScale = reader.readSE();
                nextScale = (lastScale + deltaScale + 256) % 256;
            }
            lastScale = (nextScale == 0) ? lastScale : nextScale;
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

    // 辅助类：位读取器
    private static class BitReader {

        private final byte[] data;
        private int byteOffset;
        private int bitOffset;

        public BitReader(byte[] data, int offset) {
            this.data = data;
            this.byteOffset = offset;
            this.bitOffset = 0;
        }

        // 读取指定位数
        public int readBits(int numBits) {
            int result = 0;
            for (int i = 0; i < numBits; i++) {
                if (byteOffset >= data.length) {
                    return 0;
                }

                int bit = (data[byteOffset] >> (7 - bitOffset)) & 1;
                result = (result << 1) | bit;

                if (++bitOffset == 8) {
                    bitOffset = 0;
                    byteOffset++;
                }
            }
            return result;
        }

        // 读取无符号指数哥伦布编码
        public int readUE() {
            int leadingZeroBits = -1;
            for (int b = 0; b == 0; leadingZeroBits++) {
                b = readBits(1);
            }
            return (1 << leadingZeroBits) - 1 + readBits(leadingZeroBits);
        }

        // 读取有符号指数哥伦布编码
        public int readSE() {
            int ue = readUE();
            return (ue % 2 == 0) ? -(ue / 2) : (ue + 1) / 2;
        }

        // 读取布尔值
        public boolean readBool() {
            return readBits(1) != 0;
        }
    }
}
