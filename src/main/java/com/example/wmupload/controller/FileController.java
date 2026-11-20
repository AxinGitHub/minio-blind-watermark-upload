package com.example.wmupload.controller;

import com.example.wmupload.service.FileService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * 分片上传。首片（uploadId 为空）自动初始化，MD5 命中且已带水印直接秒传。
     * multipart: chunk(文件分片), partNumber, totalParts, fileMd5, fileName, userId
     */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestPart("chunk") MultipartFile chunk,
                                      @RequestParam int partNumber,
                                      @RequestParam int totalParts,
                                      @RequestParam String fileMd5,
                                      @RequestParam String fileName,
                                      @RequestParam long userId,
                                      @RequestParam(required = false) String uploadId) throws Exception {
        return fileService.uploadChunk(chunk, partNumber, totalParts, fileMd5, fileName, userId, uploadId);
    }

    /** 合并分片 → 同步嵌盲水印 → 返回水印版 fileUrl。全部到齐也可在 upload 中自动合并。 */
    @PostMapping("/complete")
    public Map<String, Object> complete(@RequestBody Map<String, String> body) throws Exception {
        return fileService.complete(body.get("uploadId"));
    }
}
