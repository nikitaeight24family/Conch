package ai.eight24family.conch.linux

import ai.eight24family.conch.adb.AdbShellV2
import ai.eight24family.conch.adb.LocalAdbShell

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

    private const val ROOTFS = "$ROOT/rootfs"

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
     */
    private fun wrap(command: String): String {
        val q = command.replace("'", "'\\''")
        return "cd $ROOT && PROOT_TMP_DIR=$ROOT ./proot -r rootfs -0 " +
            "-b /dev -b /proc -b /sys -w /root " +
            "/usr/bin/env " +
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
            "HOME=/root TERM=xterm LANG=C.UTF-8 " +
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
    suspend fun isInstalled(): Boolean {
        val r = LocalAdbShell.exec("[ -x $ROOT/proot ] && [ -s $ROOTFS/etc/os-release ] && echo yes")
        return r?.stdout?.trim() == "yes"
    }

    /** Run one command inside the distribution. Null when the phone shell is not
     *  available at all — the caller has to say something different for that
     *  than for a command that ran and failed. */
    suspend fun run(command: String, limit: Int = 1 shl 20): AdbShellV2.Result? =
        LocalAdbShell.exec(wrap(command), limit)

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
    suspend fun install(prootPath: String, rootfsArchive: String, onStep: (String) -> Unit): String? {
        onStep("preparing")
        LocalAdbShell.exec("rm -rf $ROOT && mkdir -p $ROOTFS")
            ?: return "no shell access on this phone"

        onStep("installing the runtime")
        val moved = LocalAdbShell.exec("cp '$prootPath' $ROOT/proot && chmod 755 $ROOT/proot && echo ok")
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
        onStep("ready")
        return null
    }

    /** Remove it entirely. */
    suspend fun remove(): Boolean =
        LocalAdbShell.exec("rm -rf $ROOT && echo gone")?.stdout?.contains("gone") == true
}
