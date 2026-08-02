package ai.eight24family.conch.ssh

import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.domain.ServerSecrets

class PackageInstaller(private val ssh: SshClient) {

    suspend fun installTmux(server: Server, secrets: ServerSecrets, env: ServerEnvironment): Result<String> {
        if (env.hasTmux) return Result.success("tmux already present")
        return runShell(server, secrets, env, tmuxCommand(env))
    }

    suspend fun installNode(server: Server, secrets: ServerSecrets, env: ServerEnvironment): Result<String> {
        if (env.hasNode) return Result.success("node already present")
        return runShell(server, secrets, env, nodeCommand(env))
    }

    suspend fun installNpmGlobal(
        server: Server,
        secrets: ServerSecrets,
        env: ServerEnvironment,
        packages: List<String>
    ): Result<String> {
        val pkgs = packages.joinToString(" ")
        // `--userconfig=/dev/null` on the sudo path: a user ~/.npmrc with
        // `prefix=~/.local` (a common unprivileged-global-install setup) would
        // otherwise leak into the ROOT install and misdirect it out of the
        // system prefix, so the package never lands where it's expected.
        val cmd = if (env.hasSudo) "sudo -n npm install -g $pkgs --userconfig=/dev/null" else "npm install -g $pkgs"
        return ssh.execute(server, secrets, cmd)
    }

    private suspend fun runShell(
        server: Server,
        secrets: ServerSecrets,
        env: ServerEnvironment,
        cmd: String
    ): Result<String> {
        val wrapped = if (env.hasSudo) "sudo -n sh -c '$cmd'" else "sh -c '$cmd'"
        return ssh.execute(server, secrets, wrapped)
    }

    private fun tmuxCommand(env: ServerEnvironment): String = when (env.osFamily) {
        OsFamily.DEBIAN -> "DEBIAN_FRONTEND=noninteractive apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y tmux"
        OsFamily.FEDORA -> "dnf install -y tmux"
        OsFamily.ARCH -> "pacman -Sy --noconfirm tmux"
        OsFamily.ALPINE -> "apk add --no-cache tmux"
        OsFamily.MACOS -> "command -v brew >/dev/null && brew install tmux || echo \"brew not found\""
        OsFamily.OTHER -> "echo \"unsupported OS for auto-install\" >&2 && exit 1"
    }

    private fun nodeCommand(env: ServerEnvironment): String = when (env.osFamily) {
        OsFamily.DEBIAN ->
            "curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs"
        OsFamily.FEDORA -> "dnf install -y nodejs npm"
        OsFamily.ARCH -> "pacman -Sy --noconfirm nodejs npm"
        OsFamily.ALPINE -> "apk add --no-cache nodejs npm"
        OsFamily.MACOS -> "command -v brew >/dev/null && brew install node || echo \"brew not found\""
        OsFamily.OTHER -> "echo \"unsupported OS for auto-install\" >&2 && exit 1"
    }
}
