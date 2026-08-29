package ai.eight24family.conch

import ai.eight24family.conch.ssh.ServerDiagnostics
import ai.eight24family.conch.ssh.TcpProbe
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Reading a failed connection the way the deleted pre-flight probe used to read
 * its own socket.
 *
 * The probe existed so the app could say WHY a server was unreachable. It paid
 * for that with a connect-and-hang-up, which is a preauth disconnect in the
 * server's log — and three of those in ten minutes got the owner's phone banned
 * by his own fail2ban, with no failed login anywhere in the file. The same
 * evidence is in the exception the real connection throws, so that is where it
 * comes from now. These tests are what makes that swap safe to believe.
 */
class ConnectFailureKindTest {

    private fun kindOf(t: Throwable) = ServerDiagnostics.connectFailureKind(t)

    @Test
    fun `each socket failure is recognised`() {
        assertEquals(TcpProbe.Outcome.Failed.Kind.DnsFailed, kindOf(UnknownHostException("nope")))
        assertEquals(TcpProbe.Outcome.Failed.Kind.Timeout, kindOf(SocketTimeoutException("slow")))
        assertEquals(TcpProbe.Outcome.Failed.Kind.Refused, kindOf(ConnectException("refused")))
        assertEquals(TcpProbe.Outcome.Failed.Kind.NoRoute, kindOf(NoRouteToHostException("nope")))
    }

    @Test
    fun `no route and refused stay distinct`() {
        // They are siblings under SocketException, not parent and child, so
        // neither can shadow the other — but they mean very different things to
        // the user ("this network cannot reach that host" versus "the host said
        // no"), and a single misplaced branch would collapse them.
        val noRoute: java.net.SocketException = NoRouteToHostException("unreachable")
        val refused: java.net.SocketException = ConnectException("refused")
        assertEquals(TcpProbe.Outcome.Failed.Kind.NoRoute, kindOf(noRoute))
        assertEquals(TcpProbe.Outcome.Failed.Kind.Refused, kindOf(refused))
    }

    @Test
    fun `the cause chain is unwrapped — sshj hides the real failure underneath`() {
        // This is the shape that actually arrives: a transport exception with
        // the socket failure two levels down. Reading only the top gives Other,
        // and the user gets "something went wrong" instead of "connection
        // refused — the server may have banned this address".
        val wrapped = IOException(
            "Could not connect to the server",
            IllegalStateException("transport died", ConnectException("Connection refused")),
        )
        assertEquals(TcpProbe.Outcome.Failed.Kind.Refused, kindOf(wrapped))
    }

    @Test
    fun `an unrecognised failure is Other, not a guess`() {
        assertEquals(TcpProbe.Outcome.Failed.Kind.Other, kindOf(IllegalArgumentException("bad host key")))
    }

    @Test
    fun `a self-referencing cause chain terminates`() {
        // A throwable whose cause is itself would spin forever on a naive walk.
        val looping = object : IOException("loops") {
            override val cause: Throwable get() = this
        }
        assertEquals(TcpProbe.Outcome.Failed.Kind.Other, kindOf(looping))
    }

    @Test
    fun `a cycle deeper in the chain also terminates`() {
        val a = IOException("a")
        val b = IOException("b", a)
        val looping = object : IOException("c", b) {
            override val cause: Throwable get() = b
        }
        assertEquals(TcpProbe.Outcome.Failed.Kind.Other, kindOf(looping))
    }
}
