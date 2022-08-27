// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2021 MariaDB Corporation Ab

package org.tidb.jdbc.unit.client.tls;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.tidb.jdbc.client.tls.TiDBX509TrustingManager;

public class TiDBX509TrustingManagerTest {

  @Test
  public void check() {
    TiDBX509TrustingManager trustingManager = new TiDBX509TrustingManager();
    assertNull(trustingManager.getAcceptedIssuers());
    trustingManager.checkClientTrusted(null, null);
    trustingManager.checkServerTrusted(null, null);
  }
}
