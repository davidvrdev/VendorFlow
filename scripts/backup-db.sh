#!/usr/bin/env bash
# Encrypted logical backup of the production database (pg_dump | gpg symmetric AES-256).
#
# Usage:   BACKUP_DATABASE_URL='postgresql://user:pass@host:5432/postgres' \
#          BACKUP_PASSPHRASE_FILE=~/.vendorflow-backup-passphrase \
#          scripts/backup-db.sh [output-dir]
#
# Needs: pg_dump (client version >= server major, i.e. 17), gpg. Run from the owner's machine or a trusted job.
# The connection string comes from the environment so it never lands in shell history or the process list
# (do NOT pass it as an argument). The passphrase is read from a file with mode 600, never from the command line.
# Output: <output-dir>/vendorflow-YYYYmmddTHHMMSSZ.sql.gz.gpg (default dir: ./backups, gitignored).
# Restore drill: docs/RUNBOOKS.md, "Restore drill".
set -euo pipefail
umask 077

: "${BACKUP_DATABASE_URL:?Set BACKUP_DATABASE_URL (Supabase direct or session-pooler connection string)}"
: "${BACKUP_PASSPHRASE_FILE:?Set BACKUP_PASSPHRASE_FILE (file containing the encryption passphrase, chmod 600)}"
[ -r "$BACKUP_PASSPHRASE_FILE" ] || { echo "Cannot read BACKUP_PASSPHRASE_FILE" >&2; exit 1; }
command -v pg_dump >/dev/null || { echo "pg_dump not found (install the PostgreSQL 17 client)" >&2; exit 1; }
command -v gpg >/dev/null || { echo "gpg not found" >&2; exit 1; }

out_dir="${1:-./backups}"
mkdir -p "$out_dir"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
out="$out_dir/vendorflow-$stamp.sql.gz.gpg"
tmp="$out.partial"
trap 'rm -f "$tmp"' EXIT

# --no-owner/--no-privileges: the dump restores into any database/role. Schema + data of the public schema only
# (Supabase's own schemas are managed by Supabase and are not ours to restore).
pg_dump --dbname="$BACKUP_DATABASE_URL" --schema=public --no-owner --no-privileges --format=plain \
  | gzip -9 \
  | gpg --batch --yes --quiet --symmetric --cipher-algo AES256 --passphrase-file "$BACKUP_PASSPHRASE_FILE" -o "$tmp"

[ -s "$tmp" ] || { echo "Backup is empty: aborting" >&2; exit 1; }
mv "$tmp" "$out"
trap - EXIT
echo "Backup written: $out ($(wc -c < "$out") bytes)"
echo "Reminder: copy it OFF this machine and test a restore regularly (docs/RUNBOOKS.md)."
