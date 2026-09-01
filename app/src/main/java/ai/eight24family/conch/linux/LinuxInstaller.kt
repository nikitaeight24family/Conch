package ai.eight24family.conch.linux

import ai.eight24family.conch.adb.LocalAdbShell
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Puts a Linux environment on this phone — with no server, no computer, and no
 * network of any kind.
 *
 * ⛔ THE PIECES SHIP INSIDE THE APP, AND THAT IS THE WHOLE POINT. A first
 * version fetched them through the owner's own server, to protect the promise
 * that Conch contacts nothing but the servers you add. It preserved the promise
 * and destroyed the feature: this exists for people who have NEITHER a server
 * NOR a PC (owner, 2026-08-30), and a phone that needs a server to become
 * self-sufficient is not self-sufficient.
 *
 * Bundling is better on every axis at once, which is how you know it is the
 * right answer:
 *
 *  • Works with the phone in flight mode, on first launch, with nothing set up.
 *  • The promise stays literally true — no connection is opened at all.
 *  • It is what Google's policy asks for: an app may not fetch executable code
 *    from anywhere but the store. Downloading a runtime at install time is the
 *    risky shape; carrying it in the package is the compliant one.
 *  • ~4.7 MB on an 8.9 MB app, and every byte of it is inspectable in the APK.
 *
 * What still uses the network is `apk add`, and that is the OWNER's command
 * typed inside their own Linux — the same way a browser is not "the app
 * phoning home".
 *
 * The hand-off runs through the app's external files directory, because the app
 * can write there and the shell uid can read there — measured, since SELinux
 * keeps the shell out of app-private storage, and only the shell's own
 * directory is allowed to hold something executable.
 */
object LinuxInstaller {

    /**
     * What the bundled runtime IS, so an environment installed by an older
     * Conch can be brought up to it.
     *
     * ⛔ AN ALREADY-INSTALLED ENVIRONMENT KEEPS ITS OLD `proot` FOREVER
     * OTHERWISE. [LinuxEnv.install] only ever runs on a fresh install, so the
     * runtime that shipped with the version that first set the environment up
     * is the one it keeps — and the runtime is exactly the piece that decides
     * whether node, npm and every agent CLI work at all (proot 5.1.0 does not
     * translate `statx`, so node's every path lookup failed against Android's
     * real root: "ENOENT: lstat '/usr'", measured on the owner's phone
     * 2026-08-31). Bump this string whenever the asset changes.
     */
    const val RUNTIME_VERSION = "proot-84a5bdf8-statx"

    private const val ASSET_PROOT = "linux/proot"
    /** PRoot's injected bootstrap ELF — see [LinuxEnv.LOADER] for why it is a
     *  separate file rather than baked into the binary. */
    private const val ASSET_LOADER = "linux/loader"
    /**
     * ⛔ THE EXTENSION IS DELIBERATELY NOT .tar.gz, AND MUST NOT BECOME ONE.
     * The build system unpacks a `.gz` asset and drops the extension — the
     * archive shipped as `assets/linux/rootfs.tar`, 8.5 MB instead of 3.8, and
     * the app then asked for a name that was not in its own package: "Could not
     * unpack the bundled Linux" with nothing in the log to say why (2026-08-30).
     * An extension it has no rule for keeps the bytes exactly as written.
     */
    private const val ASSET_ROOTFS = "linux/rootfs.bin"

    /**
     * @return null when the environment is ready, otherwise a sentence saying
     *   what stopped it — always something the owner can act on.
     */
    suspend fun install(onStep: (String) -> Unit): String? = withContext(Dispatchers.IO) {
        val arch = LocalAdbShell.exec("uname -m")?.stdout?.trim()
            ?: return@withContext "No shell access on this phone yet — set it up in Settings › Phone bridge."
        if (arch != "aarch64") {
            return@withContext "This phone is $arch; the bundled Linux is built for aarch64 only so far."
        }

        onStep("unpacking from the app")
        val handoff = File(ServiceLocator.appContext.getExternalFilesDir(null), "linux").apply { mkdirs() }
        val prootFile = File(handoff, "proot")
        val loaderFile = File(handoff, "loader")
        val rootfsFile = File(handoff, "rootfs.tar.gz")
        val copied = SilentlyTry.logged("Conch-Linux", "copy bundled pieces out") {
            copyAsset(ASSET_PROOT, prootFile)
            copyAsset(ASSET_LOADER, loaderFile)
            copyAsset(ASSET_ROOTFS, rootfsFile)
            true
        }
        if (copied != true) return@withContext "Could not unpack the bundled Linux from the app."

        val err = LinuxEnv.install(
            prootPath = prootFile.absolutePath,
            loaderPath = loaderFile.absolutePath,
            rootfsArchive = rootfsFile.absolutePath,
            onStep = onStep,
        )
        // 4.7 MB of duplicate once the shell has its own copies.
        SilentlyTry.fired("Conch-Linux", "clear hand-off copies") {
            prootFile.delete(); loaderFile.delete(); rootfsFile.delete()
        }
        err
    }

    /**
     * Put the CURRENT runtime in place, on an environment that already exists.
     *
     * Cheap and idempotent: one marker file read per call, and it does nothing
     * at all once the versions agree. Returns true when it actually replaced
     * the binary — the caller may want to say so, since anything running under
     * the old one has just been stopped.
     */
    suspend fun ensureRuntimeCurrent(): Boolean = withContext(Dispatchers.IO) {
        if (LinuxEnv.presence() != LinuxEnv.Presence.INSTALLED) return@withContext false
        val current = LocalAdbShell.exec("cat ${LinuxEnv.ROOT}/.runtime 2>/dev/null")
            ?.stdout?.trim()
            ?: return@withContext false
        if (current == RUNTIME_VERSION) return@withContext false
        android.util.Log.i("Conch-Linux", "runtime is '$current', replacing with $RUNTIME_VERSION")
        val handoff = File(ServiceLocator.appContext.getExternalFilesDir(null), "linux").apply { mkdirs() }
        val prootFile = File(handoff, "proot")
        val loaderFile = File(handoff, "loader")
        val copied = SilentlyTry.logged("Conch-Linux", "copy bundled runtime out") {
            copyAsset(ASSET_PROOT, prootFile)
            copyAsset(ASSET_LOADER, loaderFile)
            true
        }
        if (copied != true) return@withContext false
        // Whatever is running was started by the OLD binary; it has to go before
        // the file under it changes.
        LocalAdbShell.exec("pkill -f ${LinuxEnv.ROOT}/proot")
        val ok = LocalAdbShell.exec(
            "mkdir -p ${LinuxEnv.LOADER.substringBeforeLast('/')} && " +
                "cp '${prootFile.absolutePath}' ${LinuxEnv.ROOT}/proot && " +
                "cp '${loaderFile.absolutePath}' ${LinuxEnv.LOADER} && " +
                "chmod 755 ${LinuxEnv.ROOT}/proot ${LinuxEnv.LOADER} && " +
                "printf %s $RUNTIME_VERSION > ${LinuxEnv.ROOT}/.runtime && echo ok",
        )?.stdout?.contains("ok") == true
        SilentlyTry.fired("Conch-Linux", "clear hand-off copies") {
            prootFile.delete(); loaderFile.delete()
        }
        ok
    }

    private fun copyAsset(name: String, dest: File) {
        ServiceLocator.appContext.assets.open(name).use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        }
    }
}
