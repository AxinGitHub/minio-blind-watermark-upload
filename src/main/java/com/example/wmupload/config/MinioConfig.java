package com.example.wmupload.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Bean
    public MinioClient minioClient(@Value("${minio.endpoint}") String endpoint,
                                    @Value("${minio.access-key}") String ak,
                                    @Value("${minio.secret-key}") String sk,
                                    @Value("${minio.raw-bucket}") String rawBucket,
                                    @Value("${minio.wm-bucket}") String wmBucket) throws Exception {
        MinioClient client = MinioClient.builder().endpoint(endpoint).credentials(ak, sk).build();
        for (String bucket : new String[]{rawBucket, wmBucket}) {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        return client;
    }
}
