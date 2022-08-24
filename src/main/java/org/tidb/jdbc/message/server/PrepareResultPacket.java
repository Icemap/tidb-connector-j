// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2021 MariaDB Corporation Ab

package org.tidb.jdbc.message.server;

import java.io.IOException;
import java.sql.SQLException;
import org.tidb.jdbc.ServerPreparedStatement;
import org.tidb.jdbc.client.Client;
import org.tidb.jdbc.client.Column;
import org.tidb.jdbc.client.Completion;
import org.tidb.jdbc.client.Context;
import org.tidb.jdbc.client.ReadableByteBuf;
import org.tidb.jdbc.client.socket.Reader;
import org.tidb.jdbc.export.Prepare;
import org.tidb.jdbc.util.constants.Capabilities;
import org.tidb.jdbc.util.log.Logger;
import org.tidb.jdbc.util.log.Loggers;

/** Prepare result packet See https://mariadb.com/kb/en/com_stmt_prepare/#COM_STMT_PREPARE_OK */
public class PrepareResultPacket implements Completion, Prepare {

  private static final Logger logger = Loggers.getLogger(PrepareResultPacket.class);
  private final Column[] parameters;
  private Column[] columns;

  /** prepare statement id */
  protected int statementId;

  /**
   * Prepare packet constructor (parsing)
   *
   * @param buffer packet buffer
   * @param reader packet reader
   * @param context connection context
   * @throws IOException if socket exception occurs
   */
  public PrepareResultPacket(ReadableByteBuf buffer, Reader reader, Context context)
      throws IOException {
    boolean trace = logger.isTraceEnabled();
    buffer.readByte(); /* skip COM_STMT_PREPARE_OK */
    this.statementId = buffer.readInt();
    final int numColumns = buffer.readUnsignedShort();
    final int numParams = buffer.readUnsignedShort();
    this.parameters = new Column[numParams];
    this.columns = new Column[numColumns];

    if (numParams > 0) {
      for (int i = 0; i < numParams; i++) {
        parameters[i] =
            new ColumnDefinitionPacket(
                reader.readPacket(false, trace),
                context.hasClientCapability(Capabilities.EXTENDED_TYPE_INFO));
      }
      if (!context.isEofDeprecated()) {
        reader.readPacket(true, trace);
      }
    }
    if (numColumns > 0) {
      for (int i = 0; i < numColumns; i++) {
        columns[i] =
            new ColumnDefinitionPacket(
                reader.readPacket(false, trace),
                context.hasClientCapability(Capabilities.EXTENDED_TYPE_INFO));
      }
      if (!context.isEofDeprecated()) {
        reader.readPacket(true, trace);
      }
    }
  }

  /**
   * Close prepare packet
   *
   * @param con current connection
   * @throws SQLException if exception occurs
   */
  public void close(Client con) throws SQLException {
    con.closePrepare(this);
  }

  /**
   * Decrement use of prepare packet, so closing it if last used
   *
   * @param con connection
   * @param preparedStatement current prepared statement that was using prepare object
   * @throws SQLException if exception occurs
   */
  public void decrementUse(Client con, ServerPreparedStatement preparedStatement)
      throws SQLException {
    close(con);
  }

  /**
   * Get statement id
   *
   * @return statement id
   */
  public int getStatementId() {
    return statementId;
  }

  public Column[] getParameters() {
    return parameters;
  }

  public Column[] getColumns() {
    return columns;
  }

  public void setColumns(Column[] columns) {
    this.columns = columns;
  }
}
