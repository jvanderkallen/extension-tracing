/*
 * Copyright (c) 2010-2019. Axon Framework
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
package org.axonframework.extensions.tracing;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.tag.Tags;
import org.axonframework.commandhandling.CommandBus;
import org.axonframework.commandhandling.CommandCallback;
import org.axonframework.commandhandling.CommandExecutionException;
import org.axonframework.commandhandling.CommandMessage;
import org.axonframework.commandhandling.CommandResultMessage;
import org.axonframework.commandhandling.GenericCommandMessage;
import org.axonframework.commandhandling.callbacks.FutureCallback;
import org.axonframework.commandhandling.gateway.DefaultCommandGateway;
import org.axonframework.commandhandling.gateway.RetryScheduler;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.messaging.MessageDispatchInterceptor;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.axonframework.common.BuilderUtils.assertNonNull;
import static org.axonframework.extensions.tracing.SpanUtils.withMessageTags;

/**
 * A tracing command gateway which activates a calling {@link Span}, when the {@link CompletableFuture} completes.
 *
 * @author Christophe Bouhier
 * @author Allard Buijze
 * @author Steven van Beelen
 * @since 4.0
 */
public class TracingCommandGateway extends DefaultCommandGateway {

    private final Tracer tracer;

    /**
     * Instantiate a {@link TracingCommandGateway} based on the fields contained in the {@link Builder}.
     * <p>
     * Will assert that the {@link CommandBus} and {@link Tracer} are not {@code null}, and will throw an
     * {@link AxonConfigurationException} if they are.
     *
     * @param builder the {@link Builder} used to instantiate a {@link TracingCommandGateway} instance
     */
    protected TracingCommandGateway(Builder builder) {
        super(builder);
        this.tracer = builder.tracer;
    }

    /**
     * Instantiate a Builder to be able to create a {@link TracingCommandGateway}.
     * <p>
     * The {@code dispatchInterceptors} are defaulted to an empty list.
     * The {@link Tracer} and {@link CommandBus} are <b>hard requirements</b> and as such should be provided.
     *
     * @return a Builder to be able to create a {@link TracingCommandGateway}
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public <C, R> void send(C command, CommandCallback<? super C, ? super R> callback) {
        CommandMessage<?> cmd = GenericCommandMessage.asCommandMessage(command);
        sendWithSpan(tracer, "sendCommandMessage", cmd, (tracer, parentSpan, childSpan) -> {
            CompletableFuture<?> resultReceived = new CompletableFuture<>();
            super.send(command, (CommandCallback<C, R>) (commandMessage, commandResultMessage) -> {
                try (Scope ignored = tracer.scopeManager().activate(parentSpan, false)) {
                    childSpan.log("resultReceived");
                    callback.onResult(commandMessage, commandResultMessage);
                    childSpan.log("afterCallbackInvocation");
                } finally {
                    resultReceived.complete(null);
                }
            });
            childSpan.log("dispatchComplete");
            resultReceived.thenRun(childSpan::finish);
        });
    }

    @Override
    public <R> R sendAndWait(Object command) {
        return doSendAndExtract(command, FutureCallback::getResult);
    }

    @Override
    public <R> R sendAndWait(Object command, long timeout, TimeUnit unit) {
        return doSendAndExtract(command, f -> f.getResult(timeout, unit));
    }

    private <R> R doSendAndExtract(Object command,
                                   Function<FutureCallback<Object, R>, CommandResultMessage<? extends R>> resultExtractor) {
        FutureCallback<Object, R> futureCallback = new FutureCallback<>();

        sendAndRestoreParentSpan(command, futureCallback);
        CommandResultMessage<? extends R> commandResultMessage = resultExtractor.apply(futureCallback);
        if (commandResultMessage.isExceptional()) {
            throw asRuntime(commandResultMessage.exceptionResult());
        }
        return commandResultMessage.getPayload();
    }

    private <R> void sendAndRestoreParentSpan(Object command, FutureCallback<Object, R> futureCallback) {
        CommandMessage<?> cmd = GenericCommandMessage.asCommandMessage(command);
        sendWithSpan(tracer, "sendCommandMessageAndWait", cmd, (tracer, parentSpan, childSpan) -> {
            super.send(cmd, futureCallback);
            futureCallback.thenRun(() -> childSpan.log("resultReceived"));

            childSpan.log("dispatchComplete");
            futureCallback.thenRun(childSpan::finish);
        });
    }

    private RuntimeException asRuntime(Throwable e) {
        Throwable failure = e.getCause();
        if (failure instanceof Error) {
            throw (Error) failure;
        } else if (failure instanceof RuntimeException) {
            return (RuntimeException) failure;
        } else {
            return new CommandExecutionException("An exception occurred while executing a command", failure);
        }
    }

    private void sendWithSpan(Tracer tracer, String operation, CommandMessage<?> command, SpanConsumer consumer) {
        Span parent = tracer.activeSpan();
        Tracer.SpanBuilder spanBuilder = withMessageTags(tracer.buildSpan(operation), command)
                .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT);
        try (Scope scope = spanBuilder.startActive(false)) {
            consumer.accept(tracer, parent, scope.span());
        }
        tracer.scopeManager().activate(parent, false);
    }

    @FunctionalInterface
    private interface SpanConsumer {

        void accept(Tracer tracer, Span activeSpan, Span parentSpan);
    }

    /**
     * Builder class to instantiate a {@link TracingCommandGateway}.
     * <p>
     * The {@code dispatchInterceptors} are defaulted to an empty list.
     * The {@link Tracer} and {@link CommandBus} are <b>hard requirements</b> and as such should be provided.
     */
    public static class Builder extends DefaultCommandGateway.Builder {

        private Tracer tracer;

        @Override
        public Builder commandBus(CommandBus commandBus) {
            super.commandBus(commandBus);
            return this;
        }

        @Override
        public Builder retryScheduler(RetryScheduler retryScheduler) {
            super.retryScheduler(retryScheduler);
            return this;
        }

        @Override
        public Builder dispatchInterceptors(
                MessageDispatchInterceptor<? super CommandMessage<?>>... dispatchInterceptors) {
            super.dispatchInterceptors(dispatchInterceptors);
            return this;
        }

        @Override
        public Builder dispatchInterceptors(
                List<MessageDispatchInterceptor<? super CommandMessage<?>>> dispatchInterceptors) {
            super.dispatchInterceptors(dispatchInterceptors);
            return this;
        }

        /**
         * Sets the {@link Tracer} used to set a {@link Span} on dispatched {@link CommandMessage}s.
         *
         * @param tracer a {@link Tracer} used to set a {@link Span} on dispatched {@link CommandMessage}s.
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder tracer(Tracer tracer) {
            assertNonNull(tracer, "Tracer may not be null");
            this.tracer = tracer;
            return this;
        }

        /**
         * Initializes a {@link TracingCommandGateway} as specified through this Builder.
         *
         * @return a {@link TracingCommandGateway} as specified through this Builder
         */
        public TracingCommandGateway build() {
            return new TracingCommandGateway(this);
        }

        @Override
        protected void validate() throws AxonConfigurationException {
            super.validate();
            assertNonNull(tracer, "The Tracer is a hard requirement and should be provided");
        }
    }
}
