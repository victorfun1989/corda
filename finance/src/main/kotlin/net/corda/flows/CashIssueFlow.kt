package net.corda.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.contracts.asset.Cash
import net.corda.core.contracts.Amount
import net.corda.core.contracts.TransactionType
import net.corda.core.contracts.issuedBy
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.serialization.OpaqueBytes
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import java.util.*

/**
 * Initiates a flow that produces cash issuance transaction.
 *
 * @param amount the amount of currency to issue.
 * @param issueRef a reference to put on the issued currency.
 * @param recipient the party who should own the currency after it is issued.
 * @param notary the notary to set on the output states.
 */
@StartableByRPC
class CashIssueFlow(val amount: Amount<Currency>,
                    val issueRef: OpaqueBytes,
                    val recipient: Party,
                    val notary: Party,
                    progressTracker: ProgressTracker) : AbstractCashFlow<Pair<SignedTransaction, Map<Party, TxKeyFlow.AnonymousIdentity>>>(progressTracker) {
    constructor(amount: Amount<Currency>,
                issueRef: OpaqueBytes,
                recipient: Party,
                notary: Party) : this(amount, issueRef, recipient, notary, tracker())

    @Suspendable
    override fun call(): Pair<SignedTransaction, Map<Party, TxKeyFlow.AnonymousIdentity>> {
        progressTracker.currentStep = GENERATING_ID
        val txIdentities = subFlow(TxKeyFlow.Requester(recipient))
        val anonymousRecipient = txIdentities[recipient]!!.identity
        progressTracker.currentStep = GENERATING_TX
        val builder: TransactionBuilder = TransactionType.General.Builder(notary = notary)
        val issuer = serviceHub.myInfo.legalIdentity.ref(issueRef)
        Cash().generateIssue(builder, amount.issuedBy(issuer), anonymousRecipient, notary)
        progressTracker.currentStep = SIGNING_TX
        val tx = serviceHub.signInitialTransaction(builder, issuer.party.owningKey)
        progressTracker.currentStep = FINALISING_TX
        subFlow(FinalityFlow(tx))
        return Pair(tx, txIdentities)
    }
}
