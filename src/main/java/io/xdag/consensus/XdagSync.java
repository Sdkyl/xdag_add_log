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

package io.xdag.consensus;

import com.google.common.util.concurrent.SettableFuture;
import io.xdag.Kernel;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.config.MainnetConfig;
import io.xdag.config.TestnetConfig;
import io.xdag.core.Block;
import io.xdag.core.XdagState;
import io.xdag.db.BlockStore;
import io.xdag.net.Channel;
import io.xdag.net.ChannelManager;
import io.xdag.utils.XdagRandomUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.MutableBytes;

import java.nio.ByteOrder;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;

import static io.xdag.config.Constants.REQUEST_BLOCKS_MAX_TIME;
import static io.xdag.config.Constants.REQUEST_WAIT;

@Slf4j
public class XdagSync {

    private static final ThreadFactory factory = new BasicThreadFactory.Builder()
            .namingPattern("XdagSync-thread-%d")
            .daemon(true)
            .build();

    private final ChannelManager channelMgr;
    private final BlockStore blockStore;
    private final ScheduledExecutorService sendTask;
    @Getter
    private final ConcurrentHashMap<Long, SettableFuture<Bytes>> sumsRequestMap;
    @Getter
    private final ConcurrentHashMap<Long, SettableFuture<Bytes>> blocksRequestMap;

    private final LinkedList<Long> syncWindow = new LinkedList<>();

    @Getter
    @Setter
    private Status status;//内部枚举类，有两种情况:SYNCING, SYNC_DONE

    private final Kernel kernel;
    private ScheduledFuture<?> sendFuture;
    private volatile boolean isRunning;

    private long lastRequestTime;//最新的请求时间

    public XdagSync(Kernel kernel) {
        this.kernel = kernel;
        this.channelMgr = kernel.getChannelMgr();//简称，有东西了往外发
        this.blockStore = kernel.getBlockStore();
        sendTask = new ScheduledThreadPoolExecutor(1, factory);//定时任务的执行器，以什么方式执行什么任务呢？scheduleAtFixedRate(this::syncLoop, 32, 10, TimeUnit.SECONDS);这样
        sumsRequestMap = new ConcurrentHashMap<>();
        blocksRequestMap = new ConcurrentHashMap<>();
    }

    /**
     * 不断发送send request
     */
    public void start() {
        if (status != Status.SYNCING) {
            isRunning = true;
            status = Status.SYNCING;
            // TODO: paulochen 开始同步的时间点/快照时间点
//            startSyncTime = 1588687929343L; // 1716ffdffff 171e52dffff
            sendFuture = sendTask.scheduleAtFixedRate(this::syncLoop, 32, 10, TimeUnit.SECONDS);
        }
    }

    private void syncLoop() {
        try {
            if (syncWindow.isEmpty()) {
                log.debug("同步任务开始，开始找需要同步的块的起始时间");
                requestBlocks(0, 1L << 48);
            }//经过这个if应该会往syncWindow里添加时间

            log.debug("start getting blocks");
            getBlocks();
        } catch (Throwable e) {
            log.error("error when requestBlocks {}", e.getMessage());
        }
    }

    /**
     *  Use syncWindow to request blocks in segments.
     */
    private void getBlocks() {
        List<Channel> any = getAnyNode();
        if (any == null || any.isEmpty()) {
            return;
        }
        SettableFuture<Bytes> sf = SettableFuture.create();
        int index = XdagRandomUtils.nextInt() % any.size();
        Channel xc = any.get(index);
        long lastTime = getLastTime();

        // Extract the time that has been synchronized,
        while (!syncWindow.isEmpty() && syncWindow.get(0) < lastTime) {
            syncWindow.pollFirst();
        }

        // Segmented requests, each request for 32 time periods.
        int size = syncWindow.size();
        for (int i = 0; i < 128; i++) {
            if (i >= size) {
                break;
            }
            //when the synchronization process channel is removed and reset, update the channel
            if (!xc.isActive()){
                log.debug("sync channel need to update");
                return;
            }

            log.debug("activeChannel.size = {}",any.size());
            long time = syncWindow.get(i);
            if (time >= lastRequestTime) {
                log.debug("从时间：{}开始请求16个Epoch的块",time);
                sendGetBlocks(xc, time, sf);
                lastRequestTime = time;
            }

        }

    }


    /**
     * @param t start time
     * @param dt interval time
     */
    private void requestBlocks(long t, long dt) {// if (syncWindow.isEmpty())
        // Not in sync state, synchronization is complete, stop synchronization task.
        if (status != Status.SYNCING) {
            stop();
            return;
        }

        List<Channel> any = getAnyNode();
        if (any == null || any.isEmpty()) {
            return;
        }

        SettableFuture<Bytes> sf = SettableFuture.create();
        int index = XdagRandomUtils.nextInt() % any.size();
        Channel xc = any.get(index);
        if (dt > REQUEST_BLOCKS_MAX_TIME) {//新轮回第一次进入该requestBlocks方法时，会走该if
            findGetBlocks(xc, t, dt, sf);//Channel,start time,interval time,手动控制异步任务44,44，(参数的意思是，跟谁要多少时间段的区块，结果是什么)
        } else {
            if (!kernel.getSyncMgr().isSyncOld() && !kernel.getSyncMgr().isSync()) {
                log.debug("set sync old");
                setSyncOld();//kernel.setXdagState(XdagState.CONNP)
            }

            if (t > getLastTime()) {//只要自己当前主块之后高度的
                syncWindow.offerLast(t);
                log.debug("要的起始时间：{}开始的时间段的块，此时活跃通道的个数为：{}",t,any.size());
            }
        }
    }

    /**
     * Request blocks from remote nodes.
     * @param t request time
     */
    private void sendGetBlocks(Channel xc, long t, SettableFuture<Bytes> sf) {
        long randomSeq = xc.getP2pHandler().sendGetBlocks(t, t + REQUEST_BLOCKS_MAX_TIME);//16个Epoch
        blocksRequestMap.put(randomSeq, sf);
        try {
            sf.get(REQUEST_WAIT, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            blocksRequestMap.remove(randomSeq);
            log.error(e.getMessage(), e);
        }
    }


    /**
     * Recursively find the time periods to request.
     */
    private void findGetBlocks(Channel xc, long t, long dt, SettableFuture<Bytes> sf) {//Channel,start time,interval time,手动控制异步任务，这里t我的理解为历史最新时间
        MutableBytes lSums = MutableBytes.create(256);
        Bytes rSums;
        if (blockStore.loadSum(t, t + dt, lSums) <= 0) {//先取的Epoch的高位，此时的浓缩度最高，可做大范围检查，快速确认是否一致，lsums会被赋值
            return;
        }
        long randomSeq = xc.getP2pHandler().sendGetSums(t, t + dt);//发消息时用到的随机数
        sumsRequestMap.put(randomSeq, sf);
        try {
            Bytes sums = sf.get(REQUEST_WAIT, TimeUnit.SECONDS);//等64秒拿结果，拿到其他节点发过来的结果并且会逐个更新状态
            rSums = sums.copy();
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            sumsRequestMap.remove(randomSeq);
            log.error(e.getMessage(), e);
            return;
        }
        sumsRequestMap.remove(randomSeq);
        dt >>= 4;//
        for (int i = 0; i < 16; i++) {
            long lSumsSum = lSums.getLong(i * 16, ByteOrder.LITTLE_ENDIAN);
            long lSumsSize = lSums.getLong(i * 16 + 8, ByteOrder.LITTLE_ENDIAN);
            long rSumsSum = rSums.getLong(i * 16, ByteOrder.LITTLE_ENDIAN);
            long rSumsSize = rSums.getLong(i * 16 + 8, ByteOrder.LITTLE_ENDIAN);

            if (lSumsSize != rSumsSize || lSumsSum != rSumsSum) {
                requestBlocks(t + i * dt, dt);//dt得大于2的20次方，否则走另一个逻辑，(这里其实可以break了，因为后面已经没必要检查了，相同或者不相同都没有意义了，break后直接只用走走新request里的循环逻辑)
            }
        }
    }

    public void setSyncOld() {
        Config config = kernel.getConfig();
        if (config instanceof MainnetConfig) {
            if (kernel.getXdagState() != XdagState.CONNP) {
                kernel.setXdagState(XdagState.CONNP);
            }
        } else if (config instanceof TestnetConfig) {
            if (kernel.getXdagState() != XdagState.CTSTP) {
                kernel.setXdagState(XdagState.CTSTP);
            }
        } else if (config instanceof DevnetConfig) {
            if (kernel.getXdagState() != XdagState.CDSTP) {
                kernel.setXdagState(XdagState.CDSTP);
            }
        }
    }

    /**
     * Obtain the timestamp of the latest confirmed main block.
     */
    public long getLastTime() {
        long height = blockStore.getXdagStatus().nmain;
        if(height == 0) return 0;
        Block lastBlock = blockStore.getBlockByHeight(height);
        if (lastBlock != null) {
            return lastBlock.getTimestamp();
        }
        return 0;
    }

    public List<Channel> getAnyNode() {
        return channelMgr.getActiveChannels();
    }


    public void stop() {
        log.debug("stop sync");
        if (isRunning) {
            try {
                if (sendFuture != null) {
                    sendFuture.cancel(true);
                }
                // 关闭线程池
                sendTask.shutdownNow();
                sendTask.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.error(e.getMessage(), e);
            }
            isRunning = false;
            log.debug("Sync Stop");
        }
    }

    public boolean isRunning() {
        return isRunning;
    }

    public enum Status {
        /**
         * syncing
         */
        SYNCING, SYNC_DONE
    }
}
