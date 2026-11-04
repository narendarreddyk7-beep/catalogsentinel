package io.catalogsentinel.core.testsupport;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** A DataSource that can be switched "down" to simulate a database outage (connection refused). */
public final class FlakyDataSource implements DataSource {

    private final DataSource delegate;
    private final AtomicBoolean down = new AtomicBoolean();

    public FlakyDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    public void setDown(boolean value) {
        down.set(value);
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (down.get()) {
            throw new SQLTransientConnectionException("simulated outage: connection refused", "08001");
        }
        return delegate.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }
}
