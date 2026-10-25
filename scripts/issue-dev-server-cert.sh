#!/usr/bin/env bash
set -euo pipefail

# Reissue the existing Nginx leaf certificate for the confirmed test-server IP.
# The CA private key is used locally and is never printed or copied.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CERT_DIR="$ROOT_DIR/server/nginx/certs"
MDM_CERT_DIR="$ROOT_DIR/deployment/headwind-mdm/certs"
SERVER_IP="${1:-192.168.100.50}"
if [[ ! "$SERVER_IP" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
  echo "Usage: $0 [server-ipv4]" >&2
  exit 2
fi
IFS=. read -r octet1 octet2 octet3 octet4 <<< "$SERVER_IP"
for octet in "$octet1" "$octet2" "$octet3" "$octet4"; do
  (( 10#$octet <= 255 )) || { echo "Invalid IPv4 address: $SERVER_IP" >&2; exit 2; }
done

for required in rootCA.crt rootCA.key server.key rootCA.srl; do
  [[ -f "$CERT_DIR/$required" ]] || { echo "Missing $CERT_DIR/$required" >&2; exit 1; }
done
[[ -f "$MDM_CERT_DIR/privkey.pem" ]] || { echo "Missing $MDM_CERT_DIR/privkey.pem" >&2; exit 1; }

OPENSSL_BIN="$(command -v openssl || true)"
[[ -n "$OPENSSL_BIN" ]] || { echo "OpenSSL is not on PATH; run this from Git Bash." >&2; exit 1; }

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

cat > "$WORK_DIR/server.ext" <<EOF
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=@alt_names

[alt_names]
DNS.1=localhost
DNS.2=headwind-mdm
IP.1=127.0.0.1
IP.2=10.0.2.2
IP.3=$SERVER_IP
EOF

cat > "$WORK_DIR/req.cnf" <<'EOF'
[req]
distinguished_name=dn
prompt=no
[dn]
CN=localhost
EOF

"$OPENSSL_BIN" req -new -key "$CERT_DIR/server.key" \
  -out "$WORK_DIR/server.csr" -config "$WORK_DIR/req.cnf"
"$OPENSSL_BIN" x509 -req -in "$WORK_DIR/server.csr" \
  -CA "$CERT_DIR/rootCA.crt" -CAkey "$CERT_DIR/rootCA.key" \
  -CAserial "$CERT_DIR/rootCA.srl" -days 825 -sha256 \
  -extfile "$WORK_DIR/server.ext" -out "$WORK_DIR/server.crt"

"$OPENSSL_BIN" req -new -key "$MDM_CERT_DIR/privkey.pem" \
  -out "$WORK_DIR/mdm.csr" -config "$WORK_DIR/req.cnf"
"$OPENSSL_BIN" x509 -req -in "$WORK_DIR/mdm.csr" \
  -CA "$CERT_DIR/rootCA.crt" -CAkey "$CERT_DIR/rootCA.key" \
  -CAserial "$CERT_DIR/rootCA.srl" -days 825 -sha256 \
  -extfile "$WORK_DIR/server.ext" -out "$WORK_DIR/mdm.crt"

"$OPENSSL_BIN" verify -CAfile "$CERT_DIR/rootCA.crt" "$WORK_DIR/server.crt"
"$OPENSSL_BIN" verify -CAfile "$CERT_DIR/rootCA.crt" -verify_ip "$SERVER_IP" "$WORK_DIR/server.crt"
"$OPENSSL_BIN" verify -CAfile "$CERT_DIR/rootCA.crt" -verify_ip "$SERVER_IP" "$WORK_DIR/mdm.crt"
"$OPENSSL_BIN" x509 -in "$WORK_DIR/server.crt" -noout \
  -subject -issuer -dates -ext subjectAltName
"$OPENSSL_BIN" x509 -in "$WORK_DIR/mdm.crt" -noout \
  -subject -issuer -dates -ext subjectAltName

BACKUP="$CERT_DIR/server.crt.backup-$(date +%Y%m%d-%H%M%S)"
cp -p "$CERT_DIR/server.crt" "$BACKUP"
install -m 0644 "$WORK_DIR/server.crt" "$CERT_DIR/server.crt"
MDM_BACKUP="$MDM_CERT_DIR/fullchain.pem.backup-$(date +%Y%m%d-%H%M%S)"
cp -p "$MDM_CERT_DIR/fullchain.pem" "$MDM_BACKUP"
install -m 0644 "$WORK_DIR/mdm.crt" "$MDM_CERT_DIR/cert.pem"
install -m 0644 "$WORK_DIR/mdm.crt" "$MDM_CERT_DIR/fullchain.pem"
echo "Server certificate installed; previous copy backed up to $(basename "$BACKUP")."
echo "Headwind virtual-host certificate installed; previous copy backed up to $(basename "$MDM_BACKUP")."
echo "Next: from project root run: podman compose up -d --force-recreate nginx"
