#!/usr/bin/env bash
# MAMA — create the STABLE pilot signing keystore and (optionally) load it into the
# GitHub Actions secrets the real-server-pilot workflow reads. Run this ONCE, on the
# OWNER'S machine. The signing key is generated here and stays the owner's secret; it
# is never committed, printed, or sent anywhere except (optionally) GitHub's encrypted
# Actions secret store. Losing the keystore or its password means the fleet can no
# longer update-install — so this script also writes a protected local backup you must
# keep safe (an encrypted off-machine copy).
#
# Usage:
#   scripts/make-pilot-keystore.sh                 # create keystore + local backup only
#   scripts/make-pilot-keystore.sh --set-secrets   # also set the 4 Actions secrets via gh
#
# Options / env:
#   --set-secrets              after creating the keystore, run `gh secret set` for all four
#   --repo <owner/repo>        target repo for gh secrets (default: lezhik0036-sys/-)
#   KEYSTORE_PATH=<path>       where to write the keystore (default: ./mama-pilot.jks)
#   KEY_ALIAS=<alias>          key alias (default: mama-pilot)
#
# Requirements: keytool (JDK) and openssl. `gh` only for --set-secrets (and you must be
# `gh auth login`-ed with repo access). Nothing here contacts SMS.RU or the server.
set -euo pipefail

REPO="lezhik0036-sys/-"
SET_SECRETS=0
while [ $# -gt 0 ]; do
  case "$1" in
    --set-secrets) SET_SECRETS=1 ;;
    --repo) shift; REPO="${1:?--repo needs owner/repo}" ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 64 ;;
  esac
  shift
done

KEYSTORE_PATH="${KEYSTORE_PATH:-./mama-pilot.jks}"
KEY_ALIAS="${KEY_ALIAS:-mama-pilot}"
BACKUP_FILE="${KEYSTORE_PATH%.jks}.secrets.txt"

command -v keytool >/dev/null || { echo "ERROR: keytool not found (install a JDK)." >&2; exit 78; }
command -v openssl >/dev/null || { echo "ERROR: openssl not found." >&2; exit 78; }

# Never clobber an existing fleet identity: a new key is a new app identity that forces
# testers to uninstall/reinstall. If you truly want a fresh key, move the old files aside.
if [ -e "$KEYSTORE_PATH" ]; then
  echo "ERROR: $KEYSTORE_PATH already exists. Refusing to overwrite the fleet signing key." >&2
  echo "       Use the existing keystore, or move it aside first if you really want a new one." >&2
  exit 1
fi

umask 077  # keystore + backup readable only by this user

# A strong random password. PKCS12 uses one password for the store and the key.
PILOT_PASSWORD="$(openssl rand -base64 33 | tr -d '\n/+=' | cut -c1-40)"

echo "Creating PKCS12 keystore $KEYSTORE_PATH (RSA-4096, 10000 days, alias $KEY_ALIAS)..."
keytool -genkeypair \
  -keystore "$KEYSTORE_PATH" -storetype PKCS12 \
  -alias "$KEY_ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=MAMA Pilot, O=MAMA, C=RU" \
  -storepass "$PILOT_PASSWORD" -keypass "$PILOT_PASSWORD" >/dev/null

# base64 of the keystore, one line (Linux: -w0; macOS: strip newlines).
if base64 --help 2>&1 | grep -q '\-w'; then
  KEYSTORE_B64="$(base64 -w0 "$KEYSTORE_PATH")"
else
  KEYSTORE_B64="$(base64 "$KEYSTORE_PATH" | tr -d '\n')"
fi

# Signing-cert fingerprint, so you can confirm every pilot build shares this identity.
CERT_SHA256="$(keytool -list -v -keystore "$KEYSTORE_PATH" -alias "$KEY_ALIAS" \
  -storepass "$PILOT_PASSWORD" 2>/dev/null | grep -i 'SHA256:' | head -n1 | sed 's/.*SHA256: //')"

# Protected local backup — the ONLY place the password is written. Keep it safe.
{
  echo "# MAMA pilot signing secrets — created $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "# KEEP THIS FILE SAFE AND OFF THE BUILD MACHINE (encrypted). Never commit it."
  echo "# These are the four GitHub Actions secrets the real-server-pilot workflow reads."
  echo "MAMA_PILOT_KEY_ALIAS=$KEY_ALIAS"
  echo "MAMA_PILOT_STORE_PASSWORD=$PILOT_PASSWORD"
  echo "MAMA_PILOT_KEY_PASSWORD=$PILOT_PASSWORD"
  echo "SIGNING_CERT_SHA256=$CERT_SHA256"
  echo "# MAMA_PILOT_KEYSTORE_B64 (one line) follows:"
  echo "MAMA_PILOT_KEYSTORE_B64=$KEYSTORE_B64"
} > "$BACKUP_FILE"

echo
echo "Keystore:        $KEYSTORE_PATH"
echo "Secrets backup:  $BACKUP_FILE  (chmod 600 — keep an encrypted off-machine copy)"
echo "Cert SHA-256:    ${CERT_SHA256:-<run keytool -list to view>}"
echo

if [ "$SET_SECRETS" -eq 1 ]; then
  command -v gh >/dev/null || { echo "ERROR: gh not found; cannot --set-secrets." >&2; exit 78; }
  echo "Setting the four Actions secrets on $REPO via gh..."
  printf '%s' "$KEYSTORE_B64"   | gh secret set MAMA_PILOT_KEYSTORE_B64   --repo "$REPO"
  printf '%s' "$PILOT_PASSWORD" | gh secret set MAMA_PILOT_STORE_PASSWORD --repo "$REPO"
  printf '%s' "$KEY_ALIAS"      | gh secret set MAMA_PILOT_KEY_ALIAS      --repo "$REPO"
  printf '%s' "$PILOT_PASSWORD" | gh secret set MAMA_PILOT_KEY_PASSWORD   --repo "$REPO"
  echo "Done. All four MAMA_PILOT_* secrets are set on $REPO."
else
  echo "Next: add these four GitHub Actions secrets (Settings -> Secrets and variables ->"
  echo "Actions) on $REPO, taking the values from $BACKUP_FILE:"
  echo "  MAMA_PILOT_KEYSTORE_B64, MAMA_PILOT_STORE_PASSWORD, MAMA_PILOT_KEY_ALIAS, MAMA_PILOT_KEY_PASSWORD"
  echo "Or re-run with --set-secrets (requires gh auth) to set them automatically."
fi
