package apoc.load;

import apoc.util.TestUtil;
import apoc.util.Util;
import apoc.util.s3.S3Container;
import apoc.xml.XmlTestUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.jupiter.api.AfterAll;

import org.neo4j.driver.internal.util.Iterables;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import static apoc.ApocConfig.APOC_IMPORT_FILE_ENABLED;
import static apoc.ApocConfig.APOC_IMPORT_FILE_USE_NEO4J_CONFIG;
import static apoc.ApocConfig.apocConfig;
import static apoc.load.LoadCsvTest.assertRow;
import static apoc.util.MapUtil.map;
import static apoc.util.TestUtil.testCall;
import static apoc.util.TestUtil.testResult;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;

public class LoadS3Test {

    @Rule
    public DbmsRule db = new ImpermanentDbmsRule();

    private S3Container minio;

    @BeforeClass
    public static void init() {
        // In test environment we skip the MD5 validation that can cause issues
        System.setProperty("com.amazonaws.services.s3.disableGetObjectMD5Validation", "true");
        System.setProperty("com.amazonaws.sdk.disableCertChecking", "true");
    }

    @AfterClass
    public static void destroy() {
        System.clearProperty("com.amazonaws.services.s3.disableGetObjectMD5Validation");
        System.clearProperty("com.amazonaws.sdk.disableCertChecking");
    }

    @Before
    public void setUp() throws Exception {
        TestUtil.registerProcedure(db, LoadCsv.class, LoadJson.class, Xml.class);
        apocConfig().setProperty(APOC_IMPORT_FILE_ENABLED, true);
        apocConfig().setProperty(APOC_IMPORT_FILE_USE_NEO4J_CONFIG, false);
        minio = new S3Container();
    }

    @After
    public void tearDown() throws Exception {
        minio.close();
    }

    @AfterAll
    public void tearDownAll() {
        db.shutdown();
    }

    @Test
    public void testLoadCsvS3() throws Exception {
        String url = minio.putFile("src/test/resources/test.csv");
        testResult(db, "CALL apoc.load.csv($url,{failOnError:false})", map("url", url), (r) -> {
            assertRow(r, "Selma", "8", 0L);
            assertRow(r, "Rana", "11", 1L);
            assertRow(r, "Selina", "18", 2L);
            assertEquals(false, r.hasNext());
        });
    }

    @Test public void testLoadJsonS3() throws Exception {
        String url = minio.putFile("src/test/resources/map.json");

        testCall(db, "CALL apoc.load.json($url,'')",map("url", url),
                (row) -> {
                    assertEquals(map("foo",asList(1L,2L,3L)), row.get("value"));
                });
    }

    @Test public void testLoadXmlS3() throws Exception {
        String url = minio.putFile("src/test/resources/xml/books.xml");

        testCall(db, "CALL apoc.load.xml($url,'/catalog/book[title=\"Maeve Ascendant\"]/.',{failOnError:false}) yield value as result", Util.map("url", url), (r) -> {
            Object value = Iterables.single(r.values());
            Assert.assertEquals(XmlTestUtils.XML_XPATH_AS_NESTED_MAP, value);
        });
    }


}
