package com.example.atomikos.support;

import oracle.jdbc.xa.client.OracleXADataSource;

import javax.sql.ConnectionEventListener;
import javax.sql.StatementEventListener;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Oracle XADataSource used by tests to wrap XAResource calls in a
 * j-xa-tester-style fault-injecting proxy.
 */
public class FaultInjectingOracleXADataSource implements XADataSource {

    public static final String RESOURCE_ID = "oracleDataSource";

    private static final TestXaFaultEngine ENGINE = new TestXaFaultEngine();

    private final OracleXADataSource delegate;

    public FaultInjectingOracleXADataSource() {
        try {
            delegate = new OracleXADataSource();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create Oracle XA data source", e);
        }
    }

    public static TestXaFaultEngine engine() {
        return ENGINE;
    }

    public void setURL(String url) throws SQLException {
        delegate.setURL(url);
    }

    public void setUser(String user) throws SQLException {
        delegate.setUser(user);
    }

    public void setPassword(String password) throws SQLException {
        delegate.setPassword(password);
    }

    @Override
    public XAConnection getXAConnection() throws SQLException {
        return new FaultInjectingXAConnection(delegate.getXAConnection());
    }

    @Override
    public XAConnection getXAConnection(String user, String password) throws SQLException {
        return new FaultInjectingXAConnection(delegate.getXAConnection(user, password));
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
    public Logger getParentLogger() {
        return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
    }

    private static final class FaultInjectingXAConnection implements XAConnection {
        private final XAConnection delegate;

        private FaultInjectingXAConnection(XAConnection delegate) {
            this.delegate = delegate;
        }

        @Override
        public XAResource getXAResource() throws SQLException {
            return new FaultInjectingXAResource(delegate.getXAResource());
        }

        @Override
        public Connection getConnection() throws SQLException {
            return delegate.getConnection();
        }

        @Override
        public void close() throws SQLException {
            delegate.close();
        }

        @Override
        public void addConnectionEventListener(ConnectionEventListener listener) {
            delegate.addConnectionEventListener(listener);
        }

        @Override
        public void removeConnectionEventListener(ConnectionEventListener listener) {
            delegate.removeConnectionEventListener(listener);
        }

        @Override
        public void addStatementEventListener(StatementEventListener listener) {
            delegate.addStatementEventListener(listener);
        }

        @Override
        public void removeStatementEventListener(StatementEventListener listener) {
            delegate.removeStatementEventListener(listener);
        }
    }

    private static final class FaultInjectingXAResource implements XAResource {
        private final XAResource delegate;

        private FaultInjectingXAResource(XAResource delegate) {
            this.delegate = delegate;
        }

        @Override
        public void commit(Xid xid, boolean onePhase) throws XAException {
            aroundVoid(TestXaFaultEngine.Operation.COMMIT, onePhase, () -> delegate.commit(xid, onePhase));
        }

        @Override
        public void end(Xid xid, int flags) throws XAException {
            aroundVoid(TestXaFaultEngine.Operation.END, null, () -> delegate.end(xid, flags));
        }

        @Override
        public void forget(Xid xid) throws XAException {
            aroundVoid(TestXaFaultEngine.Operation.FORGET, null, () -> delegate.forget(xid));
        }

        @Override
        public int getTransactionTimeout() throws XAException {
            return around(TestXaFaultEngine.Operation.GET_TIMEOUT, null, delegate::getTransactionTimeout);
        }

        @Override
        public boolean isSameRM(XAResource xaResource) throws XAException {
            XAResource other = xaResource instanceof FaultInjectingXAResource wrapper
                    ? wrapper.delegate
                    : xaResource;
            return around(TestXaFaultEngine.Operation.IS_SAME_RM, null, () -> delegate.isSameRM(other));
        }

        @Override
        public int prepare(Xid xid) throws XAException {
            return around(TestXaFaultEngine.Operation.PREPARE, null, () -> delegate.prepare(xid));
        }

        @Override
        public Xid[] recover(int flag) throws XAException {
            return around(TestXaFaultEngine.Operation.RECOVER, null, () -> delegate.recover(flag));
        }

        @Override
        public void rollback(Xid xid) throws XAException {
            aroundVoid(TestXaFaultEngine.Operation.ROLLBACK, null, () -> delegate.rollback(xid));
        }

        @Override
        public boolean setTransactionTimeout(int seconds) throws XAException {
            return around(TestXaFaultEngine.Operation.SET_TIMEOUT, null,
                    () -> delegate.setTransactionTimeout(seconds));
        }

        @Override
        public void start(Xid xid, int flags) throws XAException {
            aroundVoid(TestXaFaultEngine.Operation.START, null, () -> delegate.start(xid, flags));
        }

        private void aroundVoid(TestXaFaultEngine.Operation operation, Boolean onePhase, XaCall call)
                throws XAException {
            around(operation, onePhase, () -> {
                call.execute();
                return null;
            });
        }

        private <T> T around(TestXaFaultEngine.Operation operation, Boolean onePhase, XaSupplier<T> call)
                throws XAException {
            ENGINE.record(RESOURCE_ID, operation, TestXaFaultEngine.Position.BEFORE, onePhase);
            try {
                T result = call.get();
                ENGINE.record(RESOURCE_ID, operation, TestXaFaultEngine.Position.AFTER_SUCCESS, onePhase);
                return result;
            } catch (XAException e) {
                ENGINE.recordFailure(RESOURCE_ID, operation, onePhase, e);
                throw e;
            }
        }
    }

    @FunctionalInterface
    private interface XaCall {
        void execute() throws XAException;
    }

    @FunctionalInterface
    private interface XaSupplier<T> {
        T get() throws XAException;
    }
}
