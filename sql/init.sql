-- MySQL 8 建表（容器首次启动自动执行，见 docker-compose 挂载）
CREATE DATABASE IF NOT EXISTS wmdb;
USE wmdb;

CREATE TABLE IF NOT EXISTS file_record (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    upload_id     VARCHAR(64)  NOT NULL UNIQUE,
    md5           VARCHAR(32)  NOT NULL,
    file_name     VARCHAR(255) NOT NULL,
    total_parts   INT          NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    user_id       BIGINT       NOT NULL,
    wm_object_key VARCHAR(300) NULL,
    create_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_md5 (md5)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS upload_chunk (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    upload_id   VARCHAR(64)  NOT NULL,
    part_number INT          NOT NULL,
    etag        VARCHAR(64)  NOT NULL,
    object_key  VARCHAR(300) NOT NULL,
    UNIQUE KEY uk_part (upload_id, part_number)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS watermark_trace (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    wm_object_key VARCHAR(300) NOT NULL,
    user_id       BIGINT      NOT NULL,
    payload_hash  CHAR(64)    NOT NULL,
    embed_time    TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    status        VARCHAR(10) NOT NULL,
    INDEX idx_trace_hash (payload_hash)
) ENGINE=InnoDB;
