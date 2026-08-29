package ai.eight24family.conch.ui.viewmodel

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentInstallManager
import ai.eight24family.conch.agent.AgentStatus
import ai.eight24family.conch.agent.InstallOp

/**
 * Install / update pipeline pulled out of [AgentPickerViewModel].
 *
 * Owns the multi-path bash bootstrap that lands `claude` / `codex` /
 * `gemini` on the server, plus the post-install refresh hand-off.
 *
 * Cascade verified against the actually-published install methods:
 *   1. Claude-specific official installer (`claude.ai/install.sh`)
 *   2. `npm install -g` as the current user.
 *   3. `sudo -n npm install -g` — passwordless sudo retry.
 *   4. System pkg-mgr install of nodejs + npm, then npm retry.
 *   5. nvm fallback — works without root.
 *   6. Direct node tarball — last resort.
 *
 * Per user policy: NO error UI is surfaced. The button either
 * completes (refresh flips the badge to `[ login req ]` or
 * `[ ready ]`) or stays put for retry. The intermediate spinner is
 * the only "in-flight" UI.
 */
internal class AgentPickerViewModelInstall(
    private val serverId: String,
    private val statusesRead: () -> Map<Agent, AgentStatus>?,
) {

    /**
     * Install / update the agent's CLI. Hands off to the process-scoped
     * [AgentInstallManager] so it runs in the BACKGROUND (survives leaving
     * this screen), in PARALLEL with other agents (no single-flight lock),
     * and refreshes [AgentStatusCache] when done. We only build the bootstrap
     * script here (it doesn't depend on the VM); the manager owns execution +
     * in-flight state.
     */
    fun installAgent(agent: Agent) {
        val currentStatus = statusesRead()?.get(agent)
        val forceLatest = currentStatus?.let { it.installed && it.updateAvailable } ?: false
        AgentInstallManager.run(serverId, agent, buildInstallScript(agent, forceLatest), forceLatest)
    }

    /**
     * Suspend-form for callers that must wait (the auto-recovery branch in
     * [AgentPickerViewModelOAuth.startOAuthLogin] installs the CLI then logs
     * in). Delegates to the manager's suspend runner.
     */
    suspend fun doInstall(
        agent: Agent,
        forceLatest: Boolean,
        /**
         * Reinstall even though the binary is already on PATH, WITHOUT moving
         * to a newer version. The repair path needs exactly this: the old
         * `forceLatest = true` was the only way to skip the
         * already-installed fast path, so a repair silently doubled as an
         * upgrade — and an upgrade can change what SAFE/AUTO/YOLO grant
         * (see the pin comment in [buildInstallScript]).
         */
        forceReinstall: Boolean = false,
    ) {
        val script = buildInstallScript(agent, forceLatest, forceReinstall)
        AgentInstallManager.runAndWait(serverId, agent, script, forceLatest || forceReinstall)
    }

    /**
     * UNINSTALL the agent's CLI from the server (long-press → accounts sheet →
     * "remove from server"). Removes ONLY the binary — the chat history under
     * `~/.<agent>/` is left untouched (a re-install + the same `--resume <id>`
     * still opens every past session). Rides the same [AgentInstallManager]
     * machinery as install: background, parallel, streams the live log under
     * the row ("removing…"), and re-probes the cache when done → the badge
     * flips to `[ install ]`.
     */
    fun uninstallAgent(agent: Agent) {
        AgentInstallManager.run(
            serverId, agent, buildUninstallScript(agent), forceLatest = false,
            op = InstallOp.REMOVE,
        )
    }

    /**
     * Best-effort uninstall across every channel the install cascade could
     * have used: npm (user-prefix, default-prefix, sudo, nvm) + a hard `rm` of
     * the binary from every known location. Pure on [agent] (npmPackage /
     * cliCommand) — no VM state. Leaves `~/.<agent>/` history alone.
     */
    private fun buildUninstallScript(agent: Agent): String {
        val pkg = agent.npmPackage
        val bin = agent.cliCommand
        return """
            set +e
            export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
            SUDO=""
            if [ "${'$'}(id -u)" != "0" ] && command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
                SUDO="sudo -n"
            fi
            echo "removing $bin…"
            ${if (pkg != null) """
            # 1. npm uninstall — user prefix, then default prefix, then sudo.
            if command -v npm >/dev/null 2>&1; then
                npm config set prefix "${'$'}HOME/.local" 2>&1 || true
                npm uninstall -g $pkg 2>&1 || true
                npm config delete prefix 2>&1 || true
                npm uninstall -g $pkg 2>&1 || true
                if [ -n "${'$'}SUDO" ]; then ${'$'}SUDO npm uninstall -g $pkg 2>&1 || true; fi
            fi
            # 2. nvm-managed npm, if present.
            if [ -s "${'$'}HOME/.nvm/nvm.sh" ]; then
                export NVM_DIR="${'$'}HOME/.nvm"
                . "${'$'}HOME/.nvm/nvm.sh" 2>/dev/null
                command -v npm >/dev/null 2>&1 && npm uninstall -g $pkg 2>&1 || true
            fi
            """ else """
            # 1-2. Not an npm package — nothing for npm to uninstall. The
            #      vendor installer's own tree is removed with the binary below.
            """}
            # 3. Hard-remove the binary from every known install location
            #    (official installer / tarball drop their own symlink/wrapper
            #    that npm uninstall never sees).
            for CAND in \
                "${'$'}HOME/.local/bin/$bin" \
                "/usr/local/bin/$bin" \
                "/usr/bin/$bin" \
                "${'$'}HOME/.nvm/versions/node/"*"/bin/$bin" \
                "${'$'}HOME/.local/node-"*"/bin/$bin"; do
                if [ -e "${'$'}CAND" ] || [ -L "${'$'}CAND" ]; then
                    rm -f "${'$'}CAND" 2>/dev/null
                    if [ -e "${'$'}CAND" ] && [ -n "${'$'}SUDO" ]; then ${'$'}SUDO rm -f "${'$'}CAND" 2>/dev/null; fi
                fi
            done
            # 4. Verify it's gone from PATH.
            if command -v $bin >/dev/null 2>&1; then
                echo "warning: $bin still on PATH at ${'$'}(command -v $bin)"
                exit 1
            fi
            echo "removed $bin"
            exit 0
        """.trimIndent()
    }

    /**
     * Build the bash bootstrap script for [agent]. Strategy verified
     * against the actual published install methods (not invented):
     *
     *  - **Claude Code** has an OFFICIAL installer at `claude.ai/install.sh`
     *    that puts `claude` into `~/.local/bin`. The npm package
     *    (`@anthropic-ai/claude-code`) is officially deprecated. Use
     *    the official installer first.
     *  - **Codex CLI** / **Gemini CLI** are npm-only. They require Node
     *    22+ and 20+ respectively. Debian's apt-get nodejs is 18.x —
     *    too old for Codex. We use NodeSource (`deb.nodesource.com`,
     *    `rpm.nodesource.com`) to get a current Node, fall back to
     *    nvm or a direct tarball if NodeSource isn't reachable.
     *  - **All npm globals** go into `~/.local` via `npm config set
     *    prefix ~/.local` so the binary lands at `~/.local/bin/<bin>` —
     *    which Debian's default `~/.profile` adds to PATH. No sudo
     *    needed for the install, AND the binary survives subsequent
     *    `bash -lc` probes (which on Debian skip `.bashrc` because
     *    of its early non-interactive return — so any nvm-only setup
     *    is invisible to the probe).
     *
     * Cascade (each step exits 0 once binary appears on PATH):
     *
     *   1. Claude-specific official installer (Claude only)
     *   2. Ensure recent Node: NodeSource for apt/dnf systems, system
     *      pkg mgr for the rest, nvm fallback, tarball as last resort
     *   3. `npm config set prefix ~/.local`, then `npm install -g <pkg>`
     *   4. Probe — if found, exit 0. If not, also try `sudo npm` as
     *      a last-ditch retry for system-wide install.
     */
    private fun buildInstallScript(
        agent: Agent,
        forceLatest: Boolean = false,
        forceReinstall: Boolean = false,
    ): String {
        val pkg = agent.npmPackage
        val bin = agent.cliCommand
        // The vendor's own installer, when npm is not the channel — read off
        // the spec, never branched on agent identity here.
        val officialInstall = ai.eight24family.conch.agent.spec
            .AgentSpecRegistry[agent].officialInstallCommand
        // `@latest` suffix forces npm to fetch the registry's current
        // version instead of returning whatever's cached locally. Used
        // for update flow (existing version present, newer version
        // wanted).
        // ⛔ PIN, don't reach for whatever @latest happens to be.
        //
        // A fresh install lands on the version whose mode flags were actually
        // replayed through the CLI's parser (spec/CliContract). Asked in public
        // 2026-08-27: "an app that auto-installs the CLIs could quietly change
        // the permissions a mode grants after an update" — and an audit of the
        // installed binaries found exactly that had already happened: at codex
        // 0.149.1 the SAFE and AUTO invocations were rejected at parse time
        // while YOLO still worked.
        //
        // `forceLatest` is the explicit "Update" tap and still goes to @latest:
        // the user asked for the newest, and the flag audit re-runs afterwards
        // so an unverified version cannot pass for a verified one.
        val pinned = ai.eight24family.conch.agent.spec.CliContracts[agent]?.pinnedVersion
        val npmTarget = when {
            pkg == null -> null // not on npm — the vendor installer is the channel
            forceLatest -> "$pkg@latest"
            pinned != null -> "$pkg@$pinned"
            else -> pkg
        }
        // Update flow skips the "already installed → exit 0" fast path
        // — that fast-path was THE bug that made `[ update ]` taps
        // silently no-op when the binary was already on PATH.
        // Reinstalling a BROKEN install has to get past the
        // already-on-PATH fast path too, not just an update.
        val skipEarlyExit = forceLatest || forceReinstall
        return """
            set +e
            export DEBIAN_FRONTEND=noninteractive
            # Ensure ~/.local/bin is on PATH for THIS shell — Debian
            # adds it via ~/.profile but only for login shells; we
            # need it inside `bash -lc 'cat | bash -s'` too.
            export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
            mkdir -p ~/.local/bin

            # Detect sudo availability up front.
            SUDO=""
            if [ "${'$'}(id -u)" != "0" ] && command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
                SUDO="sudo -n"
            fi

            ${if (skipEarlyExit) """
            # Update flow — skip the already-installed fast path.
            # First try to update IN PLACE: detect where the current
            # binary lives and use that channel's npm so the new
            # version lands at the same path. Otherwise the new
            # install ends up at ~/.local/bin/$bin while the old
            # one stays at /usr/local/bin/$bin shadowing it via PATH.
            CURRENT_BIN="${'$'}(command -v $bin 2>/dev/null || true)"
            case "${'$'}CURRENT_BIN" in
                ${if (pkg == null) "" else """/usr/local/bin/*|/usr/bin/*)
                    # System path — update via system npm with sudo.
                    #
                    # CRITICAL: pin the prefix to where the binary ACTUALLY lives
                    # and IGNORE the user's ~/.npmrc. A very common setup has
                    # `prefix=~/.local` in the user's ~/.npmrc (unprivileged global
                    # installs). When we `sudo npm install -g`, npm reads that
                    # ~/.npmrc, so the ROOT install either errors ("prefix cannot be
                    # changed from project config") or lands in the user's ~/.local
                    # instead of /usr — the system binary NEVER updates and the row
                    # is stuck on "[ update ]" forever no matter how many times it's
                    # tapped (user, 2026-07-16). `--prefix` (bin is <prefix>/bin/x, so
                    # prefix = dirname(dirname(bin))) targets the real location;
                    # `--userconfig=/dev/null` stops the stray ~/.npmrc from
                    # redirecting the sudo install.
                    if command -v npm >/dev/null 2>&1; then
                        if [ -n "${'$'}SUDO" ] || [ "${'$'}(id -u)" = "0" ]; then
                            SYSPREFIX="${'$'}(dirname "${'$'}(dirname "${'$'}CURRENT_BIN")")"
                            ${'$'}SUDO npm install -g $npmTarget --prefix="${'$'}SYSPREFIX" --userconfig=/dev/null 2>&1
                            if "${'$'}CURRENT_BIN" --version 2>/dev/null | head -1; then exit 0; fi
                        fi
                    fi
                    ;;
                "${'$'}HOME"/.nvm/*)
                    # nvm-managed install — same nvm npm.
                    export NVM_DIR="${'$'}HOME/.nvm"
                    [ -s "${'$'}NVM_DIR/nvm.sh" ] && . "${'$'}NVM_DIR/nvm.sh"
                    if command -v npm >/dev/null 2>&1; then
                        npm install -g $npmTarget 2>&1
                        if "${'$'}CURRENT_BIN" --version 2>/dev/null | head -1; then exit 0; fi
                    fi
                    ;;
                """}
                "${'$'}HOME"/.local/bin/$bin)
                    # User-prefix install. Vendor installer where there is one;
                    # npm otherwise.
                    ${if (officialInstall != null) """
                    if command -v curl >/dev/null 2>&1; then
                        $officialInstall 2>&1
                        if "${'$'}HOME/.local/bin/$bin" --version 2>/dev/null | head -1; then exit 0; fi
                    fi
                    """ else """
                    if command -v npm >/dev/null 2>&1; then
                        npm config set prefix "${'$'}HOME/.local" 2>&1 || true
                        npm install -g $npmTarget 2>&1
                        if "${'$'}HOME/.local/bin/$bin" --version 2>/dev/null | head -1; then exit 0; fi
                    fi
                    """}
                    ;;
            esac
            # In-place update didn't stick — fall through to the full
            # install cascade. May install to a different prefix; the
            # final verification block will figure out which one wins.
            """.trimIndent() else """
            # 0. Already installed?
            if command -v $bin >/dev/null 2>&1; then exit 0; fi
            """.trimIndent()}

            ${if (officialInstall != null) """
            # 1. The vendor's OWN installer (Claude Code, Cursor). Lands in
            #    ~/.local/bin/$bin, already on PATH (and Debian's ~/.profile
            #    picks up ~/.local/bin too).
            if command -v curl >/dev/null 2>&1; then
                $officialInstall 2>&1
                if command -v $bin >/dev/null 2>&1; then exit 0; fi
            fi
            """ else ""}
            ${if (pkg == null) """
            # This CLI does not ship on npm, so there is no npm cascade to
            # fall through to: the vendor installer above is the only channel.
            # Fall through to the verification block, which reports honestly
            # whether the binary appeared.
            """ else """

            # 2. Ensure recent Node. Codex requires 22+, Gemini 20+.
            #    Debian/Ubuntu apt nodejs is too old — use NodeSource.
            need_recent_node=1
            if command -v node >/dev/null 2>&1; then
                NODE_MAJOR=${'$'}(node -v 2>/dev/null | sed 's/^v//' | cut -d. -f1)
                if [ -n "${'$'}NODE_MAJOR" ] && [ "${'$'}NODE_MAJOR" -ge 20 ] 2>/dev/null; then
                    need_recent_node=0
                fi
            fi

            if [ "${'$'}need_recent_node" = "1" ]; then
                if command -v apt-get >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then
                    # NodeSource for Debian / Ubuntu / derivatives — gives
                    # current Node LTS into /usr/bin (always on PATH).
                    curl -fsSL https://deb.nodesource.com/setup_22.x | ${'$'}SUDO bash - 2>&1
                    ${'$'}SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs 2>&1
                elif command -v dnf >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then
                    # NodeSource for Fedora / RHEL / Rocky / Alma.
                    curl -fsSL https://rpm.nodesource.com/setup_22.x | ${'$'}SUDO bash - 2>&1
                    ${'$'}SUDO dnf install -y nodejs 2>&1
                elif command -v yum >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then
                    curl -fsSL https://rpm.nodesource.com/setup_22.x | ${'$'}SUDO bash - 2>&1
                    ${'$'}SUDO yum install -y nodejs 2>&1
                elif command -v pacman >/dev/null 2>&1; then
                    ${'$'}SUDO pacman -Sy --noconfirm nodejs npm 2>&1
                elif command -v apk >/dev/null 2>&1; then
                    ${'$'}SUDO apk add --no-cache nodejs npm 2>&1
                elif command -v zypper >/dev/null 2>&1; then
                    ${'$'}SUDO zypper -n install -y nodejs npm 2>&1
                elif command -v xbps-install >/dev/null 2>&1; then
                    ${'$'}SUDO xbps-install -Sy nodejs 2>&1
                elif command -v tdnf >/dev/null 2>&1; then
                    ${'$'}SUDO tdnf install -y nodejs npm 2>&1
                elif command -v eopkg >/dev/null 2>&1; then
                    ${'$'}SUDO eopkg install -y nodejs 2>&1
                elif command -v urpmi >/dev/null 2>&1; then
                    ${'$'}SUDO urpmi --auto nodejs npm 2>&1
                elif command -v opkg >/dev/null 2>&1; then
                    ${'$'}SUDO opkg update 2>&1 || true
                    ${'$'}SUDO opkg install node-npm 2>&1
                elif command -v emerge >/dev/null 2>&1; then
                    ${'$'}SUDO emerge --quiet net-libs/nodejs 2>&1
                elif command -v pkg >/dev/null 2>&1 && uname -s | grep -qi bsd; then
                    ${'$'}SUDO pkg install -y node npm 2>&1
                elif command -v brew >/dev/null 2>&1; then
                    brew install node 2>&1
                elif command -v pkg >/dev/null 2>&1; then
                    # Android Termux
                    pkg install -y nodejs 2>&1
                fi
            fi

            # 3. If npm is now available, force the user-local prefix
            #    so the binary lands in ~/.local/bin (no sudo, on PATH
            #    via Debian's ~/.profile and our exported PATH above).
            if command -v npm >/dev/null 2>&1; then
                npm config set prefix "${'$'}HOME/.local" 2>&1 || true
                npm install -g $npmTarget 2>&1
                if command -v $bin >/dev/null 2>&1; then exit 0; fi
                # Last try with sudo into the system prefix — some
                # setups have a writable global node_modules and we
                # want to maximise success rate. `--userconfig=/dev/null`
                # so a user ~/.npmrc `prefix=~/.local` can't redirect the
                # ROOT install back into the unprivileged home dir.
                if [ -n "${'$'}SUDO" ]; then
                    ${'$'}SUDO npm install -g $npmTarget --userconfig=/dev/null 2>&1
                    if command -v $bin >/dev/null 2>&1; then exit 0; fi
                fi
            fi

            # 4. nvm fallback — works without root. Crucially, we ALSO
            #    add a line to ~/.profile so subsequent `bash -lc` (the
            #    probe shell) finds the nvm-installed node. Debian's
            #    default ~/.bashrc early-returns for non-interactive
            #    shells, so nvm.sh sourced inside .bashrc is invisible
            #    to the probe.
            if command -v curl >/dev/null 2>&1; then
                export NVM_DIR="${'$'}HOME/.nvm"
                if [ ! -s "${'$'}NVM_DIR/nvm.sh" ]; then
                    curl -fsSL https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash 2>&1
                fi
                if [ -s "${'$'}NVM_DIR/nvm.sh" ]; then
                    . "${'$'}NVM_DIR/nvm.sh"
                    nvm install --lts 2>&1
                    nvm use --lts 2>&1
                    npm config set prefix "${'$'}HOME/.local" 2>&1 || true
                    npm install -g $npmTarget 2>&1
                    # Persist PATH + nvm sourcing into ~/.profile so the
                    # next `bash -lc` probe finds node + the installed bin.
                    if ! grep -q 'NVM_DIR' ~/.profile 2>/dev/null; then
                        cat >> ~/.profile <<'PROFEOF'

# Added by Conch install bootstrap
export NVM_DIR="${'$'}HOME/.nvm"
[ -s "${'$'}NVM_DIR/nvm.sh" ] && . "${'$'}NVM_DIR/nvm.sh"
[ -d "${'$'}HOME/.local/bin" ] && export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
PROFEOF
                    fi
                    if command -v $bin >/dev/null 2>&1; then exit 0; fi
                fi
            fi

            # 5. Direct node tarball — last resort. Works on any glibc
            #    Linux with curl + tar + xz.
            if command -v curl >/dev/null 2>&1 && command -v tar >/dev/null 2>&1; then
                NODE_VER="v22.11.0"
                case "${'$'}(uname -m)" in
                    x86_64|amd64) NODE_ARCH=x64 ;;
                    aarch64|arm64) NODE_ARCH=arm64 ;;
                    armv7l|armv6l) NODE_ARCH=armv7l ;;
                    ppc64le) NODE_ARCH=ppc64le ;;
                    s390x) NODE_ARCH=s390x ;;
                    *) NODE_ARCH="" ;;
                esac
                if [ -n "${'$'}NODE_ARCH" ]; then
                    URL="https://nodejs.org/dist/${'$'}NODE_VER/node-${'$'}NODE_VER-linux-${'$'}NODE_ARCH.tar.xz"
                    curl -fsSL "${'$'}URL" -o /tmp/node.tar.xz 2>&1
                    if [ -s /tmp/node.tar.xz ]; then
                        tar -xJf /tmp/node.tar.xz -C ~/.local 2>&1
                        rm -f /tmp/node.tar.xz
                        NODE_DIR=${'$'}(ls -d ~/.local/node-${'$'}NODE_VER-linux-${'$'}NODE_ARCH 2>/dev/null | head -1)
                        if [ -n "${'$'}NODE_DIR" ] && [ -x "${'$'}NODE_DIR/bin/node" ]; then
                            export PATH="${'$'}NODE_DIR/bin:${'$'}PATH"
                            "${'$'}NODE_DIR/bin/npm" config set prefix "${'$'}HOME/.local" 2>&1 || true
                            "${'$'}NODE_DIR/bin/npm" install -g $npmTarget 2>&1
                            # Persist for future login shells.
                            if ! grep -q "${'$'}NODE_DIR" ~/.profile 2>/dev/null; then
                                echo "export PATH=\"${'$'}NODE_DIR/bin:\${'$'}HOME/.local/bin:\${'$'}PATH\"" >> ~/.profile
                            fi
                        fi
                    fi
                fi
            fi
            """}

            # Final verification — RUN the binary directly from every
            # known install location. If it executes (exit 0 from its
            # built-in `--version` / `--help`), install is confirmed.
            # No symlinks, no PATH patches, no .profile fiddling — we
            # just trust "it ran" as proof.
            for CAND in \
                "${'$'}HOME/.local/bin/$bin" \
                "/usr/local/bin/$bin" \
                "/usr/bin/$bin" \
                "${'$'}HOME/.nvm/versions/node/"*"/bin/$bin" \
                "${'$'}HOME/.local/node-v22.11.0-linux-x64/bin/$bin"; do
                if [ -x "${'$'}CAND" ]; then
                    if "${'$'}CAND" --version >/dev/null 2>&1 \
                        || "${'$'}CAND" --help >/dev/null 2>&1; then
                        exit 0
                    fi
                fi
            done
            # PATH may already have it on simple setups.
            if command -v $bin >/dev/null 2>&1; then exit 0; fi
            exit 1
        """.trimIndent()
    }
}
