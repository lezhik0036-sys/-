import DeviceActivity
import Foundation
import ManagedSettings

/// Runs the series' daily window without the app: the system calls it when
/// the window begins and ends, even if MAMA is not open.
final class MamaActivityMonitor: DeviceActivityMonitor {
    override func intervalDidStart(for activity: DeviceActivityName) {
        super.intervalDidStart(for: activity)
        // Evidence for the app: what Screen Time access looked like here.
        AccessLog.record()
        if activity == .series {
            Shield.on()
        } else if activity == .test {
            TestShield.set(true)
        }
    }

    override func intervalDidEnd(for activity: DeviceActivityName) {
        super.intervalDidEnd(for: activity)
        AccessLog.record()
        if activity == .series {
            Shield.off()
            // After the last lock of the series nothing is scheduled any more.
            if Schedule.seriesOver { Schedule.stop() }
        } else if activity == .test {
            TestShield.set(false)
        }
    }
}
