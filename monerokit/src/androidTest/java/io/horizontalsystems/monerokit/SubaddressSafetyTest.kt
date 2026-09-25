package io.horizontalsystems.monerokit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.horizontalsystems.hdwalletkit.Mnemonic
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
import java.util.UUID
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
        assertTrue(WalletClosings.await(walletId, CLOSE_WAIT_MS))
        assertEquals(7, subaddressCountOnDisk(walletId))
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
    fun startCreatesTheRequiredSubaddresses() {
        val walletId = newWalletId()
        val kit = newKit(newSeed(), walletId)
        kit.requiredSubaddressCount = 5

        // Opens the wallet, creates indices 1 to 4, then fails at the node and closes the wallet.
        runBlocking { kit.start() }
        assertFalse(kit.isStarted)
        assertTrue(WalletClosings.await(walletId, CLOSE_WAIT_MS))
        assertEquals(5, subaddressCountOnDisk(walletId))

        // Enough already: a second start adds nothing.
        runBlocking { kit.stop() }
        kit.requiredSubaddressCount = 3
        runBlocking { kit.start() }
        assertTrue(WalletClosings.await(walletId, CLOSE_WAIT_MS))
        assertEquals(5, subaddressCountOnDisk(walletId))
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
