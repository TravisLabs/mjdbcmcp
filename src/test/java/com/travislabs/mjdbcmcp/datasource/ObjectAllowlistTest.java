package com.travislabs.mjdbcmcp.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Allowlist resolution is name matching, not SQL evaluation, so it sits in the same pure tier as
 * Classification (ADR-0009).
 */
class ObjectAllowlistTest {

    /** Verifies that null, empty, or whitespace strings parse to an unrestricted allowlist. */
    @Test
    void blankIsUnrestricted() {
        assertThat(ObjectAllowlist.parse(null).isUnrestricted()).isTrue();
        assertThat(ObjectAllowlist.parse("   ").isUnrestricted()).isTrue();
        assertThat(ObjectAllowlist.parse("").permitsTable("anything", "at_all")).isTrue();
    }

    /** Verifies that a schema-qualified entry matches only the specified table within that schema. */
    @Test
    void qualifiedEntryMatchesOnlyThatTable() {
        var allowlist = ObjectAllowlist.parse("sales.orders");

        assertThat(allowlist.permitsTable("sales", "orders")).isTrue();
        assertThat(allowlist.permitsTable("sales", "customers")).isFalse();
        assertThat(allowlist.permitsTable("hr", "orders")).isFalse();
    }

    /** Verifies that a schema wildcard entry permits all tables within the schema. */
    @Test
    void wildcardCoversAWholeSchema() {
        var allowlist = ObjectAllowlist.parse("staging.*");

        assertThat(allowlist.permitsTable("staging", "anything")).isTrue();
        assertThat(allowlist.permitsTable("public", "anything")).isFalse();
    }

    /** Verifies that unquoted schema and table names match case-insensitively. */
    @Test
    void unquotedNamesFoldCase() {
        var allowlist = ObjectAllowlist.parse("Sales.Orders");

        assertThat(allowlist.permitsTable("SALES", "ORDERS")).isTrue();
        assertThat(allowlist.permitsTable("sales", "orders")).isTrue();
    }

    /** Verifies that double-quoted names match case-sensitively. */
    @Test
    void quotedNamesMatchLiterally() {
        var allowlist = ObjectAllowlist.parse("\"Sales\".\"Orders\"");

        assertThat(allowlist.permitsTable("Sales", "Orders")).isTrue();
        assertThat(allowlist.permitsTable("sales", "orders")).isFalse();
    }

    /** Verifies that an unqualified table entry matches that table in any schema. */
    @Test
    void unqualifiedEntryMatchesTheNameInAnySchema() {
        var allowlist = ObjectAllowlist.parse("orders");

        assertThat(allowlist.permitsTable("sales", "orders")).isTrue();
        assertThat(allowlist.permitsTable(null, "orders")).isTrue();
        assertThat(allowlist.permitsTable("sales", "customers")).isFalse();
    }

    /** Verifies that multiple allowlist entries can be separated by commas or newlines. */
    @Test
    void entriesSplitOnCommasAndNewlines() {
        var allowlist = ObjectAllowlist.parse("sales.orders,\n staging.*\nhr.people");

        assertThat(allowlist.entries()).hasSize(3);
        assertThat(allowlist.permitsTable("hr", "people")).isTrue();
        assertThat(allowlist.permitsTable("staging", "scratch")).isTrue();
    }

    /** Verifies that schemas containing no permitted tables are hidden during schema filtering. */
    @Test
    void schemaFilteringHidesSchemasWithNothingReachable() {
        var allowlist = ObjectAllowlist.parse("sales.orders");

        assertThat(allowlist.permitsSchema("sales")).isTrue();
        assertThat(allowlist.permitsSchema("hr")).isFalse();
    }

    /** Verifies that unqualified table entries do not exclude schemas from schema filtering. */
    @Test
    void unqualifiedEntriesCannotExcludeASchema() {
        // An entry that says nothing about schemas must not hide every schema from list_schemas.
        assertThat(ObjectAllowlist.parse("orders").permitsSchema("anything")).isTrue();
    }

    /** Verifies that 3-part names (catalog.schema.table) retain the schema and table parts. */
    @Test
    void catalogQualifiedEntriesKeepTheLastTwoParts() {
        var allowlist = ObjectAllowlist.parse("warehouse.sales.orders");

        assertThat(allowlist.permitsTable("sales", "orders")).isTrue();
    }

    /** Verifies fallback to the default schema when statement table references are unqualified. */
    @Test
    void unqualifiedNamesResolveAgainstTheDefaultSchema() {
        assertThat(ObjectAllowlist.resolveSchema(null, "sales")).isEqualTo("sales");
        assertThat(ObjectAllowlist.resolveSchema("hr", "sales")).isEqualTo("hr");
        assertThat(ObjectAllowlist.resolveSchema(null, null)).isNull();
        assertThat(ObjectAllowlist.resolveSchema("  ", "sales")).isEqualTo("sales");
    }

    /** Verifies that formatting an allowlist and re-parsing yields equivalent entries. */
    @Test
    void formatRoundTrips() {
        var original = ObjectAllowlist.parse("sales.orders\nstaging.*\n\"Odd Name\".t");

        assertThat(ObjectAllowlist.parse(original.format()).entries()).isEqualTo(original.entries());
    }
}
