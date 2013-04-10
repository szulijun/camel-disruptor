/*
 * Copyright 2012 Riccardo Sirchia
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.camel.component.disruptor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.camel.AsyncCallback;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangeTimedOutException;
import org.apache.camel.WaitForTaskToComplete;
import org.apache.camel.impl.DefaultAsyncProducer;
import org.apache.camel.support.SynchronizationAdapter;
import org.apache.camel.util.ExchangeHelper;

/**
 * TODO: documentation
 */
public class DisruptorProducer extends DefaultAsyncProducer {

    private final WaitForTaskToComplete waitForTaskToComplete;
    private final long timeout;

    private final DisruptorEndpoint endpoint;

    public DisruptorProducer(DisruptorEndpoint endpoint, WaitForTaskToComplete waitForTaskToComplete, long timeout) {
        super(endpoint);
        this.waitForTaskToComplete = waitForTaskToComplete;
        this.timeout = timeout;
        this.endpoint = endpoint;
    }

    @Override
    public DisruptorEndpoint getEndpoint() {
        return endpoint;
    }

    @Override
    protected void doStart() throws Exception {
        getEndpoint().onStarted(this);
    }

    @Override
    protected void doStop() throws Exception {
        getEndpoint().onStopped(this);
    }

    @Override
    public boolean process(final Exchange exchange, final AsyncCallback callback) {
        WaitForTaskToComplete wait = waitForTaskToComplete;
        if (exchange.getProperty(Exchange.ASYNC_WAIT) != null) {
            wait = exchange.getProperty(Exchange.ASYNC_WAIT, WaitForTaskToComplete.class);
        }

        if (wait == WaitForTaskToComplete.Always
                || (wait == WaitForTaskToComplete.IfReplyExpected && ExchangeHelper.isOutCapable(exchange))) {

            // do not handover the completion as we wait for the copy to complete, and copy its result back when it done
            Exchange copy = prepareCopy(exchange, false);

            // latch that waits until we are complete
            final CountDownLatch latch = new CountDownLatch(1);

            // we should wait for the reply so install a on completion so we know when its complete
            copy.addOnCompletion(new SynchronizationAdapter() {
                @Override
                public void onDone(Exchange response) {
                    // check for timeout, which then already would have invoked the latch
                    if (latch.getCount() == 0) {
                        if (log.isTraceEnabled()) {
                            log.trace("{}. Timeout occurred so response will be ignored: {}", this, response.hasOut() ? response.getOut() : response.getIn());
                        }
                        return;
                    } else {
                        if (log.isTraceEnabled()) {
                            log.trace("{} with response: {}", this, response.hasOut() ? response.getOut() : response.getIn());
                        }
                        try {
                            ExchangeHelper.copyResults(exchange, response);
                        } finally {
                            // always ensure latch is triggered
                            latch.countDown();
                        }
                    }
                }

                @Override
                public boolean allowHandover() {
                    // do not allow handover as we want to seda producer to have its completion triggered
                    // at this point in the routing (at this leg), instead of at the very last (this ensure timeout is honored)
                    return false;
                }

                @Override
                public String toString() {
                    return "onDone at endpoint: " + endpoint;
                }
            });

            log.trace("Publishing Exchange to disruptor ringbuffer: {}", copy);
            endpoint.publish(copy);

            if (timeout > 0) {
                if (log.isTraceEnabled()) {
                    log.trace("Waiting for task to complete using timeout (ms): {} at [{}]", timeout, endpoint.getEndpointUri());
                }
                // lets see if we can get the task done before the timeout
                boolean done = false;
                try {
                    done = latch.await(timeout, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    // ignore
                }
                if (!done) {
                    exchange.setException(new ExchangeTimedOutException(exchange, timeout));
                    // Remove timed out Exchange from disruptor
                    // endpoint.
                    // TODO Remove exchange from disruptor. Maybe do this by setting a Property on the exchange and the value
                    // would be an AtomicBoolean. This is set by the Producer and the Consumer would look up that Property and
                    // check the AtomicBOolean. If the AtomicBoolean says that we are good to proceed, it will process the
                    // exchange. If false, it will simply disregard the exchange.
                    // But since the Property map is a Concurrent one, maybe we don't need the AtomicBoolean. Check with Simon.
                    // Also check the TimeoutHandler of the new Disruptor 3.0.0, consider making the switch to the latest version.
                    exchange.setProperty(DisruptorEndpoint.DISRUPTOR_IGNORE_EXCHANGE, true);

                    // count down to indicate timeout
                    latch.countDown();
                }
            } else {
                if (log.isTraceEnabled()) {
                    log.trace("Waiting for task to complete (blocking) at [{}]", endpoint.getEndpointUri());
                }
                // no timeout then wait until its done
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        } else {
            // no wait, eg its a InOnly then just publish to the ringbuffer and return
            // handover the completion so its the copy which performs that, as we do not wait
            Exchange copy = prepareCopy(exchange, true);
            log.trace("Publishing Exchange to disruptor ringbuffer: {}", copy);
            endpoint.publish(copy);
        }

        // we use OnCompletion on the Exchange to callback and wait for the Exchange to be done
        // so we should just signal the callback we are done synchronously
        callback.done(true);
        return true;
    }


    protected Exchange prepareCopy(Exchange exchange, boolean handover) {
        // use a new copy of the exchange to route async
        Exchange copy = ExchangeHelper.createCorrelatedCopy(exchange, handover);
        // set a new from endpoint to be the disruptor
        copy.setFromEndpoint(endpoint);
        return copy;
    }
}
