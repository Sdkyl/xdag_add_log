/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.xdag.net;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultMessageSizeEstimator;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.NettyRuntime;
import io.xdag.Kernel;
import io.xdag.utils.NettyUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SystemUtils;

@Slf4j
public class PeerServer {
    private final Kernel kernel;
    private EventLoopGroup bossGroup;//每个 EventLoop 都会在一个单独的线程中运行，并负责处理与特定通道（Channel）相关的所有 I/O 操作。
    private EventLoopGroup workerGroup;
    private ChannelFuture channelFuture;//可以检查异步操作是否完成（成功或失败）,可以通过它查询该操作的状态,可以添加监听器,可以通过 channel() 方法获取与当前 ChannelFuture 关联的 Channel 对象。
    private final int workerThreadPoolSize = NettyRuntime.availableProcessors() * 2;

    public PeerServer(final Kernel kernel) {
        this.kernel = kernel;
    }

    public void start() {
        start(kernel.getConfig().getNodeSpec().getNodeIp(), kernel.getConfig().getNodeSpec().getNodePort());
    }

    public void start(String ip, int port) {
        try {

            if(SystemUtils.IS_OS_LINUX) {
                bossGroup = new EpollEventLoopGroup();//Group:管理事件的，可以一说是管理线程的
                workerGroup = new EpollEventLoopGroup(workerThreadPoolSize);
            } else if(SystemUtils.IS_OS_MAC) {
                bossGroup = new KQueueEventLoopGroup();
                workerGroup = new KQueueEventLoopGroup(workerThreadPoolSize);

            } else {
                bossGroup = new NioEventLoopGroup();
                workerGroup = new NioEventLoopGroup(workerThreadPoolSize);
            }

            ServerBootstrap b = NettyUtils.nativeEventLoopGroup(bossGroup, workerGroup);
            b.childOption(ChannelOption.TCP_NODELAY, true);
            b.childOption(ChannelOption.SO_KEEPALIVE, true);
            b.childOption(ChannelOption.MESSAGE_SIZE_ESTIMATOR, DefaultMessageSizeEstimator.DEFAULT);
            b.childOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, kernel.getConfig().getNodeSpec().getConnectionTimeout());
            b.handler(new LoggingHandler());
            b.childHandler(new XdagChannelInitializer(kernel, true, null));
            log.debug("Xdag Node start host:[{}:{}].", ip, port);
            channelFuture = b.bind(ip, port).sync();//调用 sync() 方法会使当前线程等待，直到与 ChannelFuture 相关联的操作（例如连接、写数据、关闭通道等）完成。这个过程是阻塞的，即在此期间，当前线程不能执行其他操作。
        } catch (Exception e) {
            log.error("Xdag Node start error:{}.", e.getMessage(), e);
        }
    }

    public void close() {
        if (channelFuture != null && channelFuture.channel().isOpen()) {
            try {
                channelFuture.channel().close().sync();
                workerGroup.shutdownGracefully();
                bossGroup.shutdownGracefully();
                log.debug("Xdag Node closed.");
            } catch (Exception e) {
                log.error("Xdag Node close error:{}", e.getMessage(), e);
            }
        }
    }

}
