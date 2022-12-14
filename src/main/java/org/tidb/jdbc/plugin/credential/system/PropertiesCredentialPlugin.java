// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (c) 2012-2014 Monty Program Ab
// Copyright (c) 2015-2021 MariaDB Corporation Ab

package org.tidb.jdbc.plugin.credential.system;

import org.tidb.jdbc.Configuration;
import org.tidb.jdbc.HostAddress;
import org.tidb.jdbc.plugin.Credential;
import org.tidb.jdbc.plugin.CredentialPlugin;

/**
 * Authentication using java system properties.
 *
 * <p>default implementation use system properties `tidbdb.user` and `tidb.pwd`
 *
 * <p>example : `jdbc:tidb://host/db?credentialType=PROPERTY`
 *
 * <p>2 options `userKey` and `pwdKey` permits indicating which system properties to use .
 */
public class PropertiesCredentialPlugin implements CredentialPlugin {

  private Configuration conf;
  private String userName;

  @Override
  public String type() {
    return "PROPERTY";
  }

  @Override
  public CredentialPlugin initialize(Configuration conf, String userName, HostAddress hostAddress) {
    this.conf = conf;
    this.userName = userName;
    return this;
  }

  @Override
  public Credential get() {

    String userKey = this.conf.nonMappedOptions().getProperty("userKey");
    String pwdKey = this.conf.nonMappedOptions().getProperty("pwdKey");
    String propUser = System.getProperty(userKey != null ? userKey : "tidbdb.user");

    return new Credential(
        propUser == null ? userName : propUser,
        System.getProperty(pwdKey != null ? pwdKey : "tidb.pwd"));
  }
}
