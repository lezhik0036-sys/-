import DeviceActivity
import FamilyControls
import Foundation
import ManagedSettings

/// What the app and its Screen Time extensions share (App Group container).
enum MamaShared {
    static let appGroup = "group.app.mama.shared"

    static var defaults: UserDefaults { UserDefaults(suiteName: appGroup) ?? .standard }

    /// Moments the shield came on (epoch ms, newest last): the iPhone commitment point.
    static let shieldOnTimesKey = "mama.shieldOnTimesMs"

    /// End of the series' last lock (epoch ms): after it the monitor stops.
    static let lastLockEndKey = "mama.lastLockEndMs"

    static var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

extension ManagedSettingsStore.Name {
    static let mama = Self("mama")
}

extension DeviceActivityName {
    static let series = Self("mama.series")
}

/// The iPhone protection: the widest shield Screen Time allows. The user does
/// not pick apps: every app category and every web category is shielded.
/// Phone and emergency calls are never shielded by iOS.
///
/// With individual authorization (the adult owner of the iPhone) iOS gives no
/// parental-control protections: the user can still delete MAMA, sign out of
/// iCloud or withdraw Screen Time access. MAMA does not claim otherwise;
/// access withdrawn during an ACTIVE series is detected and is a failure.
enum Shield {
    static func on() {
        let store = ManagedSettingsStore(named: .mama)
        store.shield.applicationCategories = .all()
        store.shield.webDomainCategories = .all()
        var times = MamaShared.defaults.array(forKey: MamaShared.shieldOnTimesKey) as? [Int64] ?? []
        times.append(MamaShared.nowMs)
        MamaShared.defaults.set(Array(times.suffix(14)), forKey: MamaShared.shieldOnTimesKey)
    }

    static func off() {
        ManagedSettingsStore(named: .mama).clearAllSettings()
    }

    static var isOn: Bool {
        ManagedSettingsStore(named: .mama).shield.applicationCategories != nil
    }

    static var onTimes: [Int64] {
        MamaShared.defaults.array(forKey: MamaShared.shieldOnTimesKey) as? [Int64] ?? []
    }

    static func forgetOnTimes() {
        MamaShared.defaults.removeObject(forKey: MamaShared.shieldOnTimesKey)
    }
}

/// The series' daily window as a DeviceActivity schedule: the monitor
/// extension switches the shield on when the window begins and off when it ends.
enum Schedule {
    static func start(startMinute: Int, endMinute: Int, lastLockEndMs: Int64) throws {
        let center = DeviceActivityCenter()
        center.stopMonitoring([.series])
        let schedule = DeviceActivitySchedule(
            intervalStart: DateComponents(hour: startMinute / 60, minute: startMinute % 60),
            intervalEnd: DateComponents(hour: endMinute / 60, minute: endMinute % 60),
            repeats: true
        )
        MamaShared.defaults.set(lastLockEndMs, forKey: MamaShared.lastLockEndKey)
        try center.startMonitoring(.series, during: schedule)
    }

    static func stop() {
        DeviceActivityCenter().stopMonitoring([.series])
        MamaShared.defaults.removeObject(forKey: MamaShared.lastLockEndKey)
    }

    static var isMonitoring: Bool {
        DeviceActivityCenter().activities.contains(.series)
    }

    /// True once the series' last lock has ended (with a minute of slack).
    static var seriesOver: Bool {
        guard let last = MamaShared.defaults.object(forKey: MamaShared.lastLockEndKey) as? Int64 else { return false }
        return MamaShared.nowMs >= last - 60_000
    }
}

extension DeviceActivityName {
    static let test = Self("mama.test")
}

/// Debug only: a shield separate from the series, to check Screen Time on a device.
enum TestShield {
    private static var store: ManagedSettingsStore { ManagedSettingsStore(named: ManagedSettingsStore.Name("mama.test")) }

    static func set(_ on: Bool) {
        if on {
            store.shield.applicationCategories = .all()
            store.shield.webDomainCategories = .all()
        } else {
            store.clearAllSettings()
        }
    }

    static var isOn: Bool { store.shield.applicationCategories != nil }

    /// A one-off DeviceActivity window: the monitor extension shields from [minutesFromNow] for 15 minutes.
    static func schedule(minutesFromNow: Int) throws {
        let calendar = Calendar.current
        let start = calendar.date(byAdding: .minute, value: minutesFromNow, to: Date()) ?? Date()
        let end = calendar.date(byAdding: .minute, value: 15, to: start) ?? start
        let center = DeviceActivityCenter()
        center.stopMonitoring([.test])
        try center.startMonitoring(
            .test,
            during: DeviceActivitySchedule(
                intervalStart: calendar.dateComponents([.hour, .minute], from: start),
                intervalEnd: calendar.dateComponents([.hour, .minute], from: end),
                repeats: false
            )
        )
    }

    static var isScheduled: Bool { DeviceActivityCenter().activities.contains(.test) }
}

/// Raw Screen Time readings with their time, from the app and from the
/// monitor extension. The app folds them into a verdict with the shared rules
/// (`MamaBridge.observeAccess`): only a denial that lasts is a withdrawal.
enum AccessLog {
    private static let key = "mama.accessReadings"

    /// The current authorization status, as a shared `AccessReading` name.
    static func reading() -> String {
        switch AuthorizationCenter.shared.authorizationStatus {
        case .approved: return "APPROVED"
        case .denied: return "DENIED"
        case .notDetermined: return "NOT_DETERMINED"
        @unknown default: return "UNREADABLE"
        }
    }

    static func record() {
        var list = MamaShared.defaults.stringArray(forKey: key) ?? []
        list.append("\(MamaShared.nowMs)|\(reading())")
        MamaShared.defaults.set(Array(list.suffix(20)), forKey: key)
    }

    /// The readings not folded yet, oldest first; they are removed.
    static func drain() -> [(ms: Int64, reading: String)] {
        let list = MamaShared.defaults.stringArray(forKey: key) ?? []
        MamaShared.defaults.removeObject(forKey: key)
        return list.compactMap { entry -> (ms: Int64, reading: String)? in
            let parts = entry.split(separator: "|")
            guard parts.count == 2, let ms = Int64(parts[0]) else { return nil }
            return (ms: ms, reading: String(parts[1]))
        }
        .sorted { $0.ms < $1.ms }
    }
}
