package ai.eight24family.conch.ui.navigation

/**
 * One-shot hand-off so a screen can ask the Settings tab to open straight at a
 * specific category (e.g. chat → "Connect phone" with Shizuku off should land in
 * the Phone-bridge section, not the Settings index). We DON'T pass it as a nav
 * argument because Settings is a tab and tab navigation restores saved state,
 * which would drop the arg. Set [pendingCategory], navigate to the Settings tab;
 * SettingsScreen consumes it on open and clears it.
 */
object SettingsDeepLink {
    @Volatile
    var pendingCategory: String? = null
}
