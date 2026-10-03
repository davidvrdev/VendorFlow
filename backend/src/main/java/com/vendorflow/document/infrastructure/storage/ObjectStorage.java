package com.vendorflow.document.infrastructure.storage;

import java.io.InputStream;

/**
 * Private object storage for document files (ADR-0007). Keys are server-generated ({@code org/{uuid}/doc/{uuid}});
 * implementations must reject any key that does not match {@link StorageKeys#isValid}. Nothing here is ever exposed
 * publicly: downloads go through an authorized API endpoint.
 */
public interface ObjectStorage {

    /**
     * Stores the stream under {@code key} atomically (readers never see a partial object). The stream is read to its
     * end and NOT closed here. If reading fails, nothing is left behind.
     *
     * @param sizeHint expected size in bytes, or -1; informational (implementations must not trust it)
     */
    void put(String key, InputStream content, long sizeHint);

    /** @throws ObjectNotFoundException if the object does not exist. The caller closes the stream. */
    InputStream get(String key);

    /** Removes the object; an absent object is not an error. */
    void delete(String key);

    boolean exists(String key);
}
