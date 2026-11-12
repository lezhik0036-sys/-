# MAMA RuStore release

Build on the owner-controlled signing machine after the real server and device acceptance checks pass. Provide the existing RuStore upload keystore and its credentials through the local secret store as MAMA_RELEASE_KEYSTORE, MAMA_RELEASE_STORE_PASSWORD, MAMA_RELEASE_KEY_ALIAS and MAMA_RELEASE_KEY_PASSWORD. Do not commit credentials or use the pilot/debug key. Confirm that app.mama and versionCode match the existing RuStore record and increase versionCode if needed.

```sh
./gradlew :app:bundleRelease -PmamaBackendBaseUrl=https://api.mama-app.ru
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab
keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab
```

Match the signer fingerprint against the expected RuStore upload certificate before uploading. CI tests refusal without credentials and does not sign, publish or create a replacement key. Validate the release on Android 13 and a Wi-Fi-only device. Family and the real SMS/BRICK recovery paths require independent acceptance before release.
