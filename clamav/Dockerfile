# ClamAV daemon for upload scanning (ADR-0010). Private service: only the API connects, over the private network.
# Official image: runs freshclam (signature updates, several times a day) and clamd (TCP 3310) in one container.
FROM clamav/clamav:1.4

# The official entrypoint turns CLAMD_CONF_<Key> into clamd.conf lines.
# StreamMaxLength / MaxFileSize must be >= the upload cap (app.documents.max-size = 15 MB), otherwise clamd answers
# ERROR on a big file and the API fails closed (rejects the upload).
ENV CLAMD_CONF_StreamMaxLength=16M \
    CLAMD_CONF_MaxFileSize=16M \
    CLAMD_CONF_TCPSocket=3310 \
    CLAMD_CONF_TCPAddr=0.0.0.0 \
    CLAMD_CONF_MaxThreads=6 \
    CLAMD_CONF_ReadTimeout=60

EXPOSE 3310
# The signature database is loaded into ~1.3 GB of RAM: run on a plan with >= 2 GB. A fresh container downloads the
# database first (minutes); mount a disk on /var/lib/clamav to keep it between deploys.
HEALTHCHECK --interval=30s --timeout=10s --start-period=300s --retries=5 CMD ["clamdcheck.sh"]
