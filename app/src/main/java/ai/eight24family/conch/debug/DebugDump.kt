package ai.eight24family.conch.debug

import android.util.Log
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.di.ServiceLocator
import java.io.File

/**
 * Helper for dumping raw session JSONL contents on demand. Files land in
 * `getExternalFilesDir(null)/dumps/` so they can be adb-pulled without root:
 *
 *   adb pull /storage/emulated/0/Android/data/ai.eight24family.conch/files/dumps/
 */
object DebugDump {

    fun write(agent: Agent, serverId: String, sourcePath: String?, content: String) {
        runCatching {
            val app = ServiceLocator.appContext
            val dir = File(app.getExternalFilesDir(null), "dumps").apply { mkdirs() }
            val safeAgent = agent.name.lowercase()
            val ts = System.currentTimeMillis()
            val short = serverId.replace("-", "").take(8)
            val file = File(dir, "${safeAgent}_${short}_$ts.jsonl")
            file.writeText(content)
            Log.i(TAG, "dump → ${file.absolutePath} (${content.length} bytes; from $sourcePath)")
            // Also chunk to logcat so it's visible without pulling files.
            val tag = "${TAG}_${safeAgent.uppercase()}"
            content.lineSequence().forEachIndexed { idx, line ->
                if (line.isEmpty()) return@forEachIndexed
                line.chunked(3500).forEachIndexed { c, chunk ->
                    Log.i(tag, "L$idx${if (c > 0) ".$c" else ""}: $chunk")
                }
            }
        }.onFailure { Log.w(TAG, "dump failed: ${it.message}") }
    }

    private const val TAG = "SSHAI_DUMP"
}
