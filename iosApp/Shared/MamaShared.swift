import DeviceActivity
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
/// not pick apps: every app category and every web category is shielded, and
/// MAMA cannot be deleted while it is on. Phone and emergency calls are never
/// shielded by iOS.
enum Shield {
    static func on() {
        let store = ManagedSettingsStore(named: .mama)
        store.shield.applicationCategories = .all()
        store.shield.webDomainCategories = .all()
        store.application.denyAppRemoval = true
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
