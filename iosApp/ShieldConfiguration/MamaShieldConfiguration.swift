import ManagedSettings
import ManagedSettingsUI
import UIKit

/// What a shielded app or site shows during a MAMA lock: calm, firm, never shaming.
final class MamaShieldConfiguration: ShieldConfigurationDataSource {
    private var mama: ShieldConfiguration {
        ShieldConfiguration(
            backgroundBlurStyle: .systemUltraThinMaterialDark,
            backgroundColor: UIColor(red: 0.05, green: 0.09, blue: 0.11, alpha: 1),
            icon: nil,
            title: ShieldConfiguration.Label(text: "MAMA", color: .white),
            subtitle: ShieldConfiguration.Label(
                text: "Сейчас идёт блокировка.\nТы держишь слово, данное себе.",
                color: UIColor(white: 1, alpha: 0.8)
            ),
            primaryButtonLabel: ShieldConfiguration.Label(text: "Закрыть", color: .black),
            primaryButtonBackgroundColor: .white
        )
    }

    override func configuration(shielding application: Application) -> ShieldConfiguration { mama }

    override func configuration(shielding application: Application, in category: ActivityCategory) -> ShieldConfiguration { mama }

    override func configuration(shielding webDomain: WebDomain) -> ShieldConfiguration { mama }

    override func configuration(shielding webDomain: WebDomain, in category: ActivityCategory) -> ShieldConfiguration { mama }
}
