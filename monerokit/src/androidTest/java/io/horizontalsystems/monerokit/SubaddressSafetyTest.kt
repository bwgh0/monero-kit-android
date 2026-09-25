package io.horizontalsystems.monerokit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.monerokit.data.TxData
import io.horizontalsystems.monerokit.model.PendingTransaction
import io.horizontalsystems.monerokit.model.Wallet
import io.horizontalsystems.monerokit.model.WalletManager
import io.horizontalsystems.monerokit.util.Helper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Subaddresses and the start/stop lifecycle against a real wallet2, with fresh random seeds. The node is a
 * local port nobody listens on, so a start opens the wallet and then fails at the node. The test that
 * needs a syncing wallet runs only when the moneroNode instrumentation argument names a node (host:port).
 */
@RunWith(AndroidJUnit4::class)
class SubaddressSafetyTest {

    companion object {
        private const val DEAD_NODE = "localhost:18081"
        private const val CLOSE_WAIT_MS = 30_000L
        private const val SCAN_BLOCKS = 5_000L

        @BeforeClass
        @JvmStatic
        fun plantLogs() {
            if (Timber.treeCount == 0) Timber.plant(Timber.DebugTree())
        }
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val restoreHeight = MoneroKit.restoreHeightForNewWallet()
    private val kits = mutableListOf<MoneroKit>()
    private val walletIds = mutableListOf<String>()

    @After
    fun tearDown() {
        runBlocking { kits.forEach { it.stop() } }
        walletIds.forEach { MoneroKit.deleteWallet(context, it) }
    }

    @Test
    fun subaddressesMatchWallet2AndNothingIsCreatedByAGetter() {
        val seed = newSeed()
        val walletId = newWalletId()
        createWalletFiles(seed, walletId)

        // Never opened: everything comes from the seed.
        val seedOnly = MoneroKit(context, seed, restoreHeight, walletId, WalletService(context), DEAD_NODE, false)
        assertFalse(seedOnly.isWalletOpen)
        assertNull(seedOnly.openWalletPrimaryAddress())
        val beforeOpen = seedOnly.getSubaddresses()
        assertEquals(listOf(0), beforeOpen.map { it.addressIndex })
        assertEquals(seedOnly.seedPrimaryAddress(), beforeOpen[0].address)
        assertEquals(seedOnly.seedPrimaryAddress(), seedOnly.receiveAddress)

        val service = WalletService(context)
        assertNotNull(service.openWallet(walletId, ""))
        val kit = MoneroKit(context, seed, restoreHeight, walletId, service, DEAD_NODE, false)
        assertTrue(kit.isWalletOpen)
        assertEquals(kit.seedPrimaryAddress(), kit.openWalletPrimaryAddress())

        // A fresh wallet has index 0 only, and the receive address is the primary one.
        assertEquals(listOf(0), kit.getSubaddresses().map { it.addressIndex })
        assertEquals(kit.seedPrimaryAddress(), kit.receiveAddress)
        assertEquals(1, kit.getSubaddresses().size)

        assertEquals(4, service.ensureSubaddresses(0, 5) { false })
        assertEquals(0, service.ensureSubaddresses(0, 5) { false })
        val list = kit.getSubaddresses()
        assertEquals(listOf(0, 1, 2, 3, 4), list.map { it.addressIndex })
        list.forEach { assertEquals(seedOnly.getSubaddress(0, it.addressIndex)?.address, it.address) }
        list.forEach { assertEquals(it.address, kit.getSubaddress(0, it.addressIndex)?.address) }

        // The newest unused subaddress, read again and again: nothing gets created.
        repeat(3) { assertEquals(list[4].address, kit.receiveAddress) }
        assertEquals(5, kit.getSubaddresses().size)

        val added = kit.addSubaddress()
        assertNotNull(added)
        assertEquals(5, added!!.addressIndex)
        assertEquals(6, kit.getSubaddresses().size)
        assertEquals(added.address, kit.getSubaddresses()[5].address)
        val created = kit.createSubaddress()
        assertEquals(kit.getSubaddresses()[6].address, created)
        assertEquals(7, kit.getSubaddresses().size)

        service.stop()
        assertFalse(kit.isWalletOpen)
        assertNull(kit.openWalletPrimaryAddress())
        assertNull(kit.addSubaddress())
        assertEquals(listOf(0), kit.getSubaddresses().map { it.addressIndex })

        // Nothing was scanned, so nothing was stored: the adds and the close left the keys file only. A
        // cache at height 1 would open as a new wallet and scan from the chain tip.
        assertTrue(WalletClosings.await(walletId, CLOSE_WAIT_MS))
        assertFalse(cacheFile(walletId).exists())
        assertTrue(keysFile(walletId).exists())
        assertEquals(1, subaddressCountOnDisk(walletId))
    }

    @Test
    fun watchOnlySeedHasItsPrimaryAddressOnly() {
        val seed = newSeed()
        val keys = MoneroKit.getKeys(seed)
        val address = MoneroKit.getAddress(seed, 0, 0)
        val kit = MoneroKit(
            context, Seed.WatchOnly(address, keys.privateViewKey), restoreHeight, newWalletId(),
            WalletService(context), DEAD_NODE, false
        )
        assertEquals(address, kit.seedPrimaryAddress())
        assertEquals(address, kit.receiveAddress)
        assertEquals(listOf(address), kit.getSubaddresses().map { it.address })
        assertEquals(address, kit.getSubaddress(0, 0)?.address)
        assertNull(kit.getSubaddress(0, 1))
    }

    @Test
    fun startCreatesTheRequiredSubaddressesBeforeItContactsTheNode() {
        SilentNode().use { node ->
            val walletId = newWalletId()
            val kit = newKit(newSeed(), walletId, node.address)
            kit.requiredSubaddressCount = 5
            val starter = thread(name = "starter") { runBlocking { kit.start() } }

            // The start opens the wallet, creates indices 1 to 4, then waits on the node's connection check.
            val deadline = System.currentTimeMillis() + 20_000
            while (!(kit.isWalletOpen && kit.getSubaddresses().size == 5) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals((0..4).toList(), kit.getSubaddresses().map { it.addressIndex })
            assertTrue("never reached the node", node.awaitConnection(10_000))

            // Abandoned mid-start: the connection is cut, and the start gives up well before the 15 s check.
            val abandonedAt = System.currentTimeMillis()
            kit.abandonStart()
            starter.join(10_000)
            assertFalse("start still running", starter.isAlive)
            assertTrue(System.currentTimeMillis() - abandonedAt < 5_000)
            assertFalse(kit.isStarted)
            assertTrue(kit.syncStateFlow.value is SyncState.Connecting)

            // Never scanned: the close stored no cache, so the next start creates them again.
            runBlocking { kit.stop() }
            assertTrue(WalletClosings.await(walletId, CLOSE_WAIT_MS))
            assertFalse(cacheFile(walletId).exists())
            assertTrue(keysFile(walletId).exists())
        }
    }

    @Test
    fun feeEstimateBeforeInitThrowsInsteadOfAborting() {
        val seed = newSeed()
        val walletId = newWalletId()
        createWalletFiles(seed, walletId)
        val service = WalletService(context)
        val wallet = service.openWallet(walletId, "")
        assertNotNull(wallet)
        val kit = MoneroKit(context, seed, restoreHeight, walletId, service, DEAD_NODE, false)
        val address = kit.seedPrimaryAddress()

        // The kit refuses before start() has initialised the wallet for a node.
        val refused = runCatching { kit.estimateFee(1_000_000L, address, null) }.exceptionOrNull()
        assertTrue("got $refused", refused is IllegalStateException)
        assertEquals("Wallet is not connected", refused?.message)

        // Called directly, wallet2 throws for its fork rules: JNI hands that over as a Java exception.
        val txData = TxData().apply {
            setDestination(address)
            setAmount(1_000_000L)
            mixin = 0
            priority = PendingTransaction.Priority.Priority_Medium
        }
        val notInitialised = runCatching { wallet!!.estimateTransactionFee(txData) }.exceptionOrNull()
        assertTrue("got $notInitialised", notInitialised is IllegalStateException)
        wallet!!.setOffline(true)
        val offline = runCatching { wallet.estimateTransactionFee(txData) }.exceptionOrNull()
        assertTrue("got $offline", offline is IllegalStateException)
        wallet.setOffline(false)

        // wallet2's connection check throws for a wallet never initialised: JNI reports it disconnected.
        assertEquals(Wallet.ConnectionStatus.ConnectionStatus_Disconnected, wallet.connectionStatus)

        // A send is refused the same way, before wallet2 would start refresh for it.
        val send = runCatching { kit.send(1_000_000L, address, null) }.exceptionOrNull()
        assertEquals("Wallet is not connected", send?.message)

        service.stop()
        assertTrue(WalletClosings.await(walletId, CLOSE_WAIT_MS))
    }

    @Test
    fun abandonBeforeStartOpensNothing() {
        val walletId = newWalletId()
        val kit = newKit(newSeed(), walletId)
        val keysFile = File(Helper.getWalletRoot(context), "$walletId.keys")

        kit.abandonStart()
        runBlocking { kit.start() }
        assertFalse(kit.isStarted)
        assertTrue(kit.syncStateFlow.value is SyncState.Connecting)
        assertFalse(kit.isWalletOpen)
        assertFalse(keysFile.exists())

        // The abandon is used up: the next start runs, and fails at the node.
        runBlocking { kit.start() }
        assertTrue(keysFile.exists())
        assertTrue(kit.syncStateFlow.value is SyncState.NotSynced)
    }

    @Test
    fun stopClearsAnAbandon() {
        val walletId = newWalletId()
        val kit = newKit(newSeed(), walletId)

        kit.abandonStart()
        runBlocking { kit.stop() }
        runBlocking { kit.start() }
        assertTrue(File(Helper.getWalletRoot(context), "$walletId.keys").exists())
    }

    @Test
    fun gettersAndAddsRaceTheClose() {
        val seed = newSeed()
        val walletId = newWalletId()
        createWalletFiles(seed, walletId)

        val slowestReadMs = AtomicLong()
        repeat(8) {
            assertTrue(WalletClosings.await(walletId, CLOSE_WAIT_MS))
            val service = WalletService(context)
            assertNotNull(service.openWallet(walletId, ""))
            val kit = MoneroKit(context, seed, restoreHeight, walletId, service, DEAD_NODE, false)

            val running = AtomicBoolean(true)
            val failure = AtomicReference<Throwable>()
            val readers = List(4) { n ->
                thread(name = "reader-$n") {
                    try {
                        while (running.get()) {
                            val startedAt = System.nanoTime()
                            kit.getSubaddresses()
                            slowestReadMs.accumulateAndGet((System.nanoTime() - startedAt) / 1_000_000, ::maxOf)
                            kit.receiveAddress
                            kit.getKeys()
                            kit.statusInfo()
                            kit.openWalletPrimaryAddress()
                            kit.getSubaddress(0, 2)
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
            }
            val adder = thread(name = "adder") {
                try {
                    while (running.get()) kit.addSubaddress()
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }

            Thread.sleep(300)
            service.stop()
            Thread.sleep(200)
            running.set(false)
            (readers + adder).forEach { it.join() }
            failure.get()?.let { throw it }
            assertFalse(kit.isWalletOpen)
        }
        Timber.i("gettersAndAddsRaceTheClose: slowest getSubaddresses() %d ms", slowestReadMs.get())
    }

    @Test
    fun addsWhileRefreshScans() {
        val node = InstrumentationRegistry.getArguments().getString("moneroNode")
        assumeTrue("needs -e moneroNode host:port", !node.isNullOrEmpty())

        val walletId = newWalletId()
        val kit = newKit(newSeed(), walletId, node!!, restoreHeight - SCAN_BLOCKS)
        runBlocking { kit.start() }
        assertTrue("start failed: ${kit.syncStateFlow.value.description}", kit.isStarted)
        // Add while wallet2 scans full blocks, which reads the subaddress map. It first pulls block hashes
        // only, from its last built-in checkpoint up to the restore height.
        val scanning = runBlocking {
            withTimeoutOrNull(300_000) {
                kit.syncStateFlow.first { it is SyncState.Syncing && (it.remainingBlocks ?: 0) in 500..SCAN_BLOCKS + 2_000 }
            }
        }
        assertNotNull("never reached the block scan: ${kit.syncStateFlow.value.description}", scanning)

        val running = AtomicBoolean(true)
        val failure = AtomicReference<Throwable>()
        val slowestReadMs = AtomicLong()
        val readers = List(3) { n ->
            thread(name = "reader-$n") {
                try {
                    while (running.get()) {
                        val startedAt = System.nanoTime()
                        kit.getSubaddresses()
                        kit.receiveAddress
                        slowestReadMs.accumulateAndGet((System.nanoTime() - startedAt) / 1_000_000, ::maxOf)
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        val adders = List(3) { n ->
            thread(name = "adder-$n") {
                try {
                    repeat(4) { assertNotNull(kit.addSubaddress()) }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        adders.forEach { it.join() }
        running.set(false)
        readers.forEach { it.join() }
        failure.get()?.let { throw it }

        val list = kit.getSubaddresses()
        assertEquals((0..12).toList(), list.map { it.addressIndex })
        Timber.i(
            "addsWhileRefreshScans: slowest read %d ms, sync state %s",
            slowestReadMs.get(), kit.syncStateFlow.value.description
        )
    }

    @Test
    fun heightOneCacheScansFromTheRestoreHeight() {
        val node = InstrumentationRegistry.getArguments().getString("moneroNode")
        assumeTrue("needs -e moneroNode host:port", !node.isNullOrEmpty())

        // What older builds could leave behind: a cache stored before the first scan.
        val seed = newSeed()
        val walletId = newWalletId()
        createWalletFiles(seed, walletId)
        val raw = WalletManager.getInstance().openWallet(Helper.getWalletFile(context, walletId).absolutePath, "")
        assertTrue(raw.status.errorString, raw.status.isOk)
        assertEquals(1L, raw.blockChainHeight)
        assertTrue(raw.store())
        raw.close()
        assertTrue(cacheFile(walletId).exists())

        // wallet2 opens it as a new wallet, and its init() moves the scan start to the chain tip.
        val scanFrom = restoreHeight - SCAN_BLOCKS
        val service = WalletService(context)
        val kit = MoneroKit(context, seed, scanFrom, walletId, service, node!!, false).also { kits += it }
        runBlocking { kit.start() }
        assertTrue("start failed: ${kit.syncStateFlow.value.description}", kit.isStarted)
        assertEquals(scanFrom, service.wallet?.restoreHeight)
    }

    private fun newSeed(): Seed.Electrum =
        Seed.Bip39(Mnemonic().generate(Mnemonic.EntropyStrength.VeryHigh), "").toElectrum()

    private fun newWalletId(): String = "test-${UUID.randomUUID()}".also { walletIds += it }

    private fun newKit(seed: Seed, walletId: String, node: String = DEAD_NODE, height: Long = restoreHeight): MoneroKit =
        MoneroKit.getInstance(context, seed, height.toString(), walletId, node, false).also { kits += it }

    // As MoneroKit.createWalletIfNotExists does: keys file only, the first open writes a fresh cache.
    private fun createWalletFiles(seed: Seed.Electrum, walletId: String) {
        val file = Helper.getWalletFile(context, walletId)
        val wallet = WalletManager.getInstance()
            .recoveryWallet(file, "", seed.mnemonic.joinToString(" "), seed.passphrase, restoreHeight)
        assertTrue(wallet.status.errorString, wallet.status.isOk)
        wallet.close()
        file.delete()
    }

    private fun cacheFile(walletId: String) = File(Helper.getWalletRoot(context), walletId)

    private fun keysFile(walletId: String) = File(Helper.getWalletRoot(context), "$walletId.keys")

    private fun subaddressCountOnDisk(walletId: String): Int {
        val wallet = WalletManager.getInstance().openWallet(Helper.getWalletFile(context, walletId).absolutePath, "")
        try {
            assertTrue(wallet.status.errorString, wallet.status.isOk)
            return wallet.getNumSubaddresses(0)
        } finally {
            wallet.close()
        }
    }
}

/**
 * A node that accepts connections and never answers, so a start waits in wallet2's connection check. Binds
 * every local address: "localhost" may resolve to either loopback family.
 */
private class SilentNode : AutoCloseable {
    private val server = ServerSocket(0)
    private val accepted = CopyOnWriteArrayList<Socket>()
    private val connected = CountDownLatch(1)
    private val acceptor = thread(name = "silent-node") {
        try {
            while (true) {
                accepted += server.accept()
                connected.countDown()
            }
        } catch (_: Exception) {
            // closed
        }
    }

    val address = "localhost:${server.localPort}"

    fun awaitConnection(timeoutMs: Long) = connected.await(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        server.close()
        accepted.forEach { runCatching { it.close() } }
        acceptor.join(1_000)
    }
}
