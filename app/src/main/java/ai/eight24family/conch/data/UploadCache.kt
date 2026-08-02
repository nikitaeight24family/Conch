package ai.eight24family.conch.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

/**
 * Tracks files we've already streamed up to a given server, keyed by
 * `serverId + sha256(bytes)` → absolute remote path.
 *
 * The cache is advisory only: callers MUST verify the remote file still
 * exists (e.g. via `[ -f $path ]`) before relying on a hit, because the
 * server's `/tmp/conch_uploads/` is wiped on reboot and a user may have
 * deleted the file manually.
 *
 * Stored in its own DataStore file. One entry per file — no bulk JSON re-
 * encode on every write. Many thousands of entries are fine.
 */
class UploadCache(private val context: Context) {

    private val Context.uploadDataStore by preferencesDataStore(name = "upload_cache")

    suspend fun lookup(serverId: String, sha256: String): String? {
        return context.uploadDataStore.data.first()[key(serverId, sha256)]
    }

    suspend fun record(serverId: String, sha256: String, remotePath: String) {
        context.uploadDataStore.edit { it[key(serverId, sha256)] = remotePath }
    }

    suspend fun forget(serverId: String, sha256: String) {
        context.uploadDataStore.edit { it.remove(key(serverId, sha256)) }
    }

    private fun key(serverId: String, sha256: String) =
        stringPreferencesKey("u/$serverId/$sha256")

    companion object {
        fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        /** Streaming SHA-256 — hashes [input] in 64 KiB chunks WITHOUT
         *  materialising the whole file in memory (large attachments). Same
         *  lowercase-hex format as [sha256Hex]. Caller owns/closes [input]. */
        fun sha256HexStream(input: java.io.InputStream): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
            return hex(md.digest())
        }

        private fun hex(digest: ByteArray): String = buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xff
                append(HEX[v ushr 4])
                append(HEX[v and 0x0f])
            }
        }

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
