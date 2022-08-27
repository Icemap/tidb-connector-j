// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2021 MariaDB Corporation Ab

package org.tidb.jdbc;

import static org.tidb.jdbc.util.constants.Capabilities.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.tidb.jdbc.client.Completion;
import org.tidb.jdbc.client.result.CompleteResult;
import org.tidb.jdbc.client.result.Result;
import org.tidb.jdbc.client.util.Parameters;
import org.tidb.jdbc.export.ExceptionFactory;
import org.tidb.jdbc.message.ClientMessage;
import org.tidb.jdbc.message.client.BulkExecutePacket;
import org.tidb.jdbc.message.client.ExecutePacket;
import org.tidb.jdbc.message.client.PreparePacket;
import org.tidb.jdbc.message.client.QueryPacket;
import org.tidb.jdbc.message.server.ColumnDefinitionPacket;
import org.tidb.jdbc.message.server.OkPacket;
import org.tidb.jdbc.message.server.PrepareResultPacket;
import org.tidb.jdbc.util.ParameterList;

/**
 * Server prepare statement. command will generate COM_STMT_PREPARE + COM_STMT_EXECUTE (+
 * COM_STMT_CLOSE)
 */
public class ServerPreparedStatement extends BasePreparedStatement {
  private static final Pattern PREPARABLE_STATEMENT_PATTERN =
      Pattern.compile(
          "^(\\s*/\\*([^*]|\\*[^/])*\\*/)*\\s*(SELECT|UPDATE|INSERT|DELETE|REPLACE|DO|CALL)",
          Pattern.CASE_INSENSITIVE);

  /**
   * Server prepare statement constructor
   *
   * @param sql command
   * @param con connection
   * @param lock thread safe lock
   * @param canUseServerTimeout can server use timeout
   * @param canUseServerMaxRows can server use max rows
   * @param autoGeneratedKeys must command return automatically generated keys
   * @param resultSetType resultset type
   * @param resultSetConcurrency resultset concurrency
   * @param defaultFetchSize default fetch size
   * @throws SQLException if prepare fails
   */
  public ServerPreparedStatement(
      String sql,
      Connection con,
      ReentrantLock lock,
      boolean canUseServerTimeout,
      boolean canUseServerMaxRows,
      int autoGeneratedKeys,
      int resultSetType,
      int resultSetConcurrency,
      int defaultFetchSize)
      throws SQLException {
    super(
        sql,
        con,
        lock,
        canUseServerTimeout,
        canUseServerMaxRows,
        autoGeneratedKeys,
        resultSetType,
        resultSetConcurrency,
        defaultFetchSize);
    prepareResult = con.getContext().getPrepareCache().get(sql, this);
    if (prepareResult == null && !PREPARABLE_STATEMENT_PATTERN.matcher(sql).find()) {
      con.getClient().execute(new PreparePacket(sql), this, true);
    }
    parameters = new ParameterList();
  }

  /**
   * Execute command with parameters
   *
   * @throws SQLException if any error occurs
   */
  protected void executeInternal() throws SQLException {
    checkNotClosed();
    validParameters();
    lock.lock();
    String cmd = escapeTimeout(sql);
    if (prepareResult == null) prepareResult = con.getContext().getPrepareCache().get(cmd, this);
    try {
      if (prepareResult == null && con.getContext().hasClientCapability(STMT_BULK_OPERATIONS)) {
        try {
          executePipeline(cmd);
        } catch (BatchUpdateException b) {
          throw (SQLException) b.getCause();
        }
      } else {
        executeStandard(cmd);
      }
    } finally {
      localInfileInputStream = null;
      lock.unlock();
    }
  }

  /**
   * Send COM_STMT_PREPARE + COM_STMT_EXECUTE, then read for the 2 answers
   *
   * @param cmd command
   * @throws SQLException if IOException / Command error
   */
  private void executePipeline(String cmd) throws SQLException {
    // server is 10.2+, permitting to execute last prepare with (-1) statement id.
    // Server send prepare, followed by execute, in one exchange.
    QueryPacket set = new QueryPacket(setTimeoutAndSelectLimit());
    PreparePacket prepare = new PreparePacket(cmd);
    ExecutePacket execute = new ExecutePacket(null, parameters, cmd, this, localInfileInputStream);
    try {
      List<Completion> res =
          con.getClient()
              .executePipelineAndRemoveFirstResult(
                  new ClientMessage[] {set, prepare, execute},
                  this,
                  fetchSize,
                  maxRows,
                  resultSetConcurrency,
                  resultSetType,
                  closeOnCompletion,
                  false);
    } catch (SQLException ex) {
      results = null;
      throw ex;
    }
  }

  private void executeStandard(String cmd) throws SQLException {
    // send COM_STMT_PREPARE
    if (prepareResult == null) {
      prepareResult = con.getContext().getPrepareCache().get(cmd, this);
      if (prepareResult == null) {
        ClientMessage[] packets =
            new ClientMessage[] {
              new QueryPacket(setTimeoutAndSelectLimit()), new PreparePacket(cmd)
            };

        con.getClient()
            .executePipelineAndRemoveFirstResult(
                packets,
                this,
                0,
                0L,
                ResultSet.CONCUR_READ_ONLY,
                ResultSet.TYPE_FORWARD_ONLY,
                false,
                true);
      }
    }
    validParameters();
    // send COM_STMT_EXECUTE

    QueryPacket set = new QueryPacket(setTimeoutAndSelectLimit());
    ExecutePacket execute =
        new ExecutePacket(prepareResult, parameters, cmd, this, localInfileInputStream);
    ClientMessage[] packets = new ClientMessage[] {set, execute};

    results =
        con.getClient()
            .executePipelineAndRemoveFirstResult(
                packets,
                this,
                fetchSize,
                maxRows,
                resultSetConcurrency,
                resultSetType,
                closeOnCompletion,
                false);
  }

  private void executeInternalPreparedBatch() throws SQLException {
    checkNotClosed();
    String cmd = escapeTimeout(sql);
    if (batchParameters.size() > 1 && con.getContext().hasServerCapability(STMT_BULK_OPERATIONS)) {

      // ensure pipelining is possible (no LOAD DATA/XML INFILE commands)
      boolean possibleLoadLocal = con.getContext().hasClientCapability(LOCAL_FILES);
      if (possibleLoadLocal) {
        String sqlUpper = sql.toUpperCase(Locale.ROOT);
        possibleLoadLocal =
            sqlUpper.contains(" LOCAL ")
                && sqlUpper.contains("LOAD")
                && sqlUpper.contains(" INFILE");
      }

      if (!possibleLoadLocal) {
        if (con.getContext().getConf().useBulkStmts()
            && autoGeneratedKeys != RETURN_GENERATED_KEYS) {
          executeBatchBulk(cmd);
        } else {
          executeBatchPipeline(cmd);
        }
        return;
      }
    }
    executeBatchStandard(cmd);
  }

  /**
   * Send COM_STMT_PREPARE + X * COM_STMT_BULK_EXECUTE, then read for the all answers
   *
   * @param cmd command
   * @throws SQLException if IOException / Command error
   */
  private void executeBatchBulk(String cmd) throws SQLException {
    List<Completion> res;
    if (prepareResult == null) prepareResult = con.getContext().getPrepareCache().get(cmd, this);
    try {
      if (prepareResult == null) {
        ClientMessage[] packets;
        packets =
            new ClientMessage[] {
              new QueryPacket(setTimeoutAndSelectLimit()),
              new PreparePacket(cmd),
              new BulkExecutePacket(null, batchParameters, cmd, this)
            };
        res =
            con.getClient()
                .executePipelineAndRemoveFirstResult(
                    packets,
                    this,
                    0,
                    maxRows,
                    ResultSet.CONCUR_READ_ONLY,
                    ResultSet.TYPE_FORWARD_ONLY,
                    closeOnCompletion,
                    false);

        // in case of failover, prepare is done in failover, skipping prepare result
        if (res.get(0) instanceof PrepareResultPacket) {
          results = res.subList(1, res.size());
        } else {
          results = res;
        }
      } else {
        ClientMessage[] packets =
            new ClientMessage[] {
              new QueryPacket(setTimeoutAndSelectLimit()),
              new BulkExecutePacket(prepareResult, batchParameters, cmd, this)
            };

        results =
            con.getClient()
                .executePipelineAndRemoveFirstResult(
                    packets,
                    this,
                    0,
                    maxRows,
                    ResultSet.CONCUR_READ_ONLY,
                    ResultSet.TYPE_FORWARD_ONLY,
                    closeOnCompletion,
                    false);
      }

    } catch (SQLException bue) {
      results = null;
      throw exceptionFactory()
          .createBatchUpdate(Collections.emptyList(), batchParameters.size(), bue);
    }
  }

  /**
   * Send COM_STMT_PREPARE + X * COM_STMT_EXECUTE, then read for the all answers
   *
   * @param cmd command
   * @throws SQLException if Command error
   */
  private void executeBatchPipeline(String cmd) throws SQLException {
    if (prepareResult == null) prepareResult = con.getContext().getPrepareCache().get(cmd, this);
    // server is 10.2+, permitting to execute last prepare with (-1) statement id.
    // Server send prepare, followed by execute, in one exchange.
    int maxCmd = 250;
    List<Completion> res = new ArrayList<>();
    try {
      int index = 0;
      if (prepareResult == null) {
        res.addAll(executeBunchPrepare(cmd, index, maxCmd));
        index += maxCmd;
      }
      while (index < batchParameters.size()) {
        res.addAll(executeBunch(cmd, index, maxCmd));
        index += maxCmd;
      }
      results = res;

    } catch (SQLException bue) {
      results = null;
      throw exceptionFactory().createBatchUpdate(res, batchParameters.size(), bue);
    }
  }

  private List<Completion> executeBunch(String cmd, int index, int maxCmd) throws SQLException {
    int maxCmdToSend = Math.min(batchParameters.size() - index, maxCmd);
    ClientMessage[] packets = new ClientMessage[maxCmdToSend + 1];
    packets[0] = new QueryPacket(setTimeoutAndSelectLimit());
    for (int i = index; i < index + maxCmdToSend; i++) {
      packets[i - index + 1] =
          new ExecutePacket(
              prepareResult, batchParameters.get(i), cmd, this, localInfileInputStream);
    }
    return con.getClient()
        .executePipelineAndRemoveFirstResult(
            packets,
            this,
            0,
            maxRows,
            ResultSet.CONCUR_READ_ONLY,
            ResultSet.TYPE_FORWARD_ONLY,
            closeOnCompletion,
            false);
  }

  private List<Completion> executeBunchPrepare(String cmd, int index, int maxCmd)
      throws SQLException {
    int maxCmdToSend = Math.min(batchParameters.size() - index, maxCmd);
    ClientMessage[] packets = new ClientMessage[maxCmdToSend + 2];
    packets[0] = new QueryPacket(setTimeoutAndSelectLimit());
    packets[1] = new PreparePacket(cmd);
    for (int i = index; i < index + maxCmdToSend; i++) {
      packets[i + 2 - index] =
          new ExecutePacket(null, batchParameters.get(i), cmd, this, localInfileInputStream);
    }
    List<Completion> res =
        con.getClient()
            .executePipelineAndRemoveFirstResult(
                packets,
                this,
                0,
                maxRows,
                ResultSet.CONCUR_READ_ONLY,
                ResultSet.TYPE_FORWARD_ONLY,
                closeOnCompletion,
                false);
    // in case of failover, prepare is done in failover, skipping prepare result
    if (res.get(0) instanceof PrepareResultPacket) {
      return res.subList(1, res.size());
    } else {
      return res;
    }
  }

  /**
   * Send COM_STMT_PREPARE + read answer, then Send a COM_STMT_EXECUTE + read answer * n time
   *
   * @param cmd command
   * @throws SQLException if IOException / Command error
   */
  private void executeBatchStandard(String cmd) throws SQLException {
    // send COM_STMT_PREPARE
    List<Completion> tmpResults = new ArrayList<>();
    SQLException error = null;
    for (Parameters batchParameter : batchParameters) {
      // prepare is in loop, because if connection fail, prepare is reset, and need to be re
      // prepared
      if (prepareResult == null) {
        prepareResult = con.getContext().getPrepareCache().get(cmd, this);
        if (prepareResult == null) {
          ClientMessage[] packets =
              new ClientMessage[] {
                new QueryPacket(setTimeoutAndSelectLimit()), new PreparePacket(cmd)
              };
          con.getClient()
              .executePipeline(
                  packets,
                  this,
                  0,
                  0L,
                  ResultSet.CONCUR_READ_ONLY,
                  ResultSet.TYPE_FORWARD_ONLY,
                  false,
                  false);
        }
      }
      try {
        ClientMessage[] packets =
            new ClientMessage[] {
              new QueryPacket(setTimeoutAndSelectLimit()),
              new ExecutePacket(prepareResult, batchParameter, cmd, this, localInfileInputStream)
            };
        tmpResults.addAll(
            con.getClient()
                .executePipelineAndRemoveFirstResult(
                    packets,
                    this,
                    0,
                    0L,
                    ResultSet.CONCUR_READ_ONLY,
                    ResultSet.TYPE_FORWARD_ONLY,
                    false,
                    false));
      } catch (SQLException e) {
        if (error == null) error = e;
      }
    }

    if (error != null) {
      throw exceptionFactory().createBatchUpdate(tmpResults, batchParameters.size(), error);
    }
    this.results = tmpResults;
  }

  /**
   * Executes the SQL statement in this <code>PreparedStatement</code> object, which may be any kind
   * of SQL statement. Some prepared statements return multiple results; the <code>execute</code>
   * method handles these complex statements as well as the simpler form of statements handled by
   * the methods <code>executeQuery</code> and <code>executeUpdate</code>.
   *
   * <p>The <code>execute</code> method returns a <code>boolean</code> to indicate the form of the
   * first result. You must call either the method <code>getResultSet</code> or <code>getUpdateCount
   * </code> to retrieve the result; you must call <code>getMoreResults</code> to move to any
   * subsequent result(s).
   *
   * @return <code>true</code> if the first result is a <code>ResultSet</code> object; <code>false
   *     </code> if the first result is an update count or there is no result
   * @throws SQLException if a database access error occurs; this method is called on a closed
   *     <code>PreparedStatement</code> or an argument is supplied to this method
   * @throws SQLTimeoutException when the driver has determined that the timeout value that was
   *     specified by the {@code setQueryTimeout} method has been exceeded and has at least
   *     attempted to cancel the currently running {@code Statement}
   * @see Statement#execute
   * @see Statement#getResultSet
   * @see Statement#getUpdateCount
   * @see Statement#getMoreResults
   */
  @Override
  public boolean execute() throws SQLException {
    executeInternal();
    handleParameterOutput();
    if (results.size() > 0) {
      currResult = results.remove(0);
      return currResult instanceof Result;
    }
    return false;
  }

  @Override
  public void setMaxRows(int max) throws SQLException {
    super.setMaxRows(max);
    if (canUseServerMaxRows && prepareResult != null) {
      prepareResult.decrementUse(con.getClient(), this);
      prepareResult = null;
    }
  }

  @Override
  public void setLargeMaxRows(long max) throws SQLException {
    super.setLargeMaxRows(max);
    if (canUseServerMaxRows && prepareResult != null) {
      prepareResult.decrementUse(con.getClient(), this);
      prepareResult = null;
    }
  }

  @Override
  public void setQueryTimeout(int seconds) throws SQLException {
    super.setQueryTimeout(seconds);
    if (canUseServerTimeout && prepareResult != null) {
      prepareResult.decrementUse(con.getClient(), this);
      prepareResult = null;
    }
  }

  /**
   * Executes the SQL query in this <code>PreparedStatement</code> object and returns the <code>
   * ResultSet</code> object generated by the query.
   *
   * @return a <code>ResultSet</code> object that contains the data produced by the query; never
   *     <code>null</code>
   * @throws SQLException if a database access error occurs; this method is called on a closed
   *     <code>PreparedStatement</code> or the SQL statement does not return a <code>ResultSet
   *     </code> object
   * @throws SQLTimeoutException when the driver has determined that the timeout value that was
   *     specified by the {@code setQueryTimeout} method has been exceeded and has at least
   *     attempted to cancel the currently running {@code Statement}
   */
  @Override
  public ResultSet executeQuery() throws SQLException {
    executeInternal();
    handleParameterOutput();
    if (results.size() > 0) {
      currResult = results.remove(0);
      if (currResult instanceof Result) return (Result) currResult;
    }
    return new CompleteResult(new ColumnDefinitionPacket[0], new byte[0][], con.getContext());
  }

  /**
   * Executes the SQL statement in this <code>PreparedStatement</code> object, which must be an SQL
   * Data Manipulation Language (DML) statement, such as <code>INSERT</code>, <code>UPDATE</code> or
   * <code>DELETE</code>; or an SQL statement that returns nothing, such as a DDL statement.
   *
   * @return either (1) the row count for SQL Data Manipulation Language (DML) statements or (2) 0
   *     for SQL statements that return nothing
   * @throws SQLException if a database access error occurs; this method is called on a closed
   *     <code>PreparedStatement</code> or the SQL statement returns a <code>ResultSet</code> object
   * @throws SQLTimeoutException when the driver has determined that the timeout value that was
   *     specified by the {@code setQueryTimeout} method has been exceeded and has at least
   *     attempted to cancel the currently running {@code Statement}
   */
  @Override
  public int executeUpdate() throws SQLException {
    return (int) executeLargeUpdate();
  }

  /**
   * Executes the SQL statement in this <code>PreparedStatement</code> object, which must be an SQL
   * Data Manipulation Language (DML) statement, such as <code>INSERT</code>, <code>UPDATE</code> or
   * <code>DELETE</code>; or an SQL statement that returns nothing, such as a DDL statement.
   *
   * <p>This method should be used when the returned row count may exceed {@link Integer#MAX_VALUE}.
   *
   * <p>The default implementation will throw {@code UnsupportedOperationException}
   *
   * @return either (1) the row count for SQL Data Manipulation Language (DML) statements or (2) 0
   *     for SQL statements that return nothing
   * @throws SQLException if a database access error occurs; this method is called on a closed
   *     <code>PreparedStatement</code> or the SQL statement returns a <code>ResultSet</code> object
   * @throws SQLTimeoutException when the driver has determined that the timeout value that was
   *     specified by the {@code setQueryTimeout} method has been exceeded and has at least
   *     attempted to cancel the currently running {@code Statement}
   * @since 1.8
   */
  @Override
  public long executeLargeUpdate() throws SQLException {
    executeInternal();
    handleParameterOutput();
    currResult = results.remove(0);
    if (currResult instanceof Result) {
      throw exceptionFactory()
          .create("the given SQL statement produces an unexpected ResultSet object", "HY000");
    }
    return ((OkPacket) currResult).getAffectedRows();
  }

  /**
   * Handle output parameter result-set (only for CallableStatement)
   *
   * @throws SQLException if any error occurs
   */
  protected void handleParameterOutput() throws SQLException {}

  /**
   * Adds a set of parameters to this <code>PreparedStatement</code> object's batch of commands.
   *
   * @throws SQLException if a database access error occurs or this method is called on a closed
   *     <code>PreparedStatement</code>
   * @see Statement#addBatch
   * @since 1.2
   */
  @Override
  public void addBatch() throws SQLException {
    validParameters();
    if (batchParameters == null) batchParameters = new ArrayList<>();
    batchParameters.add(parameters);
    parameters = parameters.clone();
  }

  /**
   * Validated that all parameters have been set.
   *
   * @throws SQLException if number of parameters doesn't correspond to expected number
   */
  protected void validParameters() throws SQLException {
    if (prepareResult != null) {
      for (int i = 0; i < prepareResult.getParameters().length; i++) {
        if (!parameters.containsKey(i)) {
          throw exceptionFactory()
              .create("Parameter at position " + (i + 1) + " is not set", "07004");
        }
      }
    } else {

      if (batchParameters != null
          && !batchParameters.isEmpty()
          && parameters.size() < batchParameters.get(0).size()) {
        // ensure batch parameters set same number
        throw exceptionFactory()
            .create(
                "batch set of parameters differ from previous set. All parameters must be set",
                "07004");
      }

      // ensure all parameters are set
      for (int i = 0; i < parameters.size(); i++) {
        if (!parameters.containsKey(i)) {
          throw exceptionFactory()
              .create("Parameter at position " + (i + 1) + " is not set", "07004");
        }
      }
    }
  }

  /**
   * Retrieves a <code>ResultSetMetaData</code> object that contains information about the columns
   * of the <code>ResultSet</code> object that will be returned when this <code>PreparedStatement
   * </code> object is executed.
   *
   * <p>Because a <code>PreparedStatement</code> object is precompiled, it is possible to know about
   * the <code>ResultSet</code> object that it will return without having to execute it.
   * Consequently, it is possible to invoke the method <code>getMetaData</code> on a <code>
   * PreparedStatement</code> object rather than waiting to execute it and then invoking the <code>
   * ResultSet.getMetaData</code> method on the <code>ResultSet</code> object that is returned.
   *
   * <p><B>NOTE:</B> Using this method may be expensive for some drivers due to the lack of
   * underlying DBMS support.
   *
   * @return the description of a <code>ResultSet</code> object's columns or <code>null</code> if
   *     the driver cannot return a <code>ResultSetMetaData</code> object
   * @throws SQLException if a database access error occurs or this method is called on a closed
   *     <code>PreparedStatement</code>
   * @throws SQLFeatureNotSupportedException if the JDBC driver does not support this method
   * @since 1.2
   */
  @Override
  public ResultSetMetaData getMetaData() throws SQLException {

    // send COM_STMT_PREPARE
    if (prepareResult == null) {
      con.getClient().execute(new PreparePacket(escapeTimeout(sql)), this, true);
    }

    return new org.tidb.jdbc.client.result.ResultSetMetaData(
        exceptionFactory(), prepareResult.getColumns(), con.getContext().getConf(), false);
  }

  /**
   * Retrieves the number, types and properties of this <code>PreparedStatement</code> object's
   * parameters.
   *
   * @return a <code>ParameterMetaData</code> object that contains information about the number,
   *     types and properties for each parameter marker of this <code>PreparedStatement</code>
   *     object
   * @throws SQLException if a database access error occurs or this method is called on a closed
   *     <code>PreparedStatement</code>
   * @see org.tidb.jdbc.ParameterMetaData
   * @since 1.4
   */
  @Override
  public java.sql.ParameterMetaData getParameterMetaData() throws SQLException {
    // send COM_STMT_PREPARE
    if (prepareResult == null) {
      con.getClient().execute(new PreparePacket(escapeTimeout(sql)), this, true);
    }

    return new org.tidb.jdbc.ParameterMetaData(exceptionFactory(), prepareResult.getParameters());
  }

  @Override
  public int[] executeBatch() throws SQLException {
    checkNotClosed();
    if (batchParameters == null || batchParameters.isEmpty()) return new int[0];
    lock.lock();
    try {
      executeInternalPreparedBatch();

      int[] updates = new int[batchParameters.size()];
      if (results.size() != updates.length) {
        for (int i = 0; i < updates.length; i++) {
          updates[i] = SUCCESS_NO_INFO;
        }
      } else {
        for (int i = 0; i < updates.length; i++) {
          if (results.get(i) instanceof OkPacket) {
            updates[i] = (int) ((OkPacket) results.get(i)).getAffectedRows();
          } else {
            updates[i] = Statement.SUCCESS_NO_INFO;
          }
        }
      }
      currResult = results.remove(0);
      return updates;

    } finally {
      localInfileInputStream = null;
      batchParameters.clear();
      lock.unlock();
    }
  }

  @Override
  public long[] executeLargeBatch() throws SQLException {
    checkNotClosed();
    if (batchParameters == null || batchParameters.isEmpty()) return new long[0];
    lock.lock();
    try {
      executeInternalPreparedBatch();

      long[] updates = new long[batchParameters.size()];
      if (results.size() != updates.length) {
        for (int i = 0; i < updates.length; i++) {
          updates[i] = SUCCESS_NO_INFO;
        }
      } else {
        for (int i = 0; i < updates.length; i++) {
          if (results.get(i) instanceof OkPacket) {
            updates[i] = ((OkPacket) results.get(i)).getAffectedRows();
          } else {
            updates[i] = Statement.SUCCESS_NO_INFO;
          }
        }
      }

      currResult = results.remove(0);
      return updates;

    } finally {
      batchParameters.clear();
      lock.unlock();
    }
  }

  private ExceptionFactory exceptionFactory() {
    return con.getExceptionFactory().of(this);
  }

  @Override
  public void close() throws SQLException {
    if (prepareResult != null) {
      prepareResult.decrementUse(con.getClient(), this);
      prepareResult = null;
    }
    con.fireStatementClosed(this);
    super.close();
  }

  /**
   * reset prepare statement in case of a failover. (Command need then to be re-prepared on server)
   */
  public void reset() {
    lock.lock();
    try {
      prepareResult = null;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public String toString() {
    return "ServerPreparedStatement{" + super.toString() + '}';
  }
}