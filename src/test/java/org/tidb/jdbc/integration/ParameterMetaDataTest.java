// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2021 MariaDB Corporation Ab

package org.tidb.jdbc.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.tidb.jdbc.Statement;

public class ParameterMetaDataTest extends Common {

  @AfterAll
  public static void after2() throws SQLException {
    sharedConn.createStatement().execute("DROP TABLE IF EXISTS parameter_meta");
  }

  @BeforeAll
  public static void beforeAll2() throws SQLException {
    after2();
    Statement stmt = sharedConn.createStatement();
    stmt.execute(
        "CREATE TABLE parameter_meta ("
            + "id int not null primary key auto_increment, "
            + "test varchar(20), "
            + "t2 DECIMAL(10,3))");
  }

  @Test
  public void parameterMetaDataTypeNotAvailable() throws Exception {
    try (org.tidb.jdbc.Connection con = createCon("&useServerPrepStmts=false")) {
      parameterMetaDataTypeNotAvailable(con);
    }
    try (org.tidb.jdbc.Connection con = createCon("&useServerPrepStmts")) {
      parameterMetaDataTypeNotAvailable(con);
    }
  }

  private void parameterMetaDataTypeNotAvailable(org.tidb.jdbc.Connection con) throws SQLException {
    String query = "SELECT * FROM parameter_meta WHERE test = ? and id = ?";
    try (PreparedStatement prepStmt = con.prepareStatement(query)) {
      ParameterMetaData parameterMetaData = prepStmt.getParameterMetaData();
      assertEquals(2, parameterMetaData.getParameterCount());
      assertThrowsContains(
          SQLException.class,
          () -> parameterMetaData.getParameterType(1),
          "Getting parameter type metadata are not supported");
      assertThrowsContains(
          SQLException.class, () -> parameterMetaData.isNullable(-1), "Wrong index position");
      assertThrowsContains(
          SQLException.class, () -> parameterMetaData.isNullable(3), "Wrong index position");
    }
  }

  @Test
  public void parameterMetaDataNotPreparable() throws Exception {
    try (org.tidb.jdbc.Connection con = createCon("&useServerPrepStmts=false")) {
      // statement that cannot be prepared
      try (PreparedStatement pstmt =
          con.prepareStatement("select  TMP.field1 from (select ? from dual) TMP")) {
        ParameterMetaData meta = pstmt.getParameterMetaData();
        assertEquals(1, meta.getParameterCount());
        assertEquals(ParameterMetaData.parameterModeIn, meta.getParameterMode(1));
        assertThrowsContains(
            SQLSyntaxErrorException.class,
            () -> meta.getParameterTypeName(1),
            "Unknown parameter metadata type name");
        assertThrowsContains(
            SQLFeatureNotSupportedException.class,
            () -> meta.getParameterClassName(1),
            "Unknown parameter metadata class name");
        assertThrowsContains(
            SQLFeatureNotSupportedException.class,
            () -> meta.getParameterType(1),
            "Getting parameter type metadata is not supported");
        assertThrowsContains(
            SQLSyntaxErrorException.class,
            () -> meta.getPrecision(1),
            "Unknown parameter metadata precision");
        assertThrowsContains(
            SQLSyntaxErrorException.class,
            () -> meta.getScale(1),
            "Unknown parameter metadata scale");

        assertThrowsContains(
            SQLSyntaxErrorException.class,
            () -> meta.getParameterMode(0),
            "Wrong index position. Is 0 but must be in 1-1 range");
        assertThrowsContains(
            SQLSyntaxErrorException.class,
            () -> meta.getParameterTypeName(0),
            "Wrong index position. Is 0 but must be in 1-1 range");
        assertThrowsContains(
            SQLSyntaxErrorException.class,
            () -> meta.getParameterMode(0),
            "Wrong index position. Is 0 but must be in 1-1 range");
        assertThrowsContains(
            SQLSyntaxErrorException.class,
            () -> meta.getParameterClassName(0),
            "Wrong index position. Is 0 but must be in 1-1 range");
        assertThrowsContains(
            SQLSyntaxErrorException.class,
            () -> meta.getParameterType(2),
            "Wrong index position. Is 2 but must be in 1-1 range");
        assertThrowsContains(
            SQLSyntaxErrorException.class,
            () -> meta.getPrecision(0),
            "Wrong index position. Is 0 but must be in 1-1 range");
        assertThrowsContains(
            SQLSyntaxErrorException.class,
            () -> meta.getScale(0),
            "Wrong index position. Is 0 but must be in 1-1 range");
      }
    }
    try (org.tidb.jdbc.Connection con = createCon("&useServerPrepStmts")) {
      // statement that cannot be prepared
      try (PreparedStatement pstmt =
          con.prepareStatement("select  TMP.field1 from (select ? from dual) TMP")) {
        try {
          pstmt.getParameterMetaData();
          fail();
        } catch (SQLSyntaxErrorException e) {
          // eat
        }
      } catch (SQLSyntaxErrorException e) {
        // eat
      }
    }
  }

  // Parameter type are not sent by TiDB.
  // It's MariaDB specific capabilities, you can see: org.tidb.jdbc.util.constants.Capabilities
  // @Test
  public void parameterMetaDataBasic() throws SQLException {
    String query = "SELECT * FROM parameter_meta WHERE test = ? and id = ? and t2 = ?";

    try (PreparedStatement prepStmt = sharedConnBinary.prepareStatement(query)) {
      prepStmt.setString(1, "");
      prepStmt.setInt(2, 1);
      prepStmt.setInt(3, 1);
      prepStmt.executeQuery();
      ParameterMetaData meta = prepStmt.getParameterMetaData();
      assertEquals(3, meta.getParameterCount());
      assertEquals(16383, meta.getPrecision(1));
      assertEquals(31, meta.getScale(1));

      assertTrue(meta.isSigned(1));
      assertEquals(ParameterMetaData.parameterNullable, meta.isNullable(1));
      assertEquals(ParameterMetaData.parameterModeIn, meta.getParameterMode(1));

      assertThrowsContains(
          SQLFeatureNotSupportedException.class,
          () -> meta.getParameterType(1),
          "Getting parameter type " + "metadata are not supported");
      assertThrowsContains(
          SQLFeatureNotSupportedException.class,
          () -> meta.getParameterClassName(1),
          "Unknown parameter metadata class name");

      assertThrowsContains(
          SQLException.class,
          () -> meta.getScale(20),
          "Wrong index position. Is 20 but must be in 1-3 range");
      assertThrowsContains(
          SQLException.class,
          () -> meta.getPrecision(20),
          "Wrong index position. Is 20 but must be in 1-3 range");
      assertThrowsContains(
          SQLException.class,
          () -> meta.isSigned(20),
          "Wrong index position. Is 20 but must be in 1-3 range");
      assertThrowsContains(
          SQLException.class,
          () -> meta.isNullable(20),
          "Wrong index position. Is 20 but must be in 1-3 range");
      assertThrowsContains(
          SQLException.class,
          () -> meta.getParameterTypeName(20),
          "Wrong index position. Is 20 but must be in 1-3 range");
      assertThrowsContains(
          SQLException.class,
          () -> meta.getParameterMode(20),
          "Wrong index position. Is 20 but must be in 1-3 range");

      meta.unwrap(java.sql.ParameterMetaData.class);
      assertThrowsContains(
          SQLException.class,
          () -> meta.unwrap(String.class),
          "The receiver is not a wrapper for java.lang.String");
    }
  }
}
