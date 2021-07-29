// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2021 MariaDB Corporation Ab

package org.mariadb.jdbc.integration;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.mariadb.jdbc.Common;

public class Sha256AuthenticationTest extends Common {

  private static String rsaPublicKey;
  private static final boolean isWindows =
      System.getProperty("os.name").toLowerCase().contains("win");

  @AfterAll
  public static void drop() throws SQLException {
    if (sharedConn != null) {
      org.mariadb.jdbc.Statement stmt = sharedConn.createStatement();
      stmt.execute("DROP USER IF EXISTS 'cachingSha256User'@'%'");
      stmt.execute("DROP USER IF EXISTS 'cachingSha256User2'@'%'");
      stmt.execute("DROP USER IF EXISTS 'cachingSha256User3'@'%'");
      stmt.execute("DROP USER IF EXISTS 'cachingSha256User4'@'%'");
    }
    // reason is that after nativePassword test, it sometime always return wrong authentication id
    // not cached
    // !? strange, but mysql server error.
    if (haveSsl() && !isMariaDBServer() && minVersion(8, 0, 0)) {
      try (Connection con = createCon("sslMode=trust")) {
        con.createStatement().execute("DO 1");
      }
    }
  }

  @BeforeAll
  public static void init() throws Exception {
    Assumptions.assumeTrue(!isMariaDBServer() && minVersion(8, 0, 0));
    drop();
    Statement stmt = sharedConn.createStatement();
    rsaPublicKey = checkFileExists(System.getProperty("rsaPublicKey"));
    if (rsaPublicKey == null) {
      ResultSet rs = stmt.executeQuery("SELECT @@caching_sha2_password_public_key_path");
      rs.next();
      rsaPublicKey = checkFileExists(rs.getString(1));
      if (rsaPublicKey == null
          && rs.getString(1) != null
          && System.getenv("TEST_DB_RSA_PUBLIC_KEY") != null) {
        rsaPublicKey = checkFileExists(System.getenv("TEST_DB_RSA_PUBLIC_KEY"));
      }
    }
    if (rsaPublicKey == null) {
      rsaPublicKey = checkFileExists("../../ssl/public.key");
    }

    stmt.execute(
        "CREATE USER 'cachingSha256User'@'%' IDENTIFIED WITH caching_sha2_password BY '!Passw0rd3Works'");
    stmt.execute(
        "CREATE USER 'cachingSha256User2'@'%' IDENTIFIED WITH caching_sha2_password BY ''");
    stmt.execute(
        "CREATE USER 'cachingSha256User3'@'%' IDENTIFIED WITH caching_sha2_password BY '!Passw0rd3Works'");
    stmt.execute(
        "CREATE USER 'cachingSha256User4'@'%' IDENTIFIED WITH caching_sha2_password BY '!Passw0rd3Works'");
    stmt.execute("GRANT ALL PRIVILEGES ON *.* TO 'cachingSha256User'@'%'");
    stmt.execute("GRANT ALL PRIVILEGES ON *.* TO 'cachingSha256User2'@'%'");
    stmt.execute("GRANT ALL PRIVILEGES ON *.* TO 'cachingSha256User3'@'%'");
    stmt.execute("GRANT ALL PRIVILEGES ON *.* TO 'cachingSha256User4'@'%'");
    stmt.execute("FLUSH PRIVILEGES");
  }

  private static String checkFileExists(String path) throws IOException {
    if (path == null) return null;
    System.out.println("check path:" + path);
    File f = new File(path);
    if (f.exists()) {
      System.out.println("path exist :" + path);
      return f.getCanonicalPath().replace("\\", "/");
    }
    return null;
  }

  @Test
  public void nativePassword() throws Exception {
    Assumptions.assumeTrue(haveSsl());
    Assumptions.assumeTrue(
        !isWindows && !isMariaDBServer() && rsaPublicKey != null && minVersion(8, 0, 0));
    Statement stmt = sharedConn.createStatement();
    stmt.execute("DROP USER IF EXISTS tmpUser@'%'");
    stmt.execute(
        "CREATE USER tmpUser@'%' IDENTIFIED WITH mysql_native_password BY '!Passw0rd3Works'");
    stmt.execute("grant all on `" + sharedConn.getCatalog() + "`.* TO tmpUser@'%'");
    stmt.execute("FLUSH PRIVILEGES"); // reset cache
    try (Connection con = createCon("user=tmpUser&password=!Passw0rd3Works")) {
      con.isValid(1);
    }
    stmt.execute("DROP USER IF EXISTS tmpUser@'%' ");
  }

  @Test
  public void cachingSha256Empty() throws Exception {
    Assumptions.assumeTrue(
        !isWindows && !isMariaDBServer() && rsaPublicKey != null && minVersion(8, 0, 0));
    sharedConn.createStatement().execute("FLUSH PRIVILEGES"); // reset cache
    try (Connection con = createCon("user=cachingSha256User2&allowPublicKeyRetrieval&password=")) {
      con.isValid(1);
    }
  }

  @Test
  public void wrongRsaPath() throws Exception {
    Assumptions.assumeTrue(
        !isWindows && !isMariaDBServer() && rsaPublicKey != null && minVersion(8, 0, 0));
    sharedConn.createStatement().execute("FLUSH PRIVILEGES"); // reset cache
    File tempFile = File.createTempFile("log", ".tmp");
    assertThrowsContains(
        SQLException.class,
        () ->
            createCon(
                "user=cachingSha256User4&serverRsaPublicKeyFile="
                    + tempFile.getPath()
                    + "2&password=!Passw0rd3Works"),
        "Could not read server RSA public key from file");
  }

  @Test
  public void cachingSha256Allow() throws Exception {
    Assumptions.assumeTrue(
        !isWindows && !isMariaDBServer() && rsaPublicKey != null && minVersion(8, 0, 0));
    sharedConn.createStatement().execute("FLUSH PRIVILEGES"); // reset cache
    try (Connection con =
        createCon("user=cachingSha256User3&allowPublicKeyRetrieval&password=!Passw0rd3Works")) {
      con.isValid(1);
    }
  }

  @Test
  public void cachingSha256PluginTest() throws Exception {
    Assumptions.assumeTrue(
        !isWindows && !isMariaDBServer() && rsaPublicKey != null && minVersion(8, 0, 0));
    sharedConn.createStatement().execute("FLUSH PRIVILEGES"); // reset cache

    try (Connection con =
        createCon(
            "user=cachingSha256User&password=!Passw0rd3Works&serverRsaPublicKeyFile="
                + rsaPublicKey)) {
      con.isValid(1);
    }

    try (Connection con =
        createCon("user=cachingSha256User&password=!Passw0rd3Works&allowPublicKeyRetrieval")) {
      con.isValid(1);
    }

    Assumptions.assumeTrue(haveSsl());
    try (Connection con =
        createCon("user=cachingSha256User&password=!Passw0rd3Works&sslMode=trust")) {
      con.isValid(1);
    }

    try (Connection con =
        createCon("user=cachingSha256User&password=!Passw0rd3Works&allowPublicKeyRetrieval")) {
      con.isValid(1);
    }

    try (Connection con =
        createCon(
            "user=cachingSha256User&password=!Passw0rd3Works&serverRsaPublicKeyFile="
                + rsaPublicKey)) {
      con.isValid(1);
    }
  }

  @Test
  public void cachingSha256PluginTestWithoutServerRsaKey() throws Exception {
    Assumptions.assumeTrue(!isWindows && minVersion(8, 0, 0));
    sharedConn.createStatement().execute("FLUSH PRIVILEGES"); // reset cache
    try (Connection con =
        createCon("user=cachingSha256User&password=!Passw0rd3Works&allowPublicKeyRetrieval")) {
      con.isValid(1);
    }
  }

  @Test
  public void cachingSha256PluginTestException() throws Exception {
    Assumptions.assumeTrue(!isMariaDBServer() && minVersion(8, 0, 0));
    sharedConn.createStatement().execute("FLUSH PRIVILEGES"); // reset cache

    assertThrowsContains(
        SQLException.class,
        () -> createCon("user=cachingSha256User&password=!Passw0rd3Works"),
        "RSA public key is not available client side");
  }
}
