package net.corda.contracts.universal

import net.corda.core.identity.PartyWithoutCertificate
import java.math.BigDecimal
import java.util.*

fun swap(partyA: PartyWithoutCertificate, amountA: BigDecimal, currencyA: Currency, partyB: PartyWithoutCertificate, amountB: BigDecimal, currencyB: Currency) =
        arrange {
            partyA.owes(partyB, amountA, currencyA)
            partyB.owes(partyA, amountB, currencyB)
        }

fun fx_swap(expiry: String, notional: BigDecimal, strike: BigDecimal,
            foreignCurrency: Currency, domesticCurrency: Currency,
            partyA: PartyWithoutCertificate, partyB: PartyWithoutCertificate) =
        arrange {
            actions {
                (partyA or partyB).may {
                    "execute".givenThat(after(expiry)) {
                        swap(partyA, notional * strike, domesticCurrency, partyB, notional, foreignCurrency)
                    }
                }
            }
        }

// building an fx swap using abstract swap
fun fx_swap2(expiry: String, notional: Long, strike: Double,
             foreignCurrency: Currency, domesticCurrency: Currency,
             partyA: PartyWithoutCertificate, partyB: PartyWithoutCertificate) =
        Action("execute", after(expiry) and (signedBy(partyA) or signedBy(partyB)),
                swap(partyA, BigDecimal(notional * strike), domesticCurrency, partyB, BigDecimal(notional), foreignCurrency))
