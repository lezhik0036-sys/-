import re
import sys
from zipfile import ZipFile

with ZipFile(sys.argv[1]) as apk:
    dex = [apk.read(name) for name in apk.namelist() if re.fullmatch(r"classes[0-9]*\.dex", name)]
    assert dex, "APK has no DEX"
    combined = b"".join(dex)
    for marker in (b"FakeTrustedExitBackend", b"DebugCodeSmsService", b"SimulatedSmsService",
                   b"DeviceChallengeVault", b"MockSmsProvider", b"mama_fake_exit_server",
                   b"https://example.invalid", b"http://localhost"):
        assert marker not in combined, f"Test marker in release DEX: {marker!r}"
    assert b"https://api.mama-app.ru" in combined, "Expected HTTPS endpoint missing"

assert len(sys.argv) > 2, "Ktor jar missing"
for path in sys.argv[2:]:
    with ZipFile(path) as jar:
        assert not any("MockSmsProvider" in name for name in jar.namelist()), path
print("Release APK DEX and Ktor jar passed")
