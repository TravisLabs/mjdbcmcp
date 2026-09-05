package com.travislabs.mjdbcmcp.datasource;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Delegating wrapper that lets {@link java.sql.DriverManager} use a driver loaded by a child
 * classloader — DriverManager refuses drivers whose class is not visible to the caller's loader,
 * and this shim is.
 */
final class DriverShim implements Driver {

    /** The underlying delegate JDBC driver. */
    private final Driver delegate;

    /**
     * Constructs a DriverShim delegating to the specified driver.
     *
     * @param delegate the actual driver instance
     */
    DriverShim(Driver delegate) {
        this.delegate = delegate;
    }

    /**
     * Returns the fully qualified class name of the underlying delegate driver.
     *
     * @return delegate driver class name
     */
    String delegateClassName() {
        return delegate.getClass().getName();
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        return delegate.connect(url, info);
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return delegate.acceptsURL(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return delegate.getPropertyInfo(url, info);
    }

    @Override
    public int getMajorVersion() {
        return delegate.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        return delegate.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant() {
        return delegate.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }
}
