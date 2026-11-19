# Android compatibility

API 26–32 use bundled Ed25519 while API 33+ keep the platform implementation.
Both use the same public key, signature and PKCS#8 format and protect stored
private bytes using the existing non-exportable Android Keystore AES key.
Key backing depends on the device. A hardware-wrapped software Ed25519 key
is not a hardware-non-exportable signing key. A failure to open or persist
the identity disables authenticated operations. Android upgrades preserve the
identity. Clearing data or losing the Keystore key requires separate account
recovery, which is not implemented by this change.

BRICK depends on user-granted overlay, Accessibility and Device Admin.
Accessibility may be unavailable before the first unlock after reboot.
Without exact-alarm access Android may delay scheduled transitions.
Background execution and OEM power controls can suspend app enforcement.
Ordinary app installation does not grant kiosk/device-owner control.
Without a network the device cannot obtain a server-authorized early exit.
Validate these conditions on physical devices before enabling strict BRICK.
