package apoc.ttl;

import apoc.periodic.Periodic;
import apoc.util.TestUtil;
import org.junit.AfterClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import static org.junit.Assert.assertTrue;

/**
 * TODO:
 * Normally apocConfig() can be used to replace ApocSettings,
 * but apocConfig() doesn't exist yet in @BeforeClass and it is too late to set the config values in @Before.
 * Instead we must either find a way to mock this or
 * make it use Neo4jContainerExtension, which would work if core was a submodule
 * (currently this approach has the same issue as CoreExtendedTest.java)
 */
@Ignore
public class TTLTest {

    @ClassRule
    public static DbmsRule db = new ImpermanentDbmsRule();
            //.withSetting(ApocSettings.apoc_ttl_schedule, Duration.ofMillis(3000))
            //.withSetting(ApocSettings.apoc_ttl_enabled, true);

    @AfterClass
    public static void tearDown() {
        db.shutdown();
    }

    @Test
    public void testExpireManyNodes() throws Exception {
        int fooCount = 200;
        int barCount = 300;
        restartAndRegister(db);
        db.executeTransactionally("UNWIND range(1," + fooCount + ") as range CREATE (:Baz)-[:REL_TEST]->(n:Foo:TTL {id: range, ttl: timestamp() + 100});");
        db.executeTransactionally("UNWIND range(1," + barCount + ") as range CREATE (n:Bar:TTL {id: range, ttl: timestamp() + 100});");
        assertTrue(isNodeCountConsistent(fooCount, barCount));
        org.neo4j.test.assertion.Assert.assertEventually(() -> isNodeCountConsistent(0, 0), (value) -> value, 30L, TimeUnit.SECONDS);
    }

    // test extracted from apoc.date
    @Test
    public void testExpire() throws Exception {
        restartAndRegister(db);
        db.executeTransactionally("CREATE (n:Foo:TTL) SET n.ttl = timestamp() + 100");
        db.executeTransactionally("CREATE (n:Bar) WITH n CALL apoc.ttl.expireIn(n,500,'ms') RETURN count(*)");
        assertTrue(isNodeCountConsistent(1,1));
        org.neo4j.test.assertion.Assert.assertEventually(() -> isNodeCountConsistent(0, 0), (value) -> value, 10L, TimeUnit.SECONDS);
    }

    private static boolean isNodeCountConsistent(int foo, int bar) {
        try (Transaction tx = db.beginTx()) {
            boolean isNotCountConsistent = foo == Iterators.count(tx.findNodes(Label.label("Foo")))
                    && bar == Iterators.count(tx.findNodes(Label.label("Bar")))
                    && foo + bar == Iterators.count(tx.findNodes(Label.label("TTL")));
            tx.commit();
            return isNotCountConsistent;
        }
    }

    private static void restartAndRegister(DbmsRule db) throws Exception {
        db.restartDatabase();
        TestUtil.registerProcedure(db, TTL.class, Periodic.class);
    }
}
