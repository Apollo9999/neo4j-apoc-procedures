package apoc.custom;

import apoc.RegisterComponentFactory;
import apoc.SystemLabels;
import apoc.SystemPropertyKeys;
import apoc.util.StatusCodeMatcher;
import apoc.util.TestUtil;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.rules.ExpectedException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static apoc.custom.CypherProceduresHandler.FUNCTION;
import static apoc.custom.CypherProceduresHandler.PROCEDURE;
import static apoc.custom.Signatures.SIGNATURE_SYNTAX_ERROR;
import static apoc.util.TestUtil.testCall;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author mh
 * @since 18.08.18
 */
public class CypherProceduresTest  {

    @Rule
    public DbmsRule db = new ImpermanentDbmsRule();

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Before
    public void setup() {
        TestUtil.registerProcedure(db, CypherProcedures.class);
    }

    @AfterAll
    public void tearDown() {
        db.shutdown();
    }

    @Test
    public void registerSimpleStatement() throws Exception {
        db.executeTransactionally("CALL apoc.custom.declareProcedure('answer2() :: (answer::INT)','RETURN 42 as answer')");
        TestUtil.testCall(db, "call custom.answer2()", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void registerSimpleStatementWithOneChar() throws Exception {
        TestUtil.testFail(db, "CALL apoc.custom.declareProcedure('b() :: (answer::INT)','RETURN 42 as answer')", QueryExecutionException.class);
    }
    
    @Test
    public void testValidationProceduresIssue2654() {
        db.executeTransactionally("CALL apoc.custom.declareProcedure('doubleProc(input::INT) :: (answer::INT)', 'RETURN $input * 2 AS answer')");
        TestUtil.testCall(db, "CALL custom.doubleProc(4);", (r) -> assertEquals(8L, r.get("answer")));
        
        db.executeTransactionally("CALL apoc.custom.declareProcedure('testValTwo(input::INT) :: (answer::INT)', 'RETURN $input ^ 2 AS answer')");
        TestUtil.testCall(db, "CALL custom.testValTwo(4);", (r) -> assertEquals(16D, r.get("answer")));

        db.executeTransactionally("CALL apoc.custom.declareProcedure('testValThree(input::MAP, power :: LONG) :: (answer::INT)', 'RETURN $input.a ^ $power AS answer')");
        TestUtil.testCall(db, "CALL custom.testValThree({a: 2}, 3);", (r) -> assertEquals(8D, r.get("answer")));

        db.executeTransactionally("CALL apoc.custom.declareProcedure($signature, $query)",
                Map.of("signature", "testValFour(input::INT, power::NUMBER) :: (answer::INT)",
                        "query", "UNWIND range(0, $power) AS power RETURN $input ^ power AS answer"));

        TestUtil.testResult(db, "CALL custom.testValFour(2, 3)",
                (r) -> assertEquals(List.of(1D, 2D, 4D, 8D), Iterators.asList(r.columnAs("answer"))));

        db.executeTransactionally("CALL apoc.custom.declareProcedure($signature, $query)",
                Map.of("signature", "multiProc(input::LOCALDATETIME, minus::INT) :: (first::INT, second:: STRING, third::DATETIME)",
                        "query", "WITH $input AS input RETURN input.year - $minus AS first, toString(input) as second, input as third"));

        TestUtil.testCall(db, "CALL custom.multiProc(localdatetime('2020'), 3);", (r) -> {
            assertEquals(2017L, r.get("first"));
            assertEquals("2020-01-01T00:00:00", r.get("second"));
            assertEquals(LocalDateTime.of(2020, 1, 1, 0, 0, 0, 0), r.get("third"));
        });
    }
    
    @Test
    public void testValidationFunctionsIssue2654() {    
        db.executeTransactionally("CALL apoc.custom.declareFunction('double(input::INT) :: INT', 'RETURN $input * 2 AS answer')");
        TestUtil.testCall(db, "RETURN custom.double(4) AS answer", (r) -> assertEquals(8L, r.get("answer")));
        
        db.executeTransactionally("CALL apoc.custom.declareFunction('testValOne(input::INT) :: INT', 'RETURN $input ^ 2 AS answer')");
        TestUtil.testCall(db, "RETURN custom.testValOne(3) as result", (r) -> assertEquals(9D, r.get("result")));

        db.executeTransactionally("CALL apoc.custom.declareFunction($signature, $query)", 
                Map.of("signature", "multiFun(point:: POINT, input ::DATETIME, duration :: DURATION, minus = 1 ::INT) :: STRING", 
                        "query", "RETURN toString($duration) + ', ' + toString($input.epochMillis - $minus) + ', ' + toString($point) as result"));
        
        TestUtil.testCall(db, "RETURN custom.multiFun(point({x: 1, y:1}), datetime('2020'), duration('P5M1DT12H')) as result", 
                (r) -> assertEquals("P5M1DT12H, 1577836799999, point({x: 1.0, y: 1.0, crs: 'cartesian'})", r.get("result")));
    }

    @Test
    public void  testDeclareFunctionReturnTypes() {
        // given
        db.executeTransactionally("UNWIND range(1, 4) as val CREATE (i:Target {value: val});");

        // when
        db.executeTransactionally("CALL apoc.custom.declareFunction('ret_node(val :: INTEGER) :: NODE ', 'MATCH (t:Target {value : $val}) RETURN t')");
        db.executeTransactionally("CALL apoc.custom.declareFunction('ret_node_list(val :: INTEGER) :: LIST OF NODE ', 'MATCH (t:Target {value : $val}) RETURN [t]')");
        db.executeTransactionally("CALL apoc.custom.declareFunction('ret_map(val :: INTEGER) :: MAP ', 'RETURN {value : $val} as value')");
        db.executeTransactionally("CALL apoc.custom.declareFunction('ret_map_list(val :: INTEGER) :: LIST OF MAP ', 'RETURN [{value : $val}] as value')");

        // then
        TestUtil.testResult(db, "RETURN custom.ret_node(1) AS val", (result) -> {
            Node node = result.<Node>columnAs("val").next();
            assertTrue(node.hasLabel(Label.label("Target")));
            assertEquals(1L, node.getProperty("value"));
        });
        TestUtil.testResult(db, "RETURN custom.ret_node_list(2) AS val", (result) -> {
            List<List<Node>> nodes = result.<List<List<Node>>>columnAs("val").next();
            assertEquals(1, nodes.size());
            Node node = nodes.get(0).get(0);
            assertTrue(node.hasLabel(Label.label("Target")));
            assertEquals(2L, node.getProperty("value"));
        });
        TestUtil.testResult(db, "RETURN custom.ret_map(3) AS val", (result) -> {
            Map<String, Map<String, Object>> map = result.<Map<String, Map<String, Object>>>columnAs("val").next();
            assertEquals(1, map.size());
            assertEquals(3L, map.get("value").get("value"));
        });
        TestUtil.testResult(db, "RETURN custom.ret_map_list(4) AS val", (result) -> {
            List<Map<String, List<Map<String, Object>>>> list = result.<List<Map<String, List<Map<String, Object>>>>>columnAs("val").next();
            assertEquals(1, list.size());
            assertEquals(1, list.get(0).size());
            assertEquals(4L, list.get(0).get("value").get(0).get("value"));
        });
    }

    @Test
    public void registerSimpleStatementFunction() throws Exception {
        db.executeTransactionally("CALL apoc.custom.declareFunction('answer2() :: STRING','RETURN 42 as answer')");
        TestUtil.testCall(db, "return custom.answer2() as row", (row) -> assertEquals(42L, row.get("row")));
    }

    @Test
    public void registerSimpleStatementFunctionWithOneChar() throws Exception {
        final String procedureSignature = "b() :: STRING";
        assertProcedureFails(String.format(SIGNATURE_SYNTAX_ERROR, procedureSignature),
                "CALL apoc.custom.declareFunction('" + procedureSignature + "','RETURN 42 as answer')");
    }

    @Test
    public void shouldListAllProceduresAndFunctions() throws Exception {
        // given
        db.executeTransactionally("CALL apoc.custom.declareProcedure('answer(input = 42 ::INT) :: (answer::NUMBER)','RETURN $input as answer', 'READ', 'Procedure that answer to the Ultimate Question of Life, the Universe, and Everything')");
        db.executeTransactionally("CALL apoc.custom.declareFunction('answer(input::NUMBER) :: INT','RETURN $input as answer')");

        // when
        TestUtil.testResult(db, "call apoc.custom.list", (row) -> {
            // then
            assertTrue(row.hasNext());
            while (row.hasNext()){
                Map<String, Object> value = row.next();
                assertTrue(value.containsKey("type"));
                assertTrue(FUNCTION.equals(value.get("type")) || PROCEDURE.equals(value.get("type")));

                if(PROCEDURE.equals(value.get("type"))){
                    assertEquals("answer", value.get("name"));
                    assertEquals(asList(asList("answer", "number")), value.get("outputs"));
                    assertEquals(asList(asList("input", "integer", "42")), value.get("inputs"));
                    assertEquals("Procedure that answer to the Ultimate Question of Life, the Universe, and Everything", value.get("description").toString());
                    assertNull(value.get("forceSingle"));
                    assertEquals("read", value.get("mode"));
                }

                if(FUNCTION.equals(value.get("type"))){
                    assertEquals("answer", value.get("name"));
                    assertEquals("integer", value.get("outputs"));
                    assertEquals(asList(asList("input", "number")), value.get("inputs"));
                    assertEquals("", value.get("description"));
                    assertFalse((Boolean) value.get("forceSingle"));
                    assertNull(value.get("mode"));
                }
            }
        });
    }

    @Test
    public void shouldProvideAnEmptyList() throws Exception {
        // when
        TestUtil.testResult(db, "call apoc.custom.list", (row) ->
            // then
            assertFalse(row.hasNext())
        );
    }

    @Test
    public void shouldRemoveTheCustomProcedure() throws Exception {
        thrown.expect(QueryExecutionException.class);
        thrown.expectMessage("There is no procedure with the name `custom.answer` registered for this database instance. " +
                "Please ensure you've spelled the procedure name correctly and that the procedure is properly deployed.");
        thrown.expect(new StatusCodeMatcher("Neo.ClientError.Statement.SyntaxError"));

        // given
        db.executeTransactionally("CALL apoc.custom.declareProcedure('answer() :: (answer::LONG)','RETURN 42 as answer')");
        TestUtil.testCall(db, "call custom.answer()", (row) ->  assertEquals(42L, row.get("answer")));

        // when
        db.executeTransactionally("call apoc.custom.removeProcedure('answer')");
        db.executeTransactionally("call db.clearQueryCaches()");

        // then
        TestUtil.count(db, "call custom.answer()");
    }

    @Test
    public void shouldOverrideAndRemoveTheCustomFunctionWithDotInName() throws Exception {
        thrown.expect(QueryExecutionException.class);
        thrown.expectMessage("Unknown function 'custom.aa.bb.cc'");
        thrown.expect(new StatusCodeMatcher("Neo.ClientError.Statement.SyntaxError"));

        // given
        db.executeTransactionally("CALL apoc.custom.declareFunction('aa.bb.cc() :: LONG','RETURN 42 as answer')");
        TestUtil.testCall(db, "return custom.aa.bb.cc() as answer", (row) -> assertEquals(42L, row.get("answer")));
        db.executeTransactionally("call db.clearQueryCaches()");
        db.executeTransactionally("CALL apoc.custom.declareFunction('aa.bb.cc() :: LONG','RETURN 43 as answer')");
        TestUtil.testCall(db, "return custom.aa.bb.cc() as answer", (row) -> assertEquals(43L, row.get("answer")));
        db.executeTransactionally("call db.clearQueryCaches()");

        // when
        db.executeTransactionally("call apoc.custom.removeFunction('aa.bb.cc')");
        db.executeTransactionally("call db.clearQueryCaches()");

        // then
        TestUtil.count(db, "RETURN custom.aa.bb.cc()");
    }

    @Test
    public void shouldOverrideAndRemoveTheCustomProcedureWithDotInName() throws Exception {
        thrown.expect(QueryExecutionException.class);
        thrown.expectMessage("There is no procedure with the name `custom.aa.bb.cc` registered for this database instance. " +
                "Please ensure you've spelled the procedure name correctly and that the procedure is properly deployed.");
        thrown.expect(new StatusCodeMatcher("Neo.ClientError.Statement.SyntaxError"));

        // given
        db.executeTransactionally("CALL apoc.custom.declareProcedure('aa.bb.cc() :: (answer::LONG)','RETURN 42 as answer')");
        TestUtil.testCall(db, "call custom.aa.bb.cc()", (row) -> assertEquals(42L, row.get("answer")));
        db.executeTransactionally("call db.clearQueryCaches()");
        db.executeTransactionally("CALL apoc.custom.declareProcedure('aa.bb.cc() :: (answer::LONG)','RETURN 43 as answer')");
        TestUtil.testCall(db, "call custom.aa.bb.cc()", (row) -> assertEquals(43L, row.get("answer")));
        db.executeTransactionally("call db.clearQueryCaches()");

        // when
        db.executeTransactionally("call apoc.custom.removeProcedure('aa.bb.cc')");
        db.executeTransactionally("call db.clearQueryCaches()");

        // then
        TestUtil.count(db, "call custom.aa.bb.cc()");
    }

    @Test
    public void shouldRemoveTheCustomFunctionWithDotInName() throws Exception {
        thrown.expect(QueryExecutionException.class);
        thrown.expectMessage("Unknown function 'custom.aa.bb.cc'");
        thrown.expect(new StatusCodeMatcher("Neo.ClientError.Statement.SyntaxError"));

        // given
        db.executeTransactionally("CALL apoc.custom.declareFunction('aa.bb.cc() :: LONG','RETURN 42 as answer')");
        TestUtil.testCall(db, "return custom.aa.bb.cc() as answer", (row) -> assertEquals(42L, row.get("answer")));

        // when
        db.executeTransactionally("call apoc.custom.removeFunction('aa.bb.cc')");
        db.executeTransactionally("call db.clearQueryCaches()");

        // then
        TestUtil.count(db, "RETURN custom.aa.bb.cc()");
    }

    @Test
    public void shouldRemoveTheCustomProcedureWithDotInName() throws Exception {
        thrown.expect(QueryExecutionException.class);
        thrown.expectMessage("There is no procedure with the name `custom.aa.bb.cc` registered for this database instance. " +
                "Please ensure you've spelled the procedure name correctly and that the procedure is properly deployed.");
        thrown.expect(new StatusCodeMatcher("Neo.ClientError.Statement.SyntaxError"));

        // given
        db.executeTransactionally("CALL apoc.custom.declareProcedure('aa.bb.cc() :: (answer::LONG)','RETURN 42 as answer')");
        TestUtil.testCall(db, "call custom.aa.bb.cc()", (row) -> assertEquals(42L, row.get("answer")));

        // when
        db.executeTransactionally("call apoc.custom.removeProcedure('aa.bb.cc')");
        db.executeTransactionally("call db.clearQueryCaches()");

        // then
        TestUtil.count(db, "call custom.aa.bb.cc()");
    }

    @Test
    public void shouldOverrideCustomFunctionWithDotInNameOnlyIfWithSameNamespaceAndFinalName() throws Exception {

        // given
        db.executeTransactionally("CALL apoc.custom.declareFunction('aa.bb.name() :: LONG','RETURN 42 as answer')");
        TestUtil.testCall(db, "return custom.aa.bb.name() as answer", (row) -> assertEquals(42L, row.get("answer")));
        db.executeTransactionally("call db.clearQueryCaches()");

        db.executeTransactionally("CALL apoc.custom.declareFunction('aa.bb.name() :: LONG','RETURN 34 as answer')");
        TestUtil.testCall(db, "return custom.aa.bb.name() as answer", (row) -> assertEquals(34L, row.get("answer")));
        db.executeTransactionally("call db.clearQueryCaches()");

        db.executeTransactionally("CALL apoc.custom.declareFunction('xx.zz.name() :: LONG','RETURN 12 as answer')");
        TestUtil.testCall(db, "return custom.xx.zz.name() as answer", (row) -> assertEquals(12L, row.get("answer")));
        TestUtil.testCall(db, "return custom.aa.bb.name() as answer", (row) -> assertEquals(34L, row.get("answer")));
        db.executeTransactionally("call db.clearQueryCaches()");

        TestUtil.testResult(db, "call apoc.custom.list", (row) -> {
            assertTrue(row.hasNext());
            Map<String, Object> mapFirst = row.next();
            assertEquals("aa.bb.name", mapFirst.get("name"));
            assertEquals("RETURN 34 as answer", mapFirst.get("statement"));
            assertEquals(FUNCTION, mapFirst.get("type"));
            assertTrue(row.hasNext());
            Map<String, Object> mapSecond = row.next();
            assertEquals("xx.zz.name", mapSecond.get("name"));
            assertEquals("RETURN 12 as answer", mapSecond.get("statement"));
            assertEquals(FUNCTION, mapSecond.get("type"));
            assertFalse(row.hasNext());
        });

        db.executeTransactionally("call apoc.custom.removeFunction('aa.bb.name')");
        db.executeTransactionally("call db.clearQueryCaches()");

        TestUtil.testResult(db, "call apoc.custom.list", (row) -> {
            assertTrue(row.hasNext());
            Map<String, Object> map = row.next();
            assertEquals("xx.zz.name", map.get("name"));
            assertEquals("RETURN 12 as answer", map.get("statement"));
            assertEquals(FUNCTION, map.get("type"));
            assertFalse(row.hasNext());
        });

        db.executeTransactionally("call apoc.custom.removeFunction('xx.zz.name')");
        db.executeTransactionally("call db.clearQueryCaches()");

        TestUtil.testCallEmpty(db, "call apoc.custom.list()", Collections.emptyMap());
    }

    @Test
    public void shouldOverrideCustomProcedureWithDotInNameOnlyIfWithSameNamespaceAndFinalName() throws Exception {

        // given
        db.executeTransactionally("CALL apoc.custom.declareProcedure('aa.bb.name() :: (answer::LONG)','RETURN 42 as answer')");
        TestUtil.testCall(db, "call custom.aa.bb.name()", (row) -> assertEquals(42L, row.get("answer")));
        db.executeTransactionally("call db.clearQueryCaches()");

        db.executeTransactionally("CALL apoc.custom.declareProcedure('aa.bb.name() :: (answer::LONG)','RETURN 34 as answer')");
        TestUtil.testCall(db, "call custom.aa.bb.name()", (row) -> assertEquals(34L, row.get("answer")));
        db.executeTransactionally("call db.clearQueryCaches()");

        db.executeTransactionally("CALL apoc.custom.declareProcedure('xx.zz.name() :: (answer::LONG)','RETURN 12 as answer')");
        TestUtil.testCall(db, "call custom.xx.zz.name()", (row) -> assertEquals(12L, row.get("answer")));
        TestUtil.testCall(db, "call custom.aa.bb.name()", (row) -> assertEquals(34L, row.get("answer")));

        TestUtil.testResult(db, "call apoc.custom.list", (row) -> {
            assertTrue(row.hasNext());
            Map<String, Object> mapFirst = row.next();
            assertEquals("aa.bb.name", mapFirst.get("name"));
            assertEquals("RETURN 34 as answer", mapFirst.get("statement"));
            assertEquals(PROCEDURE, mapFirst.get("type"));
            assertTrue(row.hasNext());
            Map<String, Object> mapSecond = row.next();
            assertEquals("xx.zz.name", mapSecond.get("name"));
            assertEquals("RETURN 12 as answer", mapSecond.get("statement"));
            assertEquals(PROCEDURE, mapSecond.get("type"));
            assertFalse(row.hasNext());
        });

        db.executeTransactionally("call apoc.custom.removeProcedure('aa.bb.name')");
        db.executeTransactionally("call db.clearQueryCaches()");

        TestUtil.testResult(db, "call apoc.custom.list", (row) -> {
            assertTrue(row.hasNext());
            Map<String, Object> map = row.next();
            assertEquals("xx.zz.name", map.get("name"));
            assertEquals("RETURN 12 as answer", map.get("statement"));
            assertEquals(PROCEDURE, map.get("type"));
            assertFalse(row.hasNext());
        });

        db.executeTransactionally("call apoc.custom.removeProcedure('xx.zz.name')");
        db.executeTransactionally("call db.clearQueryCaches()");

        TestUtil.testCallEmpty(db, "call apoc.custom.list()", Collections.emptyMap());
    }

    @Test
    public void shouldRemoveTheCustomFunction() throws Exception {
        thrown.expect(QueryExecutionException.class);
        thrown.expectMessage("Unknown function 'custom.answer'");
        thrown.expect(new StatusCodeMatcher("Neo.ClientError.Statement.SyntaxError"));

        // given
        db.executeTransactionally("CALL apoc.custom.declareFunction('answer() :: LONG','RETURN 42 as answer')");
        TestUtil.testCall(db, "return custom.answer() as answer", (row) -> assertEquals(42L, row.get("answer")));

        // when
        db.executeTransactionally("call apoc.custom.removeFunction('answer')");
        db.executeTransactionally("call db.clearQueryCaches()");

        // then
        TestUtil.count(db, "return custom.answer()");
    }

    @Test
    public void shouldRemovalOfFunctionNodeDeactivate() {
        thrown.expect(QueryExecutionException.class);
        thrown.expectMessage("Unknown function 'custom.answer'");
        thrown.expect(new StatusCodeMatcher("Neo.ClientError.Statement.SyntaxError"));

        //given
        db.executeTransactionally("CALL apoc.custom.declareFunction('answer() :: LONG','RETURN 42 as answer')");

        long answer = TestUtil.singleResultFirstColumn(db, "return custom.answer()");
        assertEquals(42L, answer);

        // remove the node in systemdb
        GraphDatabaseService systemDb = db.getManagementService().database("system");
        try (Transaction tx = systemDb.beginTx()) {
            Node node = tx.findNode(SystemLabels.ApocCypherProcedures, SystemPropertyKeys.name.name(), "answer");
            node.delete();
            tx.commit();
        }

        // refresh procedures
        RegisterComponentFactory.RegisterComponentLifecycle registerComponentLifecycle = db.getDependencyResolver().resolveDependency(RegisterComponentFactory.RegisterComponentLifecycle.class);
        CypherProceduresHandler cypherProceduresHandler = (CypherProceduresHandler) registerComponentLifecycle.getResolvers().get(CypherProceduresHandler.class).get(db.databaseName());
        cypherProceduresHandler.restoreProceduresAndFunctions();

        // when
        TestUtil.singleResultFirstColumn(db, "return custom.answer()");
    }

    @Test
    public void shouldFailDeclareFunctionWithDefaultNumberParameters() {
        final String query = "RETURN $base * $exp AS res";
        db.executeTransactionally("CALL apoc.custom.declareFunction('defaultFloatFun(base=2.4::FLOAT,exp=1.2::FLOAT):: INT', $query)",
                Map.of("query", query));
        testCall(db, "RETURN custom.defaultFloatFun() AS res", (row) -> assertEquals(2.4D * 1.2D, (double) row.get("res"), 0.1D));
        testCall(db, "RETURN custom.defaultFloatFun(1.1) AS res", (row) -> assertEquals(1.1D * 1.2D, (double) row.get("res"), 0.1D));
        testCall(db, "RETURN custom.defaultFloatFun(1.5, 7.1) AS res", (row) -> assertEquals(1.5D * 7.1D, (double) row.get("res"), 0.1D));

        db.executeTransactionally("CALL apoc.custom.declareFunction('defaultDoubleFun(base = 2.4 :: DOUBLE, exp = 1.2 :: DOUBLE):: DOUBLE', $query)",
                Map.of("query", query));
        testCall(db, "RETURN custom.defaultDoubleFun() AS res", (row) -> assertEquals(2.4D * 1.2D, (double) row.get("res"), 0.1D));
        testCall(db, "RETURN custom.defaultDoubleFun(1.1) AS res", (row) -> assertEquals(1.1D * 1.2D, (double) row.get("res"), 0.1D));
        testCall(db, "RETURN custom.defaultDoubleFun(1.5, 7.1) AS res", (row) -> assertEquals(1.5D * 7.1D, (double) row.get("res"), 0.1D));

        db.executeTransactionally("CALL apoc.custom.declareFunction('defaultIntFun(base = 4 ::INT, exp = 5 :: INT):: INT', $query)",
                Map.of("query", query));
        testCall(db, "RETURN custom.defaultIntFun() AS res", (row) -> assertEquals(4L * 5L, row.get("res")));
        testCall(db, "RETURN custom.defaultIntFun(2) AS res", (row) -> assertEquals(2L * 5L, row.get("res")));
        testCall(db, "RETURN custom.defaultIntFun(3, 7) AS res", (row) -> assertEquals(3L * 7L, row.get("res")));

        db.executeTransactionally("CALL apoc.custom.declareFunction('defaultLongFun(base = 4 ::LONG, exp = 5 :: LONG):: LONG', $query)",
                Map.of("query", query));
        testCall(db, "RETURN custom.defaultLongFun() AS res", (row) -> assertEquals(4L * 5L, row.get("res")));
        testCall(db, "RETURN custom.defaultLongFun(2) AS res", (row) -> assertEquals(2L * 5L, row.get("res")));
        testCall(db, "RETURN custom.defaultLongFun(3, 7) AS res", (row) -> assertEquals(3L * 7L, row.get("res")));
    }

    @Test
    public void shouldFailDeclareProcedureWithDefaultNumberParameters() {
        final String query = "RETURN $base * $exp AS res";
        db.executeTransactionally("CALL apoc.custom.declareProcedure('defaultFloatProc(base=2.4::FLOAT,exp=1.2::FLOAT)::(res::INT)', $query)",
                Map.of("query", query));
        testCall(db, "CALL custom.defaultFloatProc", (row) -> assertEquals(2.4D * 1.2D, (double) row.get("res"), 0.1D));
        testCall(db, "CALL custom.defaultFloatProc(1.1)", (row) -> assertEquals(1.1D * 1.2D, (double) row.get("res"), 0.1D));
        testCall(db, "CALL custom.defaultFloatProc(1.5, 7.1)", (row) -> assertEquals(1.5D * 7.1D, (double) row.get("res"), 0.1D));

        db.executeTransactionally("CALL apoc.custom.declareProcedure('defaultDoubleProc(base = 2.4 :: DOUBLE, exp = 1.2 :: DOUBLE)::(res::DOUBLE)', $query)",
                Map.of("query", query));
        testCall(db, "CALL custom.defaultDoubleProc", (row) -> assertEquals(2.4D * 1.2D, (double) row.get("res"), 0.1D));
        testCall(db, "CALL custom.defaultDoubleProc(1.1)", (row) -> assertEquals(1.1D * 1.2D, (double) row.get("res"), 0.1D));
        testCall(db, "CALL custom.defaultDoubleProc(1.5, 7.1)", (row) -> assertEquals(1.5D * 7.1D, (double) row.get("res"), 0.1D));

        db.executeTransactionally("CALL apoc.custom.declareProcedure('defaultIntProc(base = 4 ::INT, exp = 5 :: INT)::(res::INT)', $query)",
                Map.of("query", query));
        testCall(db, "CALL custom.defaultIntProc", (row) -> assertEquals(4L * 5L, row.get("res")));
        testCall(db, "CALL custom.defaultIntProc(2)", (row) -> assertEquals(2L * 5L, row.get("res")));
        testCall(db, "CALL custom.defaultIntProc(3, 7)", (row) -> assertEquals(3L * 7L, row.get("res")));

        db.executeTransactionally("CALL apoc.custom.declareProcedure('defaultLongProc(base = 4 ::LONG, exp = 5 :: LONG)::(res::LONG)', $query)",
                Map.of("query", query));
        testCall(db, "CALL custom.defaultLongProc", (row) -> assertEquals(4L * 5L, row.get("res")));
        testCall(db, "CALL custom.defaultLongProc(2)", (row) -> assertEquals(2L * 5L, row.get("res")));
        testCall(db, "CALL custom.defaultLongProc(3, 7)", (row) -> assertEquals(3L * 7L, row.get("res")));
    }
    
    @Test
    public void shouldFailDeclareFunctionAndProcedureWithInvalidParameterTypes() {
        final String procedureStatementInvalidInput = "sum(input:: INVALID) :: (answer::INT)";
        assertProcedureFails(String.format(SIGNATURE_SYNTAX_ERROR, procedureStatementInvalidInput),
                "call apoc.custom.declareProcedure('" + procedureStatementInvalidInput + "','RETURN $input AS input')");
        final String functionStatementInvalidInput = "double(input :: INVALID) :: INT";
        assertProcedureFails(String.format(SIGNATURE_SYNTAX_ERROR, functionStatementInvalidInput),
                "call apoc.custom.declareFunction('" + functionStatementInvalidInput + "','RETURN $input*2 as answer')");

        final String procedureStatementInvalidOutput = "myProc(input :: INTEGER) :: (sum :: DUNNO)";
        assertProcedureFails(String.format(SIGNATURE_SYNTAX_ERROR, procedureStatementInvalidOutput), 
                "call apoc.custom.declareProcedure('" + procedureStatementInvalidOutput + "','RETURN $input AS sum')");
        final String functionStatementInvalidOutput = "myFunc(val :: INTEGER) :: DUNNO";
        assertProcedureFails(String.format(SIGNATURE_SYNTAX_ERROR, functionStatementInvalidOutput),
                "CALL apoc.custom.declareFunction('" + functionStatementInvalidOutput + "', 'RETURN $val')");
    }

    @Test
    public void shouldCreateFunctionWithDefaultParameters() {
        // default inputs
        db.executeTransactionally("CALL apoc.custom.declareFunction('multiParDeclareFun(params = {} :: MAP) :: INT ', 'RETURN $one + $two as sum')");
        TestUtil.testCall(db, "return custom.multiParDeclareFun({one:2, two: 3}) as row", (row) -> assertEquals(5L, row.get("row")));
        
        db.executeTransactionally("CALL apoc.custom.declareProcedure('multiParDeclareProc(params = {} :: MAP) :: (sum :: INT) ', 'RETURN $one + $two + $three as sum')");
        TestUtil.testCall(db, "call custom.multiParDeclareProc({one:2, two: 3, three: 4})", (row) -> assertEquals(9L, row.get("sum")));

        // default outputs
        db.executeTransactionally("CALL apoc.custom.declareProcedure('declareDefaultOut(one :: INTEGER, two :: INTEGER) :: (row :: MAP) ', 'RETURN $one + $two as sum')");
        TestUtil.testCall(db, "call custom.declareDefaultOut(5, 3)", (row) -> assertEquals(8L, ((Map<String, Object>)row.get("row")).get("sum")));
    }
    
    @Test
    public void testIssue2032() {
        String functionSignature = "foobar(xx::NODE, y::NODE) ::(NODE)";
        assertProcedureFails(String.format(SIGNATURE_SYNTAX_ERROR, functionSignature), 
                "CALL apoc.custom.declareFunction('" + functionSignature + "', 'MATCH (n) RETURN n limit 1');");
        
        String procedureSignature = "testFail(first::INT, s::INT) :: (answer::INT)";
        assertProcedureFails(String.format(SIGNATURE_SYNTAX_ERROR, procedureSignature), 
                "call apoc.custom.declareProcedure('" + procedureSignature + "','RETURN $first + $s AS answer')");
    }
    

    private void assertProcedureFails(String expectedMessage, String query) {
        try {
            testCall(db, query, row -> fail("The test should fail because of: " + expectedMessage));
        } catch (QueryExecutionException e) {
            Throwable except = ExceptionUtils.getRootCause(e);
            assertTrue(except instanceof RuntimeException);
            assertTrue(except.getMessage().contains(expectedMessage));
        }
    }
}
