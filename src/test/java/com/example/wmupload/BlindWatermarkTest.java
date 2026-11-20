package com.example.wmupload;

import com.example.wmupload.watermark.BlindWatermark;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BlindWatermarkTest {

    private static final String PAYLOAD = "42:1732000000";

    @Test
    void embedThenDecode() {
        BufferedImage src = BlindWatermark.noiseImage(1280, 1280, 1);
        BlindWatermark wm = new BlindWatermark();
        BufferedImage out = wm.embed(src, PAYLOAD.getBytes(StandardCharsets.UTF_8));
        assertEquals(PAYLOAD, new String(wm.decode(out), StandardCharsets.UTF_8));
    }

    @Test
    void survivesJpegQ50() {
        BufferedImage src = BlindWatermark.noiseImage(1280, 1280, 2);
        BlindWatermark wm = new BlindWatermark();
        BufferedImage out = wm.embed(src, PAYLOAD.getBytes(StandardCharsets.UTF_8));
        assertTrue(wm.survivesJpeg(out), "watermark lost after JPEG q=0.5");
    }

    @Test
    void survivesCenterCrop75() {
        BufferedImage src = BlindWatermark.noiseImage(1280, 1280, 3);
        BlindWatermark wm = new BlindWatermark();
        BufferedImage out = wm.embed(src, PAYLOAD.getBytes(StandardCharsets.UTF_8));
        assertTrue(wm.survivesCrop(out), "watermark lost after 75% crop");
    }

    @Test
    void selfCheckPathEndsToEnd() throws Exception {
        BufferedImage src = BlindWatermark.noiseImage(1280, 1280, 4);
        BufferedImage out = new BlindWatermark().embedWithSelfCheck(src, PAYLOAD.getBytes(StandardCharsets.UTF_8));
        assertEquals(PAYLOAD, new String(new BlindWatermark().decode(out), StandardCharsets.UTF_8));
        // round-trip through PNG bytes (真实文件 IO)
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bos);
        BufferedImage re = ImageIO.read(new java.io.ByteArrayInputStream(bos.toByteArray()));
        assertEquals(PAYLOAD, new String(new BlindWatermark().decode(re), StandardCharsets.UTF_8));
    }
}
