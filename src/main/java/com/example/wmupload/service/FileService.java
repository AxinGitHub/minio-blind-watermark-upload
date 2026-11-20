package com.example.wmupload.service;

import com.example.wmupload.watermark.BlindWatermark;
import io.minio.*;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FileService {

    private final MinioClient minio;
    private final JdbcTemplate jdbc;
    private final String rawBucket;
    private final String wmBucket;
    private final String endpoint;

    public FileService(MinioClient minio, JdbcTemplate jdbc,
                       @Value("${minio.raw-bucket}") String rawBucket,
                       @Value("${minio.wm-bucket}") String wmBucket,
                       @Value("${minio.endpoint}") String endpoint) {
        this.minio = minio;
        this.jdbc = jdbc;
        this.rawBucket = rawBucket;
        this.wmBucket = wmBucket;
        this.endpoint = endpoint;
    }

    // ---------- 1. 分片上传（首片初始化 + 秒传 + 幂等） ----------

    public Map<String, Object> uploadChunk(MultipartFile chunk, int partNumber, int totalParts,
                                           String fileMd5, String fileName, long userId,
                                           String uploadId) throws Exception {
        // 秒传：MD5 命中且水印版已存在
        List<Map<String, Object>> hits = jdbc.queryForList(
                "SELECT wm_object_key FROM file_record WHERE md5 = ? AND status = 'WATERMARKED' LIMIT 1", fileMd5);
        if (!hits.isEmpty()) {
            return Map.of("instant", true, "fileUrl", publicUrl((String) hits.get(0).get("wm_object_key")));
        }

        // 首片：初始化上传任务
        if (uploadId == null || uploadId.isBlank()) {
            uploadId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO file_record(upload_id, md5, file_name, total_parts, status, user_id) VALUES(?,?,?,?,?,?)",
                    uploadId, fileMd5, fileName, totalParts, "UPLOADING", userId);
        }

        // 幂等：同分片重复上传直接跳过
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM upload_chunk WHERE upload_id = ? AND part_number = ?", Integer.class, uploadId, partNumber);
        if (exists != null && exists > 0) {
            return Map.of("uploadId", uploadId, "partNumber", partNumber, "skipped", true);
        }

        String objectKey = "raw/" + uploadId + "/part-" + partNumber;
        try (var in = chunk.getInputStream()) {
            minio.putObject(PutObjectArgs.builder()
                    .bucket(rawBucket).object(objectKey)
                    .stream(in, chunk.getSize(), -1)
                    .build());
        }
        jdbc.update("INSERT INTO upload_chunk(upload_id, part_number, etag, object_key) VALUES(?,?,?,?)",
                uploadId, partNumber, "stored", objectKey);

        return Map.of("uploadId", uploadId, "partNumber", partNumber, "skipped", false);
    }

    // ---------- 2. 合并 + 盲水印 ----------

    public Map<String, Object> complete(String uploadId) throws Exception {
        List<Map<String, Object>> recs = jdbc.queryForList(
                "SELECT md5, file_name, total_parts, status, user_id FROM file_record WHERE upload_id = ?", uploadId);
        if (recs.isEmpty()) throw new IllegalArgumentException("uploadId not found: " + uploadId);
        Map<String, Object> rec = recs.get(0);

        if ("WATERMARKED".equals(rec.get("status"))) {
            String key = jdbc.queryForObject(
                    "SELECT wm_object_key FROM file_record WHERE upload_id = ?", String.class, uploadId);
            return Map.of("fileUrl", publicUrl(key), "alreadyDone", true);
        }

        int totalParts = ((Number) rec.get("total_parts")).intValue();
        Integer done = jdbc.queryForObject(
                "SELECT COUNT(*) FROM upload_chunk WHERE upload_id = ?", Integer.class, uploadId);
        if (done == null || done != totalParts)
            throw new IllegalStateException("chunks incomplete: " + done + "/" + totalParts);

        // 按序拼接分片 → 内存合并（demo 假设图片可整体载入；生产应流式处理）
        ByteArrayOutputStream merged = new ByteArrayOutputStream();
        List<String> keys = jdbc.queryForList(
                "SELECT object_key FROM upload_chunk WHERE upload_id = ? ORDER BY part_number", String.class, uploadId);
        byte[] buf = new byte[8192];
        for (String key : keys) {
            try (var in = minio.getObject(GetObjectArgs.builder().bucket(rawBucket).object(key).build())) {
                int n;
                while ((n = in.read(buf)) > 0) merged.write(buf, 0, n);
            }
        }
        byte[] rawBytes = merged.toByteArray();

        // 校验 MD5
        String actualMd5 = HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5").digest(rawBytes));
        if (!actualMd5.equals(rec.get("md5")))
            throw new IllegalStateException("md5 mismatch: " + actualMd5);

        // 盲水印：payload = "userId:epoch"，自检通过才返回（内部含 JPEG/裁剪攻击模拟）
        long userId = ((Number) rec.get("user_id")).longValue();
        String payload = userId + ":" + Instant.now().getEpochSecond();
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(rawBytes));
        if (src == null) throw new IllegalArgumentException("not an image / unsupported format");
        BufferedImage marked = new BlindWatermark().embedWithSelfCheck(src,
                payload.getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(marked, "png", out);
        String wmKey = "wm/" + uploadId + ".png";
        try (var in = new ByteArrayInputStream(out.toByteArray())) {
            minio.putObject(PutObjectArgs.builder()
                    .bucket(wmBucket).object(wmKey)
                    .stream(in, out.size(), -1)
                    .contentType("image/png")
                    .build());
        }

        // 追溯记录：payload_hash → user_id + embed_time，泄露后解码 hash 反查
        String payloadHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO watermark_trace(wm_object_key, user_id, payload_hash, status) VALUES(?,?,?,?)",
                wmKey, userId, payloadHash, "OK");
        jdbc.update("UPDATE file_record SET status = 'WATERMARKED', wm_object_key = ? WHERE upload_id = ?",
                wmKey, uploadId);

        return Map.of("fileUrl", publicUrl(wmKey), "wmObjectKey", wmKey, "payloadHash", payloadHash);
    }

    private String publicUrl(String objectKey) {
        return endpoint + "/" + wmBucket + "/" + objectKey;
    }
}
