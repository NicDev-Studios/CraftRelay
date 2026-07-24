/*
 * Copyright 2026 NicDev-Studios
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tv.nicdev.craftrelay.common.internal.request;

import java.util.Objects;
import tv.nicdev.craftrelay.common.internal.concurrent.FutureCompletionDispatcher;
import tv.nicdev.craftrelay.common.internal.runtime.MessagingRuntime;

/**
 * Internal factories for request-handler registries.
 */
public final class RequestHandlerRegistries {

    private RequestHandlerRegistries() {
    }

    /**
     * Creates an isolated registry attached to a messaging runtime.
     *
     * @param runtime messaging runtime
     * @param completionDispatcher controlled asynchronous completion dispatcher
     * @return new request-handler registry
     */
    public static RequestHandlerRegistry create(
            MessagingRuntime runtime,
            FutureCompletionDispatcher completionDispatcher) {
        return new DefaultRequestHandlerRegistry(
                Objects.requireNonNull(runtime, "runtime"),
                Objects.requireNonNull(completionDispatcher, "completionDispatcher"));
    }
}
