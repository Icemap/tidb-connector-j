// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2021 MariaDB Corporation Ab

package org.tidb.jdbc.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.sql.*;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.tidb.jdbc.Configuration;
import org.tidb.jdbc.HostAddress;
import org.tidb.jdbc.TiDBDataSource;
import org.tidb.jdbc.TiDBPoolDataSource;
import org.tidb.jdbc.export.SslMode;
import org.tidb.jdbc.integration.tools.TcpProxy;
import org.tidb.jdbc.pool.TiDBInnerPoolConnection;

public class PooledConnectionTest extends Common {

  @Test
  public void testPooledConnectionClosed() throws Exception {
    ConnectionPoolDataSource ds = new TiDBDataSource(mDefUrl);
    PooledConnection pc = ds.getPooledConnection();
    Connection connection = pc.getConnection();
    MyEventListener listener = new MyEventListener();
    pc.addConnectionEventListener(listener);
    pc.addStatementEventListener(listener);
    pc.close();
    assertTrue(listener.closed);
    assertThrows(SQLException.class, () -> connection.createStatement().execute("select 1"));
    pc.removeConnectionEventListener(listener);
    pc.removeStatementEventListener(listener);
  }

  @Test
  public void testPoolWait() throws Exception {
    try (TiDBPoolDataSource ds =
        new TiDBPoolDataSource(
            mDefUrl + "&sessionVariables=wait_timeout=1&maxIdleTime=2&testMinRemovalDelay=2")) {
      Thread.sleep(4000);
      PooledConnection pc = ds.getPooledConnection();
      pc.getConnection().isValid(1);
      pc.close();
    }
  }

  @Test
  public void testPoolWaitWithValidation() throws Exception {
    try (TiDBPoolDataSource ds = new TiDBPoolDataSource(mDefUrl + "&poolValidMinDelay=1")) {
      Thread.sleep(100);
      PooledConnection pc = ds.getPooledConnection();
      pc.getConnection().isValid(1);
      pc.close();
    }
  }

  @Test
  public void testPoolFailover() throws Exception {
    Assumptions.assumeTrue(
        !"skysql".equals(System.getenv("srv")) && !"skysql-ha".equals(System.getenv("srv")));

    Configuration conf = Configuration.parse(mDefUrl);
    HostAddress hostAddress = conf.addresses().get(0);
    try {
      proxy = new TcpProxy(hostAddress.host, hostAddress.port);
    } catch (IOException i) {
      throw new SQLException("proxy error", i);
    }

    String url = mDefUrl.replaceAll("//([^/]*)/", "//localhost:" + proxy.getLocalPort() + "/");
    if (conf.sslMode() == SslMode.VERIFY_FULL) {
      url = url.replaceAll("sslMode=verify-full", "sslMode=verify-ca");
    }

    try (TiDBPoolDataSource ds =
        new TiDBPoolDataSource(url + "&poolValidMinDelay=1&connectTimeout=10&maxPoolSize=1")) {

      PooledConnection pc = ds.getPooledConnection();
      pc.getConnection().isValid(1);
      pc.close();
      Thread.sleep(200);
      proxy.stop();
      assertThrowsContains(
          SQLException.class,
          ds::getPooledConnection,
          "No connection available within the specified time");
    }
  }

  @Test
  public void testPoolKillConnection() throws Exception {
    Assumptions.assumeTrue(
        !"maxscale".equals(System.getenv("srv"))
            && !"skysql".equals(System.getenv("srv"))
            && !"skysql-ha".equals(System.getenv("srv")));

    try (TiDBPoolDataSource ds =
        new TiDBPoolDataSource(mDefUrl + "&maxPoolSize=1&allowPublicKeyRetrieval")) {
      Thread.sleep(100);
      TiDBInnerPoolConnection pc = (TiDBInnerPoolConnection) ds.getPooledConnection();
      org.tidb.jdbc.Connection conn = pc.getConnection();
      String threadId = conn.getTiDBConnectionID();

      conn.getClient().forceClose();
      pc.close();
      pc = (TiDBInnerPoolConnection) ds.getPooledConnection();
      conn = pc.getConnection();
      assertTrue(conn.isClosed());
      pc.close();
    }
  }

  @Test
  public void testPooledConnectionException() throws Exception {
    Assumptions.assumeTrue(
        !"skysql".equals(System.getenv("srv")) && !"skysql-ha".equals(System.getenv("srv")));

    ConnectionPoolDataSource ds = new TiDBDataSource(mDefUrl);
    PooledConnection pc = null;
    try {
      pc = ds.getPooledConnection();
      MyEventListener listener = new MyEventListener();
      pc.addConnectionEventListener(listener);
      Connection connection = pc.getConnection();

      /* Ask server to abort the connection */
      ((org.tidb.jdbc.Connection) connection).getClient().forceClose();

      /* Try to read  after server side closed the connection */
      assertThrows(SQLException.class, () -> connection.createStatement().execute("SELECT 1"));
    } finally {
      if (pc != null) {
        pc.close();
      }
    }
  }

  @Test
  public void testPooledConnectionStatementError() throws Exception {
    Assumptions.assumeTrue(
        !"maxscale".equals(System.getenv("srv")) && !"skysql-ha".equals(System.getenv("srv")));
    Statement stmt = sharedConn.createStatement();
    try {
      stmt.execute("DROP USER 'dsUser'");
    } catch (SQLException e) {
      // eat
    }

    stmt.execute("CREATE USER 'dsUser'@'%' IDENTIFIED BY 'MySup8%rPassw@ord'");
    stmt.execute("GRANT SELECT ON " + sharedConn.getCatalog() + ".* TO 'dsUser'@'%'");
    stmt.execute("FLUSH PRIVILEGES");

    ConnectionPoolDataSource ds = new TiDBDataSource(mDefUrl);
    PooledConnection pc = ds.getPooledConnection("dsUser", "MySup8%rPassw@ord");
    MyEventListener listener = new MyEventListener();
    pc.addStatementEventListener(listener);
    Connection connection = pc.getConnection();
    try (PreparedStatement ps = connection.prepareStatement("SELECT ?")) {
      ps.execute();
      fail("should never get there");
    } catch (Exception e) {
      assertTrue(listener.statementErrorOccurred);
    }
    assertTrue(listener.statementClosed);
    pc.close();
  }

  public static class MyEventListener implements ConnectionEventListener, StatementEventListener {

    public SQLException sqlException;
    public boolean closed;
    public boolean connectionErrorOccurred;
    public boolean statementClosed;
    public boolean statementErrorOccurred;

    /** MyEventListener initialisation. */
    public MyEventListener() {
      sqlException = null;
      closed = false;
      connectionErrorOccurred = false;
    }

    public void connectionClosed(ConnectionEvent event) {
      sqlException = event.getSQLException();
      closed = true;
    }

    public void connectionErrorOccurred(ConnectionEvent event) {
      sqlException = event.getSQLException();
      connectionErrorOccurred = true;
    }

    public void statementClosed(StatementEvent event) {
      statementClosed = true;
    }

    public void statementErrorOccurred(StatementEvent event) {
      sqlException = event.getSQLException();
      statementErrorOccurred = true;
    }
  }
}
