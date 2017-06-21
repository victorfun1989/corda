package net.corda.node.messaging

import net.corda.core.node.services.DEFAULT_SESSION_ID
import net.corda.core.node.services.ServiceInfo
import net.corda.node.services.messaging.Message
import net.corda.node.services.messaging.TopicStringValidator
import net.corda.node.services.messaging.createMessage
import net.corda.node.services.network.NetworkMapService
import net.corda.testing.node.MockNetwork
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class InMemoryMessagingTests {
    val mockNet = MockNetwork()

    @Test
    fun topicStringValidation() {
        TopicStringValidator.check("this.is.ok")
        TopicStringValidator.check("this.is.OkAlso")
        assertFails {
            TopicStringValidator.check("this.is.not-ok")
        }
        assertFails {
            TopicStringValidator.check("")
        }
        assertFails {
            TopicStringValidator.check("this.is not ok")   // Spaces
        }
    }

    @Test
    fun basics() {
        val node1 = mockNet.createNode(advertisedServices = ServiceInfo(NetworkMapService.type))
        val node2 = mockNet.createNode(networkMapAddress = node1.info.addresses.first())
        val node3 = mockNet.createNode(networkMapAddress = node1.info.addresses.first())

        val bits = "test-content".toByteArray()
        var finalDelivery: Message? = null

        with(node2) {
            node2.network.addMessageHandler { msg, _ ->
                node2.network.send(msg, node3.info.addresses.first())
            }
        }

        with(node3) {
            node2.network.addMessageHandler { msg, _ ->
                finalDelivery = msg
            }
        }

        // Node 1 sends a message and it should end up in finalDelivery, after we run the network
        node1.network.send(node1.network.createMessage("test.topic", DEFAULT_SESSION_ID, bits), node2.info.addresses.first())

        mockNet.runNetwork(rounds = 1)

        assertTrue(Arrays.equals(finalDelivery!!.data, bits))
    }

    @Test
    fun broadcast() {
        val node1 = mockNet.createNode(advertisedServices = ServiceInfo(NetworkMapService.type))
        val node2 = mockNet.createNode(networkMapAddress = node1.info.addresses.first())
        val node3 = mockNet.createNode(networkMapAddress = node1.info.addresses.first())

        val bits = "test-content".toByteArray()

        var counter = 0
        listOf(node1, node2, node3).forEach { it.network.addMessageHandler { _, _ -> counter++ } }
        node1.network.send(node2.network.createMessage("test.topic", DEFAULT_SESSION_ID, bits), mockNet.messagingNetwork.everyoneOnline)
        mockNet.runNetwork(rounds = 1)
        assertEquals(3, counter)
    }

    /**
     * Tests that unhandled messages in the received queue are skipped and the next message processed, rather than
     * causing processing to return null as if there was no message.
     */
    @Test
    fun `skip unhandled messages`() {
        val node1 = mockNet.createNode(advertisedServices = ServiceInfo(NetworkMapService.type))
        val node2 = mockNet.createNode(networkMapAddress = node1.info.addresses.first())
        var received: Int = 0

        node1.network.addMessageHandler("valid_message") { _, _ ->
            received++
        }

        val invalidMessage = node2.network.createMessage("invalid_message", DEFAULT_SESSION_ID, ByteArray(0))
        val validMessage = node2.network.createMessage("valid_message", DEFAULT_SESSION_ID, ByteArray(0))
        node2.network.send(invalidMessage, node1.network.myAddress)
        mockNet.runNetwork()
        assertEquals(0, received)

        node2.network.send(validMessage, node1.network.myAddress)
        mockNet.runNetwork()
        assertEquals(1, received)

        // Here's the core of the test; previously the unhandled message would cause runNetwork() to abort early, so
        // this would fail. Make fresh messages to stop duplicate uniqueMessageId causing drops
        val invalidMessage2 = node2.network.createMessage("invalid_message", DEFAULT_SESSION_ID, ByteArray(0))
        val validMessage2 = node2.network.createMessage("valid_message", DEFAULT_SESSION_ID, ByteArray(0))
        node2.network.send(invalidMessage2, node1.network.myAddress)
        node2.network.send(validMessage2, node1.network.myAddress)
        mockNet.runNetwork()
        assertEquals(2, received)
    }
}
