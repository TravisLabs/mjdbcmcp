package com.travislabs.mjdbcmcp.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.travislabs.mjdbcmcp.Refusal;
import com.travislabs.mjdbcmcp.datasource.Capability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Classification is where correctness lives (ADR-0004), so this tier has no database behind it and
 * covers the cases leading-keyword matching gets wrong.
 */
class SqlClassifierTest {

    /** Classifier instance under test. */
    private final SqlClassifier classifier = new SqlClassifier();

    /** Verifies correct Capability assignment across various SQL statement types. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            SELECT 1                                              | SELECT
            select * from orders where id = ?                     | SELECT
            SELECT * FROM a JOIN b ON b.id = a.id                 | SELECT
            WITH r AS (SELECT * FROM orders) SELECT * FROM r      | SELECT
            INSERT INTO t VALUES (1)                              | DML
            UPDATE t SET a = 1                                    | DML
            DELETE FROM t                                         | DML
            CREATE TABLE t (id INT)                               | DDL_CREATE
            CREATE INDEX i ON t (a)                               | DDL_CREATE
            CREATE VIEW v AS SELECT 1                             | DDL_CREATE
            ALTER TABLE t ADD COLUMN c INT                        | DDL_ALTER
            DROP TABLE t                                          | DDL_DROP
            TRUNCATE TABLE t                                      | DDL_DROP
            """)
    void classifiesByStatementClass(String sql, Capability expected) {
        assertThat(classifier.classify(sql).capability()).isEqualTo(expected);
    }

    /** Verifies that leading SQL comments do not obscure the statement verb. */
    @Test
    void commentPreambleDoesNotHideTheVerb() {
        assertThat(classifier.classify("-- just looking\nSELECT a FROM t").capability())
                .isEqualTo(Capability.SELECT);
        assertThat(classifier.classify("/* housekeeping */ DELETE FROM t").capability())
                .isEqualTo(Capability.DML);
    }

    /** Verifies that data-modifying CTEs classify according to their inner write operations. */
    @Test
    void dataModifyingCteClassifiesAsItsWriteVerb() {
        // The case leading-keyword matching gets wrong: this reads as a SELECT and is not.
        var classification = classifier.classify(
                "WITH d AS (DELETE FROM staging RETURNING *) SELECT * FROM d");

        assertThat(classification.capability()).isEqualTo(Capability.DML);
        assertThat(classification.modifyingCte()).isTrue();
    }

    /** Verifies that CTE write operations combined with outer write operations classify as writes. */
    @Test
    void dataModifyingCteFeedingAnInsertIsStillAWrite() {
        assertThat(classifier.classify(
                "WITH d AS (DELETE FROM staging RETURNING *) INSERT INTO live SELECT * FROM d")
                .capability())
                .isEqualTo(Capability.DML);
    }

    /** Verifies that statement verbs are reported as readable SQL keywords rather than internal AST class names. */
    @Test
    void verbIsSqlsWordNotTheParsersClassName() {
        // An agent told its statement is a "PLAINSELECT" learns nothing.
        assertThat(classifier.classify("SELECT 1").verb()).isEqualTo("SELECT");
        assertThat(classifier.classify("CREATE TABLE t (id INT)").verb()).isEqualTo("CREATE TABLE");
        // For a data-modifying CTE the useful verb is the one inside the WITH clause.
        assertThat(classifier.classify("WITH d AS (DELETE FROM staging RETURNING *) SELECT * FROM d").verb())
                .isEqualTo("DELETE");
    }

    /** Verifies that standard read-only CTEs are not flagged as modifying CTEs. */
    @Test
    void readOnlyCteIsNotFlaggedAsModifying() {
        assertThat(classifier.classify("WITH r AS (SELECT * FROM orders) SELECT * FROM r").modifyingCte())
                .isFalse();
    }

    /** Verifies that multi-statement SQL strings are refused by single statement classification. */
    @Test
    void multipleStatementsAreRefused() {
        assertThatThrownBy(() -> classifier.classify("SELECT 1; DROP TABLE users"))
                .isInstanceOf(Refusal.class)
                .extracting(e -> ((Refusal) e).kind())
                .isEqualTo(Refusal.Kind.MULTIPLE_STATEMENTS);
    }

    /** Verifies that semicolons within string literals do not trigger multiple-statement refusal. */
    @Test
    void semicolonInsideALiteralIsOneStatement() {
        assertThat(classifier.classify("SELECT ';' FROM t").capability()).isEqualTo(Capability.SELECT);
    }

    /** Verifies that syntax errors fail closed as UNPARSEABLE refusals. */
    @Test
    void unparseableSqlIsRefusedRatherThanPassedThrough() {
        assertThatThrownBy(() -> classifier.classify("SELCT * FROM"))
                .isInstanceOf(Refusal.class)
                .extracting(e -> ((Refusal) e).kind())
                .isEqualTo(Refusal.Kind.UNPARSEABLE);
    }

    /** Verifies that empty or whitespace-only queries are refused. */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "-- nothing but a comment\n"})
    void emptyPayloadsAreRefused(String sql) {
        assertThatThrownBy(() -> classifier.classify(sql)).isInstanceOf(Refusal.class);
    }

    /** Verifies distinct Refusal kinds between parse errors and multi-statement payloads. */
    @Test
    void refusalKindsAreDistinguishable() {
        // ADR-0004: an agent told only "refused" retries a Capability problem by rewriting syntax.
        Refusal parse = catchRefusal("SELCT 1");
        Refusal batch = catchRefusal("SELECT 1; SELECT 2");
        assertThat(parse.kind()).isNotEqualTo(batch.kind());
        assertThat(parse.toAgentMessage()).startsWith("unparseable:");
        assertThat(batch.toAgentMessage()).startsWith("multiple_statements:");
    }

    /** Verifies extraction of all table names referenced in JOIN queries for allowlist verification. */
    @Test
    void namesTablesForAllowlistChecking() {
        var classification = classifier.classify("SELECT * FROM sales.orders o JOIN sales.lines l ON l.id = o.id");

        assertThat(classification.tables())
                .extracting(Classification.QualifiedName::toString)
                .containsExactlyInAnyOrder("sales.orders", "sales.lines");
    }

    /** Verifies that unqualified table references have a null schema component. */
    @Test
    void unqualifiedTableHasNoSchema() {
        var name = classifier.classify("SELECT * FROM orders").tables().get(0);

        assertThat(name.schema()).isNull();
        assertThat(name.table()).isEqualTo("orders");
    }

    /** Verifies extraction of target tables modified inside CTE bodies. */
    @Test
    void namesTablesWrittenToByADataModifyingCte() {
        // The allowlist check is only as good as this: miss the CTE's target and a scoped
        // Datasource passes a write to a table it was never scoped to.
        assertThat(classifier.classify("WITH d AS (DELETE FROM staging RETURNING *) SELECT * FROM d")
                .tables())
                .extracting(Classification.QualifiedName::table)
                .contains("staging");
    }

    /** Verifies that CTE temporary aliases are excluded from extracted table lists. */
    @Test
    void cteAliasesAreNotReportedAsTables() {
        // 'd' is a name local to the query; allowlisting it would be meaningless and refusing over
        // it would be wrong.
        assertThat(classifier.classify("WITH d AS (SELECT * FROM orders) SELECT * FROM d").tables())
                .extracting(Classification.QualifiedName::table)
                .containsExactly("orders");
    }

    /** Verifies that tablesResolved is true when all table references are fully identified. */
    @Test
    void tablesAreMarkedResolvedWhenTheyAreKnown() {
        assertThat(classifier.classify("SELECT * FROM orders").tablesResolved()).isTrue();
        assertThat(classifier.classify("WITH d AS (DELETE FROM staging RETURNING *) SELECT * FROM d")
                .tablesResolved()).isTrue();
    }

    /** Verifies script classification for atomic multi-statement DML payloads. */
    @Test
    void classifiesMultiStatementScripts() {
        String script = """
                INSERT INTO orders (id, total) VALUES (1, 100);
                INSERT INTO orders (id, total) VALUES (2, 200);
                UPDATE accounts SET balance = balance - 300 WHERE id = 10;
                """;
        ScriptClassification sc = classifier.classifyScript(script);

        assertThat(sc.classifications()).hasSize(3);
        assertThat(sc.requiredCapabilities()).containsExactly(Capability.DML);
        assertThat(sc.allTables())
                .extracting(Classification.QualifiedName::table)
                .containsExactlyInAnyOrder("orders", "orders", "accounts");
        assertThat(sc.allTablesResolved()).isTrue();
    }

    /** Verifies script classification for scripts requiring multiple distinct capabilities. */
    @Test
    void classifiesMultiStatementScriptsWithMixedCapabilities() {
        String script = """
                CREATE TABLE staging (id INT);
                INSERT INTO staging VALUES (1);
                DROP TABLE staging;
                """;
        ScriptClassification sc = classifier.classifyScript(script);

        assertThat(sc.classifications()).hasSize(3);
        assertThat(sc.requiredCapabilities())
                .containsExactlyInAnyOrder(Capability.DDL_CREATE, Capability.DML, Capability.DDL_DROP);
    }

    /** Verifies query classification for EXPLAIN statements. */
    @Test
    void classifiesExplainQueries() {
        Classification c1 = classifier.classifyExplain("SELECT * FROM orders WHERE id = 1");
        assertThat(c1.capability()).isEqualTo(Capability.SELECT);
        assertThat(c1.verb()).isEqualTo("EXPLAIN");
        assertThat(c1.tables()).extracting(Classification.QualifiedName::table).containsExactly("orders");

        Classification c2 = classifier.classifyExplain("EXPLAIN SELECT * FROM orders");
        assertThat(c2.capability()).isEqualTo(Capability.SELECT);
    }

    /** Verifies that EXPLAIN refuses write statements. */
    @Test
    void explainQueryRefusesWrites() {
        assertThatThrownBy(() -> classifier.classifyExplain("INSERT INTO orders VALUES (1)"))
                .isInstanceOf(Refusal.class)
                .extracting(e -> ((Refusal) e).kind())
                .isEqualTo(Refusal.Kind.CAPABILITY);
    }

    /** Verifies that EXPLAIN refuses data-modifying CTEs. */
    @Test
    void explainQueryRefusesModifyingCte() {
        assertThatThrownBy(() -> classifier.classifyExplain("WITH d AS (DELETE FROM staging RETURNING *) SELECT * FROM d"))
                .isInstanceOf(Refusal.class)
                .extracting(e -> ((Refusal) e).kind())
                .isEqualTo(Refusal.Kind.CAPABILITY);
    }

    /** Captures and returns the Refusal thrown by classifier execution. */
    private Refusal catchRefusal(String sql) {
        try {
            classifier.classify(sql);
            throw new AssertionError("expected a refusal for: " + sql);
        } catch (Refusal e) {
            return e;
        }
    }
}
