// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2021 MariaDB Corporation Ab

package org.tidb.jdbc;

import java.sql.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import javax.sql.ConnectionEvent;
import org.tidb.jdbc.client.Client;
import org.tidb.jdbc.client.Context;
import org.tidb.jdbc.export.ExceptionFactory;
import org.tidb.jdbc.message.client.ChangeDbPacket;
import org.tidb.jdbc.message.client.PingPacket;
import org.tidb.jdbc.message.client.QueryPacket;
import org.tidb.jdbc.message.client.ResetPacket;
import org.tidb.jdbc.util.NativeSql;
import org.tidb.jdbc.util.constants.Capabilities;
import org.tidb.jdbc.util.constants.ConnectionState;
import org.tidb.jdbc.util.constants.ServerStatus;

/** Public Connection class */
public class Connection implements java.sql.Connection {

  private static final Pattern CALLABLE_STATEMENT_PATTERN =
      Pattern.compile(
          "^(\\s*\\{)?\\s*((\\?\\s*=)?(\\s*/\\*([^*]|\\*[^/])*\\*/)*\\s*"
              + "call(\\s*/\\*([^*]|\\*[^/])*\\*/)*\\s*((((`[^`]+`)|([^`}]+))\\.)?"
              + "((`[^`]+`)|([^`}(]+)))\\s*(\\(.*\\))?(\\s*/\\*([^*]|\\*[^/])*\\*/)*"
              + "\\s*(#.*)?)\\s*(}\\s*)?$",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private final ReentrantLock lock;
  private final Configuration conf;
  private ExceptionFactory exceptionFactory;
  private final Client client;
  private final Properties clientInfo = new Properties();
  private int lowercaseTableNames = -1;
  private final AtomicInteger savepointId = new AtomicInteger();
  private boolean readOnly;
  private final boolean canUseServerTimeout;
  private final boolean canUseServerMaxRows;
  private final int defaultFetchSize;
  private final boolean forceTransactionEnd;
  private TiDBPoolConnection poolConnection;

  /**
   * Connection construction.
   *
   * @param conf configuration
   * @param lock thread safe locker
   * @param client client object
   */
  public Connection(Configuration conf, ReentrantLock lock, Client client) {
    this.conf = conf;
    this.forceTransactionEnd =
        Boolean.parseBoolean(conf.nonMappedOptions().getProperty("forceTransactionEnd", "false"));
    this.lock = lock;
    this.exceptionFactory = client.getExceptionFactory().setConnection(this);
    this.client = client;
    Context context = this.client.getContext();
    this.canUseServerTimeout = true;
    this.canUseServerMaxRows = context.getVersion().versionGreaterOrEqual(4, 0, 2);
    this.defaultFetchSize = context.getConf().defaultFetchSize();
  }

  /**
   * Internal method. Indicate that connection is created from internal pool
   *
   * @param poolConnection PoolConnection
   */
  public void setPoolConnection(TiDBPoolConnection poolConnection) {
    this.poolConnection = poolConnection;
    this.exceptionFactory = exceptionFactory.setPoolConnection(poolConnection);
  }

  /**
   * Cancels the current query - clones the current protocol and executes a query using the new
   * connection.
   *
   * @throws SQLException never thrown
   */
  public void cancelCurrentQuery() throws SQLException {
    client.forceClose();
  }

  @Override
  public org.tidb.jdbc.Statement createStatement() {
    return new org.tidb.jdbc.Statement(
        this,
        lock,
        canUseServerTimeout,
        canUseServerMaxRows,
        org.tidb.jdbc.Statement.RETURN_GENERATED_KEYS,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        defaultFetchSize);
  }

  @Override
  public PreparedStatement prepareStatement(String sql) throws SQLException {
    return prepareInternal(
        sql,
        org.tidb.jdbc.Statement.NO_GENERATED_KEYS,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        conf.useServerPrepStmts());
  }

  /**
   * Prepare statement creation
   *
   * @param sql sql
   * @param autoGeneratedKeys auto generated key required
   * @param resultSetType result-set type
   * @param resultSetConcurrency concurrency
   * @param useBinary use server prepare statement
   * @return prepared statement
   * @throws SQLException if Prepare fails
   */
  public PreparedStatement prepareInternal(
      String sql,
      int autoGeneratedKeys,
      int resultSetType,
      int resultSetConcurrency,
      boolean useBinary)
      throws SQLException {
    checkNotClosed();
    if (useBinary) {
      try {
        return new ServerPreparedStatement(
            NativeSql.parse(sql, client.getContext()),
            this,
            lock,
            canUseServerTimeout,
            canUseServerMaxRows,
            autoGeneratedKeys,
            resultSetType,
            resultSetConcurrency,
            defaultFetchSize);
      } catch (SQLException e) {
        // failover to client
      }
    }
    return new ClientPreparedStatement(
        NativeSql.parse(sql, client.getContext()),
        this,
        lock,
        canUseServerTimeout,
        canUseServerMaxRows,
        autoGeneratedKeys,
        resultSetType,
        resultSetConcurrency,
        defaultFetchSize);
  }

  private ExceptionFactory exceptionFactory() {
    return this.getExceptionFactory();
  }

  @Override
  public CallableStatement prepareCall(String sql) throws SQLException {
    throw exceptionFactory().notSupported("TiDB not supported procedures");
  }

  @Override
  public String nativeSQL(String sql) throws SQLException {
    return NativeSql.parse(sql, client.getContext());
  }

  @Override
  public boolean getAutoCommit() {
    return (client.getContext().getServerStatus() & ServerStatus.AUTOCOMMIT) > 0;
  }

  @Override
  public void setAutoCommit(boolean autoCommit) throws SQLException {
    if (autoCommit == getAutoCommit()) {
      return;
    }
    lock.lock();
    try {
      getContext().addStateFlag(ConnectionState.STATE_AUTOCOMMIT);
      client.execute(
          new QueryPacket(((autoCommit) ? "set autocommit=1" : "set autocommit=0")), true);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void commit() throws SQLException {
    lock.lock();
    try {
      if (forceTransactionEnd
          || (client.getContext().getServerStatus() & ServerStatus.IN_TRANSACTION) > 0) {
        client.execute(new QueryPacket("COMMIT"), false);
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void rollback() throws SQLException {
    lock.lock();
    try {
      if (forceTransactionEnd
          || (client.getContext().getServerStatus() & ServerStatus.IN_TRANSACTION) > 0) {
        client.execute(new QueryPacket("ROLLBACK"), true);
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void close() throws SQLException {
    if (poolConnection != null) {
      poolConnection.fireConnectionClosed(new ConnectionEvent(poolConnection));
      return;
    }
    client.close();
  }

  @Override
  public boolean isClosed() {
    return client.isClosed();
  }

  /**
   * Connection context.
   *
   * @return connection context.
   */
  public Context getContext() {
    return client.getContext();
  }

  /**
   * Are table case-sensitive or not . Default Value: 0 (Unix), 1 (Windows), 2 (Mac OS X). If set to
   * 0 (the default on Unix-based systems), table names and aliases and database names are compared
   * in a case-sensitive manner. If set to 1 (the default on Windows), names are stored in lowercase
   * and not compared in a case-sensitive manner. If set to 2 (the default on Mac OS X), names are
   * stored as declared, but compared in lowercase.
   *
   * @return int value.
   * @throws SQLException if a connection error occur
   */
  public int getLowercaseTableNames() throws SQLException {
    if (lowercaseTableNames == -1) {
      try (java.sql.Statement st = createStatement()) {
        try (ResultSet rs = st.executeQuery("select @@lower_case_table_names")) {
          rs.next();
          lowercaseTableNames = rs.getInt(1);
        }
      }
    }
    return lowercaseTableNames;
  }

  @Override
  public org.tidb.jdbc.DatabaseMetaData getMetaData() {
    return new DatabaseMetaData(this, this.conf);
  }

  @Override
  public boolean isReadOnly() {
    return this.readOnly;
  }

  @Override
  public void setReadOnly(boolean readOnly) throws SQLException {
    lock.lock();
    try {
      if (this.readOnly != readOnly) {
        client.setReadOnly(readOnly);
      }
      this.readOnly = readOnly;
      getContext().addStateFlag(ConnectionState.STATE_READ_ONLY);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public String getCatalog() throws SQLException {

    if (client.getContext().hasClientCapability(Capabilities.CLIENT_SESSION_TRACK)) {
      return client.getContext().getDatabase();
    }

    org.tidb.jdbc.Statement stmt = createStatement();
    ResultSet rs = stmt.executeQuery("select database()");
    rs.next();
    client.getContext().setDatabase(rs.getString(1));
    return client.getContext().getDatabase();
  }

  @Override
  public void setCatalog(String catalog) throws SQLException {
    if (client.getContext().hasClientCapability(Capabilities.CLIENT_SESSION_TRACK)
        && catalog.equals(client.getContext().getDatabase())) {
      return;
    }
    lock.lock();
    try {
      getContext().addStateFlag(ConnectionState.STATE_DATABASE);
      client.execute(new ChangeDbPacket(catalog), true);
      client.getContext().setDatabase(catalog);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int getTransactionIsolation() throws SQLException {
    // TiDB Not support READ_UNCOMMITTED and SERIALIZABLE transaction isolation level
    String sql = "SELECT @@tx_isolation";
    ResultSet rs = createStatement().executeQuery(sql);
    if (rs.next()) {
      final String response = rs.getString(1);
      switch (response) {
        case "REPEATABLE-READ":
          return java.sql.Connection.TRANSACTION_REPEATABLE_READ;

        case "READ-COMMITTED":
          return java.sql.Connection.TRANSACTION_READ_COMMITTED;

        default:
          throw exceptionFactory.create(
              String.format(
                  "Could not get transaction isolation level: Invalid value \"%s\"", response));
      }
    }
    throw exceptionFactory.create("Failed to retrieve transaction isolation");
  }

  @Override
  public void setTransactionIsolation(int level) throws SQLException {
    // TiDB Not support READ_UNCOMMITTED and SERIALIZABLE transaction isolation level
    String query = "SET SESSION TRANSACTION ISOLATION LEVEL";
    switch (level) {
      case java.sql.Connection.TRANSACTION_READ_UNCOMMITTED:
        throw new SQLException("TiDB Not support READ_UNCOMMITTED transaction isolation level");
      case java.sql.Connection.TRANSACTION_READ_COMMITTED:
        query += " READ COMMITTED";
        break;
      case java.sql.Connection.TRANSACTION_REPEATABLE_READ:
        query += " REPEATABLE READ";
        break;
      case java.sql.Connection.TRANSACTION_SERIALIZABLE:
        throw new SQLException("TiDB Not support SERIALIZABLE transaction isolation level");
      default:
        throw new SQLException("Unsupported transaction isolation level");
    }
    lock.lock();
    try {
      checkNotClosed();
      getContext().addStateFlag(ConnectionState.STATE_TRANSACTION_ISOLATION);
      client.getContext().setTransactionIsolationLevel(level);
      client.execute(new QueryPacket(query), true);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public SQLWarning getWarnings() throws SQLException {
    checkNotClosed();
    if (client.getContext().getWarning() == 0) {
      return null;
    }

    SQLWarning last = null;
    SQLWarning first = null;

    try (org.tidb.jdbc.Statement st = this.createStatement()) {
      try (ResultSet rs = st.executeQuery("show warnings")) {
        // returned result set has 'level', 'code' and 'message' columns, in this order.
        while (rs.next()) {
          int code = rs.getInt(2);
          String message = rs.getString(3);
          SQLWarning warning = new SQLWarning(message, null, code);
          if (first == null) {
            first = warning;
          } else {
            last.setNextWarning(warning);
          }
          last = warning;
        }
      }
    }
    return first;
  }

  @Override
  public void clearWarnings() {
    client.getContext().setWarning(0);
  }

  @Override
  public org.tidb.jdbc.Statement createStatement(int resultSetType, int resultSetConcurrency)
      throws SQLException {
    checkNotClosed();
    return new org.tidb.jdbc.Statement(
        this,
        lock,
        canUseServerTimeout,
        canUseServerMaxRows,
        org.tidb.jdbc.Statement.RETURN_GENERATED_KEYS,
        resultSetType,
        resultSetConcurrency,
        defaultFetchSize);
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
      throws SQLException {
    return prepareInternal(
        sql,
        org.tidb.jdbc.Statement.RETURN_GENERATED_KEYS,
        resultSetType,
        resultSetConcurrency,
        conf.useServerPrepStmts());
  }

  @Override
  public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
      throws SQLException {
    throw exceptionFactory().notSupported("TiDB not supported procedures");
  }

  @Override
  public Map<String, Class<?>> getTypeMap() {
    return new HashMap<>();
  }

  @Override
  public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
    throw exceptionFactory.notSupported("TypeMap are not supported");
  }

  @Override
  public int getHoldability() {
    return ResultSet.HOLD_CURSORS_OVER_COMMIT;
  }

  @Override
  public void setHoldability(int holdability) {
    // not supported
  }

  @Override
  public Savepoint setSavepoint() throws SQLException {
    if (!this.client.getContext().getVersion().versionGreaterOrEqual(6, 0, 2)) {
      throw exceptionFactory().notSupported("TiDB support savepoint at 6.0.2 version");
    }

    TiDBSavepoint savepoint = new TiDBSavepoint(savepointId.incrementAndGet());
    client.execute(new QueryPacket("SAVEPOINT `" + savepoint.rawValue() + "`"), true);
    return savepoint;
  }

  @Override
  public Savepoint setSavepoint(String name) throws SQLException {
    if (!this.client.getContext().getVersion().versionGreaterOrEqual(6, 0, 2)) {
      throw exceptionFactory().notSupported("TiDB support savepoint at 6.0.2 version");
    }

    TiDBSavepoint savepoint = new TiDBSavepoint(name.replace("`", "``"));
    client.execute(new QueryPacket("SAVEPOINT `" + savepoint.rawValue() + "`"), true);
    return savepoint;
  }

  @Override
  public void rollback(java.sql.Savepoint savepoint) throws SQLException {
    checkNotClosed();
    if (!this.client.getContext().getVersion().versionGreaterOrEqual(6, 0, 2)) {
      throw exceptionFactory().notSupported("TiDB support savepoint at 6.0.2 version");
    }

    lock.lock();
    try {
      if ((client.getContext().getServerStatus() & ServerStatus.IN_TRANSACTION) > 0) {
        if (savepoint instanceof TiDBSavepoint) {
          client.execute(
              new QueryPacket(
                  "ROLLBACK TO SAVEPOINT `" + ((TiDBSavepoint) savepoint).rawValue() + "`"),
              true);
        } else {
          throw exceptionFactory.create("Unknown savepoint type");
        }
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void releaseSavepoint(java.sql.Savepoint savepoint) throws SQLException {
    checkNotClosed();
    if (!this.client.getContext().getVersion().versionGreaterOrEqual(6, 0, 2)) {
      throw exceptionFactory().notSupported("TiDB support savepoint at 6.0.2 version");
    }

    lock.lock();
    try {
      if ((client.getContext().getServerStatus() & ServerStatus.IN_TRANSACTION) > 0) {
        if (savepoint instanceof TiDBSavepoint) {
          client.execute(
              new QueryPacket("RELEASE SAVEPOINT `" + ((TiDBSavepoint) savepoint).rawValue() + "`"),
              true);
        } else {
          throw exceptionFactory.create("Unknown savepoint type");
        }
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public org.tidb.jdbc.Statement createStatement(
      int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
    checkNotClosed();
    return new org.tidb.jdbc.Statement(
        this,
        lock,
        canUseServerTimeout,
        canUseServerMaxRows,
        org.tidb.jdbc.Statement.NO_GENERATED_KEYS,
        resultSetType,
        resultSetConcurrency,
        defaultFetchSize);
  }

  @Override
  public PreparedStatement prepareStatement(
      String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
      throws SQLException {
    return prepareStatement(sql, resultSetType, resultSetConcurrency);
  }

  @Override
  public CallableStatement prepareCall(
      String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
      throws SQLException {
    throw exceptionFactory().notSupported("TiDB not supported procedures");
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
    return prepareInternal(
        sql,
        autoGeneratedKeys,
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        conf.useServerPrepStmts());
  }

  @Override
  public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
    return prepareStatement(sql, org.tidb.jdbc.Statement.RETURN_GENERATED_KEYS);
  }

  @Override
  public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
    return prepareStatement(sql, org.tidb.jdbc.Statement.RETURN_GENERATED_KEYS);
  }

  @Override
  public Clob createClob() {
    return new TiDBClob();
  }

  @Override
  public Blob createBlob() {
    return new TiDBBlob();
  }

  @Override
  public NClob createNClob() {
    return new TiDBClob();
  }

  @Override
  public SQLXML createSQLXML() throws SQLException {
    throw exceptionFactory.notSupported("SQLXML type is not supported");
  }

  private void checkNotClosed() throws SQLException {
    if (client.isClosed()) {
      throw exceptionFactory.create("Connection is closed", "08000", 1220);
    }
  }

  @Override
  public boolean isValid(int timeout) throws SQLException {
    if (timeout < 0) {
      throw exceptionFactory.create("the value supplied for timeout is negative");
    }
    lock.lock();
    try {
      client.execute(PingPacket.INSTANCE, true);
      return true;
    } catch (SQLException sqle) {
      if (poolConnection != null) {
        TiDBPoolConnection poolConnection = this.poolConnection;
        poolConnection.fireConnectionErrorOccurred(sqle);
        poolConnection.close();
      }
      return false;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void setClientInfo(String name, String value) {
    clientInfo.put(name, value);
  }

  @Override
  public String getClientInfo(String name) {
    return (String) clientInfo.get(name);
  }

  @Override
  public Properties getClientInfo() {
    return clientInfo;
  }

  @Override
  public void setClientInfo(Properties properties) {
    clientInfo.putAll(properties);
  }

  @Override
  public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
    throw exceptionFactory.notSupported("Array type is not supported");
  }

  @Override
  public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
    throw exceptionFactory.notSupported("Struct type is not supported");
  }

  @Override
  public String getSchema() {
    // We support only catalog
    return null;
  }

  @Override
  public void setSchema(String schema) {
    // We support only catalog, and JDBC indicate "If the driver does not support schemas, it will
    // silently ignore this request."
  }

  @Override
  public void abort(Executor executor) throws SQLException {
    if (poolConnection != null) {
      TiDBPoolConnection poolConnection = this.poolConnection;
      poolConnection.close();
      return;
    }
    client.abort(executor);
  }

  @Override
  public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
    if (this.isClosed()) {
      throw exceptionFactory.create(
          "Connection.setNetworkTimeout cannot be called on a closed connection");
    }
    if (milliseconds < 0) {
      throw exceptionFactory.create(
          "Connection.setNetworkTimeout cannot be called with a negative timeout");
    }
    getContext().addStateFlag(ConnectionState.STATE_NETWORK_TIMEOUT);

    lock.lock();
    try {
      client.setSocketTimeout(milliseconds);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int getNetworkTimeout() {
    return client.getSocketTimeout();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (isWrapperFor(iface)) {
      return iface.cast(this);
    }
    throw new SQLException("The receiver is not a wrapper for " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return iface.isInstance(this);
  }

  /**
   * Associate connection client
   *
   * @return connection client
   */
  public Client getClient() {
    return client;
  }

  /** Internal Savepoint implementation */
  class TiDBSavepoint implements java.sql.Savepoint {

    private final String name;
    private final Integer id;

    public TiDBSavepoint(final String name) {
      this.name = name;
      this.id = null;
    }

    public TiDBSavepoint(final int savepointId) {
      this.id = savepointId;
      this.name = null;
    }

    /**
     * Retrieves the generated ID for the savepoint that this <code>Savepoint</code> object
     * represents.
     *
     * @return the numeric ID of this savepoint
     */
    public int getSavepointId() throws SQLException {
      if (name != null) {
        throw exceptionFactory.create("Cannot retrieve savepoint id of a named savepoint");
      }
      return id;
    }

    /**
     * Retrieves the name of the savepoint that this <code>Savepoint</code> object represents.
     *
     * @return the name of this savepoint
     */
    public String getSavepointName() throws SQLException {
      if (id != null) {
        throw exceptionFactory.create("Cannot retrieve savepoint name of an unnamed savepoint");
      }
      return name;
    }

    public String rawValue() {
      if (id != null) {
        return "_jid_" + id;
      }
      return name;
    }
  }

  /**
   * Reset connection set has it was after creating a "fresh" new connection.
   * defaultTransactionIsolation must have been initialized.
   *
   * <p>BUT : - session variable state are reset only if option useResetConnection is set and - if
   * using the option "useServerPrepStmts", PREPARE statement are still prepared
   *
   * @throws SQLException if resetting operation failed
   */
  public void reset() throws SQLException {
    // COM_RESET_CONNECTION support by: https://github.com/pingcap/tidb/pull/18893
    boolean useComReset =
        conf.useResetConnection()
            && getContext().getVersion().isTiDBServer()
            && (getContext().getVersion().versionGreaterOrEqual(4, 0, 5)
                || (getContext().getVersion().getMajorVersion() == 3
                    && getContext().getVersion().getMinorVersion() == 0
                    && getContext().getVersion().versionGreaterOrEqual(3, 0, 18)));

    if (useComReset) {
      client.execute(ResetPacket.INSTANCE, true);
    }

    // in transaction => rollback
    if (forceTransactionEnd
        || (client.getContext().getServerStatus() & ServerStatus.IN_TRANSACTION) > 0) {
      client.execute(new QueryPacket("ROLLBACK"), true);
    }

    int stateFlag = getContext().getStateFlag();
    if (stateFlag != 0) {
      try {
        if ((stateFlag & ConnectionState.STATE_NETWORK_TIMEOUT) != 0) {
          setNetworkTimeout(null, conf.socketTimeout());
        }
        if ((stateFlag & ConnectionState.STATE_AUTOCOMMIT) != 0) {
          setAutoCommit(conf.autocommit() == null ? true : conf.autocommit());
        }
        if ((stateFlag & ConnectionState.STATE_DATABASE) != 0) {
          setCatalog(conf.database());
        }
        if ((stateFlag & ConnectionState.STATE_READ_ONLY) != 0) {
          setReadOnly(false); // default to master connection
        }
        if (!useComReset && (stateFlag & ConnectionState.STATE_TRANSACTION_ISOLATION) != 0) {
          setTransactionIsolation(
              conf.transactionIsolation() == null
                  ? java.sql.Connection.TRANSACTION_REPEATABLE_READ
                  : conf.transactionIsolation().getLevel());
        }
      } catch (SQLException sqle) {
        throw exceptionFactory.create("error resetting connection");
      }
    }

    client.reset();

    clearWarnings();
  }

  /**
   * Current TiDB connection id.
   *
   * @return current TiDB connection id.
   */
  public String getTiDBConnectionID() {
    return client.getTiDBConnectionID();
  }

  /**
   * Fire event to indicate to StatementEventListeners registered on the connection that a
   * PreparedStatement is closed.
   *
   * @param prep prepare statement closing
   */
  public void fireStatementClosed(PreparedStatement prep) {
    if (poolConnection != null) {
      poolConnection.fireStatementClosed(prep);
    }
  }

  /**
   * Get connection exception factory
   *
   * @return connection exception factory
   */
  protected ExceptionFactory getExceptionFactory() {
    return exceptionFactory;
  }
}
