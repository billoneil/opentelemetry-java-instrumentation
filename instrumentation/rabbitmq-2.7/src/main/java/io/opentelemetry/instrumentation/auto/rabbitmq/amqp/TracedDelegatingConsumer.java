/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.auto.rabbitmq.amqp;

import static io.opentelemetry.instrumentation.api.decorator.BaseDecorator.extract;
import static io.opentelemetry.instrumentation.auto.rabbitmq.amqp.RabbitDecorator.DECORATE;
import static io.opentelemetry.instrumentation.auto.rabbitmq.amqp.RabbitDecorator.TRACER;
import static io.opentelemetry.instrumentation.auto.rabbitmq.amqp.TextMapExtractAdapter.GETTER;
import static io.opentelemetry.trace.Span.Kind.CONSUMER;
import static io.opentelemetry.trace.TracingContextUtils.currentContextWith;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.ShutdownSignalException;
import io.opentelemetry.context.Scope;
import io.opentelemetry.trace.Span;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapping the consumer instead of instrumenting it directly because it doesn't get access to the
 * queue name when the message is consumed.
 */
public class TracedDelegatingConsumer implements Consumer {

  private static final Logger log = LoggerFactory.getLogger(TracedDelegatingConsumer.class);

  private final String queue;
  private final Consumer delegate;

  public TracedDelegatingConsumer(String queue, Consumer delegate) {
    this.queue = queue;
    this.delegate = delegate;
  }

  @Override
  public void handleConsumeOk(String consumerTag) {
    delegate.handleConsumeOk(consumerTag);
  }

  @Override
  public void handleCancelOk(String consumerTag) {
    delegate.handleCancelOk(consumerTag);
  }

  @Override
  public void handleCancel(String consumerTag) throws IOException {
    delegate.handleCancel(consumerTag);
  }

  @Override
  public void handleShutdownSignal(String consumerTag, ShutdownSignalException sig) {
    delegate.handleShutdownSignal(consumerTag, sig);
  }

  @Override
  public void handleRecoverOk(String consumerTag) {
    delegate.handleRecoverOk(consumerTag);
  }

  @Override
  public void handleDelivery(
      String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body)
      throws IOException {
    Span span = null;
    Scope scope = null;
    try {
      Map<String, Object> headers = properties.getHeaders();
      long startTimeMillis = System.currentTimeMillis();
      span =
          TRACER
              .spanBuilder(DECORATE.spanNameOnDeliver(queue))
              .setSpanKind(CONSUMER)
              .setParent(extract(headers, GETTER))
              .setAttribute("message.size", body == null ? 0 : body.length)
              .setStartTimestamp(TimeUnit.MILLISECONDS.toNanos(startTimeMillis))
              .startSpan();
      DECORATE.afterStart(span);
      DECORATE.onDeliver(span, envelope);

      if (properties.getTimestamp() != null) {
        // this will be set if the sender sets the timestamp,
        // or if a plugin is installed on the rabbitmq broker
        long produceTime = properties.getTimestamp().getTime();
        long consumeTime = NANOSECONDS.toMillis(startTimeMillis);
        span.setAttribute("record.queue_time_ms", Math.max(0L, consumeTime - produceTime));
      }

      scope = currentContextWith(span);

    } catch (Exception e) {
      log.debug("Instrumentation error in tracing consumer", e);
    } finally {
      try {

        // Call delegate.
        delegate.handleDelivery(consumerTag, envelope, properties, body);

      } catch (Throwable throwable) {
        if (span != null) {
          DECORATE.onError(span, throwable);
        }
        throw throwable;
      } finally {
        if (scope != null) {
          DECORATE.beforeFinish(span);
          span.end();
          scope.close();
        }
      }
    }
  }
}
