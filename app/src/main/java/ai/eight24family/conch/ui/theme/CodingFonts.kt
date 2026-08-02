package ai.eight24family.conch.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import ai.eight24family.conch.R

/**
 * Curated, BUNDLED coding fonts — all free with permissive licences (SIL OFL
 * 1.1 / Apache-2.0), files in `res/font`. The user picks one in Settings
 * (custom theme → font) and it drives the whole app's [appTypography]. No
 * custom uploads — only this fixed list, so we never ship un-vetted font files.
 *
 * Licences: JetBrains Mono (OFL), Fira Code (OFL), Source Code Pro (OFL),
 * IBM Plex Mono (OFL), Space Mono (OFL), Ubuntu Mono (Ubuntu Font Licence),
 * system mono = the platform default. Credited in About.
 */
enum class CodingFont(val id: String, val label: String, val family: FontFamily) {
    SYSTEM("system", "System mono", FontFamily.Monospace),
    JETBRAINS("jetbrains_mono", "JetBrains Mono", FontFamily(Font(R.font.jetbrains_mono))),
    FIRA_CODE("fira_code", "Fira Code", FontFamily(Font(R.font.fira_code))),
    SOURCE_CODE_PRO("source_code_pro", "Source Code Pro", FontFamily(Font(R.font.source_code_pro))),
    IBM_PLEX_MONO("ibm_plex_mono", "IBM Plex Mono", FontFamily(Font(R.font.ibm_plex_mono))),
    SPACE_MONO("space_mono", "Space Mono", FontFamily(Font(R.font.space_mono))),
    UBUNTU_MONO("ubuntu_mono", "Ubuntu Mono", FontFamily(Font(R.font.ubuntu_mono))),
    ;

    companion object {
        fun byId(id: String?): CodingFont = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}
