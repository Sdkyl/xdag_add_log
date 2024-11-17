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

package io.xdag.net.node;


import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.xdag.Kernel;
import io.xdag.config.Config;
import io.xdag.net.Channel;
import io.xdag.net.PeerClient;
import io.xdag.net.XdagChannelInitializer;
import io.xdag.net.NetDBManager;
import io.xdag.net.ChannelManager;
import io.xdag.net.NetDB;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;

@Slf4j
public class NodeManager {

    private static final ThreadFactory factory = new BasicThreadFactory.Builder()
            .namingPattern("NodeManager-thread-%d")
            .daemon(true)
            .build();

    private static final long MAX_QUEUE_SIZE = 1024;
    private static final int LRU_CACHE_SIZE = 1024;
    private static final long RECONNECT_WAIT = 60L * 1000L;
    private final Deque<Node> deque = new ConcurrentLinkedDeque<>();
    /**
     * 记录对应节点节点最后一次连接的时间
     */
    private final Cache<Node, Long> lastConnect = Caffeine.newBuilder().maximumSize(LRU_CACHE_SIZE).build();
    /**
     * 定时处理
     */
    private final ScheduledExecutorService exec;
    private final Kernel kernel;
    private final PeerClient client;
    private final ChannelManager channelMgr;
    private final NetDBManager netDBManager;
    private final Config config;
    private volatile boolean isRunning;
    private ScheduledFuture<?> connectFuture;
    private ScheduledFuture<?> fetchFuture;

    public NodeManager(Kernel kernel) {
        this.kernel = kernel;
        this.client = kernel.getClient();
        this.channelMgr = kernel.getChannelMgr();
        this.exec = new ScheduledThreadPoolExecutor(1, factory);
        this.config = kernel.getConfig();
        this.netDBManager = kernel.getNetDBMgr();
    }

    /**
     * start the node manager
     */
    public synchronized void start() {
        if (!isRunning) {
            // addNodes(getSeedNodes(config.getWhiteListDir()));
            addNodes(getSeedNodes(netDBManager.getWhiteDB()));//Add address and then,connect

            // every 0.5 seconds, delayed by 1 seconds (kernel boot up)
            connectFuture = exec.scheduleAtFixedRate(this::doConnect, 1000, 500, TimeUnit.MILLISECONDS);
            // every 100 seconds, delayed by 5 seconds (public IP lookup)
            fetchFuture = exec.scheduleAtFixedRate(this::doFetch, 5, 100, TimeUnit.SECONDS);

            isRunning = true;
            log.debug("Node manager started");
        }
    }

    public synchronized void stop() {
        if (isRunning) {
            connectFuture.cancel(true);
            fetchFuture.cancel(false);
            isRunning = false;
            exec.shutdown();
            log.debug("Node manager stop...");
        }
    }

    public int queueSize() {
        return deque.size();
    }

    public void addNodes(Collection<Node> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        for (Node node : nodes) {
            addNode(node);
        }
    }

    public void addNode(Node node) {
        if (deque.contains(node)) {
            return;
        }
        deque.addFirst(node);
        while (queueSize() > MAX_QUEUE_SIZE) {
            deque.removeLast();
        }
    }


    /**
     * from net update seed nodes
     */
    protected void doFetch() {
        log.debug("Do fetch node size:{}", deque.size());
        log.debug("更新前的节点个数为:{}", deque.size());
        if (config.getNodeSpec().enableRefresh()) {//false
            netDBManager.refresh();
        }
        // 从白名单获得新节点
        addNodes(getSeedNodes(netDBManager.getWhiteDB()));
        // 从netdb获取新节点
        addNodes(getSeedNodes(netDBManager.getNetDB()));
        log.debug("更新完后的节点个数为：{}",deque.size());
    }

    public Set<Node> getSeedNodes(NetDB netDB) {
        if (netDB != null && netDB.getSize() != 0) {
            return netDB.getIPList();
        } else {
            return null;
        }
    }

    public void doConnect() {
        Set<InetSocketAddress> activeAddress = channelMgr.getActiveAddresses();
        log.debug("activeAddress的大小为{}",activeAddress.size());
        Node node;

        while ((node = deque.pollFirst()) != null && channelMgr.size() < config.getNodeSpec().getMaxConnections()) {
            Long lastCon = lastConnect.getIfPresent(node);
            long now = System.currentTimeMillis();

            if (!client.getNode().equals(node)
                    && !node.equals(client.getNode())
                    && !activeAddress.contains(node.getAddress())
                    && (lastCon == null || lastCon + RECONNECT_WAIT < now)) {
                XdagChannelInitializer initializer = new XdagChannelInitializer(kernel, false, node);
                final Node finalNode = node;
                //client.connect(node, initializer);
                ChannelFuture future = client.connect(node, initializer);
                future.addListener((ChannelFutureListener) f -> {
                    if (f.isSuccess()) {
                        log.debug("完成与{}的连接",finalNode.toString());
                    } else {
                        log.debug("未完成与{}的连接",finalNode.toString());
                        //Throwable cause = f.cause();
                        //System.err.println("Connection failed: " + cause.getMessage());
                    }
                });
                lastConnect.put(node, now);
                //log.debug("完成与{}，{}的连接",node.getIp(),node.getPort());
                break;
            }
        }

    }

    public void doConnect(String ip, int port) {
        Set<InetSocketAddress> activeAddresses = channelMgr.getActiveAddresses();
        Node remotenode = new Node(ip, port);
        if (!client.getNode().equals(remotenode) && !activeAddresses.contains(remotenode.toAddress())) {
            XdagChannelInitializer initializer = new XdagChannelInitializer(kernel, false, remotenode);
            client.connect(new Node(ip, port), initializer);
        }
    }

}
