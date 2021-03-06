package net.corda.notarydemo

import net.corda.core.div
import net.corda.core.node.services.ServiceInfo
import net.corda.core.utilities.ALICE
import net.corda.core.utilities.BOB
import net.corda.core.utilities.DUMMY_NOTARY
import net.corda.demorun.runNodes
import net.corda.node.services.startFlowPermission
import net.corda.node.services.transactions.ValidatingNotaryService
import net.corda.nodeapi.User
import net.corda.notarydemo.flows.DummyIssueAndMove
import net.corda.notarydemo.flows.RPCStartableNotaryFlowClient
import net.corda.cordform.CordformDefinition
import net.corda.cordform.CordformContext
import net.corda.demorun.util.*

fun main(args: Array<String>) = SingleNotaryCordform.runNodes()

val notaryDemoUser = User("demou", "demop", setOf(startFlowPermission<DummyIssueAndMove>(), startFlowPermission<RPCStartableNotaryFlowClient>()))

object SingleNotaryCordform : CordformDefinition("build" / "notary-demo-nodes", DUMMY_NOTARY.name) {
    init {
        node {
            name(ALICE.name)
            p2pPort(10002)
            rpcPort(10003)
            rpcUsers(notaryDemoUser)
        }
        node {
            name(BOB.name)
            p2pPort(10005)
            rpcPort(10006)
        }
        node {
            name(DUMMY_NOTARY.name)
            p2pPort(10009)
            rpcPort(10010)
            advertisedServices(ServiceInfo(ValidatingNotaryService.type))
        }
    }

    override fun setup(context: CordformContext) {}
}
