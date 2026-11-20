# minio-blind-watermark-upload

MinIO 分片上传（秒传/断点/幂等）+ 纯 Java 图片盲水印（分块 DCT + QIM）demo。
两个接口，全链路可跑。

## 架构

```
客户端 ──分片──► /files/upload ──► MinIO(raw-file 桶)     首片初始化 + MD5 秒传 + 幂等
客户端 ───────► /files/complete ─► 合并分片 → MD5 校验
                                  → 盲水印嵌入(uid:epoch)
                                  → 自检(JPEG q50 + 中心裁剪75%)
                                  → MinIO(wm-file 桶) + watermark_trace 追溯记录
```

## 快速开始

```bash
docker compose up -d                      # 起 MySQL + MinIO
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
# 或零依赖直接跑（H2 内存库，仅需 MinIO）：
# mvn spring-boot:run
```

## curl 全流程（假设 demo.png ≥ 1280×1280）

```bash
# 切 3 片
split -n 3 -d demo.png part_

# 逐片上传（首片不传 uploadId，响应里拿）
curl -F chunk=@part_00 -F partNumber=0 -F totalParts=3 \
     -F fileMd5=$(md5 -q demo.png) -F fileName=demo.png -F userId=42 \
     http://localhost:8080/api/v1/files/upload
curl -F chunk=@part_01 -F partNumber=1 -F totalParts=3 \
     -F fileMd5=$(md5 -q demo.png) -F fileName=demo.png -F userId=42 \
     -F uploadId=<上一步返回> http://localhost:8080/api/v1/files/upload
curl -F chunk=@part_02 -F partNumber=2 ... 同上

# 合并 + 盲水印（同步返回水印版 URL）
curl -X POST -H 'Content-Type: application/json' \
     -d '{"uploadId":"<id>"}' http://localhost:8080/api/v1/files/complete
```

## 盲水印容灾设计（不是随便画画的部分）

- **嵌入**：8×8 分块 DCT，(2,2) 中低频系数 QIM 量化嵌入（步长 q=64，失败加倍重试 3 次）
- **冗余交织**：`bit = (bx + 37·by) mod 160` 线性斜交织，每 bit 在全图有数百个副本；
  x/y 平移在该线性映射下坍缩为单一未知相位，解码端穷举 160 个偏移即可重新同步 → 抗任意矩形裁剪
- **纠错校验**：定长 20B 帧（MAGIC+len+data16B+CRC8），多数投票 + CRC 校验，失败即返回 null，
  绝不输出错误的溯源结论
- **主动自检**：`embedWithSelfCheck` 对输出做 JPEG q=0.5 压缩 + 中心 75% 裁剪模拟攻击，
  解码不过 → 加大步长重嵌（单元测试覆盖，`mvn test` 可验证）

## 追溯链路

水印 payload 为 `userId:epoch`，SHA-256 后写入 `watermark_trace`。
图片泄露时：解码图片 → 得 `userId:epoch` → hash 反查 `watermark_trace` → 定位泄露人及时间。
表结构见 [sql/init.sql](sql/init.sql)。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/v1/files/upload | 分片上传；首片自动初始化；MD5 命中秒传；重复分片幂等 |
| POST | /api/v1/files/complete | 合并 → 校验 MD5 → 嵌水印 → 存 wm-file 桶 + trace，返回 fileUrl |
