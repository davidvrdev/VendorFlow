package com.vendorflow.portal.api;

/**
 * Response of link creation. {@code url} carries the raw token and exists ONLY in this response (and the e-mail);
 * the server keeps just the hash, so it cannot be shown again. {@code emailSkippedReason} is
 * {@code "EMAIL_NOT_DELIVERABLE"} when an email was asked for but the vendor's address unsubscribed (link created, nothing
 * emailed, {@code emailQueued} false); null otherwise.
 */
public record CreatedUploadLink(UploadLinkView link, String url, boolean emailQueued, String emailSkippedReason) {
}
