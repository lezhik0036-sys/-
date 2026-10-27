import DeviceActivity
import FamilyControls
import SwiftUI

/// Pilot screen: grant Screen Time, pick the apps, choose "from/to", launch.
struct ContentView: View {
    @ObservedObject private var authorization = AuthorizationCenter.shared
    @Environment(\.scenePhase) private var scenePhase

    @State private var selection = MamaStore.selection
    @State private var pickerShown = false
    @State private var from = Date()
    @State private var to = Date().addingTimeInterval(30 * 60)
    @State private var sessionEnd = MamaStore.sessionEnd
    @State private var message: String?

    var body: some View {
        NavigationStack {
            Form {
                if let end = sessionEnd {
                    Section {
                        Text("Блокировка до \(end.formatted(date: .omitted, time: .shortened))")
                            .font(.title3.weight(.semibold))
                        if Pilot.allowsStop {
                            Button("Остановить (пилот)", role: .destructive, action: stop)
                        }
                    }
                } else {
                    setup
                }
                if let message {
                    Section { Text(message).foregroundStyle(Palette.warning) }
                }
            }
            .navigationTitle("MAMA")
        }
        .tint(Palette.emerald)
        .familyActivityPicker(isPresented: $pickerShown, selection: $selection)
        .onChange(of: scenePhase) { phase in
            if phase == .active { refresh() }
        }
    }

    @ViewBuilder
    private var setup: some View {
        Section("1. Экранное время") {
            if authorization.authorizationStatus == .approved {
                Label("Доступ выдан", systemImage: "checkmark.circle")
            } else {
                Button("Разрешить доступ", action: authorize)
            }
        }
        Section("2. Что блокировать") {
            Button("Выбрать приложения") { pickerShown = true }
            Text("Приложений: \(selection.applicationTokens.count), категорий: \(selection.categoryTokens.count)")
                .foregroundStyle(.secondary)
        }
        Section("3. Время") {
            DatePicker("С", selection: $from, displayedComponents: .hourAndMinute)
            DatePicker("До", selection: $to, displayedComponents: .hourAndMinute)
        }
        Section {
            Button("Запустить MAMA", action: launch)
                .disabled(authorization.authorizationStatus != .approved || selectionIsEmpty)
        }
    }

    private var selectionIsEmpty: Bool {
        selection.applicationTokens.isEmpty && selection.categoryTokens.isEmpty
            && selection.webDomainTokens.isEmpty
    }

    private func authorize() {
        Task {
            do {
                try await AuthorizationCenter.shared.requestAuthorization(for: .individual)
                message = nil
            } catch {
                message = "Доступ не выдан: \(error.localizedDescription)"
            }
        }
    }

    private func launch() {
        let now = Date()
        var (start, end) = window(from: from, to: to, now: now)
        if start < now { start = now }
        // DeviceActivity refuses intervals shorter than 15 minutes.
        guard end.timeIntervalSince(start) >= 15 * 60 else {
            message = "Минимальная длительность — 15 минут."
            return
        }
        let parts: Set<Calendar.Component> = [.year, .month, .day, .hour, .minute, .second]
        let schedule = DeviceActivitySchedule(
            intervalStart: Calendar.current.dateComponents(parts, from: start),
            intervalEnd: Calendar.current.dateComponents(parts, from: end),
            repeats: false
        )
        MamaStore.selection = selection
        do {
            try DeviceActivityCenter().startMonitoring(.session, during: schedule)
        } catch {
            message = "Не удалось запустить: \(error.localizedDescription)"
            return
        }
        MamaStore.sessionEnd = end
        if start <= now { Shield.raise(selection) }
        sessionEnd = end
        message = nil
    }

    private func stop() {
        DeviceActivityCenter().stopMonitoring([.session])
        Shield.lower()
        MamaStore.sessionEnd = nil
        sessionEnd = nil
    }

    /// The monitor extension clears the session when it ends; pick that up.
    private func refresh() {
        if let end = MamaStore.sessionEnd, end <= Date() {
            Shield.lower()
            MamaStore.sessionEnd = nil
        }
        sessionEnd = MamaStore.sessionEnd
    }

    /// The next "from–to" window by time of day: overnight if "to" is earlier
    /// than "from", and tomorrow's if today's has already ended.
    private func window(from: Date, to: Date, now: Date) -> (Date, Date) {
        let calendar = Calendar.current
        func today(_ time: Date) -> Date {
            let hm = calendar.dateComponents([.hour, .minute], from: time)
            return calendar.date(bySettingHour: hm.hour ?? 0, minute: hm.minute ?? 0, second: 0, of: now) ?? now
        }
        let day: TimeInterval = 24 * 60 * 60
        var start = today(from)
        var end = today(to)
        if end <= start { end += day }
        if end <= now {
            start += day
            end += day
        }
        return (start, end)
    }
}

private enum Palette {
    static let emerald = Color(red: 0x28 / 255, green: 0x59 / 255, blue: 0x4A / 255)
    static let warning = Color(red: 0xC8 / 255, green: 0x75 / 255, blue: 0x61 / 255)
}
