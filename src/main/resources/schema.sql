-- H2 启动自动执行；MySQL 版本见 sql/init.sql
CREATE TABLE IF NOT EXISTS file_record (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    upload_id    VARCHAR(64)  NOT NULL UNIQUE,
    md5          VARCHAR(32)  NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    total_parts  INT          NOT NULL,
    status       VARCHAR(20)  NOT NULL,          -- UPLOADING / MERGED / WATERMARKED
    user_id      BIGINT       NOT NULL,
    wm_object_key VARCHAR(300) NULL,
    create_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS upload_chunk (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    upload_id   VARCHAR(64) NOT NULL,
    part_number INT         NOT NULL,
    etag        VARCHAR(64) NOT NULL,
    object_key  VARCHAR(300) NOT NULL,
    UNIQUE (upload_id, part_number)
);

-- 追溯表：泄露图片解码出 payload_hash 后反查 user_id + embed_time
CREATE TABLE IF NOT EXISTS watermark_trace (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    wm_object_key VARCHAR(300) NOT NULL,
    user_id       BIGINT      NOT NULL,
    payload_hash  CHAR(64)    NOT NULL,
    embed_time    TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    status        VARCHAR(10) NOT NULL           -- OK / FAILED
);
CREATE INDEX IF NOT EXISTS idx_trace_hash ON watermark_trace (payload_hash);
