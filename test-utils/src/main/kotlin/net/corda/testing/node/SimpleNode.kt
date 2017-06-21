package net.corda.testing.node

import com.codahale.metrics.MetricRegistry
import com.google.common.net.HostAndPort
import com.google.common.util.concurrent.SettableFuture
import net.corda.core.crypto.commonName
import net.corda.core.crypto.generateKeyPair
import net.corda.core.flows.FlowInitiator
import net.corda.core.flows.FlowLogic
import net.corda.core.messaging.RPCOps
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.*
import net.corda.core.serialization.SerializeAsToken
import net.corda.core.transactions.SignedTransaction
import net.corda.node.internal.InitiatedFlowFactory
import net.corda.node.services.RPCUserServiceImpl
import net.corda.node.services.api.*
import net.corda.node.services.config.NodeConfiguration
import net.corda.node.services.identity.InMemoryIdentityService
import net.corda.node.services.keys.E2ETestKeyManagementService
import net.corda.node.services.messaging.ArtemisMessagingServer
import net.corda.node.services.messaging.MessagingService
import net.corda.node.services.messaging.NodeMessagingClient
import net.corda.node.services.network.InMemoryNetworkMapCache
import net.corda.node.services.statemachine.FlowStateMachineImpl
import net.corda.node.utilities.AffinityExecutor.ServiceAffinityExecutor
import net.corda.node.utilities.configureDatabase
import net.corda.node.utilities.transaction
import net.corda.testing.MOCK_VERSION_INFO
import net.corda.testing.freeLocalHostAndPort
import org.bouncycastle.cert.X509CertificateHolder
import org.jetbrains.exposed.sql.Database
import java.io.Closeable
import java.security.KeyPair
import java.time.Clock
import kotlin.concurrent.thread

/**
 * This is a bare-bones node which can only send and receive messages. It doesn't register with a network map service or
 * any other such task that would make it functional in a network and thus left to the user to do so manually.
 */
class SimpleNode(val config: NodeConfiguration, val address: HostAndPort = freeLocalHostAndPort(),
                 rpcAddress: HostAndPort = freeLocalHostAndPort(),
                 trustRoot: X509CertificateHolder? = null) : AutoCloseable {

    private val databaseWithCloseable: Pair<Closeable, Database> = configureDatabase(config.dataSourceProperties)
    val database: Database get() = databaseWithCloseable.second
    val userService = RPCUserServiceImpl(config.rpcUsers)
    val monitoringService = MonitoringService(MetricRegistry())
    val identity: KeyPair = generateKeyPair()
    val identityService: IdentityService = InMemoryIdentityService(trustRoot = trustRoot)
    val keyService: KeyManagementService = E2ETestKeyManagementService(identityService, setOf(identity))
    val executor = ServiceAffinityExecutor(config.myLegalName.commonName, 1)
    // TODO: We should have a dummy service hub rather than change behaviour in tests
    val broker = ArtemisMessagingServer(config, address.port, rpcAddress.port, InMemoryNetworkMapCache(serviceHub = null), userService)
    val networkMapRegistrationFuture: SettableFuture<Unit> = SettableFuture.create<Unit>()
    val network = database.transaction {
        NodeMessagingClient(
                config,
                MOCK_VERSION_INFO,
                address,
                identity.public,
                executor,
                database,
                networkMapRegistrationFuture,
                monitoringService)
    }

    fun start() {
        broker.start()
        network.start(
                object : RPCOps {
                    override val protocolVersion = 0
                },
                userService)
        thread(name = config.myLegalName.commonName) {
            network.run(broker.serverControl)
        }
    }

    override fun close() {
        network.stop()
        broker.stop()
        databaseWithCloseable.first.close()
        executor.shutdownNow()
    }
}
