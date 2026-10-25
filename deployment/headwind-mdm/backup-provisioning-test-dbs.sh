#!/usr/bin/env bash
set -Eeuo pipefail

# Creates paired, private SQL dumps before the two-device factory-reset test.
# Does not stop containers, change rows, or include credentials in the dumps.
ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
MDM_DIR="$ROOT_DIR/deployment/headwind-mdm"

read_env() {
  local file="$1" key="$2"
  sed -n "s/^${key}=//p" "$file" | tail -n 1 | tr -d '\r'
}

verify_dump() {
  local path="$1"
  [[ -s "$path" ]] && tail -n 5 "$path" | grep -Eq '^-- PostgreSQL database dump complete$|^\\unrestrict .+$'
}

MDM_ENV="$MDM_DIR/.env"
SMARTBIS_ENV="$ROOT_DIR/.env"
[[ -f "$MDM_ENV" && -f "$SMARTBIS_ENV" ]] || {
  echo 'Required .env files are missing; no backup was started.' >&2
  exit 1
}

HMDM_USER="$(read_env "$MDM_ENV" SQL_USER)"
HMDM_DB="$(read_env "$MDM_ENV" SQL_BASE)"
SMARTBIS_USER="$(read_env "$SMARTBIS_ENV" POSTGRES_USER)"
SMARTBIS_DB="$(read_env "$SMARTBIS_ENV" POSTGRES_DB)"
HMDM_DB="${HMDM_DB:-hmdm}"
[[ -n "$HMDM_USER" && -n "$SMARTBIS_USER" && -n "$SMARTBIS_DB" ]] || {
  echo 'Database name/user settings are incomplete; no backup was started.' >&2
  exit 1
}

HMDM_CONTAINER="smartbis-headwind-mdm-postgresql-1"
SMARTBIS_CONTAINER="smartbis_jangsung-postgres-db-1"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$MDM_DIR/backups/provisioning-test-$STAMP"
umask 077
mkdir -p "$BACKUP_DIR"
if command -v cygpath >/dev/null 2>&1 && command -v icacls.exe >/dev/null 2>&1; then
  USER_SID="$(powershell.exe -NoProfile -Command '[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value' | tr -d '\r')"
  [[ "$USER_SID" =~ ^S-1-[0-9-]+$ ]] || {
    echo 'Could not resolve the current Windows user SID; refusing to write DB dumps.' >&2
    exit 1
  }
  MSYS_NO_PATHCONV=1 icacls.exe "$(cygpath -w "$BACKUP_DIR")" /inheritance:r \
    /grant:r "*${USER_SID}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' \
    /T /C >/dev/null
else
  chmod 700 "$BACKUP_DIR"
fi

cleanup() {
  [[ -n "${TMP_HMDM:-}" ]] && rm -f -- "$TMP_HMDM"
  [[ -n "${TMP_SMARTBIS:-}" ]] && rm -f -- "$TMP_SMARTBIS"
}
trap cleanup EXIT
TMP_HMDM="$BACKUP_DIR/hmdm.sql.tmp"
TMP_SMARTBIS="$BACKUP_DIR/smartbis.sql.tmp"

echo 'Backing up Headwind DB (no DB rows are changed)...'
podman exec --user postgres "$HMDM_CONTAINER" pg_dump --no-owner --no-privileges \
  --username="$HMDM_USER" --dbname="$HMDM_DB" > "$TMP_HMDM"
verify_dump "$TMP_HMDM" || {
  echo 'Headwind dump verification failed.' >&2; exit 1;
}
mv -- "$TMP_HMDM" "$BACKUP_DIR/hmdm.sql"

echo 'Backing up SmartBIS DB (no DB rows are changed)...'
podman exec --user postgres "$SMARTBIS_CONTAINER" pg_dump --no-owner --no-privileges \
  --username="$SMARTBIS_USER" --dbname="$SMARTBIS_DB" > "$TMP_SMARTBIS"
verify_dump "$TMP_SMARTBIS" || {
  echo 'SmartBIS dump verification failed.' >&2; exit 1;
}
mv -- "$TMP_SMARTBIS" "$BACKUP_DIR/smartbis.sql"

(
  cd "$BACKUP_DIR"
  sha256sum hmdm.sql smartbis.sql > SHA256SUMS
)
echo "Both DB dumps verified. Restricted backup directory: $BACKUP_DIR"
echo 'The directory is Git-ignored. Keep its dumps protected and do not restore them over a live DB without an explicit recovery plan.'
