package com.vendorflow.document.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * {@code app.documents.*}. The multipart limits in application.yml are deliberately slightly ABOVE max-size so a file
 * just over the limit reaches our own check (clear 413 message) instead of the container's.
 */
@Component
@ConfigurationProperties(prefix = "app.documents")
public class DocumentProperties {

    private DataSize maxSize = DataSize.ofMegabytes(15);

    /** Total stored bytes per organization (all document states: superseded/archived files are still stored). */
    private DataSize orgQuota = DataSize.ofGigabytes(5);

    public DataSize getOrgQuota() {
        return orgQuota;
    }

    public void setOrgQuota(DataSize orgQuota) {
        this.orgQuota = orgQuota;
    }

    public long orgQuotaBytes() {
        return orgQuota.toBytes();
    }

    public DataSize getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(DataSize maxSize) {
        this.maxSize = maxSize;
    }

    public long maxSizeBytes() {
        return maxSize.toBytes();
    }
}
