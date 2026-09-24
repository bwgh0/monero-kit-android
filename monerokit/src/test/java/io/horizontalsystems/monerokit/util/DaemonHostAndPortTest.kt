package io.horizontalsystems.monerokit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DaemonHostAndPortTest {

    @Test
    fun hostAndPort() {
        assertEquals("node.example" to 18089, daemonHostAndPort("node.example:18089"))
    }

    @Test
    fun schemePrefix() {
        assertEquals("node.example" to 443, daemonHostAndPort("https://node.example:443"))
        assertEquals("node.example" to 18081, daemonHostAndPort("http://node.example:18081/"))
    }

    @Test
    fun bracketedHost() {
        assertEquals("node.example" to 18081, daemonHostAndPort("[node.example]:18081"))
    }

    @Test
    fun loginIsIgnored() {
        assertEquals("node.example" to 18089, daemonHostAndPort("user:pass@node.example:18089"))
    }

    @Test
    fun rejectsAddressesWithoutAUsablePort() {
        assertNull(daemonHostAndPort("node.example"))
        assertNull(daemonHostAndPort("node.example:0"))
        assertNull(daemonHostAndPort("node.example:70000"))
        assertNull(daemonHostAndPort(":18081"))
        assertNull(daemonHostAndPort("[node.example"))
        assertNull(daemonHostAndPort(""))
    }
}
