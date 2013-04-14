/*
 * Copyright (c) 2013, Tripwire, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *  o Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 *  o Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.jmxdatamart.Extractor;

import com.google.inject.Inject;
import org.jmxdatamart.common.DBException;
import org.jmxdatamart.common.HypersqlHandler;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class StatisticsWriter {
  private final org.slf4j.Logger logger = LoggerFactory.getLogger(StatisticsWriter.class);
  private final Lock connLock = new ReentrantLock();
  private final Properties props = new Properties();
  private final Bean2DB bd = new Bean2DB();
  private final String dbName;
  private final HypersqlHandler hsql;
  private Connection conn;

  @Inject
  public StatisticsWriter(ExtractorSettings settings) {
    props.put("username", "sa");
    props.put("password", "whatever");
    hsql = new HypersqlHandler();
    hsql.loadDriver(hsql.getDriver());

    String statsDirectory = settings.getFolderLocation();
    dbName = statsDirectory + File.separator + "Extractor" + new SimpleDateFormat("yyyyMMddHHmmss").format(new java.util.Date());
  }

  void doneWritingStatistics() {
    cleanupDatabaseConnection();
    connLock.unlock();
  }

  private void cleanupDatabaseConnection() {
    hsql.shutdownDatabase(conn);
    HypersqlHandler.releaseDatabaseResource(null, null, null, conn);
    conn = null;
  }

  void startWritingStatistics() {
    connLock.lock();
    conn = hsql.connectDatabase(dbName, props);
  }

  void close() {
    try {
      connLock.lock();
      if (conn != null && !conn.isClosed()) {
        cleanupDatabaseConnection();
      }

    } catch (SQLException ex) {
      logger.error("Error while closing HSQL connection", ex);

    } finally {
      connLock.unlock();
    }
  }

  void writeStatistics(MBeanData beanData, Map<Attribute, Object> statisticValues) throws StatisticsWriterException {
    try {
      bd.export2DB(conn, beanData, statisticValues);
    } catch (SQLException e) {
      throw new StatisticsWriterException("While writing statistics to HSQL", e);
    } catch (DBException e) {
      throw new StatisticsWriterException("While writing statistics to HSQL", e);
    }
  }
}
