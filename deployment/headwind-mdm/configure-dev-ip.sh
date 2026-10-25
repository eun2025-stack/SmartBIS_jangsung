#!/usr/bin/env bash
set -Eeuo pipefail

# Configure the separate Headwind deployment on the current development LAN IP.
# Run from Git Bash; this script never prints generated secrets or private keys.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
MDM_DIR="$SCRIPT_DIR"
ENV_FILE="$MDM_DIR/.env"
CERT_DIR="$MDM_DIR/certs"
ROOT_CA_DIR="$REPO_ROOT/server/nginx/certs"
ROOT_COMPOSE="$REPO_ROOT/compose.yaml"
MDM_COMPOSE="$MDM_DIR/compose.yaml"
ROOT_XML="$MDM_DIR/volumes/hmdm-config/ROOT.xml"
INIT_SQL="$MDM_DIR/volumes/work/init.sql"
fail() { printf '?¤ë¥˜: %s\n' "$1" >&2; exit 1; }
need_file() { [[ -f "$1" ]] || fail "?Œì¼???†ìŠµ?ˆë‹¤: $1"; }

need_file "$ENV_FILE"
need_file "$ROOT_COMPOSE"
need_file "$MDM_COMPOSE"
need_file "$ROOT_XML"
need_file "$ROOT_CA_DIR/rootCA.crt"
need_file "$ROOT_CA_DIR/rootCA.key"
need_file "$ROOT_CA_DIR/rootCA.srl"
command -v openssl >/dev/null 2>&1 || fail 'Git Bash?ì„œ openssl??ì°¾ì„ ???†ìŠµ?ˆë‹¤.'
command -v podman >/dev/null 2>&1 || fail 'PATH?ì„œ podman??ì°¾ì„ ???†ìŠµ?ˆë‹¤.'

env_value() {
  local key="$1"
  sed -n "s/^${key}=//p" "$ENV_FILE" | tail -n 1
}

MDM_IP="${1:-$(env_value MDM_HOST)}"
[[ "$MDM_IP" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || fail 'MDM_HOST??IPv4 ì£¼ì†Œ?¬ì•¼ ?©ë‹ˆ??'
IFS=. read -r o1 o2 o3 o4 <<< "$MDM_IP"
for octet in "$o1" "$o2" "$o3" "$o4"; do
  (( 10#$octet <= 255 )) || fail '?˜ëª»??IPv4 ì£¼ì†Œ?…ë‹ˆ??'
done

SQL_USER="$(env_value SQL_USER)"
SQL_BASE="$(env_value SQL_BASE)"
[[ -n "$SQL_BASE" ]] || SQL_BASE=hmdm
[[ "$SQL_USER" =~ ^[A-Za-z0-9_]+$ ]] || fail 'SQL_USER ê°’ì´ ?†ê±°??ì§€?í•˜ì§€ ?ŠëŠ” ?•ì‹?…ë‹ˆ??'

mkdir -p "$CERT_DIR"
tmp_dir="$(mktemp -d "$CERT_DIR/.ip-cert.XXXXXX")"
cleanup() {
  [[ "$tmp_dir" == "$CERT_DIR"/.ip-cert.* ]] || return 0
  rm -f -- "$tmp_dir/privkey.pem" "$tmp_dir/mdm.csr" "$tmp_dir/cert.pem" "$tmp_dir/openssl.cnf"
  rmdir -- "$tmp_dir" 2>/dev/null || true
}
trap cleanup EXIT

printf 'ê°œë°œ IP %s???¬ì„¤ ?¸ì¦?œë? ë°œê¸‰?˜ê³  ?ˆìŠµ?ˆë‹¤...\n' "$MDM_IP"
printf '[req]\nprompt = no\ndistinguished_name = dn\nreq_extensions = v3_req\n[dn]\nCN = %s\n[v3_req]\nbasicConstraints = critical,CA:FALSE\nkeyUsage = critical,digitalSignature,keyEncipherment\nextendedKeyUsage = serverAuth\nsubjectAltName = @alt_names\n[alt_names]\nIP.1 = %s\nDNS.1 = headwind-mdm\n' \
  "$MDM_IP" "$MDM_IP" > "$tmp_dir/openssl.cnf"

openssl req -new -newkey rsa:2048 -nodes \
  -keyout "$tmp_dir/privkey.pem" \
  -out "$tmp_dir/mdm.csr" \
  -config "$tmp_dir/openssl.cnf" -reqexts v3_req \
  || fail '?¸ì¦???”ì²­ ?ì„±???¤íŒ¨?ˆìŠµ?ˆë‹¤. ??OpenSSL ?¤ë¥˜ë¥??•ì¸?˜ì„¸??'

openssl x509 -req -in "$tmp_dir/mdm.csr" \
  -CA "$ROOT_CA_DIR/rootCA.crt" \
  -CAkey "$ROOT_CA_DIR/rootCA.key" \
  -CAserial "$ROOT_CA_DIR/rootCA.srl" \
  -out "$tmp_dir/cert.pem" -days 397 -sha256 \
  -extfile "$tmp_dir/openssl.cnf" -extensions v3_req \
  || fail 'ê¸°ì¡´ SmartBIS ?¬ì„¤ Root CAë¡??¸ì¦???œëª…???¤íŒ¨?ˆìŠµ?ˆë‹¤.'

openssl verify -CAfile "$ROOT_CA_DIR/rootCA.crt" -verify_ip "$MDM_IP" "$tmp_dir/cert.pem" \
  >/dev/null 2>&1 \
  || fail 'ë°œê¸‰ ?¸ì¦?œì˜ IP SAN/CA ê²€ì¦ì— ?¤íŒ¨?ˆìŠµ?ˆë‹¤.'

cp "$tmp_dir/cert.pem" "$CERT_DIR/cert.pem"
cp "$tmp_dir/cert.pem" "$CERT_DIR/fullchain.pem"
cp "$tmp_dir/privkey.pem" "$CERT_DIR/privkey.pem"
chmod 600 "$CERT_DIR/privkey.pem" 2>/dev/null || true

# Only change the advertised server IP. Preserve database credentials and the
# HMDM signing secret; rotating either is unrelated to an IP change and can
# invalidate an already initialized deployment.
sed -i "s|^MDM_HOST=.*|MDM_HOST=$MDM_IP|" "$ENV_FILE"
sed -i "s|^SERVER_LAN_IP=.*|SERVER_LAN_IP=$MDM_IP|" "$ENV_FILE"

sed -i "/name=\"base.url\"/c\    <Parameter name=\"base.url\" value=\"https://$MDM_IP:8443\"/>" "$ROOT_XML"
sed -i "/name=\"mqtt.server.uri\"/c\    <Parameter name=\"mqtt.server.uri\" value=\"0.0.0.0:31000\"/>" "$ROOT_XML"

# Seed URLs are useful only on a fresh initialization; update the source for future restores too.
if [[ -f "$INIT_SQL" ]]; then
  sed -i -E "s|https://[^/' ]+:8443/|https://$MDM_IP:8443/|g" "$INIT_SQL"
fi

# Enable the IP virtual host only after cert/key and secret configuration are ready.
sed -i 's|mdm.conf.template:/etc/nginx/conf.d/headwind-mdm.conf.template|mdm.conf.template:/etc/nginx/conf.d/headwind-mdm.conf|' "$ROOT_COMPOSE"

cd "$REPO_ROOT"
if ! podman compose run --rm --no-deps nginx nginx -t; then
  sed -i 's|mdm.conf.template:/etc/nginx/conf.d/headwind-mdm.conf:ro|mdm.conf.template:/etc/nginx/conf.d/headwind-mdm.conf.template:ro|' "$ROOT_COMPOSE"
  fail 'Nginx ?¤ì • ê²€?¬ê? ?¤íŒ¨?ˆìŠµ?ˆë‹¤. ê¸°ì¡´ ?¤í–‰ ì¤‘ì¸ Nginx???¬ì‹œ?‘í•˜ì§€ ?Šì•˜?µë‹ˆ??'
fi

cd "$MDM_DIR"
podman compose up -d --force-recreate hmdm
cd "$REPO_ROOT"
podman compose up -d --force-recreate nginx

printf '\n?¤ì • ?„ë£Œ: https://%s:8443 (Headwind) / https://%s:8443/api/ (SmartBIS)\n' "$MDM_IP" "$MDM_IP"
printf 'MQTT ì£¼ì†Œ??%s:31000?…ë‹ˆ?? DB ë¹„ë?ë²ˆí˜¸?€ Headwind ê³µìœ  ë¹„ë?ê°’ì? ? ì??ˆìŠµ?ˆë‹¤. ?¸ì¦??ê°œì¸?¤ëŠ” ì¶œë ¥?˜ì? ?Šì•˜?µë‹ˆ??\n' "$MDM_IP"
printf '?¤ìŒ ?•ì¸: podman compose ps ë°?curl --cacert server/nginx/certs/rootCA.crt https://%s:8443/\n' "$MDM_IP"
