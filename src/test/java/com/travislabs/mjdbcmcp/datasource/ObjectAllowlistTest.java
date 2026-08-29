package com.travislabs.mjdbcmcp.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Allowlist resolution is name matching, not SQL evaluation, so it sits in the same pure tier as
 * Classification (ADR-0009).
 */
class ObjectAllowlistTest {

    @Test
    void blankIsUnrestricted() {
        assertThat(ObjectAllowlist.parse(null).isUnrestricted()).isTrue();
        assertThat(ObjectAllowlist.parse("   ").isUnrestricted()).isTrue();
        assertThat(ObjectAllowlist.parse("").permitsTable("anything", "at_all")).isTrue();
    }

    @Test
    void qualifiedEntryMatchesOnlyThatTable() {
        var allowlist = ObjectAllowlist.parse("sales.orders");

        assertThat(allowlist.permitsTable("sales", "orders")).isTrue();
        assertThat(allowlist.permitsTable("sales", "customers")).isFalse();
        assertThat(allowlist.permitsTable("hr", "orders")).isFalse();
    }

    @Test
    void wildcardCoversAWholeSchema() {
        var allowlist = ObjectAllowlist.parse("staging.*");

        assertThat(allowlist.permitsTable("staging", "anything")).isTrue();
        assertThat(allowlist.permitsTable("public", "anything")).isFalse();
    }

    @Test
    void unquotedNamesFoldCase() {
        var allowlist = ObjectAllowlist.parse("Sales.Orders");

        assertThat(allowlist.permitsTable("SALES", "ORDERS")).isTrue();
        assertThat(allowlist.permitsTable("sales", "orders")).isTrue();
    }

    @Test
    void quotedNamesMatchLiterally() {
        var allowlist = ObjectAllowlist.parse("\"Sales\".\"Orders\"");

        assertThat(allowlist.permitsTable("Sales", "Orders")).isTrue();
        assertThat(allowlist.permitsTable("sales", "orders")).isFalse();
    }

    @Test
    void unqualifiedEntryMatchesTheNameInAnySchema() {
        var allowlist = ObjectAllowlist.parse("orders");

        assertThat(allowlist.permitsTable("sales", "orders")).isTrue();
        assertThat(allowlist.permitsTable(null, "orders")).isTrue();
        assertThat(allowlist.permitsTable("sales", "customers")).isFalse();
    }

    @Test
    void entriesSplitOnCommasAndNewlines() {
        var allowlist = ObjectAllowlist.parse("sales.orders,\n staging.*\nhr.people");

        assertThat(allowlist.entries()).hasSize(3);
        assertThat(allowlist.permitsTable("hr", "people")).isTrue();
        assertThat(allowlist.permitsTable("staging", "scratch")).isTrue();
    }

    @Test
    void schemaFilteringHidesSchemasWithNothingReachable() {
        var allowlist = ObjectAllowlist.parse("sales.orders");

        assertThat(allowlist.permitsSchema("sales")).isTrue();
        assertThat(allowlist.permitsSchema("hr")).isFalse();
    }

    @Test
    void unqualifiedEntriesCannotExcludeASchema() {
        // An entry that says nothing about schemas must not hide every schema from list_schemas.
        assertThat(ObjectAllowlist.parse("orders").permitsSchema("anything")).isTrue();
    }

    @Test
    void catalogQualifiedEntriesKeepTheLastTwoParts() {
        var allowlist = ObjectAllowlist.parse("warehouse.sales.orders");

        assertThat(allowlist.permitsTable("sales", "orders")).isTrue();
    }

    @Test
    void unqualifiedNamesResolveAgainstTheDefaultSchema() {
        assertThat(ObjectAllowlist.resolveSchema(null, "sales")).isEqualTo("sales");
        assertThat(ObjectAllowlist.resolveSchema("hr", "sales")).isEqualTo("hr");
        assertThat(ObjectAllowlist.resolveSchema(null, null)).isNull();
        assertThat(ObjectAllowlist.resolveSchema("  ", "sales")).isEqualTo("sales");
    }

    @Test
    void formatRoundTrips() {
        var original = ObjectAllowlist.parse("sales.orders\nstaging.*\n\"Odd Name\".t");

        assertThat(ObjectAllowlist.parse(original.format()).entries()).isEqualTo(original.entries());
    }
}
