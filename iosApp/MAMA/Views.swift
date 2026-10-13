import Combine
import MamaCore
import SwiftUI
import UIKit

enum Theme {
    static let night = Color(red: 0.05, green: 0.09, blue: 0.11)
    static let card = Color.white.opacity(0.07)
    static let emerald = Color(red: 0.18, green: 0.49, blue: 0.42)
    static let muted = Color.white.opacity(0.65)
}

/// "23:00" for a minute of the day.
func clock(_ minute: Int) -> String { String(format: "%02d:%02d", minute / 60, minute % 60) }

/// "8 ч", "8 ч 30 мин".
func lengthText(_ minutes: Int) -> String {
    let h = minutes / 60, m = minutes % 60
    if m == 0 { return "\(h) ч" }
    return h == 0 ? "\(m) мин" : "\(h) ч \(m) мин"
}

/// "1 день", "2 дня", "5 дней".
func daysText(_ n: Int) -> String {
    let word: String
    if (11...14).contains(n % 100) {
        word = "дней"
    } else if n % 10 == 1 {
        word = "день"
    } else if (2...4).contains(n % 10) {
        word = "дня"
    } else {
        word = "дней"
    }
    return "\(n) \(word)"
}

/// "сегодня, 23:00" / "завтра, 07:00".
func moment(_ epochMs: Int64) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "ru_RU")
    f.dateStyle = .medium
    f.timeStyle = .short
    f.doesRelativeDateFormatting = true
    return f.string(from: Date(timeIntervalSince1970: Double(epochMs) / 1000))
}

private func date(forMinute minute: Int) -> Date {
    Calendar.current.date(bySettingHour: minute / 60, minute: minute % 60, second: 0, of: Date()) ?? Date()
}

private func minute(of date: Date) -> Int {
    let c = Calendar.current.dateComponents([.hour, .minute], from: date)
    return (c.hour ?? 0) * 60 + (c.minute ?? 0)
}

struct RootView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        NavigationStack(path: $model.path) {
            HomeView()
                .navigationDestination(for: AppModel.Step.self) { step in
                    switch step {
                    case .series: SeriesStepView()
                    case .time: TimeStepView()
                    case .contact: ContactStepView()
                    case .price: PriceStepView()
                    case .review: ReviewStepView()
                    }
                }
        }
        .tint(Theme.emerald)
        .onAppear { model.refresh() }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
            model.refresh()
        }
        .alert(
            model.alert ?? "",
            isPresented: Binding(get: { model.alert != nil }, set: { if !$0 { model.alert = nil } })
        ) {
            Button("Понятно", role: .cancel) { model.alert = nil }
        }
    }
}

// MARK: - Home

struct HomeView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("MAMA").font(.largeTitle.bold())
                if !model.authorized { protectionCard }
                if let s = model.series, model.inProgress {
                    SeriesCard(series: s)
                } else {
                    startCard
                }
                #if DEBUG
                TestShieldCard()
                #endif
            }
            .padding()
        }
        .background(Theme.night.ignoresSafeArea())
        .navigationBarHidden(true)
    }

    private var protectionCard: some View {
        Card {
            Text("Защита").font(.headline)
            Text("MAMA включает блокировку через Screen Time. Приложения выбирать не нужно: MAMA закрывает всё, что разрешает закрыть iPhone. Звонки и экстренные вызовы всегда доступны.")
                .foregroundStyle(Theme.muted)
            Text("Доступ можно отключить в Настройках. Если отключить его во время серии, серия будет считаться сорванной.")
                .font(.footnote)
                .foregroundStyle(Theme.muted)
            Button("Разрешить Screen Time") { model.requestAuthorization() }
                .buttonStyle(.borderedProminent)
        }
    }

    private var startCard: some View {
        Card {
            if let s = model.series, s.status == "COMPLETED" {
                Text("Серия пройдена").font(.headline)
                Text("\(s.days) из \(s.days). Ты держишь слово, данное себе.").foregroundStyle(Theme.muted)
            } else if let s = model.series, s.status == "BROKEN" {
                Text("Серия завершена досрочно").font(.headline)
                Text(s.endReason == "PROTECTION_REVOKED"
                    ? "Доступ Screen Time был отключён во время серии."
                    : "Ты прошёл \(s.completed) из \(s.days) дней.")
                    .foregroundStyle(Theme.muted)
            } else {
                Text("Серия").font(.headline)
                Text("3, 5 или 7 дней. Одно окно времени каждый день.").foregroundStyle(Theme.muted)
            }
            let wait = model.daysUntilFreeStart
            if wait > 0 {
                Text("Следующая бесплатная серия через \(daysText(wait)). Телефон при этом не заблокирован.")
                    .font(.footnote)
                    .foregroundStyle(Theme.muted)
            }
            Button("Начать") { model.beginDraft() }
                .buttonStyle(.borderedProminent)
                .disabled(!model.authorized || wait > 0)
        }
    }
}

struct SeriesCard: View {
    @EnvironmentObject var model: AppModel
    let series: SeriesView

    var body: some View {
        Card {
            Text(title).font(.headline)
            Text(subtitle).foregroundStyle(Theme.muted)
            Text("\(clock(Int(series.startMinute))) → \(clock(Int(series.endMinute))) · каждый день")
            Text("Доверенный контакт: \(series.contactName)").foregroundStyle(Theme.muted)
            if series.status == "ACTIVE" {
                Text("Отключение доступа Screen Time во время серии — срыв.")
                    .font(.footnote)
                    .foregroundStyle(Theme.muted)
            }
            if series.status == "NEEDS_ATTENTION" {
                Button("Подтвердить запуск") { model.relaunch() }
                    .buttonStyle(.borderedProminent)
                    .disabled(!model.authorized)
            }
            if series.status != "ACTIVE" {
                Button("Отменить запуск", role: .destructive) { model.cancelLaunch() }
            }
        }
    }

    private var title: String {
        switch series.status {
        case "SCHEDULED": return "Серия запланирована"
        case "ACTIVATING": return "Включаем защиту…"
        case "NEEDS_ATTENTION": return "Серия не началась"
        default: return "Серия идёт"
        }
    }

    private var subtitle: String {
        switch series.status {
        case "SCHEDULED":
            if let start = series.lockStartMs?.int64Value { return "Первая блокировка: \(moment(start))" }
            return "Первая блокировка запланирована."
        case "ACTIVATING":
            return "Блокировка началась. Как только защита подтвердится, серия начнётся."
        case "NEEDS_ATTENTION":
            return series.autoNow
                ? "MAMA не смогла включить защиту. Блокировка начнётся сразу после подтверждения, время серии отсчитается от этого момента."
                : "MAMA не смогла включить защиту. После подтверждения — ближайшее полное окно."
        default:
            return "День \(min(Int(series.completed) + 1, Int(series.days))) из \(series.days)"
        }
    }
}

// MARK: - Draft: series → time → contact → «Цена слова» → launch

struct SeriesStepView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        Form {
            Section {
                ForEach(model.seriesDays, id: \.self) { days in
                    Button {
                        model.days = days
                    } label: {
                        HStack {
                            VStack(alignment: .leading) {
                                Text("\(days) дней").foregroundStyle(.primary)
                                Text("Restart при срыве — \(model.restartPrice(days)) ₽").font(.footnote).foregroundStyle(Theme.muted)
                            }
                            Spacer()
                            if model.days == days { Image(systemName: "checkmark").foregroundStyle(Theme.emerald) }
                        }
                    }
                }
            } footer: {
                Text("Старт серии бесплатный.")
            }
        }
        .navigationTitle("Серия")
        .safeAreaInset(edge: .bottom) { NextButton(title: "Далее") { model.path.append(.time) } }
    }
}

struct TimeStepView: View {
    @EnvironmentObject var model: AppModel
    private let tick = Timer.publish(every: 20, on: .main, in: .common).autoconnect()

    var body: some View {
        Form {
            Section {
                DatePicker(
                    "Начало",
                    selection: Binding(get: { date(forMinute: model.startMinute) }, set: { model.pickStart(minute(of: $0)) }),
                    displayedComponents: .hourAndMinute
                )
                DatePicker(
                    "Окончание",
                    selection: Binding(get: { date(forMinute: model.endMinute) }, set: { model.pickEnd(minute(of: $0)) }),
                    displayedComponents: .hourAndMinute
                )
            } header: {
                Text("Одно окно, которое повторяется каждый день серии")
            } footer: {
                Text(hint)
            }
        }
        .navigationTitle("Выбери время")
        .onAppear { model.refreshAutoNow() }
        .onReceive(tick) { _ in model.refreshAutoNow() }
        .safeAreaInset(edge: .bottom) {
            NextButton(title: "Далее", enabled: model.startMinute != model.endMinute) { model.path.append(.contact) }
        }
    }

    private var hint: String {
        if model.startMinute == model.endMinute { return "Начало и окончание совпадают." }
        let length = lengthText(model.draftLength)
        if model.autoNow {
            return "Начать после подтверждения · \(length) каждый день.\nНачало уточнится в момент запуска."
        }
        if let first = model.preview { return "Первая блокировка: \(moment(first.firstLockStartMs)) · \(length)" }
        return length
    }
}

struct ContactStepView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        Form {
            Section {
                TextField("Имя, например «Мама»", text: $model.contactName)
                TextField("+7 900 123-45-67", text: $model.contactPhone)
                    .keyboardType(.phonePad)
            } footer: {
                Text("Только этот человек сможет подтвердить досрочный выход из активной серии: MAMA пришлёт ему код.")
            }
        }
        .navigationTitle("Доверенный контакт")
        .safeAreaInset(edge: .bottom) {
            NextButton(title: "Далее", enabled: model.contactValid) { model.path.append(.price) }
        }
    }
}

struct PriceStepView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        Form {
            Section {
                Text("На iPhone «Цена слова» появится вместе с балансом MAMA. Эта серия запускается без неё.")
                    .foregroundStyle(Theme.muted)
            }
        }
        .navigationTitle("Цена слова")
        .safeAreaInset(edge: .bottom) { NextButton(title: "Далее") { model.path.append(.review) } }
    }
}

struct ReviewStepView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        Form {
            Section {
                row("Серия", "\(model.days) дней")
                row("Время", "\(clock(model.startMinute)) → \(clock(model.endMinute)) · каждый день")
                row("Первая блокировка", firstLock)
                row("Доверенный контакт", "\(model.contactName) · \(model.contactPhone)")
                row("Цена слова", "Без цены слова")
                row("Restart при срыве", "\(model.restartPrice(model.days)) ₽")
            } footer: {
                Text("После подтверждения время и длина серии не меняются.")
            }
        }
        .navigationTitle("Проверь условия")
        .safeAreaInset(edge: .bottom) { NextButton(title: "Начать \(model.days) дней") { model.launch() } }
    }

    private var firstLock: String {
        if model.autoNow { return "Сразу после подтверждения" }
        if let first = model.preview { return moment(first.firstLockStartMs) }
        return "—"
    }

    private func row(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.footnote).foregroundStyle(Theme.muted)
            Text(value)
        }
    }
}

// MARK: - Debug

struct TestShieldCard: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        Card {
            Text("Проверка Screen Time · тест").font(.headline)
            Text(model.testShieldOn ? "Тестовый щит включён: приложения закрыты." : "Тестовый щит выключен.")
                .foregroundStyle(Theme.muted)
            HStack {
                Button("Включить щит") { model.setTestShield(true) }
                Button("Снять щит") { model.setTestShield(false) }
            }
            .buttonStyle(.bordered)
            .disabled(!model.authorized)
            Button("Щит через 1 минуту на 15 минут") { model.scheduleTestShield() }
                .buttonStyle(.bordered)
                .disabled(!model.authorized)
            if model.testScheduled {
                Text("Расписание создано: щит включит DeviceActivity.").font(.footnote).foregroundStyle(Theme.muted)
            }
            Text("Доступ Screen Time: \(model.accessVerdict)").font(.footnote).foregroundStyle(Theme.muted)
            if let restored = model.protectionRestoredAtMs {
                Text("Защита восстановлена: \(moment(restored))").font(.footnote).foregroundStyle(Theme.muted)
            }
        }
    }
}

// MARK: - Components

struct Card<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 18))
    }
}

struct NextButton: View {
    let title: String
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title).frame(maxWidth: .infinity).padding(.vertical, 6)
        }
        .buttonStyle(.borderedProminent)
        .disabled(!enabled)
        .padding()
        .background(.bar)
    }
}
