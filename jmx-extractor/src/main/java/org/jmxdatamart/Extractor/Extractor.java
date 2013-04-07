/*
 * Copyright (c) 2012, Tripwire, Inc.
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
import org.jmxdatamart.Extractor.MXBean.MultiLayeredAttribute;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.MalformedURLException;
import java.util.*;

public final class Extractor {

  private final ExtractorSettings configData;
  private final StatisticsWriter writer;
  private MBeanServerConnection mbsc;
  private final org.slf4j.Logger logger = LoggerFactory.getLogger(Extractor.class);
  private Timer timer;

  @Inject
  public Extractor(ExtractorSettings configData, StatisticsWriter writer) {
    this.writer = writer;
    this.configData = configData;

    mbsc = getMBeanServerConnection();

    logger.info("Extracting JMX Statistics to directory {}", configData.getFolderLocation());

    if (isPeriodicallyExtracting()) {
      periodicallyExtract();
    } else {
      extract();
    }
  }

  private MBeanServerConnection getMBeanServerConnection() {
    MBeanServerConnection jmxConn;

    if (configData.getUrl() == null || configData.getUrl().isEmpty()) {
      jmxConn = ManagementFactory.getPlatformMBeanServer();
    } else {
      JMXServiceURL url;
      try {
        url = new JMXServiceURL(configData.getUrl());
      } catch (MalformedURLException e) {
        logger.error("Error creating JMX service URL object", e);
        throw new RuntimeException(e);
      }

      try {
        jmxConn = JMXConnectorFactory.connect(url).getMBeanServerConnection();
      } catch (IOException e) {
        logger.error(e.getMessage(), e);
        throw new RuntimeException(e);
      }
    }
    return jmxConn;
  }

  private void periodicallyExtract() {
    boolean isDaemon = true;
    timer = new Timer("JMX Statistics Extractor", isDaemon);
    long rate = configData.getPollingRate() * 1000;
    int delay = 0;
    timer.scheduleAtFixedRate(new Extract(), delay, rate);
  }

  public boolean isPeriodicallyExtracting() {
    return this.configData.getPollingRate() > 0;
  }

  void extract() {

    try {
      writer.startWritingStatistics();

      for (MBeanData beanData : this.configData.getBeans()) {
        if (beanData.isEnable()) {
          if (!beanData.isPattern()) {
            writeStatistics(beanData);

          } else {
            String originalName = beanData.getName();
            try {

              for (ObjectInstance oi : getObjectInstances(beanData)) {
                String actual = oi.getObjectName().getCanonicalName();
                beanData.setName(actual);
                beanData.setAlias(MultiLayeredAttribute.name2alias(actual));
                writeStatistics(beanData);
              }
            } catch (IOException ex) {
              logger.error("Error while trying to access MBean Server", ex);
            }
            beanData.setName(originalName);
          }
        }
      }

    } catch (StatisticsWriterException ex) {
      logger.error("Error while importing to HSQL", ex);
    } finally {
      writer.doneWritingStatistics();
    }
    logger.info("Extracted");
  }

  private Set<ObjectInstance> getObjectInstances(MBeanData beanData) throws IOException {
    Set<ObjectInstance> instances;
    try {
      ObjectName on = new ObjectName(beanData.getName());
      instances = mbsc.queryMBeans(on, null);

    } catch (MalformedObjectNameException ex) {
      logger.error("Non standard name for ObjectName " + beanData.getName(), ex);
      instances = Collections.emptySet();
    }
    return instances;
  }

  private void writeStatistics(MBeanData beanData) {
    Map<Attribute, Object> statisticValues = MBeanExtract.extract(beanData, mbsc);
    writer.writeStatistics(beanData, statisticValues);
  }

  public void stop() {
    logger.info("Stopping JMX Statistics Extractor");

    if (timer != null) {
      writer.close();
      timer.cancel();
    }

    logger.info("Stopped JMX Statistics Extractor");
  }

  private class Extract extends TimerTask {

    public Extract() {
      super();
      Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
        @Override
        public void run() {
          writer.close();
        }
      }));
    }

    @Override
    public void run() {
      try {
        extract();
      } catch (Exception e) {
        logger.debug("While extracting MBeans", e);
      }
    }
  }
}
