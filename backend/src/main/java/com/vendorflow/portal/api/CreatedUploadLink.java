package com.vendorflow.portal.api;

/**
 * Response of link creation. {@code url} carries the raw token and exists ONLY in this response (and the e-mail);
 * the server keeps just the hash, so it cannot be shown again.
 */
public record CreatedUploadLink(UploadLinkView link, String url, boolean emailQueued) {
}
