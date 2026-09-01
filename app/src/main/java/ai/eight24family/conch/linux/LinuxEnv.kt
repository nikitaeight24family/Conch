package ai.eight24family.conch.linux

import ai.eight24family.conch.adb.AdbShellV2
import ai.eight24family.conch.adb.LocalAdbShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A real Linux distribution, running on this phone.
 *
 * Not an emulator and not a terminal that pretends: a genuine Alpine userland
 * with `apk`, so anything in its 25 000 packages installs and runs. Measured on
 * the owner's phone (OPPO CPH2671, Android 16, aarch64, 2026-08-30):
 *
 *     whoami=root
 *     apk update           → OK: 25261 distinct packages available
 *     apk add python3      → OK: 49 MiB in 32 packages
 *     python3 -c print(1+1) → 2
 *
 * HOW IT CAN WORK AT ALL, with no root:
 *
 *  • The binaries live in the SHELL's directory, not ours. Android blocks an
 *    app from executing a file it wrote itself (W^X, enforced from target 36),
 *    but `/data/local/tmp` belongs to the shell uid and is executable — and the
 *    shell uid is exactly what the phone bridge already obtained. Measured:
 *    a binary copied there and run from there returns EXEC_OK.
 *  • Paths are rewritten by [proot], which needs no privilege of any kind: it
 *    intercepts syscalls with ptrace and answers `/etc/...` out of the rootfs
 *    directory. `ptrace_scope` is unrestricted on Android, so this is allowed.
 *  • `-0` makes the environment BELIEVE it is root, which is what `apk` insists
 *    on. Nothing outside the rootfs gains anything by it: the process is still
 *    uid 2000 to the kernel, and it can touch exactly what uid 2000 could.
 *
 * ⚠ IT LIVES UNTIL THE PHONE REBOOTS ONLY IN THE SENSE THAT THE SHELL DOES —
 * the files persist, but reaching them again needs the bridge armed again.
 */
object LinuxEnv {

    /** Everything lives here: the shell owns this directory, and it is the one
     *  place on the device where an unprivileged app can arrange for code to be
     *  executable. */
    const val ROOT = "/data/local/tmp/conch-linux"

    const val ROOTFS = "$ROOT/rootfs"

    /** Scripts and their logs. Lives OUTSIDE the rootfs on purpose: the phone
     *  shell reads it directly, with no proot round trip, which is what lets a
     *  running install be watched (and re-attached to) for free. */
    private const val WORK = "$ROOT/.conch"

    /** Inside the environment, the same directory the payload scripts land in. */
    private const val WORK_IN_ENV = "/root/.conch"

    /**
     * The runtime's second half.
     *
     * ⛔ THE LOADER IS NOT OPTIONAL AND NOT BUNDLED. PRoot injects a tiny ELF
     * into each traced process to bootstrap it; the build we ship keeps that
     * loader as a separate file (which is what makes the binary relocatable),
     * so every invocation has to say where it is. Without `PROOT_LOADER` the
     * runtime starts and then fails to launch anything.
     */
    const val LOADER = "$ROOT/libexec/proot/loader"

    /**
     * The invocation, with every part that had to be discovered by running it:
     *
     *  • `PROOT_TMP_DIR` — proot defaults to `/tmp`, which does not exist on
     *    Android, and fails obscurely without this.
     *  • `-0` — fake root inside the rootfs; `apk` refuses to work otherwise.
     *  • `/usr/bin/env PATH=…` — the child inherits ANDROID's PATH, so without
     *    this even `cat` is not found although it is sitting in the rootfs.
     *    Measured first run: "/bin/sh: cat: not found".
     *  • `-b /dev -b /proc -b /sys` — the kernel interfaces the userland expects.
     *  • `TMPDIR=/tmp` — ⛔ NOT COSMETIC, and `env` is why: it ADDS to the
     *    environment rather than replacing it, so Android's own
     *    `TMPDIR=/data/local/tmp` rides in — a path that does not exist inside
     *    the rootfs. Measured 2026-08-31: `make` said "TMPDIR value
     *    /data/local/tmp: No such file or directory"; npm and the vendor
     *    installers write through the same variable and would fail in ways that
     *    name a package and never the cause.
     */
    private fun wrap(command: String): String {
        val q = command.replace("'", "'\\''")
        return "cd $ROOT && PROOT_TMP_DIR=$ROOT PROOT_LOADER=$LOADER ./proot -r rootfs -0 " +
            "-b /dev -b /proc -b /sys -w /root " +
            "/usr/bin/env " +
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
            "HOME=/root TERM=xterm LANG=C.UTF-8 TMPDIR=/tmp " +
            "/bin/sh -c '$q'"
    }

    /**
     * Is a usable environment already unpacked here?
     *
     * ⛔ DO NOT TEST A PATH THAT ONLY RESOLVES INSIDE THE ROOTFS. This asked for
     * `rootfs/bin/sh`, which in every busybox image is a symlink to the ABSOLUTE
     * path `/bin/busybox` — correct inside the environment, dangling when
     * followed from Android, where that path does not exist. So a perfectly good
     * install answered "not installed": the button came back with no error,
     * because there had been no error (owner, 2026-08-30).
     *
     * `os-release` is a real file, is present in every distribution image, and
     * says nothing about symlink targets.
     */
    suspend fun isInstalled(): Boolean = presence() == Presence.INSTALLED

    /**
     * The three answers, kept apart.
     *
     * ⛔ "I COULD NOT LOOK" IS NOT "IT IS NOT THERE." [isInstalled] has to
     * return one bit, so it folds an unreachable phone shell into `false` —
     * survivable for a screen the user opened on purpose, wrong for a row that
     * sits in a list and states what the machine IS. An install that is present
     * and merely out of reach must not be advertised as missing, or the row
     * offers to install it a second time over the top of itself.
     */
    enum class Presence { INSTALLED, ABSENT, UNREACHABLE }

    suspend fun presence(): Presence {
        val r = LocalAdbShell.exec("[ -x $ROOT/proot ] && [ -s $ROOTFS/etc/os-release ] && echo yes")
            ?: return Presence.UNREACHABLE
        return if (r.stdout.trim() == "yes") Presence.INSTALLED else Presence.ABSENT
    }

    /**
     * What the app last learned about this machine.
     *
     * The servers list renders a row for it, and asking the phone from inside
     * that row would put a two-round-trip flicker on every visit to the tab —
     * and would leave the row stale after an install on the Linux page, since
     * nothing would tell it. One shared snapshot fixes both: the list paints the
     * last known truth immediately, then [refresh] corrects it, and the install
     * and remove paths write through so every screen agrees at once.
     */
    data class Snapshot(
        /** null = never looked yet, which is not the same as UNREACHABLE. */
        val presence: Presence? = null,
        /** "Alpine Linux v3.21 · 32 packages", once it has been asked. */
        val summary: String? = null,
        /** Disk it occupies, human-readable. */
        val size: String? = null,
    )

    /**
     * The one line the servers list prints under "this phone".
     *
     * ⛔ UNREACHABLE MUST NEVER READ AS AN INVITATION TO INSTALL. It is the
     * whole reason [Presence] has three values instead of a Boolean: a rootfs
     * that exists but cannot be reached right now is still a rootfs, and
     * offering to lay a fresh one over it is how the first one dies. Pure, so
     * the rule is pinned by a test rather than by a screenshot.
     */
    fun subtitle(snap: Snapshot): String = when (snap.presence) {
        Presence.INSTALLED -> listOfNotNull(snap.summary ?: "linux", snap.size).joinToString(" · ")
        Presence.ABSENT -> "linux — tap to set up"
        Presence.UNREACHABLE -> "linux — phone shell not connected"
        null -> "linux"
    }

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    /** Ask the phone, cheapest question first, publishing each answer as it
     *  arrives so the row fills in rather than waiting on the slowest part.
     *  `describe()` starts the environment; presence is one `[ -s ]` test. */
    suspend fun refresh() {
        val p = presence()
        if (p != Presence.INSTALLED) {
            _snapshot.value = Snapshot(presence = p)
            return
        }
        _snapshot.value = _snapshot.value.copy(presence = p)
        val size = diskUsage()
        _snapshot.value = _snapshot.value.copy(size = size)
        _snapshot.value = _snapshot.value.copy(summary = describe())
    }

    /** Run one command inside the distribution. Null when the phone shell is not
     *  available at all — the caller has to say something different for that
     *  than for a command that ran and failed. */
    suspend fun run(command: String, limit: Int = 1 shl 20): AdbShellV2.Result? =
        LocalAdbShell.exec(wrap(command), limit)

    // ─────────────────────── long work ───────────────────────
    //
    // ⛔ [run] CANNOT CARRY AN INSTALL, AND THE REASON IS NOT PATIENCE.
    //
    // It holds the ADB socket for the whole command, and that socket has a
    // 30-second read timeout (AdbLocal.connect). `apk add nodejs npm` alone
    // moves 100 MiB through a syscall-rewriting runtime; `npm install -g` moves
    // more. Every one of those would come back as a dead session — and worse,
    // the work would die with it, because the caller's screen owns the call.
    //
    // So anything long is DETACHED and WATCHED instead: the script is staged as
    // a file, launched with its output redirected to a log OUTSIDE the rootfs,
    // and the log is polled. Three properties fall out of that shape, all of
    // them needed:
    //
    //  • the install survives the screen being left, the app being backgrounded,
    //    and the app being killed — it is not our process;
    //  • the log is a plain file the phone shell reads directly, so watching it
    //    costs one cheap round trip and no proot at all;
    //  • an install already in flight can be RE-ATTACHED to on the way back in
    //    ([isRunning]), which is the difference between "it is still working"
    //    and a second install started over the top of the first.

    /** How a detached script ended: everything it printed, and its exit code
     *  (null ⇒ it was still running when we stopped watching). */
    data class ScriptRun(val log: String, val exitCode: Int?)

    /** The sentinel the launcher appends. Its presence in the log is what
     *  "finished" MEANS here — there is no process to wait on. */
    private const val DONE = "CONCH_DONE:"

    /** Base64 so a script body — quotes, `$`, newlines, heredocs and all —
     *  crosses `sh -c` untouched. Escaping it would work right up until the
     *  first agent whose install script contains the wrong quote. */
    private fun stagedWrite(path: String, body: String): String {
        val b64 = java.util.Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
        return "printf %s $b64 | base64 -d > $path"
    }

    /**
     * Write a file INTO the environment from the Android side, with a mode.
     *
     * [path] is absolute as the environment sees it (`/root/.ssh/authorized_keys`),
     * and the write happens straight into the rootfs directory — no proot, one
     * round trip, and it works before anything inside the environment is set up.
     */
    suspend fun writeFile(path: String, content: String, mode: String = "600"): Boolean {
        val target = "$ROOTFS$path"
        val dir = target.substringBeforeLast('/')
        val r = LocalAdbShell.exec(
            listOf(
                "mkdir -p $dir",
                stagedWrite(target, content),
                "chmod $mode $target",
                "echo WROTE",
            ).joinToString(" && "),
        ) ?: return false
        return r.stdout.contains("WROTE")
    }

    /**
     * Read a file OUT of the environment from the Android side.
     *
     * Same trust and the same single round trip as [writeFile]: the rootfs is a
     * plain directory to the phone shell, so this answers even while nothing
     * inside the environment runs — and it never touches the network or the
     * daemon's port, which is what lets [LinuxSsh] treat the result as the
     * truth about the machine rather than as a claim by whatever answered a
     * port.
     */
    suspend fun readFile(path: String, limit: Int = 1 shl 18): String? =
        LocalAdbShell.exec("cat $ROOTFS$path 2>/dev/null", limit)
            ?.stdout?.takeIf { it.isNotBlank() }

    /** Is a script of this name still running (log exists, no sentinel yet)? */
    suspend fun isRunning(name: String): Boolean {
        val r = LocalAdbShell.exec(
            "[ -f $WORK/$name.log ] && ! grep -q $DONE $WORK/$name.log && echo yes",
        ) ?: return false
        return r.stdout.trim() == "yes"
    }

    /** Everything a detached script has printed so far. */
    suspend fun scriptLog(name: String, limit: Int = 1 shl 18): String? =
        LocalAdbShell.exec("tail -c $limit $WORK/$name.log 2>/dev/null", limit)?.stdout

    /**
     * Stage [body] as `<name>.sh`, run it inside the environment detached from
     * this ADB session, and watch its log until it prints the sentinel.
     *
     * [onLine] is called with the newest non-empty output line each time the
     * log grows — that is the live "installing…" text, and it costs nothing
     * extra because we are already polling.
     *
     * Returns null only when the phone shell is out of reach entirely.
     */
    suspend fun runScript(
        name: String,
        body: String,
        timeoutMs: Long = 30 * 60_000L,
        pollMs: Long = 1_200L,
        onLine: ((String) -> Unit)? = null,
    ): ScriptRun? {
        if (!launchScript(name, body)) return null
        return runScriptWatch(name, timeoutMs, pollMs, onLine)
    }

    /**
     * Stage and launch, without waiting for anything.
     *
     * The daemon case: a script that is not supposed to END. Watching one would
     * block until the timeout and report a failure at the end of it, which is
     * precisely backwards — for a daemon, "still running" IS success.
     */
    suspend fun launchScript(name: String, body: String): Boolean {
        val payload = "$ROOTFS$WORK_IN_ENV/$name.sh"
        val launcher = "$WORK/$name.run.sh"
        val log = "$WORK/$name.log"
        // The launcher is a FILE for the same reason the payload is: it carries
        // the proot invocation, and nesting that inside `sh -c '…'` twice is how
        // a stray quote silently turns an install into a no-op.
        val launcherBody = buildString {
            appendLine("cd $ROOT")
            appendLine("PROOT_TMP_DIR=$ROOT")
            appendLine("PROOT_LOADER=$LOADER")
            appendLine("export PROOT_TMP_DIR PROOT_LOADER")
            appendLine(
                "./proot -r rootfs -0 -b /dev -b /proc -b /sys -w /root " +
                    "/usr/bin/env " +
                    "PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                    "HOME=/root TERM=xterm LANG=C.UTF-8 TMPDIR=/tmp " +
                    "/bin/sh $WORK_IN_ENV/$name.sh",
            )
            appendLine("echo \"$DONE\$?\"")
        }
        val started = LocalAdbShell.exec(
            listOf(
                "mkdir -p $WORK $ROOTFS$WORK_IN_ENV",
                stagedWrite(payload, body),
                stagedWrite(launcher, launcherBody),
                "rm -f $log",
                // nohup so the SIGHUP that follows this exec's own exit cannot
                // reach it; setsid where the phone has it, so it is not even in
                // our process group. Toybox has both, but only one is promised.
                "if command -v setsid >/dev/null 2>&1; then " +
                    "setsid sh $launcher > $log 2>&1 & " +
                    "else nohup sh $launcher > $log 2>&1 & fi",
                "echo LAUNCHED",
            ).joinToString(" ; "),
        ) ?: return false
        return started.stdout.contains("LAUNCHED")
    }

    /**
     * Watch a script someone else launched — the app that was killed mid-install
     * being the someone. Same loop, no launch: the log file IS the shared state,
     * which is what makes an install re-attachable at all.
     */
    suspend fun runScriptWatch(
        name: String,
        timeoutMs: Long = 30 * 60_000L,
        pollMs: Long = 1_200L,
        onLine: ((String) -> Unit)? = null,
    ): ScriptRun? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var seen = ""
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(pollMs)
            val text = scriptLog(name) ?: continue
            if (text != seen) {
                seen = text
                onLine?.let { emit ->
                    text.lineSequence()
                        .map { it.trim() }
                        // `CONCH_` is this layer's own protocol — the op marker
                        // and the sentinel. Neither is output, so neither may
                        // ever surface as the line a row shows.
                        .lastOrNull { it.isNotEmpty() && !it.startsWith("CONCH_") }
                        ?.let(emit)
                }
            }
            val done = text.lineSequence().firstOrNull { it.startsWith(DONE) }
            if (done != null) {
                return ScriptRun(
                    log = text.substringBefore(done),
                    exitCode = done.removePrefix(DONE).trim().toIntOrNull(),
                )
            }
        }
        return ScriptRun(log = seen, exitCode = null)
    }

    /** What the environment reports about itself, for a status line. */
    suspend fun describe(): String? {
        if (!isInstalled()) return null
        val r = run(". /etc/os-release 2>/dev/null; echo \"\$PRETTY_NAME\"; apk info 2>/dev/null | wc -l")
            ?: return null
        val lines = r.stdout.trim().lines()
        val name = lines.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "Linux"
        val pkgs = lines.getOrNull(1)?.trim()?.toIntOrNull()
        return if (pkgs != null) "$name · $pkgs packages" else name
    }

    /** Disk it occupies, human-readable ("49M"), or null if it is not there. */
    suspend fun diskUsage(): String? =
        LocalAdbShell.exec("du -sh $ROOT 2>/dev/null | cut -f1")?.stdout?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Unpack an environment from artifacts already staged on this phone.
     *
     * [prootPath] and [rootfsArchive] are paths the SHELL can read — the app's
     * own files directory is not one of them (SELinux keeps the shell out), so
     * callers stage through shared storage.
     *
     * ⛔ NOTHING HERE REACHES THE NETWORK. The runtime and the userland ship
     * inside the app, so installing opens no connection at all — which is what
     * lets this work for someone with no server and no computer, and keeps the
     * "contacts nothing but the servers you add" claim literally true.
     */
    suspend fun install(
        prootPath: String,
        loaderPath: String,
        rootfsArchive: String,
        onStep: (String) -> Unit,
    ): String? {
        onStep("preparing")
        LocalAdbShell.exec("rm -rf $ROOT && mkdir -p $ROOTFS ${LOADER.substringBeforeLast('/')}")
            ?: return "no shell access on this phone"

        onStep("installing the runtime")
        val moved = LocalAdbShell.exec(
            "cp '$prootPath' $ROOT/proot && cp '$loaderPath' $LOADER && " +
                "chmod 755 $ROOT/proot $LOADER && echo ok",
        )
        if (moved?.stdout?.trim() != "ok") return "could not place the runtime: ${moved?.stderr?.trim().orEmpty()}"

        onStep("unpacking the system")
        val untar = LocalAdbShell.exec("tar -xzf '$rootfsArchive' -C $ROOTFS 2>&1 | head -3; echo done")
        if (untar == null || !untar.stdout.contains("done")) return "could not unpack the system"

        // A resolver, so the package manager can reach its mirrors when the user
        // asks it to. Nothing in Conch contacts them; `apk` is the user's own
        // command, run inside their own environment.
        LocalAdbShell.exec("printf 'nameserver 1.1.1.1\\nnameserver 8.8.8.8\\n' > $ROOTFS/etc/resolv.conf")

        onStep("checking")
        val probe = run("echo READY") ?: return "the environment did not start"
        if (!probe.stdout.contains("READY")) {
            return "the environment did not start: ${probe.stderr.trim().take(200)}"
        }
        // Stamp WHICH runtime this environment got, so a later Conch can tell
        // whether the one on disk is still the right one (see
        // LinuxInstaller.ensureRuntimeCurrent).
        LocalAdbShell.exec("printf %s ${LinuxInstaller.RUNTIME_VERSION} > $ROOT/.runtime")
        onStep("ready")
        _snapshot.value = Snapshot(presence = Presence.INSTALLED)
        return null
    }

    /**
     * Remove it entirely.
     *
     * ⚠ WHAT RUNS IN IT IS STOPPED FIRST. Since the environment gained detached
     * work — an install, and an ssh endpoint that is meant to outlive the app —
     * deleting the directory out from under a live proot would leave the process
     * running against files nothing can see, holding their space, until the
     * phone reboots.
     */
    suspend fun remove(): Boolean {
        LocalAdbShell.exec("pkill -f $ROOT/proot")
        val gone = LocalAdbShell.exec("rm -rf $ROOT && echo gone")?.stdout?.contains("gone") == true
        if (gone) _snapshot.value = Snapshot(presence = Presence.ABSENT)
        return gone
    }
}
