#!/usr/bin/env bash
# Fails unless every bundle in the IPA carries its registered bundle ID and is
# signed with Family Controls and the App Group. Run on macOS after export.
set -euo pipefail

ipa="$1"
work=$(mktemp -d)
unzip -q "$ipa" -d "$work"
app="$work/Payload/MAMA.app"
group="group.app.mama.sleep"
failed=0

check() {
    local bundle="$1" expected="$2" ent id
    id=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$bundle/Info.plist")
    ent="$work/$expected.entitlements.plist"
    codesign -d --xml --entitlements - "$bundle" > "$ent" 2>/dev/null
    if [ "$id" != "$expected" ]; then
        echo "error: $(basename "$bundle") has bundle ID $id, expected $expected"; failed=1
    fi
    if [ "$(/usr/libexec/PlistBuddy -c 'Print :com.apple.developer.family-controls' "$ent" 2>/dev/null)" != "true" ]; then
        echo "error: $expected is not signed with com.apple.developer.family-controls"; failed=1
    fi
    if ! /usr/libexec/PlistBuddy -c 'Print :com.apple.security.application-groups' "$ent" 2>/dev/null | grep -qx "[[:space:]]*$group"; then
        echo "error: $expected is not signed with App Group $group"; failed=1
    fi
    echo "--- $expected"
    /usr/libexec/PlistBuddy -c 'Print' "$ent"
}

check "$app" app.mama.sleep
check "$app/PlugIns/MAMAShield.appex" app.mama.sleep.shield
check "$app/PlugIns/MAMAMonitor.appex" app.mama.sleep.monitor
exit $failed
