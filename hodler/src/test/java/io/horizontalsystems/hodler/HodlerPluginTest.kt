package io.horizontalsystems.hodler

import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import io.horizontalsystems.bitcoincore.blocks.BlockMedianTimeHelper
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.models.Address
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.UnspentOutput
import io.horizontalsystems.bitcoincore.transactions.builder.MutableTransaction
import io.horizontalsystems.bitcoincore.transactions.scripts.Script
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.bitcoincore.utils.IAddressConverter
import org.junit.Assert
import org.junit.Test
import org.junit.jupiter.api.assertThrows

class HodlerPluginTest {

    val hodlerPlugin = HodlerPlugin()
    val mutableTransaction = mock<MutableTransaction>()
    val addressConverter = mock<IAddressConverter>()
    val recipientAddress = mock<Address>()

    @Test
    fun processOutputs_requiredDataNotSet() {
        val pluginData = mapOf<String, Any>()

        assertThrows<IllegalStateException> {
            hodlerPlugin.processOutputs(mutableTransaction, pluginData, addressConverter)
        }
    }

    @Test
    fun processOutputs_scriptTypeNotP2PKH() {
        val pluginData = mapOf<String, Any>("lockTimeInterval" to LockTimeInterval.hour)

        whenever(mutableTransaction.recipientAddress).thenReturn(recipientAddress)
        whenever(recipientAddress.scriptType).thenReturn(ScriptType.P2SH)

        assertThrows<IllegalStateException> {
            hodlerPlugin.processOutputs(mutableTransaction, pluginData, addressConverter)
        }
    }

    val pubkeyHash = "8c005bb22d520f6a108b108242efcbe5c19315f5".hexToByteArray()
    val redeemScriptHash = "281df2e2e47141575bd98686a4f0452bcc4c7147".hexToByteArray()

    @Test
    fun processOutputs_success() {
        val pluginData = mapOf<String, Any>("lockTimeInterval" to LockTimeInterval.hour)

        val shAddress = mock<Address>()

        whenever(mutableTransaction.recipientAddress).thenReturn(recipientAddress)
        whenever(recipientAddress.scriptType).thenReturn(ScriptType.P2PKH)
        whenever(recipientAddress.hash).thenReturn(pubkeyHash)
        whenever(addressConverter.convert(redeemScriptHash, ScriptType.P2SH)).thenReturn(shAddress)

        hodlerPlugin.processOutputs(mutableTransaction, pluginData, addressConverter)

        verify(addressConverter).convert(redeemScriptHash, ScriptType.P2SH)
        verify(mutableTransaction).recipientAddress = shAddress
        verify(mutableTransaction).addPluginData(HodlerPlugin.id, ("02" + "0700" + "14" + "8c005bb22d520f6a108b108242efcbe5c19315f5").hexToByteArray())
    }

    val fullTransaction = mock<FullTransaction>()
    val chunkLockTimeInterval = mock<Script.Chunk>()
    val chunkPubkeyHash = mock<Script.Chunk>()
    val nullDataChunks = listOf(chunkLockTimeInterval, chunkPubkeyHash).iterator()
    val storage = mock<IStorage>()

    @Test
    fun processTransactionWithNullData_noRequiredData() {
        assertThrows<IllegalStateException> {
            hodlerPlugin.processTransactionWithNullData(fullTransaction, nullDataChunks, storage, addressConverter)
        }
    }

    @Test
    fun processTransactionWithNullData_success() {
        val recipientOutput = mock<TransactionOutput>()
        val originalAddress = mock<Address>()
        val originalAddressString = "originalAddress"
        val publicKey = mock<PublicKey>()
        val redeemScript = "03070040b27576a9148c005bb22d520f6a108b108242efcbe5c19315f588ac".hexToByteArray()
        val transaction = mock<Transaction>()

        whenever(chunkLockTimeInterval.data).thenReturn("0700".hexToByteArray())
        whenever(chunkPubkeyHash.data).thenReturn(pubkeyHash)
        whenever(fullTransaction.outputs).thenReturn(listOf(recipientOutput))
        whenever(recipientOutput.keyHash).thenReturn(redeemScriptHash)
        whenever(addressConverter.convert(pubkeyHash, ScriptType.P2PKH)).thenReturn(originalAddress)
        whenever(originalAddress.string).thenReturn(originalAddressString)
        whenever(storage.getPublicKeyByKeyOrKeyHash(pubkeyHash)).thenReturn(publicKey)
        whenever(publicKey.path).thenReturn("publicKey.path")
        whenever(fullTransaction.header).thenReturn(transaction)

        hodlerPlugin.processTransactionWithNullData(fullTransaction, nullDataChunks, storage, addressConverter)

        verify(addressConverter).convert(pubkeyHash, ScriptType.P2PKH)
        verify(recipientOutput).pluginId = HodlerPlugin.id
        verify(recipientOutput).pluginData = "7|originalAddress"
        verify(storage).getPublicKeyByKeyOrKeyHash(pubkeyHash)
        verify(recipientOutput).redeemScript = redeemScript
        verify(recipientOutput).publicKeyPath = "publicKey.path"
        verify(transaction).isMine = true
    }

    @Test
    fun isSpendable_nullLastBlockMedianTimePast() {
        val blockMedianTimeHelper = mock<BlockMedianTimeHelper>()

        whenever(blockMedianTimeHelper.medianTimePast).thenReturn(null)

        Assert.assertFalse(hodlerPlugin.isSpendable(mock(), blockMedianTimeHelper))
    }

    @Test
    fun isSpendable() {
        assertIsSpendable(100, 3700, true)
        assertIsSpendable(200, 3100, false)
    }

    private fun assertIsSpendable(lockedAtTimestamp: Long, lastBlockMedianTimestamp: Long, isSpendable: Boolean) {
        val unspentOutput = mock<UnspentOutput>()
        val transactionOutput = mock<TransactionOutput>()
        val blockMedianTimeHelper = mock<BlockMedianTimeHelper>()
        val transaction = mock<Transaction>()

        whenever(blockMedianTimeHelper.medianTimePast).thenReturn(lastBlockMedianTimestamp)
        whenever(unspentOutput.transaction).thenReturn(transaction)
        whenever(transaction.timestamp).thenReturn(lockedAtTimestamp)
        whenever(unspentOutput.output).thenReturn(transactionOutput)
        whenever(transactionOutput.pluginData).thenReturn("7|originalAddress")

        Assert.assertEquals(isSpendable, hodlerPlugin.isSpendable(unspentOutput, blockMedianTimeHelper))
    }

    @Test
    fun getInputSequence() {
        val pluginData = "7|originalAddress"
        val expectedSequence = 0x400007L

        val output = mock<TransactionOutput>()
        whenever(output.pluginData).thenReturn(pluginData)

        val inputSequence = hodlerPlugin.getInputSequence(output)
        Assert.assertEquals(expectedSequence, inputSequence)
    }

    @Test
    fun parsePluginData() {
        val pluginData = "7|originalAddress"

        val output = mock<TransactionOutput>()
        whenever(output.pluginData).thenReturn(pluginData)

        val parsePluginData = hodlerPlugin.parsePluginData(output)

        Assert.assertEquals(LockTimeInterval.hour, parsePluginData["lockTimeInterval"])
        Assert.assertEquals("originalAddress", parsePluginData["address"])
    }

    @Test
    fun keysForApiRestore() {
        val publicKey = mock<PublicKey>()

        val addresses = List(4) { index ->
            mock<Address> {
                on { string } doReturn "address${index}"
            }
        }

        whenever(publicKey.publicKeyHash).thenReturn(pubkeyHash)
        whenever(addressConverter.convert("281df2e2e47141575bd98686a4f0452bcc4c7147".hexToByteArray(), ScriptType.P2SH)).thenReturn(addresses[0])
        whenever(addressConverter.convert("54de17bd15b20e9981e78338c514e73f732a4d48".hexToByteArray(), ScriptType.P2SH)).thenReturn(addresses[1])
        whenever(addressConverter.convert("932db9d114e7809dfb09104488dce27c50e588fc".hexToByteArray(), ScriptType.P2SH)).thenReturn(addresses[2])
        whenever(addressConverter.convert("77bcc35ca78676af60334c96879b96d8599a2e9c".hexToByteArray(), ScriptType.P2SH)).thenReturn(addresses[3])

        val result = hodlerPlugin.keysForApiRestore(publicKey, addressConverter)

        Assert.assertArrayEquals(arrayOf("address0", "address1", "address2", "address3"), result.toTypedArray())
    }
}