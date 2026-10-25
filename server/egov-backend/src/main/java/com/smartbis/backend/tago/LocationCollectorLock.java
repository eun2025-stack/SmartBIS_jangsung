package com.smartbis.backend.tago;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/** One dedicated DB session per active collector, never a transaction-pool connection. */
final class LocationCollectorLock implements AutoCloseable {
    private static final long LOCK_KEY = 0x534249534C4F43L;
    private final DataSource dataSource;
    private Connection connection;
    LocationCollectorLock(DataSource dataSource) { this.dataSource = dataSource; }

    synchronized void acquire() throws SQLException {
        if (connection != null) throw new IllegalStateException("Location lock already acquired");
        Connection candidate = dataSource.getConnection();
        try (var statement = candidate.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, LOCK_KEY);
            statement.setQueryTimeout(5);
            try (var result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) throw new IllegalStateException("Another location collector holds the DB lock");
            }
            connection = candidate;
        } finally {
            if (connection != candidate) candidate.close();
        }
    }
    synchronized void ensureHeld() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            throw new IllegalStateException("Location collector DB lock session lost; HTTP blocked");
        }
    }
    @Override
    public synchronized void close() throws SQLException {
        if (connection == null) return;
        try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, LOCK_KEY);
            statement.setQueryTimeout(5);
            statement.execute();
        } finally {
            connection.close(); connection = null;
        }
    }
}
