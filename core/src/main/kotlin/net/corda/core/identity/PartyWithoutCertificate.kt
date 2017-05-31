package net.corda.core.identity

import net.corda.core.contracts.PartyAndReference
import net.corda.core.crypto.CertificateAndKeyPair
import net.corda.core.serialization.OpaqueBytes
import org.bouncycastle.asn1.x500.X500Name
import java.security.PublicKey

/**
 * A partial [Party] without the certificate or path to link them back to the network trust root. Generally used where
 * serialization size is important.
 */
open class PartyWithoutCertificate(val name: X500Name, owningKey: PublicKey) : AbstractParty(owningKey) {
    constructor(certAndKey: CertificateAndKeyPair) : this(certAndKey.certificate.subject, certAndKey.keyPair.public)
    override fun toString() = name.toString()
    override fun nameOrNull(): X500Name? = name

    override fun ref(bytes: OpaqueBytes): PartyAndReference = PartyAndReference(this, bytes)
}
