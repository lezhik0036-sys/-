import ManagedSettings
import ManagedSettingsUI
import UIKit

/// The night screen shown in place of a blocked app (palette from docs/DESIGN.md).
final class ShieldConfigurationExtension: ShieldConfigurationDataSource {
    override func configuration(shielding application: Application) -> ShieldConfiguration {
        mama
    }

    override func configuration(shielding application: Application, in category: ActivityCategory) -> ShieldConfiguration {
        mama
    }

    override func configuration(shielding webDomain: WebDomain) -> ShieldConfiguration {
        mama
    }

    override func configuration(shielding webDomain: WebDomain, in category: ActivityCategory) -> ShieldConfiguration {
        mama
    }

    private var mama: ShieldConfiguration {
        var subtitle = "Сейчас время отдыха."
        if let end = MamaStore.sessionEnd {
            subtitle = "Блокировка до \(end.formatted(date: .omitted, time: .shortened))."
        }
        return ShieldConfiguration(
            backgroundBlurStyle: .dark,
            backgroundColor: UIColor(hex: 0x091714),
            icon: nil,
            title: .init(text: "MAMA", color: UIColor(hex: 0xF7F4EC)),
            subtitle: .init(text: subtitle, color: UIColor(hex: 0xA8BFAF)),
            primaryButtonLabel: .init(text: "Понятно", color: UIColor(hex: 0xF7F4EC)),
            primaryButtonBackgroundColor: UIColor(hex: 0x28594A)
        )
    }
}

private extension UIColor {
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1
        )
    }
}
