package ai.eight24family.conch.ssh

import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.domain.ServerSecrets

enum class OsFamily { DEBIAN, FEDORA, ARCH, ALPINE, MACOS, OTHER }

data class ServerEnvironment(
    val osFamily: OsFamily,
    val osPretty: String,
    val hasSudo: Boolean,
    val hasTmux: Boolean,
    val hasNode: Boolean,
    val hasClaude: Boolean,
    val hasCodex: Boolean,
    val hasGemini: Boolean
)

class EnvironmentProbe(private val ssh: SshClient) {
    suspend fun probe(server: Server, secrets: ServerSecrets): Result<ServerEnvironment> {
        val script = """
            (cat /etc/os-release 2>/dev/null || sw_vers 2>/dev/null) | head -20
            echo '---SUDO---'
            command -v sudo >/dev/null 2>&1 && echo yes || echo no
            echo '---TMUX---'
            command -v tmux >/dev/null 2>&1 && echo yes || echo no
            echo '---NODE---'
            command -v node >/dev/null 2>&1 && echo yes || echo no
            echo '---CLAUDE---'
            command -v claude >/dev/null 2>&1 && echo yes || echo no
            echo '---CODEX---'
            command -v codex >/dev/null 2>&1 && echo yes || echo no
            echo '---GEMINI---'
            command -v gemini >/dev/null 2>&1 && echo yes || echo no
        """.trimIndent()
        return ssh.execute(server, secrets, script).map { parse(it) }
    }

    private fun parse(text: String): ServerEnvironment {
        val sections = text.split(Regex("---[A-Z]+---")).map { it.trim() }
        val osText = sections.getOrNull(0).orEmpty()
        val pretty = osText.lineSequence()
            .firstOrNull { it.startsWith("PRETTY_NAME=") }
            ?.substringAfter('=')
            ?.trim('"')
            ?: osText.lineSequence().firstOrNull().orEmpty().trim()

        val family = when {
            osText.contains("ID_LIKE=debian", true) || osText.contains("ID=debian", true) ||
                    osText.contains("ID=ubuntu", true) -> OsFamily.DEBIAN
            osText.contains("ID=fedora", true) || osText.contains("ID_LIKE=\"rhel", true) ||
                    osText.contains("ID=centos", true) || osText.contains("ID=rocky", true) -> OsFamily.FEDORA
            osText.contains("ID=arch", true) || osText.contains("ID_LIKE=arch", true) -> OsFamily.ARCH
            osText.contains("ID=alpine", true) -> OsFamily.ALPINE
            osText.contains("ProductName", true) || osText.contains("macOS", true) -> OsFamily.MACOS
            else -> OsFamily.OTHER
        }

        fun yes(idx: Int) = sections.getOrNull(idx)?.trim().equals("yes", ignoreCase = true)

        return ServerEnvironment(
            osFamily = family,
            osPretty = pretty,
            hasSudo = yes(1),
            hasTmux = yes(2),
            hasNode = yes(3),
            hasClaude = yes(4),
            hasCodex = yes(5),
            hasGemini = yes(6)
        )
    }
}
