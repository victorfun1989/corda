package net.corda.node.services.vault;

import com.google.common.collect.ImmutableSet;
import kotlin.Pair;
import net.corda.contracts.DealState;
import net.corda.contracts.asset.Cash;
import net.corda.core.contracts.*;
import net.corda.core.crypto.SecureHash;
import net.corda.core.node.services.Vault;
import net.corda.core.node.services.VaultService;
import net.corda.core.node.services.vault.PageSpecification;
import net.corda.core.node.services.vault.QueryCriteria;
import net.corda.core.node.services.vault.QueryCriteria.LinearStateQueryCriteria;
import net.corda.core.node.services.vault.QueryCriteria.VaultQueryCriteria;
import net.corda.core.node.services.vault.Sort;
import net.corda.core.serialization.OpaqueBytes;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.WireTransaction;
import net.corda.node.services.vault.schemas.VaultLinearStateEntity;
import net.corda.testing.node.MockServices;
import org.bouncycastle.asn1.x500.X500Name;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.exposed.sql.Database;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import rx.Observable;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static net.corda.contracts.asset.CashKt.getDUMMY_CASH_ISSUER;
import static net.corda.contracts.asset.CashKt.getDUMMY_CASH_ISSUER_KEY;
import static net.corda.contracts.testing.VaultFiller.*;
import static net.corda.core.node.services.vault.QueryCriteriaKt.and;
import static net.corda.core.node.services.vault.QueryCriteriaUtilsKt.getMAX_PAGE_SIZE;
import static net.corda.core.utilities.TestConstants.getDUMMY_NOTARY;
import static net.corda.node.utilities.DatabaseSupportKt.configureDatabase;
import static net.corda.node.utilities.DatabaseSupportKt.transaction;
import static net.corda.testing.CoreTestUtils.getMEGA_CORP;
import static net.corda.testing.node.MockServicesKt.makeTestDataSourceProperties;
import static org.assertj.core.api.Assertions.assertThat;

@Ignore
public class VaultQueryJavaTests {

    private MockServices services;
    private VaultService vaultSvc;
    private Closeable dataSource;
    private Database database;

    @Before
    public void setUp() {

        Properties dataSourceProps = makeTestDataSourceProperties(SecureHash.randomSHA256().toString());
        Pair<Closeable, Database> dataSourceAndDatabase = configureDatabase(dataSourceProps);
        dataSource = dataSourceAndDatabase.getFirst();
        database = dataSourceAndDatabase.getSecond();

        transaction(database, statement -> services = new MockServices() {
            @NotNull
            @Override
            public VaultService getVaultService() {
                return makeVaultService(dataSourceProps);
            }

            @Override
            public void recordTransactions(@NotNull Iterable<SignedTransaction> txs) {
                for (SignedTransaction stx : txs ) {
                    getStorageService().getValidatedTransactions().addTransaction(stx);
                }

                Stream<WireTransaction> wtxn = StreamSupport.stream(txs.spliterator(), false).map(txn -> txn.getTx());
                getVaultService().notifyAll(wtxn.collect(Collectors.toList()));
            }
        });

        vaultSvc = services.getVaultService();
    }

    @After
    public void cleanUp() throws IOException {
        dataSource.close();
    }

    /**
     * Sample Vault Query API tests
     */

    /**
     *  Static queryBy() tests
     */

    @Test
    public void consumedStates() {
        transaction(database, tx -> {
            fillWithSomeTestCash(services,
                                 new Amount<>(100, Currency.getInstance("USD")),
                                 getDUMMY_NOTARY(),
                                3,
                                3,
                                 new Random(),
                                 new OpaqueBytes("1".getBytes()),
                                null,
                                 getDUMMY_CASH_ISSUER(),
                                 getDUMMY_CASH_ISSUER_KEY() );

            // DOCSTART VaultJavaQueryExample1
            @SuppressWarnings("unchecked")
            Set<Class<ContractState>> contractStateTypes = new HashSet(Collections.singletonList(Cash.State.class));
            Vault.StateStatus status = Vault.StateStatus.CONSUMED;

            VaultQueryCriteria criteria = new VaultQueryCriteria(status, null, contractStateTypes);
            Vault.Page<ContractState> results = vaultSvc.queryBy(criteria);
            // DOCEND VaultJavaQueryExample1

            assertThat(results.getStates()).hasSize(3);

            return tx;
        });
    }

    @Test
    public void consumedDealStatesPagedSorted() {
        transaction(database, tx -> {

            UniqueIdentifier uid = new UniqueIdentifier();
            fillWithSomeTestLinearStates(services, 10, uid);

            List<String> dealIds = Arrays.asList("123", "456", "789");
            fillWithSomeTestDeals(services, dealIds);

            // DOCSTART VaultJavaQueryExample2
            Vault.StateStatus status = Vault.StateStatus.CONSUMED;
            @SuppressWarnings("unchecked")
            Set<Class<ContractState>> contractStateTypes = new HashSet(Collections.singletonList(Cash.State.class));

            QueryCriteria vaultCriteria = new VaultQueryCriteria(status, null, contractStateTypes);

            List<UniqueIdentifier> linearIds = Arrays.asList(uid);
            List<X500Name> dealPartyNames = Arrays.asList(getMEGA_CORP().getName());
            QueryCriteria dealCriteriaAll = new LinearStateQueryCriteria(linearIds, false, dealIds, dealPartyNames);

            QueryCriteria compositeCriteria = and(dealCriteriaAll, vaultCriteria);

            PageSpecification pageSpec  = new PageSpecification(0, getMAX_PAGE_SIZE());
            Sort.SortColumn sortByUid = new Sort.SortColumn(VaultLinearStateEntity.UUID.getName(), Sort.Direction.DESC, Sort.NullHandling.NULLS_LAST);
            Sort sorting = new Sort(ImmutableSet.of(sortByUid));
            Vault.Page<ContractState> results = vaultSvc.queryBy(compositeCriteria, pageSpec, sorting);
            // DOCEND VaultJavaQueryExample2

            assertThat(results.getStates()).hasSize(4);

            return tx;
        });
    }

    /**
     *  Dynamic trackBy() tests
     */

    @Test
    public void trackCashStates() {

        transaction(database, tx -> {
            fillWithSomeTestCash(services,
                    new Amount<>(100, Currency.getInstance("USD")),
                    getDUMMY_NOTARY(),
                    3,
                    3,
                    new Random(),
                    new OpaqueBytes("1".getBytes()),
                    null,
                    getDUMMY_CASH_ISSUER(),
                    getDUMMY_CASH_ISSUER_KEY() );

            // DOCSTART VaultJavaQueryExample1
            @SuppressWarnings("unchecked")
            Set<Class<ContractState>> contractStateTypes = new HashSet(Collections.singletonList(Cash.State.class));

            VaultQueryCriteria criteria = new VaultQueryCriteria(Vault.StateStatus.UNCONSUMED, null, contractStateTypes);
            Vault.PageAndUpdates<ContractState> results = vaultSvc.trackBy(criteria);

            Vault.Page<ContractState> snapshot = results.getCurrent();
            Observable<Vault.Update> updates = results.getFuture();

            // DOCEND VaultJavaQueryExample1
            assertThat(snapshot.getStates()).hasSize(3);

            return tx;
        });
    }

    @Test
    public void trackDealStatesPagedSorted() {
        transaction(database, tx -> {

            UniqueIdentifier uid = new UniqueIdentifier();
            fillWithSomeTestLinearStates(services, 10, uid);

            List<String> dealIds = Arrays.asList("123", "456", "789");
            fillWithSomeTestDeals(services, dealIds);

            // DOCSTART VaultJavaQueryExample2
            @SuppressWarnings("unchecked")
            Set<Class<ContractState>> contractStateTypes = new HashSet(Collections.singletonList(DealState.class));
            QueryCriteria vaultCriteria = new VaultQueryCriteria(Vault.StateStatus.UNCONSUMED, null, contractStateTypes);

            List<UniqueIdentifier> linearIds = Arrays.asList(uid);
            List<X500Name> dealPartyNames = Arrays.asList(getMEGA_CORP().getName());
            QueryCriteria dealCriteriaAll = new LinearStateQueryCriteria(linearIds, false, dealIds, dealPartyNames);

            QueryCriteria compositeCriteria = and(dealCriteriaAll, vaultCriteria);

            PageSpecification pageSpec  = new PageSpecification(0, getMAX_PAGE_SIZE());
            Sort.SortColumn sortByUid = new Sort.SortColumn(VaultLinearStateEntity.UUID.getName(), Sort.Direction.DESC, Sort.NullHandling.NULLS_LAST);
            Sort sorting = new Sort(ImmutableSet.of(sortByUid));
            Vault.PageAndUpdates<ContractState> results = vaultSvc.trackBy(compositeCriteria, pageSpec, sorting);

            Vault.Page<ContractState> snapshot = results.getCurrent();
            Observable<Vault.Update> updates = results.getFuture();
            // DOCEND VaultJavaQueryExample2

            assertThat(snapshot.getStates()).hasSize(4);

            return tx;
        });
    }

    /**
     * Deprecated usage
     */

    @Test
    public void consumedStatesDeprecated() {
        transaction(database, tx -> {
            fillWithSomeTestCash(services,
                    new Amount<>(100, Currency.getInstance("USD")),
                    getDUMMY_NOTARY(),
                    3,
                    3,
                    new Random(),
                    new OpaqueBytes("1".getBytes()),
                    null,
                    getDUMMY_CASH_ISSUER(),
                    getDUMMY_CASH_ISSUER_KEY() );

            // DOCSTART VaultDeprecatedJavaQueryExample1
            @SuppressWarnings("unchecked")
            Set<Class<ContractState>> contractStateTypes = new HashSet(Collections.singletonList(Cash.State.class));
            EnumSet<Vault.StateStatus> status = EnumSet.of(Vault.StateStatus.CONSUMED);

            // WARNING! unfortunately cannot use inlined reified Kotlin extension methods.
            Iterable<StateAndRef<ContractState>> results = vaultSvc.states(contractStateTypes, status, true);
            // DOCEND VaultDeprecatedJavaQueryExample1

            assertThat(results).hasSize(3);

            return tx;
        });
    }

    @Test
    public void consumedStatesForLinearIdDeprecated() {
        transaction(database, tx -> {

            UniqueIdentifier trackUid = new UniqueIdentifier();
            fillWithSomeTestLinearStates(services, 1, trackUid);
            fillWithSomeTestLinearStates(services, 4, new UniqueIdentifier());

            // DOCSTART VaultDeprecatedJavaQueryExample2
            @SuppressWarnings("unchecked")
            Set<Class<ContractState>> contractStateTypes = new HashSet(Collections.singletonList(LinearState.class));
            EnumSet<Vault.StateStatus> status = EnumSet.of(Vault.StateStatus.CONSUMED);

            // WARNING! unfortunately cannot use inlined reified Kotlin extension methods.
            Iterable<StateAndRef<ContractState>> results = vaultSvc.states(contractStateTypes, status, true);

            Stream<StateAndRef<ContractState>> trackedLinearState = StreamSupport.stream(results.spliterator(), false).filter(
                    state -> ((LinearState) state.component1().getData()).getLinearId() == trackUid);
            // DOCEND VaultDeprecatedJavaQueryExample2

            assertThat(results).hasSize(4);
            assertThat(trackedLinearState).hasSize(1);

            return tx;
        });
    }
}
