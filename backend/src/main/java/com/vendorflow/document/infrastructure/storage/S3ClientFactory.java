package com.vendorflow.document.infrastructure.storage;

import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/** Builds the S3 client for an S3-compatible endpoint (Supabase Storage, MinIO in tests). */
public final class S3ClientFactory {

    private S3ClientFactory() {
    }

    public static S3Client create(String endpoint, String region, String accessKeyId, String secretAccessKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                // Supabase (and MinIO) require path-style: https://host/bucket/key, not bucket.host.
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                // The SDK defaults to adding CRC checksums to every request; S3-compatible services may reject them.
                // Only compute/validate when the operation requires it.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(60)))
                .build();
    }
}
