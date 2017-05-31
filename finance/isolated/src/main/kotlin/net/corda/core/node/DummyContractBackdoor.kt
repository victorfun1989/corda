package net.corda.core.node

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.PartyAndReference
import net.corda.core.identity.PartyWithoutCertificate
import net.corda.core.transactions.TransactionBuilder

interface DummyContractBackdoor {
    fun generateInitial(owner: PartyAndReference, magicNumber: Int, notary: PartyWithoutCertificate): TransactionBuilder

    fun inspectState(state: ContractState): Int
}
