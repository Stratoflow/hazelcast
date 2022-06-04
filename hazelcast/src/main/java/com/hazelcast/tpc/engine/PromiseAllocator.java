/*
 * Copyright (c) 2008-2022, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.tpc.engine;

import static com.hazelcast.internal.util.Preconditions.checkNotNull;

final class PromiseAllocator {

    private final Eventloop eventloop;
    private Promise[] array;
    private int index = -1;

    PromiseAllocator(Eventloop eventloop, int capacity) {
        this.eventloop = checkNotNull(eventloop);
        this.array = new Promise[capacity];
    }

    int size() {
        return index + 1;
    }

    Promise allocate() {
        if (index == -1) {
            Promise promise = new Promise(eventloop);
            promise.allocator = this;
            return promise;
        }

        Promise object = array[index];
        array[index] = null;
        index--;
        return object;
    }

    void free(Promise e) {
        checkNotNull(e);

        if (index <= array.length - 1) {
            index++;
            array[index] = e;
        }
    }
}
