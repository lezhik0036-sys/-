import DeviceActivity

/// The system wakes this extension at the session's start and end, so the
/// shield follows the schedule even when the app was never reopened.
final class DeviceActivityMonitorExtension: DeviceActivityMonitor {
    override func intervalDidStart(for activity: DeviceActivityName) {
        super.intervalDidStart(for: activity)
        guard activity == .session else { return }
        Shield.raise(MamaStore.selection)
    }

    override func intervalDidEnd(for activity: DeviceActivityName) {
        super.intervalDidEnd(for: activity)
        guard activity == .session else { return }
        Shield.lower()
        MamaStore.sessionEnd = nil
        DeviceActivityCenter().stopMonitoring([.session])
    }
}
