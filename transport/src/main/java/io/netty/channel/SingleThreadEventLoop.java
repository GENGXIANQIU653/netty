/*
 * Copyright 2012 The Netty Project
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
package io.netty.channel;

import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.concurrent.RejectedExecutionHandlers;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.UnstableApi;

import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

/**
 * Abstract base class for {@link EventLoop}s that execute all its submitted tasks in a single thread.
 *
 * 实现 EventLoop 接口，继承 SingleThreadEventExecutor 抽象类，
 * 基于单线程的 EventLoop 抽象类，
 * 主要增加了 Channel 注册到 EventLoop 上
 *
 */
public abstract class SingleThreadEventLoop extends SingleThreadEventExecutor implements EventLoop {

    /**
     * 默认任务队列最大数量
     */
    protected static final int DEFAULT_MAX_PENDING_TASKS = Math.max(16,
            SystemPropertyUtil.getInt("io.netty.eventLoop.maxPendingTasks", Integer.MAX_VALUE));

    /**
     * 尾部任务队列，执行在 taskQueue 之后
     *
     * Commits
     *  * [Ability to run a task at the end of an eventLoop iteration.](https://github.com/netty/netty/pull/5513)
     *
     * Issues
     *  * [Auto-flush for channels. (`ChannelHandler` implementation)](https://github.com/netty/netty/pull/5716)
     *  * [Consider removing executeAfterEventLoopIteration](https://github.com/netty/netty/issues/7833)
     *
     * 老艿艿：未来会移除该队列，前提是实现了 Channel 的 auto flush 功能。按照最后一个 issue 的讨论
     */
    private final Queue<Runnable> tailTasks;

    protected SingleThreadEventLoop(EventLoopGroup parent, ThreadFactory threadFactory, boolean addTaskWakesUp) {
        this(parent, threadFactory, addTaskWakesUp, DEFAULT_MAX_PENDING_TASKS, RejectedExecutionHandlers.reject());
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, Executor executor, boolean addTaskWakesUp) {
        this(parent, executor, addTaskWakesUp, DEFAULT_MAX_PENDING_TASKS, RejectedExecutionHandlers.reject());
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, ThreadFactory threadFactory,
                                    boolean addTaskWakesUp, int maxPendingTasks,
                                    RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, threadFactory, addTaskWakesUp, maxPendingTasks, rejectedExecutionHandler);
        tailTasks = newTaskQueue(maxPendingTasks);
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, Executor executor,
                                    boolean addTaskWakesUp, int maxPendingTasks,
                                    RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, addTaskWakesUp, maxPendingTasks, rejectedExecutionHandler);
        tailTasks = newTaskQueue(maxPendingTasks);
    }

    protected SingleThreadEventLoop(EventLoopGroup parent, Executor executor,
                                    boolean addTaskWakesUp, Queue<Runnable> taskQueue, Queue<Runnable> tailTaskQueue,
                                    RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, addTaskWakesUp, taskQueue, rejectedExecutionHandler);
        tailTasks = ObjectUtil.checkNotNull(tailTaskQueue, "tailTaskQueue");
    }

    /**
     * 获得所属 EventLoopGroup
     * @return
     */
    @Override
    public EventLoopGroup parent() {
        // 覆盖父类方法，将返回值转换成 EventLoopGroup 类
        return (EventLoopGroup) super.parent();
    }

    /**
     * 获得自己
     * @return
     */
    @Override
    public EventLoop next() {
        // 覆盖父类方法，将返回值转换成 EventLoop 类
        return (EventLoop) super.next();
    }

    /**
     * 注册 Channel 到 EventLoop 上
     * @param channel
     * @return
     */
    @Override
    public ChannelFuture register(Channel channel) {
        /**
         * <1> 将 Channel 和 EventLoop 创建一个 DefaultChannelPromise 对象。
         * 通过这个 DefaultChannelPromise 对象，我们就能实现对异步注册过程的监听
         *
         * <2> 调用 #register(final ChannelPromise promise) 方法，注册 Channel 到 EventLoop 上
         */
        return register(new DefaultChannelPromise(channel, this));
    }

    @Override
    public ChannelFuture register(final ChannelPromise promise) {
        ObjectUtil.checkNotNull(promise, "promise");
        // 注册 Channel 到 EventLoop 上
        promise.channel().unsafe().register(this, promise);
        // 返回 ChannelPromise 对象
        return promise;
    }

    @Deprecated
    @Override
    public ChannelFuture register(final Channel channel, final ChannelPromise promise) {
        if (channel == null) {
            throw new NullPointerException("channel");
        }
        if (promise == null) {
            throw new NullPointerException("promise");
        }

        channel.unsafe().register(this, promise);
        return promise;
    }

    /**
     * Adds a task to be run once at the end of next (or current) {@code eventloop} iteration.
     *
     * @param task to be added.
     */
    @UnstableApi
    public final void executeAfterEventLoopIteration(Runnable task) {
        ObjectUtil.checkNotNull(task, "task");
        if (isShutdown()) {
            reject();
        }

        if (!tailTasks.offer(task)) {
            reject(task);
        }

        if (!(task instanceof LazyRunnable) && wakesUpForTask(task)) {
            wakeup(inEventLoop());
        }
    }

    /**
     * Removes a task that was added previously via {@link #executeAfterEventLoopIteration(Runnable)}.
     *
     * @param task to be removed.
     *
     * @return {@code true} if the task was removed as a result of this call.
     */
    @UnstableApi
    final boolean removeAfterEventLoopIterationTask(Runnable task) {
        return tailTasks.remove(ObjectUtil.checkNotNull(task, "task"));
    }

    @Override
    protected void afterRunningAllTasks() {
        runAllTasksFrom(tailTasks);
    }

    /**
     * 队列中是否有任务
     * 基于两个队列来判断是否还有任务
     * @return
     */
    @Override
    protected boolean hasTasks() {
        return super.hasTasks() || !tailTasks.isEmpty();
    }

    /**
     * 获得队列中的任务数
     * 计算两个队列的任务之和
     * @return
     */
    @Override
    public int pendingTasks() {
        return super.pendingTasks() + tailTasks.size();
    }

    /**
     * Returns the number of {@link Channel}s registered with this {@link EventLoop} or {@code -1}
     * if operation is not supported. The returned value is not guaranteed to be exact accurate and
     * should be viewed as a best effort.
     */
    @UnstableApi
    public int registeredChannels() {
        return -1;
    }
}
