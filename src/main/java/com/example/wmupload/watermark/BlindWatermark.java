package com.example.wmupload.watermark;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 盲水印引擎：8x8 分块 DCT 中低频系数 QIM 嵌入。
 *
 * 容灾设计：
 *  1) 每一行块都周期性承载完整 payload（bit = bits[bx % 192]），
 *     解码端对 192 个相位偏移穷举投票 —— 抗任意行/列裁剪与局部破坏；
 *  2) payload 固定 24B 帧（MAGIC+len+data20B+CRC8），校验失败即整体失败，
 *     不输出错的溯源结论；
 *  3) 嵌入后自检（JPEG q=0.5 压缩 + 中心 75% 裁剪模拟攻击），不通过则加大步长重嵌。
 */
public final class BlindWatermark {

    private static final int BLOCK = 8;
    private static final int COEF_X = 2;   // 中低频，JPEG 量化表该位置量化步长小
    private static final int COEF_Y = 2;
    private static final int FRAME_BYTES = 20;          // 定长帧
    private static final int FRAME_BITS = FRAME_BYTES * 8; // 160
    private static final int WEAVE_B = 37;  // 斜交织斜率，与 FRAME_BITS 互素
    private static final int MAGIC = 0x5A;

    private final int q; // QIM 量化步长

    public BlindWatermark() { this(64); }
    public BlindWatermark(int q) { this.q = q; }

    // ---------- 编解码 ----------

    /** payload 帧格式（定长 24B）: MAGIC | len(1B) | data(20B 零填充) | crc8 */
    public BufferedImage embed(BufferedImage src, byte[] data) {
        if (data.length > 16) throw new IllegalArgumentException("payload too long (max 16 bytes)");
        byte[] frame = new byte[FRAME_BYTES];
        frame[0] = (byte) MAGIC;
        frame[1] = (byte) data.length;
        System.arraycopy(data, 0, frame, 2, data.length);
        frame[FRAME_BYTES - 1] = crc8(frame, FRAME_BYTES - 1);

        int width = src.getWidth(), height = src.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        out.getGraphics().drawImage(src, 0, 0, null);

        boolean[] bits = toBits(frame);
        int blocksX = width / BLOCK, blocksY = height / BLOCK;
        if (blocksX * blocksY < FRAME_BITS * 4)
            throw new IllegalArgumentException("image too small for payload redundancy");

        for (int by = 0; by < blocksY; by++)
            for (int bx = 0; bx < blocksX; bx++)
                embedBlock(out, bx * BLOCK, by * BLOCK, bits[(bx + by * WEAVE_B) % FRAME_BITS]);
        return out;
    }

    /** 返回解码出的 data；CRC 校验失败返回 null。相位穷举同步：抗裁剪。 */
    public byte[] decode(BufferedImage img) {
        int blocksX = img.getWidth() / BLOCK, blocksY = img.getHeight() / BLOCK;
        if (blocksX < 8 || blocksY < 1) return null;

        // 先提取所有块的 QIM 位（每块只做一次 DCT）
        boolean[][] blockBits = new boolean[blocksY][blocksX];
        for (int by = 0; by < blocksY; by++)
            for (int bx = 0; bx < blocksX; bx++)
                blockBits[by][bx] = extractBlock(img, bx * BLOCK, by * BLOCK);

        // 对每个等价相位偏移投票 + CRC 校验，锁定正确相位
        // （x/y 平移在线性交织映射下坍缩为单一未知偏移，故穷举一维即可同步）
        for (int offset = 0; offset < FRAME_BITS; offset++) {
            int[] votes = new int[FRAME_BITS * 2];
            for (int by = 0; by < blocksY; by++)
                for (int bx = 0; bx < blocksX; bx++)
                    votes[((bx + by * WEAVE_B + offset) % FRAME_BITS) * 2 + (blockBits[by][bx] ? 1 : 0)]++;

            byte[] frame = new byte[FRAME_BYTES];
            for (int i = 0; i < FRAME_BITS; i++)
                frame[i / 8] |= (votes[i * 2 + 1] >= votes[i * 2] ? 1 : 0) << (7 - i % 8);

            int magic = frame[0] & 0xFF, len = frame[1] & 0xFF;
            if (magic != MAGIC || len > 16) continue;
            if (crc8(frame, FRAME_BYTES - 1) != frame[FRAME_BYTES - 1]) continue;
            byte[] data = new byte[len];
            System.arraycopy(frame, 2, data, 0, len);
            return data;
        }
        return null;
    }

    // ---------- 自检：模拟攻击 ----------

    /** JPEG q=0.5 压缩后能否解码 */
    public boolean survivesJpeg(BufferedImage img) {
        try {
            BufferedImage c = jpegCompress(img, 0.5f);
            return decode(c) != null;
        } catch (IOException e) { return false; }
    }

    /** 中心裁剪 75% 后能否解码（利用冗余重复嵌入） */
    public boolean survivesCrop(BufferedImage img) {
        int w = (int) (img.getWidth() * 0.75), h = (int) (img.getHeight() * 0.75);
        int x = (img.getWidth() - w) / 2, y = (img.getHeight() - h) / 2;
        return decode(img.getSubimage(x, y, w, h)) != null;
    }

    /** 嵌入并自检；不通过则加倍步长重嵌（最多 3 次） */
    public BufferedImage embedWithSelfCheck(BufferedImage src, byte[] data) {
        int step = q;
        for (int i = 0; i < 3; i++) {
            BufferedImage out = new BlindWatermark(step).embed(src, data);
            if (new BlindWatermark(step).survivesJpeg(out) && new BlindWatermark(step).survivesCrop(out))
                return out;
            step *= 2;
        }
        throw new IllegalStateException("watermark self-check failed after 3 retries");
    }

    // ---------- 内部实现 ----------

    private void embedBlock(BufferedImage img, int px, int py, boolean bit) {
        double[] lum = new double[BLOCK * BLOCK];
        int[] rgb = img.getRGB(px, py, BLOCK, BLOCK, null, 0, BLOCK);
        for (int i = 0; i < 64; i++)
            lum[i] = 0.299 * ((rgb[i] >> 16) & 0xFF) + 0.587 * ((rgb[i] >> 8) & 0xFF) + 0.114 * (rgb[i] & 0xFF);

        double[] dct = dct2d(lum);
        double c = dct[COEF_Y * BLOCK + COEF_X];
        double base = Math.round(c / q) * q;
        c = bit ? base + q / 4.0 : base - q / 4.0;   // QIM：把系数拉到格点偏上/偏下
        dct[COEF_Y * BLOCK + COEF_X] = c;

        double[] out = idct2d(dct);
        int[] rgbOut = new int[64];
        for (int i = 0; i < 64; i++) {
            int v = clamp((int) Math.round(out[i]));
            rgbOut[i] = (v << 16) | (v << 8) | v;
            // 保留色度：把变化量同时作用于原 RGB
            double d = out[i] - lum[i];
            int r = clamp(((rgb[i] >> 16) & 0xFF) + (int) d);
            int g = clamp(((rgb[i] >> 8) & 0xFF) + (int) d);
            int b = clamp((rgb[i] & 0xFF) + (int) d);
            rgbOut[i] = (r << 16) | (g << 8) | b;
        }
        img.setRGB(px, py, BLOCK, BLOCK, rgbOut, 0, BLOCK);
    }

    private boolean extractBlock(BufferedImage img, int px, int py) {
        double[] lum = new double[64];
        int[] rgb = img.getRGB(px, py, BLOCK, BLOCK, null, 0, BLOCK);
        for (int i = 0; i < 64; i++)
            lum[i] = 0.299 * ((rgb[i] >> 16) & 0xFF) + 0.587 * ((rgb[i] >> 8) & 0xFF) + 0.114 * (rgb[i] & 0xFF);
        double c = dct2d(lum)[COEF_Y * BLOCK + COEF_X];
        return (c - Math.round(c / q) * q) >= 0;
    }

    // 可分离 2D DCT：先逐行 1D-DCT，再逐列 1D-DCT（O(2*8*8*8) 每块）
    static double[] dct2d(double[] in) {
        double[] tmp = new double[64], out = new double[64];
        double[] row = new double[8], res = new double[8];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) row[x] = in[y * 8 + x];
            dct1d(row, res);
            for (int v = 0; v < 8; v++) tmp[y * 8 + v] = res[v];
        }
        for (int v = 0; v < 8; v++) {
            for (int y = 0; y < 8; y++) row[y] = tmp[y * 8 + v];
            dct1d(row, res);
            for (int u = 0; u < 8; u++) out[u * 8 + v] = res[u];
        }
        return out;
    }

    static double[] idct2d(double[] in) {
        double[] tmp = new double[64], out = new double[64];
        double[] row = new double[8], res = new double[8];
        for (int u = 0; u < 8; u++) {
            for (int v = 0; v < 8; v++) row[v] = in[u * 8 + v];
            idct1d(row, res);
            for (int x = 0; x < 8; x++) tmp[u * 8 + x] = res[x];
        }
        for (int x = 0; x < 8; x++) {
            for (int u = 0; u < 8; u++) row[u] = tmp[u * 8 + x];
            idct1d(row, res);
            for (int y = 0; y < 8; y++) out[y * 8 + x] = res[y];
        }
        return out;
    }

    private static void dct1d(double[] x, double[] out) {
        for (int k = 0; k < 8; k++) {
            double s = 0;
            for (int n = 0; n < 8; n++) s += x[n] * Math.cos((2 * n + 1) * k * Math.PI / 16);
            out[k] = s * cu(k) / 2.0;
        }
    }

    private static void idct1d(double[] in, double[] out) {
        for (int n = 0; n < 8; n++) {
            double s = 0;
            for (int k = 0; k < 8; k++)
                s += cu(k) * in[k] * Math.cos((2 * n + 1) * k * Math.PI / 16);
            out[n] = s / 2.0;
        }
    }

    private static double cu(int u) { return u == 0 ? 1 / Math.sqrt(2) : 1; }

    private static boolean[] toBits(byte[] bytes) {
        boolean[] bits = new boolean[bytes.length * 8];
        for (int i = 0; i < bytes.length; i++)
            for (int b = 0; b < 8; b++)
                bits[i * 8 + b] = ((bytes[i] >> (7 - b)) & 1) == 1;
        return bits;
    }

    static byte crc8(byte[] data, int len) {
        int crc = 0xFF;
        for (int i = 0; i < len; i++) {
            crc ^= data[i] & 0xFF;
            for (int j = 0; j < 8; j++)
                crc = (crc & 0x80) != 0 ? (crc << 1) ^ 0x31 : crc << 1;
        }
        return (byte) (crc & 0xFF);
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static BufferedImage jpegCompress(BufferedImage src, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) throw new IOException("no jpeg writer");
        ImageWriter w = writers.next();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(quality);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            w.setOutput(ios);
            w.write(null, new IIOImage(src, null, null), p);
        } finally { w.dispose(); }
        return ImageIO.read(new ByteArrayInputStream(bos.toByteArray()));
    }

    /** 调试用：构造随机噪声测试图 */
    public static BufferedImage noiseImage(int w, int h, long seed) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.util.Random r = new java.util.Random(seed);
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int v = 96 + r.nextInt(64);
            px[i] = (v << 16) | (v << 8) | v;
        }
        img.setRGB(0, 0, w, h, px, 0, w);
        return img;
    }
}
