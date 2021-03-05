/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

public final class ThreadPerTaskExecutor implements Executor {

    /**
     * 线程工厂对象
     * Netty 实现自定义的 ThreadFactory 类，为 io.netty.util.concurrent.DefaultThreadFactory
     */
    private final ThreadFactory threadFactory;

    public ThreadPerTaskExecutor(ThreadFactory threadFactory) {
        if (threadFactory == null) {
            throw new NullPointerException("threadFactory");
        }
        this.threadFactory = threadFactory;
    }

    /**
     * 执行任务
     *
     * @param command 任务
     *
     * 通过 ThreadFactory#newThread(Runnable) 方法，创建一个 Thread ，然后调用 Thread#start() 方法，启动线程执行任务
     */
    @Override
    public void execute(Runnable command) {
        threadFactory.newThread(command).start();
    }
}
