/*
 *  Licensed to Metamarkets Group Inc. (Metamarkets) under one
 *  or more contributor license agreements. See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership. Metamarkets licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied. See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package io.druid.emitter.graphite;

import com.codahale.metrics.graphite.PickledGraphite;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.metamx.common.ISE;
import com.metamx.common.logger.Logger;
import com.metamx.emitter.core.Emitter;
import com.metamx.emitter.core.Event;
import com.metamx.emitter.service.AlertEvent;
import com.metamx.emitter.service.ServiceMetricEvent;

import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;


public class GraphiteEmitter implements Emitter
{
  private static Logger log = new Logger(GraphiteEmitter.class);

  private final DruidToGraphiteEventConverter graphiteEventConverter;
  private final GraphiteEmitterConfig graphiteEmitterConfig;
  private final List<Emitter> emitterList;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final ObjectMapper mapper;
  private final LinkedBlockingQueue<GraphiteEvent> eventsQueue;
  private final static long DEFAULT_PUT_GET_TIMEOUT = 1000; // default wait for put/get operations on the queue 1 sec
  private static final long FLUSH_TIMEOUT = 60000; // default flush wait 1 min
  private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(2, new ThreadFactoryBuilder()
      .setDaemon(true)
      .setNameFormat("GraphiteEmitter-%s")
      .build()); // Thread pool of two in order to schedule flush runnable

  public GraphiteEmitter(
      @NotNull GraphiteEmitterConfig graphiteEmitterConfig,
      List<Emitter> emitterList, @NotNull ObjectMapper mapper
  )
  {
    this.emitterList = emitterList;
    this.mapper = mapper;
    this.graphiteEmitterConfig = graphiteEmitterConfig;
    this.graphiteEventConverter = graphiteEmitterConfig.getDruidToGraphiteEventConverter();
    this.eventsQueue = new LinkedBlockingQueue(graphiteEmitterConfig.getMaxQueueSize());
  }

  @Override
  public void start()
  {
    log.info("Starting Graphite Emitter.");
    synchronized (started) {
      if (!started.get()) {
        exec.scheduleAtFixedRate(
            new ConsumerRunnable(),
            graphiteEmitterConfig.getFlushPeriod(),
            graphiteEmitterConfig.getFlushPeriod(),
            TimeUnit.MILLISECONDS
        );
        started.set(true);
      }
    }
  }


  @Override
  public void emit(Event event)
  {
    if (!started.get()) {
      throw new ISE("WTF emit was called while service is not started yet");
    }
    if (event instanceof ServiceMetricEvent) {
      final GraphiteEvent graphiteEvent = graphiteEventConverter.druidEventToGraphite((ServiceMetricEvent) event);
      if (graphiteEvent == null) {
        return;
      }
      try {
        final boolean isSuccessful = eventsQueue.offer(graphiteEvent, DEFAULT_PUT_GET_TIMEOUT, TimeUnit.MILLISECONDS);
        if (!isSuccessful) {
          log.error(
              "Throwing event [%s] on the floor Graphite queue is full please increase the capacity or/and the consumer frequency",
              mapper.writeValueAsString(event)
          );
        }
      }
      catch (InterruptedException e) {
        log.error(e, "got interrupted with message [%s]", e.getMessage());
        Thread.currentThread().interrupt();

      }
      catch (JsonProcessingException e) {
        log.error(e, e.getMessage());
      }
    } else if (!emitterList.isEmpty() && event instanceof AlertEvent) {
      for (Emitter emitter : emitterList) {
        emitter.emit(event);
      }
    } else {
      throw new ISE("unknown event type [%s]", event.getClass());
    }
  }

  private class ConsumerRunnable implements Runnable
  {
    private PickledGraphite pickledGraphite = new PickledGraphite(
        graphiteEmitterConfig.getHostname(),
        graphiteEmitterConfig.getPort(),
        graphiteEmitterConfig.getBatchSize()
    );

    @Override
    public void run()
    {
      try {

        if (!pickledGraphite.isConnected()) {
          log.info("trying to connect to graphite server");
          pickledGraphite.connect();
        }
        while (eventsQueue.size() > 0 && !exec.isShutdown()) {
          try {
            final GraphiteEvent graphiteEvent = eventsQueue.poll(DEFAULT_PUT_GET_TIMEOUT, TimeUnit.MILLISECONDS);
            if (graphiteEvent != null) {
              log.debug(
                  "sent [%s] with value [%s] and time [%s]",
                  graphiteEvent.getEventPath(),
                  graphiteEvent.getValue(),
                  graphiteEvent.getTimestamp()
              );
              pickledGraphite.send(
                  graphiteEvent.getEventPath(),
                  graphiteEvent.getValue(),
                  graphiteEvent.getTimestamp()
              );
            }
          }
          catch (InterruptedException | IOException e) {
            log.error(e, e.getMessage());
            if (e instanceof InterruptedException) {
              Thread.currentThread().interrupt();
            }
          }
        }
        pickledGraphite.flush();
      }
      catch (Exception e) {
        log.error(e, e.getMessage());
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  @Override
  public void flush() throws IOException
  {
    if (started.get()) {
      Future future = exec.schedule(new ConsumerRunnable(), 0, TimeUnit.MILLISECONDS);
      try {
        future.get(FLUSH_TIMEOUT, TimeUnit.MILLISECONDS);
      }
      catch (InterruptedException | ExecutionException | TimeoutException e) {
        if (e instanceof InterruptedException) {
          throw new RuntimeException("interrupted flushing elements from queue", e);
        }
        log.error(e, e.getMessage());
      }
    }

  }

  @Override
  public void close() throws IOException
  {
    flush();
    started.set(false);
    exec.shutdown();
  }

  protected static String sanitize(String namespace)
  {
    Pattern DOT_OR_WHITESPACE = Pattern.compile("[\\s]+|[.]+");
    return DOT_OR_WHITESPACE.matcher(namespace).replaceAll("_");
  }
}