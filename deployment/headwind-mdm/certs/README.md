# MDM TLS certificate files

Copy the MDM-host certificate files here at deployment time. They are ignored by Git:

- `fullchain.pem` — certificate for the MDM DNS hostname (and any intermediate certificates)
- `privkey.pem` — matching private key
- `cert.pem` — leaf certificate file consumed by the Headwind container

The leaf certificate must include the exact MDM DNS name as a DNS Subject Alternative Name and be signed by the SmartBIS private CA trusted by the Android devices. Do not put the CA private key in this directory. Keep `cert.pem` as the leaf certificate and `fullchain.pem` as leaf plus any intermediates.
