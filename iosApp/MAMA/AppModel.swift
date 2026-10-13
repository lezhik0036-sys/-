import Combine
import FamilyControls
import Foundation
import MamaCore

/// The app's state: Screen Time authorization, the series draft and the
/// stored series. Every rule comes from the shared MamaCore framework (the
/// same Kotlin code as on Android); only the Screen Time protection is native.
@MainActor
final class AppModel: ObservableObject {
    enum Step: Hashable { case series, time, contact, price, review }

    @Published var authorization: AuthorizationStatus = .notDetermined
    @Published var series: SeriesView?
    @Published var path: [Step] = []
    @Published var alert: String?
    @Published var testShieldOn = TestShield.isOn
    @Published var testScheduled = TestShield.isScheduled
    /// The shared verdict on Screen Time access: APPROVED, CONFIRMED_DENIED or UNKNOWN.
    @Published var accessVerdict = "UNKNOWN"
    /// Debug: when MAMA last had to put the shield or the schedule back during an ACTIVE series.
    @Published var protectionRestoredAtMs: Int64?

    // The draft: series → time → trusted contact → «Цена слова» → launch.
    @Published var days = 7
    @Published var autoNow = true
    @Published var startMinute = 0
    @Published var endMinute = 8 * 60
    @Published var contactName = ""
    @Published var contactPhone = ""

    private let bridge = MamaBridge.shared
    private let stateKey = "mama.state"
    private let deniedSinceKey = "mama.accessDeniedSinceMs"
    private let restoredKey = "mama.protectionRestoredAtMs"
    private var authorizationWatch: AnyCancellable?
    private var recheckScheduled = false

    /// Usable from SwiftUI's non-isolated property initializers (@StateObject); state is read in [refresh].
    nonisolated init() {}

    var zoneId: String { Foundation.TimeZone.current.identifier }
    var authorized: Bool { authorization == .approved }
    var seriesDays: [Int] { bridge.seriesDays.map { $0.intValue } }

    var inProgress: Bool {
        guard let s = series else { return false }
        return ["SCHEDULED", "ACTIVATING", "NEEDS_ATTENTION", "ACTIVE"].contains(s.status)
    }

    /// Whole days until a free series may start again (the 30 days after a failure).
    var daysUntilFreeStart: Int { Int(bridge.daysUntilFreeStart(state: state, nowMs: MamaShared.nowMs)) }

    /// The shared state, stored as the shared codec's string map in the App Group.
    private var state: [String: String] {
        get { MamaShared.defaults.dictionary(forKey: stateKey) as? [String: String] ?? [:] }
        set { MamaShared.defaults.set(newValue, forKey: stateKey) }
    }

    // MARK: - Screen Time

    func requestAuthorization() {
        Task { @MainActor in
            do {
                try await AuthorizationCenter.shared.requestAuthorization(for: .individual)
            } catch {
                self.alert = "Screen Time не разрешён. Без него MAMA не может включить защиту."
            }
            self.refresh()
        }
    }

    /// Brings the series up to now. Runs at launch, on every return to the
    /// foreground and whenever Screen Time reports a change; background
    /// callbacks are not relied on. The moments the shield came on are the
    /// commitment point; Screen Time access confirmed withdrawn during an
    /// ACTIVE series is a failure (the shared rules make it happen once).
    func refresh() {
        watchAuthorization()
        authorization = AuthorizationCenter.shared.authorizationStatus
        let access = checkAccess()
        accessVerdict = access.verdict
        let outcome = bridge.refresh(
            state: state,
            nowMs: MamaShared.nowMs,
            nextSessionId: UUID().uuidString,
            shieldOnTimesMs: Shield.onTimes.map { KotlinLong(value: $0) },
            access: access.verdict
        )
        if let next = outcome.state { state = next }
        series = bridge.view(state: state)
        if inProgress {
            keepProtected(access.verdict)
        } else {
            // Nothing left to protect.
            if Schedule.isMonitoring { Schedule.stop() }
            if Shield.isOn { Shield.off() }
        }
        // A denial is only believed when it lasts: look again shortly.
        if access.pending { recheckSoon() }
        testShieldOn = TestShield.isOn
        testScheduled = TestShield.isScheduled
        protectionRestoredAtMs = MamaShared.defaults.object(forKey: restoredKey) as? Int64
    }

    /// Screen Time may report a change while MAMA is open; not guaranteed in the background.
    private func watchAuthorization() {
        guard authorizationWatch == nil else { return }
        authorizationWatch = AuthorizationCenter.shared.objectWillChange.sink { [weak self] _ in
            Task { @MainActor in self?.refresh() }
        }
    }

    /// Every Screen Time reading since the last check (the app's own now, the
    /// monitor's at its callbacks), folded with the shared rules into a verdict.
    /// `pending`: access reads "denied" but is not confirmed yet.
    private func checkAccess() -> (verdict: String, pending: Bool) {
        AccessLog.record()
        var since = (MamaShared.defaults.object(forKey: deniedSinceKey) as? Int64).map { KotlinLong(value: $0) }
        var verdict = "UNKNOWN"
        for r in AccessLog.drain() {
            let check = bridge.observeAccess(reading: r.reading, deniedSinceMs: since, nowMs: r.ms)
            verdict = check.verdict
            since = check.deniedSinceMs
        }
        if let since {
            MamaShared.defaults.set(since.int64Value, forKey: deniedSinceKey)
        } else {
            MamaShared.defaults.removeObject(forKey: deniedSinceKey)
        }
        return (verdict, since != nil && verdict != "CONFIRMED_DENIED")
    }

    private func recheckSoon() {
        guard !recheckScheduled else { return }
        recheckScheduled = true
        let delay = UInt64(bridge.accessConfirmAfterMs + 500) * 1_000_000
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: delay)
            self?.recheckScheduled = false
            self?.refresh()
        }
    }

    /// During an ACTIVE series with access approved the schedule and, inside
    /// the window, the shield must be on. If iOS dropped them (for example
    /// after access was withdrawn and given back before MAMA was opened), put
    /// them back and note it. That is not a failure: it cannot be proved.
    private func keepProtected(_ verdict: String) {
        guard verdict == "APPROVED", let v = series, v.status == "ACTIVE" else { return }
        var restored = false
        if !Schedule.isMonitoring {
            protect()
            restored = true
        }
        if let start = v.lockStartMs?.int64Value, let end = v.lockEndMs?.int64Value,
           start <= MamaShared.nowMs, MamaShared.nowMs < end, !Shield.isOn {
            Shield.on()
            restored = true
        }
        if restored { MamaShared.defaults.set(MamaShared.nowMs, forKey: restoredKey) }
    }

    // MARK: - Draft

    /// A new draft: AUTO_NOW from the current minute, 8 hours (never the previous series' time).
    func beginDraft() {
        let fresh = bridge.freshDraft(nowMs: MamaShared.nowMs, zoneId: zoneId)
        startMinute = Int(fresh.startMinute)
        endMinute = Int(fresh.endMinute)
        autoNow = true
        days = 7
        path = [.series]
    }

    var draftLength: Int { (endMinute - startMinute + 24 * 60) % (24 * 60) }

    /// AUTO_NOW: the preview follows the clock, keeping the chosen length.
    func refreshAutoNow() {
        guard autoNow else { return }
        let length = draftLength
        startMinute = Int(bridge.freshDraft(nowMs: MamaShared.nowMs, zoneId: zoneId).startMinute)
        endMinute = (startMinute + length) % (24 * 60)
    }

    /// The start set by hand: from now on a fixed window (MANUAL).
    func pickStart(_ minute: Int) {
        guard minute != startMinute else { return }
        startMinute = minute
        autoNow = false
    }

    /// Under AUTO_NOW a new end only changes the length.
    func pickEnd(_ minute: Int) {
        refreshAutoNow()
        endMinute = minute
    }

    /// What the confirmation would start right now.
    var preview: Launch? {
        bridge.resolve(
            autoNow: autoNow, startMinute: Int32(startMinute), endMinute: Int32(endMinute),
            zoneId: zoneId, days: Int32(days), nowMs: MamaShared.nowMs
        )
    }

    var contactValid: Bool {
        !contactName.trimmingCharacters(in: .whitespaces).isEmpty && bridge.normalizePhone(raw: contactPhone) != nil
    }

    func restartPrice(_ days: Int) -> Int { Int(bridge.restartPriceRub(days: Int32(days))) }

    // MARK: - Series

    /// «Начать серию»: AUTO_NOW resolves its time now; the window becomes the DeviceActivity schedule.
    func launch() {
        guard authorized else {
            alert = "Сначала разреши Screen Time: без него защита не включится."
            return
        }
        refreshAutoNow()
        let outcome = bridge.schedule(
            state: state, days: Int32(days), autoNow: autoNow,
            startMinute: Int32(startMinute), endMinute: Int32(endMinute), zoneId: zoneId,
            contactName: contactName, contactPhone: contactPhone, nowMs: MamaShared.nowMs,
            seriesId: UUID().uuidString, sessionId: UUID().uuidString
        )
        guard let next = outcome.state else {
            alert = message(for: outcome.error)
            return
        }
        state = next
        Shield.forgetOnTimes()
        protect()
        path = []
        refresh()
    }

    /// «Отменить запуск» before ACTIVE: no consequences.
    func cancelLaunch() {
        guard let next = bridge.cancel(state: state, nowMs: MamaShared.nowMs).state else {
            alert = "Серия уже началась: отменить её нельзя."
            return
        }
        state = next
        Schedule.stop()
        Shield.off()
        refresh()
    }

    /// «Подтвердить запуск» after NEEDS_ATTENTION (AUTO_NOW starts from this moment again).
    func relaunch() {
        guard authorized else {
            alert = "Сначала разреши Screen Time: без него защита не включится."
            return
        }
        guard let next = bridge.relaunch(state: state, nowMs: MamaShared.nowMs, sessionId: UUID().uuidString).state else {
            alert = "Запустить серию сейчас нельзя."
            return
        }
        state = next
        protect()
        refresh()
    }

    /// The stored window as the DeviceActivity schedule; a lock running now is shielded at once.
    private func protect() {
        guard let v = bridge.view(state: state),
              let start = v.lockStartMs?.int64Value,
              let end = v.lockEndMs?.int64Value else { return }
        let nightsAfter = Int64(max(0, Int(v.days) - 1 - Int(v.completed)))
        do {
            try Schedule.start(
                startMinute: Int(v.startMinute), endMinute: Int(v.endMinute),
                lastLockEndMs: end + nightsAfter * 86_400_000
            )
        } catch {
            alert = "Не получилось запланировать защиту: \(error.localizedDescription)"
        }
        if start <= MamaShared.nowMs { Shield.on() }
    }

    private func message(for error: String?) -> String {
        if error == bridge.BUSY { return "Сейчас уже идёт серия." }
        if error == bridge.WAITING { return "Бесплатная серия пока недоступна: после срыва — через 30 дней." }
        if error == bridge.INVALID { return "Проверь время и доверенный контакт." }
        return "Серию сейчас нельзя начать."
    }

    // MARK: - Debug: test shield

    func setTestShield(_ on: Bool) {
        TestShield.set(on)
        testShieldOn = TestShield.isOn
    }

    func scheduleTestShield() {
        do {
            try TestShield.schedule(minutesFromNow: 1)
        } catch {
            alert = "Расписание не создано: \(error.localizedDescription)"
        }
        testScheduled = TestShield.isScheduled
    }
}
