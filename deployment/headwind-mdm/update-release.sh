#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

read_env() {
  sed -n "s/^$1=//p" .env | tail -n 1 | tr -d '\r'
}

EXPECTED_WAR="https://h-mdm.com/files/hmdm-5.41.1-os.war"
EXPECTED_AGENT="6.39"
CONFIGURED_WAR="$(read_env HMDM_URL)"
CONFIGURED_AGENT="$(read_env CLIENT_VERSION)"
SQL_USER="$(read_env SQL_USER)"
SQL_BASE="$(read_env SQL_BASE)"
[[ "$CONFIGURED_WAR" == "$EXPECTED_WAR" ]] || {
  echo "HMDM_URL is not set to the verified 5.41.1 WAR; stopping." >&2; exit 1;
}
[[ "$CONFIGURED_AGENT" == "$EXPECTED_AGENT" ]] || {
  echo "CLIENT_VERSION is not set to the paired 6.39 agent; stopping." >&2; exit 1;
}
[[ -n "$SQL_USER" ]] || { echo "SQL_USER is missing in .env." >&2; exit 1; }
[[ -n "$SQL_BASE" ]] || SQL_BASE="hmdm"

DB_CONTAINER="smartbis-headwind-mdm-postgresql-1"
WAR_PATH="volumes/webapps/ROOT.war"
[[ -s "$WAR_PATH" ]] || { echo "Current deployed ROOT.war is missing." >&2; exit 1; }

STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="backups/release-$STAMP"
mkdir -p "$BACKUP_DIR"
cp -p "$WAR_PATH" "$BACKUP_DIR/ROOT.war"
cp -a volumes/hmdm-config "$BACKUP_DIR/hmdm-config"

echo "Backing up Headwind database..."
podman exec --user postgres "$DB_CONTAINER" \
  pg_dump --no-owner --no-privileges --username="$SQL_USER" \
  --dbname="$SQL_BASE" > "$BACKUP_DIR/hmdm-database.sql"
[[ -s "$BACKUP_DIR/hmdm-database.sql" ]] || {
  echo "Database backup is empty; refusing upgrade." >&2; exit 1;
}

TEMP_WAR="$BACKUP_DIR/ROOT-5.41.1.war"
curl.exe --fail --location --retry 2 "$EXPECTED_WAR" --output "$TEMP_WAR"
python -c "import sys,zipfile; z=zipfile.ZipFile(sys.argv[1]); bad=z.testzip(); print('WAR archive OK' if bad is None else 'Corrupt WAR entry: '+bad); sys.exit(bad is not None)" "$TEMP_WAR"

echo "Stopping only the Headwind application container..."
podman compose stop hmdm
cp "$TEMP_WAR" "$WAR_PATH"
echo "Starting Headwind with WAR 5.41.1 / Agent 6.39..."
podman compose up -d hmdm
echo "Upgrade submitted. Backup saved at $BACKUP_DIR"
echo "Check: podman compose logs --tail=100 hmdm"
