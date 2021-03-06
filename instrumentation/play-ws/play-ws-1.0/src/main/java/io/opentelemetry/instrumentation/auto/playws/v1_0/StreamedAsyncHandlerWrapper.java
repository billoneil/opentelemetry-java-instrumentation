/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.auto.playws.v1_0;

import io.grpc.Context;
import io.opentelemetry.trace.Span;
import org.reactivestreams.Publisher;
import play.shaded.ahc.org.asynchttpclient.handler.StreamedAsyncHandler;

public class StreamedAsyncHandlerWrapper extends AsyncHandlerWrapper
    implements StreamedAsyncHandler {
  private final StreamedAsyncHandler streamedDelegate;

  public StreamedAsyncHandlerWrapper(
      StreamedAsyncHandler delegate, Span span, Context invocationContext) {
    super(delegate, span, invocationContext);
    streamedDelegate = delegate;
  }

  @Override
  public State onStream(Publisher publisher) {
    return streamedDelegate.onStream(publisher);
  }
}
