package ai.eight24family.conch.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session

/**
 * Open a session channel whose receive-window AUTO-EXPANDS from sshj's single
 * reader thread — not only from the app's read()/drain path.
 *
 * WHY (sshj 0.39.0, ground-truthed 2026-06-28): every channel on one connection
 * is demuxed by ONE reader thread, and exec/session channels default to
 * `autoExpand = false`, so a `CHANNEL_WINDOW_ADJUST` is emitted ONLY when the app
 * calls read() and drains the buffer. A long-lived / continuously-read channel
 * (an agent turn stream) whose draining is momentarily DELAYED — e.g. while the
 * `conch-bridge` loopback churns extra channels on the SAME transport and
 * starves the single reader thread — has its 2 MiB local window consumed and
 * never replenished. The window hits 0, the server STOPS sending further
 * CHANNEL_DATA for that channel (including the final stream-json `result` line),
 * and the reader sits in read() forever: no EOF, no exception — a permanent
 * silent stall. This is the root of the "chat stuck on Synthesizing after a
 * conch-bridge turn" bug (sshj issue #576; backstopped by TURN-STUCK-RECONCILE-1).
 *
 * `autoExpand = true` makes the reader thread replenish the window in receive()
 * itself, so the window never starves regardless of drain timing — the server
 * keeps sending and `result` always arrives. Use for every channel we read
 * CONTINUOUSLY (agent turn streams). Safe for any channel we drain: buffered
 * data is bounded by maxCircularBufferSize (16 MiB).
 */
fun SSHClient.startStreamSession(): Session =
    startSession().apply { setAutoExpand(true) }
