#!/usr/bin/env bash
# Самоподписанный сертификат для локальной проверки TLS: deploy/certs/fullchain.pem + privkey.pem.
# Для боя нужен настоящий сертификат (Let's Encrypt, корпоративный CA): docs/guide/operations.md.
#
#   deploy/gen-self-signed.sh                        # localhost, 127.0.0.1, ::1
#   deploy/gen-self-signed.sh s3.example.test 10.0.0.5   # свои имена и IP (первое — CN)
set -euo pipefail
# the private key is created with mode 600 from the start (no window with a world-readable key)
umask 077

out="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/certs"
names=("$@")
[ ${#names[@]} -gt 0 ] || names=(localhost)

san="DNS:localhost,IP:127.0.0.1,IP:::1"
for n in "${names[@]}"; do
  case "$n" in localhost|127.0.0.1|::1) continue ;; esac
  if [[ "$n" =~ ^[0-9.]+$ || "$n" == *:* ]]; then san+=",IP:$n"; else san+=",DNS:$n"; fi
done

mkdir -p "$out"
openssl req -x509 -newkey rsa:2048 -sha256 -days 365 -nodes \
  -keyout "$out/privkey.pem" -out "$out/fullchain.pem" \
  -subj "/CN=${names[0]}" -addext "subjectAltName=$san" \
  -addext "basicConstraints=critical,CA:TRUE"
chmod 600 "$out/privkey.pem"
chmod 644 "$out/fullchain.pem"
echo "written: $out/fullchain.pem, $out/privkey.pem (SAN: $san, 365 days)"
echo "note: the certificate is its own CA (CA:TRUE): trust it only on test machines, never as a shared trust anchor"
echo "trust it in clients: curl --cacert $out/fullchain.pem, aws --ca-bundle $out/fullchain.pem"
