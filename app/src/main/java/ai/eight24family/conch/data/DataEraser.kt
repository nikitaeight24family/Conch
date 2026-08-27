package ai.eight24family.conch.data

import android.app.ActivityManager
import android.content.Context
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * GDPR Art. 17 "Right to erasure" implementation.
 *
 * `eraseAll()` walks every place Conch writes data on the device and
 * removes it. The list is intentionally explicit (not a single
 * `clearApplicationUserData`) so review is straightforward — every
 * additional persistence layer we add must be added here too, or the
 * privacy policy lies.
 *
 * **NOT erased by this method (out of scope):**
 *  - Files we wrote to YOUR servers (`/tmp/conch_uploads/...`,
 *    memory/subagent files in `~/.claude/...`). Those are on hosts
 *    we don't control after we wrote them.
 *
 * After erasure, the process is killed via [ActivityManager.clearApplicationUserData].
 * The OS restarts the app on next launch with empty data — nothing to
 * read since we just deleted it.
 */
object DataEraser {

    suspend fun eraseAll(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext

        // 1) Close all live AgentSession SSH connections so Room/file
        //    handles aren't held open. Skip if ServiceLocator was never
        //    initialised (defensive — shouldn't happen here).
        SilentlyTry.fired("SshAi-Eraser", "close all agent sessions") { ServiceLocator.agentSessions.closeAll() }

        // 2) Wipe the Room DB file. Closing first is necessary or the
        //    file delete on Windows-style file systems silently fails.
        SilentlyTry.fired("SshAi-Eraser", "close room db") {
            ServiceLocator.appContext.let { ctx ->
                ai.eight24family.conch.data.db.AppDatabase.get(ctx).close()
            }
        }
        SilentlyTry.fired("SshAi-Eraser", "delete room db files") {
            val dbFile = app.getDatabasePath("sshai.db")
            dbFile.delete()
            File("${dbFile.path}-shm").delete()
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-journal").delete()
        }

        // 3) DataStore (preferences) — files live under
        //    `<filesDir>/datastore/`. Wipe the directory.
        SilentlyTry.fired("SshAi-Eraser", "wipe datastore dir") {
            File(app.filesDir, "datastore").deleteRecursively()
        }

        // 4) SharedPreferences — every file in the directory.
        SilentlyTry.fired("SshAi-Eraser", "wipe shared_prefs dir") {
            File(app.dataDir, "shared_prefs").listFiles()?.forEach { it.delete() }
        }

        // 5) File caches: HistoryCache (JSONL bodies),
        //    AgentStatusCache + SessionsCache live in DataStore (already
        //    wiped above). Plus everything else we may have left in
        //    cacheDir / filesDir.
        SilentlyTry.fired("SshAi-Eraser", "delete session history cache") { File(app.cacheDir, "session_history").deleteRecursively() }
        SilentlyTry.fired("SshAi-Eraser", "delete server activity logs") { File(app.filesDir, "activity-log").deleteRecursively() }
        SilentlyTry.fired("SshAi-Eraser", "delete usage cache") { File(app.filesDir, "usage-cache.json").delete() }
        SilentlyTry.fired("SshAi-Eraser", "delete external files dir") {
            // External-files dump dir (debug feature only — production
            // builds shouldn't have anything here, but be thorough).
            app.getExternalFilesDir(null)?.deleteRecursively()
        }

        // 6) `EncryptedSharedPreferences` master key in Android Keystore.
        //    The encrypted blobs we wiped at step 4 are useless without
        //    it, but for completeness we drop it too.
        SilentlyTry.fired("SshAi-Eraser", "wipe androidx security keystore aliases") {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.aliases().toList()
                .filter { it.startsWith("_androidx_security_") }
                .forEach { ks.deleteEntry(it) }
        }

        // 7) Final hammer: ask the OS to clear all app user data and
        //    restart the process. This duplicates some of what we did
        //    above but covers anything we missed (WebView caches,
        //    glide caches, etc.) and resets the process state.
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.clearApplicationUserData()
    }
}
