import DeviceActivity
import FamilyControls
import Foundation
import ManagedSettings

/// Pilot builds keep a plain "stop" button so a test session can't strand the
/// tester. Turn it off once early exit goes through the trusted contact's code.
enum Pilot {
    static let allowsStop = true
}

/// Session state shared by the app and its extensions through the App Group.
enum MamaStore {
    /// Must match com.apple.security.application-groups in all three targets.
    static let appGroup = "group.app.mama.sleep"

    // No fallback to .standard: the app and its extensions would then each
    // keep their own copy and the schedule would raise an empty shield.
    private static let defaults: UserDefaults = {
        guard let defaults = UserDefaults(suiteName: appGroup) else {
            fatalError("App Group \(appGroup) is unavailable")
        }
        return defaults
    }()

    private static let selectionKey = "selection"
    private static let sessionEndKey = "sessionEnd"

    /// The apps, categories and sites to block.
    static var selection: FamilyActivitySelection {
        get {
            guard let data = defaults.data(forKey: selectionKey),
                  let value = try? JSONDecoder().decode(FamilyActivitySelection.self, from: data)
            else { return FamilyActivitySelection() }
            return value
        }
        set { defaults.set(try? JSONEncoder().encode(newValue), forKey: selectionKey) }
    }

    /// When the running session ends; nil when there is none.
    static var sessionEnd: Date? {
        get { defaults.object(forKey: sessionEndKey) as? Date }
        set { defaults.set(newValue, forKey: sessionEndKey) }
    }
}

/// The shield over the chosen apps. The system keeps it across restarts.
enum Shield {
    private static let store = ManagedSettingsStore(named: .init("mama"))

    static func raise(_ selection: FamilyActivitySelection) {
        store.shield.applications = selection.applicationTokens.isEmpty ? nil : selection.applicationTokens
        store.shield.applicationCategories = selection.categoryTokens.isEmpty
            ? nil : .specific(selection.categoryTokens)
        store.shield.webDomains = selection.webDomainTokens.isEmpty ? nil : selection.webDomainTokens
    }

    static func lower() {
        store.clearAllSettings()
    }
}

extension DeviceActivityName {
    static let session = Self("mama.session")
}
