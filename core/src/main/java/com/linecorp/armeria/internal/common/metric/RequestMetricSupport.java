/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.internal.common.metric;

import static com.linecorp.armeria.common.metric.MoreMeters.newDistributionSummary;
import static com.linecorp.armeria.common.metric.MoreMeters.newTimer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiPredicate;

import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.WriteTimeoutException;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.ClientConnectionTimings;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.RequestTimeoutException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.netty.util.AttributeKey;

/**
 * Collects the metric data and stores it into the {@link MeterRegistry}.
 */
public final class RequestMetricSupport {

    /**
     * Sets up request metrics.
     */
    public static void setup(
            RequestContext ctx, AttributeKey<Boolean> requestMetricsSetKey,
            MeterIdPrefixFunction meterIdPrefixFunction, boolean server,
            @Nullable BiPredicate<? super RequestContext, ? super RequestLog> successFunction) {
        final Boolean isRequestMetricsSet = ctx.attr(requestMetricsSetKey);

        if (Boolean.TRUE.equals(isRequestMetricsSet)) {
            return;
        }
        ctx.setAttr(requestMetricsSetKey, true);

        ctx.log()
           .whenAvailable(RequestLogProperty.REQUEST_START_TIME,
                          RequestLogProperty.REQUEST_HEADERS,
                          RequestLogProperty.NAME,
                          RequestLogProperty.SESSION)
           .thenAccept(log -> onRequest(log, meterIdPrefixFunction, server, successFunction));
    }

    private static void onRequest(
            RequestLog log, MeterIdPrefixFunction meterIdPrefixFunction, boolean server,
            @Nullable BiPredicate<? super RequestContext, ? super RequestLog> successFunction) {
        final RequestContext ctx = log.context();
        final MeterRegistry registry = ctx.meterRegistry();
        final MeterIdPrefix activeRequestsId =
                meterIdPrefixFunction.activeRequestPrefix(registry, log).append("active.requests");

        final ActiveRequestMetrics activeRequestMetrics = MicrometerUtil.register(
                registry, activeRequestsId, ActiveRequestMetrics.class,
                (reg, prefix) ->
                        reg.gauge(prefix.name(), prefix.tags(),
                                  new ActiveRequestMetrics(), ActiveRequestMetrics::doubleValue));
        activeRequestMetrics.increment();
        ctx.log().whenComplete().thenAccept(requestLog -> {
            onResponse(requestLog, meterIdPrefixFunction, server, successFunction);
            activeRequestMetrics.decrement();
        });
    }

    private static void onResponse(
            RequestLog log, MeterIdPrefixFunction meterIdPrefixFunction, boolean server,
            @Nullable BiPredicate<? super RequestContext, ? super RequestLog> successFunction) {
        final RequestContext ctx = log.context();
        final MeterRegistry registry = ctx.meterRegistry();
        final MeterIdPrefix idPrefix = meterIdPrefixFunction.completeRequestPrefix(registry, log);

        if (server) {
            final ServiceRequestMetrics metrics = MicrometerUtil.register(registry, idPrefix,
                                                                          ServiceRequestMetrics.class,
                                                                          DefaultServiceRequestMetrics::new);
            updateMetrics(ctx, log, metrics, successFunction);
            if (log.responseCause() instanceof RequestTimeoutException) {
                metrics.requestTimeouts().increment();
            }
            return;
        }

        final ClientRequestMetrics metrics = MicrometerUtil.register(registry, idPrefix,
                                                                     ClientRequestMetrics.class,
                                                                     DefaultClientRequestMetrics::new);
        updateMetrics(ctx, log, metrics, successFunction);
        final ClientConnectionTimings timings = log.connectionTimings();
        if (timings != null) {
            metrics.connectionAcquisitionDuration().record(timings.connectionAcquisitionDurationNanos(),
                                                           TimeUnit.NANOSECONDS);
            final long dnsResolutionDurationNanos = timings.dnsResolutionDurationNanos();
            if (dnsResolutionDurationNanos >= 0) {
                metrics.dnsResolutionDuration().record(dnsResolutionDurationNanos, TimeUnit.NANOSECONDS);
            }
            final long socketConnectDurationNanos = timings.socketConnectDurationNanos();
            if (socketConnectDurationNanos >= 0) {
                metrics.socketConnectDuration().record(socketConnectDurationNanos, TimeUnit.NANOSECONDS);
            }
            final long pendingAcquisitionDurationNanos = timings.pendingAcquisitionDurationNanos();
            if (pendingAcquisitionDurationNanos >= 0) {
                metrics.pendingAcquisitionDuration().record(pendingAcquisitionDurationNanos,
                                                            TimeUnit.NANOSECONDS);
            }
        }
        if (log.requestCause() != null) {
            if (log.requestCause() instanceof WriteTimeoutException) {
                metrics.writeTimeouts().increment();
            }
            return;
        }

        if (log.responseCause() instanceof ResponseTimeoutException) {
            metrics.responseTimeouts().increment();
        }

        final int childrenSize = log.children().size();
        if (childrenSize > 0) {
            metrics.actualRequests().increment(childrenSize);
        }
    }

    private static void updateMetrics(
            RequestContext ctx, RequestLog log, RequestMetrics metrics,
            @Nullable BiPredicate<? super RequestContext, ? super RequestLog> successFunction) {
        if (log.requestCause() != null) {
            metrics.failure().increment();
            return;
        }

        metrics.requestDuration().record(log.requestDurationNanos(), TimeUnit.NANOSECONDS);
        metrics.requestLength().record(log.requestLength());
        metrics.responseDuration().record(log.responseDurationNanos(), TimeUnit.NANOSECONDS);
        metrics.responseLength().record(log.responseLength());
        metrics.totalDuration().record(log.totalDurationNanos(), TimeUnit.NANOSECONDS);

        final boolean success;
        if (successFunction != null) {
            success = successFunction.test(ctx, log);
        } else {
            success = isSuccess(log);
        }
        if (success) {
            metrics.success().increment();
        } else {
            metrics.failure().increment();
        }
    }

    private static boolean isSuccess(RequestLog log) {
        if (log.responseCause() != null) {
            return false;
        }

        final int statusCode = log.responseHeaders().status().code();
        if (statusCode < 100 || statusCode >= 400) {
            return false;
        }

        final Object responseContent = log.responseContent();
        if (responseContent instanceof RpcResponse) {
            return !((RpcResponse) responseContent).isCompletedExceptionally();
        }

        return true;
    }

    private RequestMetricSupport() {}

    // metrics that only needed to be called when a request completed
    private interface RequestMetrics {
        Counter success();

        Counter failure();

        Timer requestDuration();

        DistributionSummary requestLength();

        Timer responseDuration();

        DistributionSummary responseLength();

        Timer totalDuration();
    }

    private interface ClientRequestMetrics extends RequestMetrics {
        Counter actualRequests();

        Timer connectionAcquisitionDuration();

        Timer dnsResolutionDuration();

        Timer socketConnectDuration();

        Timer pendingAcquisitionDuration();

        Counter writeTimeouts();

        Counter responseTimeouts();
    }

    private interface ServiceRequestMetrics extends RequestMetrics {
        Counter requestTimeouts();
    }

    private static final class ActiveRequestMetrics extends LongAdder {}

    private abstract static class AbstractRequestMetrics implements RequestMetrics {

        private final Counter success;
        private final Counter failure;
        private final Timer requestDuration;
        private final DistributionSummary requestLength;
        private final Timer responseDuration;
        private final DistributionSummary responseLength;
        private final Timer totalDuration;

        AbstractRequestMetrics(MeterRegistry parent, MeterIdPrefix idPrefix) {
            final String requests = idPrefix.name("requests");
            success = parent.counter(requests, idPrefix.tags("result", "success"));
            failure = parent.counter(requests, idPrefix.tags("result", "failure"));

            requestDuration = newTimer(parent, idPrefix.name("request.duration"), idPrefix.tags());
            requestLength = newDistributionSummary(parent, idPrefix.name("request.length"), idPrefix.tags());
            responseDuration = newTimer(parent, idPrefix.name("response.duration"), idPrefix.tags());
            responseLength = newDistributionSummary(parent, idPrefix.name("response.length"), idPrefix.tags());
            totalDuration = newTimer(parent, idPrefix.name("total.duration"), idPrefix.tags());
        }

        @Override
        public Counter success() {
            return success;
        }

        @Override
        public Counter failure() {
            return failure;
        }

        @Override
        public Timer requestDuration() {
            return requestDuration;
        }

        @Override
        public DistributionSummary requestLength() {
            return requestLength;
        }

        @Override
        public Timer responseDuration() {
            return responseDuration;
        }

        @Override
        public DistributionSummary responseLength() {
            return responseLength;
        }

        @Override
        public Timer totalDuration() {
            return totalDuration;
        }
    }

    private static class DefaultClientRequestMetrics
            extends AbstractRequestMetrics implements ClientRequestMetrics {

        private static final AtomicReferenceFieldUpdater<DefaultClientRequestMetrics, Counter>
                actualRequestsUpdater = AtomicReferenceFieldUpdater.newUpdater(
                DefaultClientRequestMetrics.class, Counter.class, "actualRequests");

        private final MeterRegistry parent;
        private final MeterIdPrefix idPrefix;

        private final Timer connectionAcquisitionDuration;
        private final Timer dnsResolutionDuration;
        private final Timer socketConnectDuration;
        private final Timer pendingAcquisitionDuration;

        private final Counter writeTimeouts;
        private final Counter responseTimeouts;

        @Nullable
        private volatile Counter actualRequests;

        DefaultClientRequestMetrics(MeterRegistry parent, MeterIdPrefix idPrefix) {
            super(parent, idPrefix);
            this.parent = parent;
            this.idPrefix = idPrefix;

            connectionAcquisitionDuration = newTimer(
                    parent, idPrefix.name("connection.acquisition.duration"), idPrefix.tags());
            dnsResolutionDuration = newTimer(
                    parent, idPrefix.name("dns.resolution.duration"), idPrefix.tags());
            socketConnectDuration = newTimer(
                    parent, idPrefix.name("socket.connect.duration"), idPrefix.tags());
            pendingAcquisitionDuration = newTimer(
                    parent, idPrefix.name("pending.acquisition.duration"), idPrefix.tags());

            final String timeouts = idPrefix.name("timeouts");
            writeTimeouts = parent.counter(timeouts, idPrefix.tags("cause", "WriteTimeoutException"));
            responseTimeouts = parent.counter(timeouts, idPrefix.tags("cause", "ResponseTimeoutException"));
        }

        @Override
        public Counter actualRequests() {
            final Counter actualRequests = this.actualRequests;
            if (actualRequests != null) {
                return actualRequests;
            }

            final Counter counter = parent.counter(idPrefix.name("actual.requests"), idPrefix.tags());
            if (actualRequestsUpdater.compareAndSet(this, null, counter)) {
                return counter;
            }
            return this.actualRequests;
        }

        @Override
        public Timer connectionAcquisitionDuration() {
            return connectionAcquisitionDuration;
        }

        @Override
        public Timer dnsResolutionDuration() {
            return dnsResolutionDuration;
        }

        @Override
        public Timer socketConnectDuration() {
            return socketConnectDuration;
        }

        @Override
        public Timer pendingAcquisitionDuration() {
            return pendingAcquisitionDuration;
        }

        @Override
        public Counter writeTimeouts() {
            return writeTimeouts;
        }

        @Override
        public Counter responseTimeouts() {
            return responseTimeouts;
        }
    }

    private static class DefaultServiceRequestMetrics
            extends AbstractRequestMetrics implements ServiceRequestMetrics {

        private final Counter requestTimeouts;

        DefaultServiceRequestMetrics(MeterRegistry parent, MeterIdPrefix idPrefix) {
            super(parent, idPrefix);
            requestTimeouts = parent.counter(idPrefix.name("timeouts"),
                                             idPrefix.tags("cause", "RequestTimeoutException"));
        }

        @Override
        public Counter requestTimeouts() {
            return requestTimeouts;
        }
    }
}
