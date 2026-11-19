#!/usr/bin/env bash
# MAMA — set up the STABLE pilot signing keystore and (optionally) load it into the
# GitHub Actions secrets the real-server-pilot workflow reads. Run this on the OWNER'S
# machine. The signing key is the owner's secret: it is never committed, printed, or sent
# anywhere except (optionally) GitHub's encrypted Actions secret store.
#
# IDEMPOTENT & non-destructive: if the keystore already exists, this script REUSES the
# existing fleet identity (it never overwrites it) — it re-derives the signing-cert
# fingerprint and can (re)set the four Actions secrets from the EXISTING keystore. Only
# when no keystore exists does it create one. A new key = a new app identity that forces
# testers to uninstall/reinstall, so the owner's key, once created, is preserved.
#
# Usage:
#   scripts/make-pilot-keystore.sh                 # create-if-absent OR reuse existing; local backup
#   scripts/make-pilot-keystore.sh --set-secrets   # also set the 4 Actions secrets via gh
#   scripts/make-pilot-keystore.sh --print-fingerprint   # just print the cert SHA-256 (reuse only)
#
# Options / env:
#   --set-secrets              set/refresh the 4 Actions secrets (from the created or existing keystore)
#   --print-fingerprint        print the signing-cert SHA-256 and exit (requires an existing keystore)
#   --repo <owner/repo>        target repo for gh secrets (default: lezhik0036-sys/-)
#   KEYSTORE_PATH=<path>       keystore location (default: ./mama-pilot.jks)
#   KEY_ALIAS=<alias>          key alias (default: mama-pilot)
#   MAMA_PILOT_STORE_PASSWORD  REQUIRED to reuse an existing keystore (else you are prompted)
#
# Requirements: keytool (JDK) and openssl. `gh` only for --set-secrets (gh auth login with
# repo access). Nothing here contacts SMS.RU or the server.
set -euo pipefail

REPO="lezhik0036-sys/-"
SET_SECRETS=0
PRINT_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --set-secrets) SET_SECRETS=1 ;;
    --print-fingerprint) PRINT_ONLY=1 ;;
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

umask 077  # keystore + backup readable only by this user

# Colon-separated SHA-256 (keytool) and the colon-free lowercase form that
# scripts' apk-check.sh / apksigner compare against.
fingerprint_colon() {
  keytool -list -v -keystore "$KEYSTORE_PATH" -alias "$KEY_ALIAS" -storepass "$1" 2>/dev/null \
    | grep -i 'SHA256:' | head -n1 | sed 's/.*SHA256: *//'
}
fingerprint_nocolon() { fingerprint_colon "$1" | tr -d ':' | tr 'A-F' 'a-f'; }

read_existing_password() {
  # Reuse requires the existing store password; we cannot and must not derive it.
  if [ -n "${MAMA_PILOT_STORE_PASSWORD:-}" ]; then
    printf '%s' "$MAMA_PILOT_STORE_PASSWORD"; return
  fi
  local p
  read -rs -p "Store password for the existing $KEYSTORE_PATH: " p >&2; echo >&2
  printf '%s' "$p"
}

if [ -e "$KEYSTORE_PATH" ]; then
  # -------- REUSE the owner's existing key (never overwrite) --------
  echo "Existing keystore found at $KEYSTORE_PATH — reusing the fleet identity (not overwriting)." >&2
  PILOT_PASSWORD="$(read_existing_password)"
  # Verify the password + alias actually open the store, else fail clearly.
  keytool -list -keystore "$KEYSTORE_PATH" -alias "$KEY_ALIAS" -storepass "$PILOT_PASSWORD" >/dev/null 2>&1 \
    || { echo "ERROR: cannot open $KEYSTORE_PATH with alias '$KEY_ALIAS' and the given password." >&2; exit 1; }
  REUSED=1
else
  if [ "$PRINT_ONLY" -eq 1 ]; then
    echo "ERROR: --print-fingerprint needs an existing keystore at $KEYSTORE_PATH." >&2; exit 1
  fi
  # -------- CREATE a new keystore (first time only) --------
  echo "No keystore at $KEYSTORE_PATH — creating a new stable pilot key (RSA-4096, 10000 days)." >&2
  PILOT_PASSWORD="$(openssl rand -base64 33 | tr -d '\n/+=' | cut -c1-40)"  # PKCS12: one password
  keytool -genkeypair \
    -keystore "$KEYSTORE_PATH" -storetype PKCS12 \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=MAMA Pilot, O=MAMA, C=RU" \
    -storepass "$PILOT_PASSWORD" -keypass "$PILOT_PASSWORD" >/dev/null
  REUSED=0
fi

CERT_SHA256="$(fingerprint_colon "$PILOT_PASSWORD")"
CERT_SHA256_NOCOLON="$(fingerprint_nocolon "$PILOT_PASSWORD")"

if [ "$PRINT_ONLY" -eq 1 ]; then
  echo "Cert SHA-256 (apksigner / apk-check.sh form): $CERT_SHA256_NOCOLON"
  echo "Cert SHA-256 (keytool form):                  $CERT_SHA256"
  exit 0
fi

# base64 of the keystore, one line (Linux: -w0; macOS: strip newlines).
if base64 --help 2>&1 | grep -q '\-w'; then
  KEYSTORE_B64="$(base64 -w0 "$KEYSTORE_PATH")"
else
  KEYSTORE_B64="$(base64 "$KEYSTORE_PATH" | tr -d '\n')"
fi

# Protected local backup — the ONLY place the password is written. Keep it safe.
{
  echo "# MAMA pilot signing secrets — $( [ "$REUSED" = 1 ] && echo 'reused existing key' || echo 'created new key' ), $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "# KEEP THIS FILE SAFE AND OFF THE BUILD MACHINE (encrypted). Never commit it."
  echo "# The four GitHub Actions secrets the real-server-pilot workflow reads:"
  echo "MAMA_PILOT_KEY_ALIAS=$KEY_ALIAS"
  echo "MAMA_PILOT_STORE_PASSWORD=$PILOT_PASSWORD"
  echo "MAMA_PILOT_KEY_PASSWORD=$PILOT_PASSWORD"
  echo "# Signing-cert SHA-256 — pass the no-colon form to scripts' apk-check.sh (EXPECTED_CERT_SHA256):"
  echo "SIGNING_CERT_SHA256=$CERT_SHA256"
  echo "SIGNING_CERT_SHA256_NOCOLON=$CERT_SHA256_NOCOLON"
  echo "# MAMA_PILOT_KEYSTORE_B64 (one line) follows:"
  echo "MAMA_PILOT_KEYSTORE_B64=$KEYSTORE_B64"
} > "$BACKUP_FILE"

echo
echo "Keystore:        $KEYSTORE_PATH  ($( [ "$REUSED" = 1 ] && echo 'existing, preserved' || echo 'newly created' ))"
echo "Secrets backup:  $BACKUP_FILE  (chmod 600 — keep an encrypted off-machine copy)"
echo "Cert SHA-256:    ${CERT_SHA256_NOCOLON:-<none>}  (use with apk-check.sh)"
echo

if [ "$SET_SECRETS" -eq 1 ]; then
  command -v gh >/dev/null || { echo "ERROR: gh not found; cannot --set-secrets." >&2; exit 78; }
  echo "Setting the four Actions secrets on $REPO via gh..."
  printf '%s' "$KEYSTORE_B64"   | gh secret set MAMA_PILOT_KEYSTORE_B64   --repo "$REPO"
  printf '%s' "$PILOT_PASSWORD" | gh secret set MAMA_PILOT_STORE_PASSWORD --repo "$REPO"
  printf '%s' "$KEY_ALIAS"      | gh secret set MAMA_PILOT_KEY_ALIAS      --repo "$REPO"
  printf '%s' "$PILOT_PASSWORD" | gh secret set MAMA_PILOT_KEY_PASSWORD   --repo "$REPO"
  echo "Done. All four MAMA_PILOT_* secrets are set on $REPO (from the $( [ "$REUSED" = 1 ] && echo existing || echo new ) key)."
else
  echo "Next: add these four GitHub Actions secrets (Settings -> Secrets and variables ->"
  echo "Actions) on $REPO, taking the values from $BACKUP_FILE:"
  echo "  MAMA_PILOT_KEYSTORE_B64, MAMA_PILOT_STORE_PASSWORD, MAMA_PILOT_KEY_ALIAS, MAMA_PILOT_KEY_PASSWORD"
  echo "Or re-run with --set-secrets (requires gh auth) to set them automatically."
fi
