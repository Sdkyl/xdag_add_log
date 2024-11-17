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

package io.xdag;

import io.xdag.cli.TelnetServer;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.config.MainnetConfig;
import io.xdag.config.TestnetConfig;
import io.xdag.consensus.SyncManager;
import io.xdag.consensus.XdagPow;
import io.xdag.consensus.XdagSync;
import io.xdag.core.*;
import io.xdag.crypto.Keys;
import io.xdag.crypto.RandomX;
import io.xdag.db.*;
import io.xdag.db.mysql.TransactionHistoryStoreImpl;
import io.xdag.db.rocksdb.*;
import io.xdag.net.*;
import io.xdag.net.message.MessageQueue;
import io.xdag.net.node.NodeManager;
import io.xdag.net.websocket.WebSocketServer;
import io.xdag.pool.PoolAwardManagerImpl;
import io.xdag.rpc.Web3;
import io.xdag.rpc.Web3Impl;
import io.xdag.rpc.cors.CorsConfiguration;
import io.xdag.rpc.modules.xdag.*;
import io.xdag.rpc.netty.*;
import io.xdag.utils.XdagTime;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.crypto.KeyPair;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Getter
@Setter
public class Kernel {

    protected Status status = Status.STOPPED;
    protected Config config;
    protected Wallet wallet;
    protected KeyPair coinbase;
    protected DatabaseFactory dbFactory;
    protected AddressStore addressStore;
    protected BlockStore blockStore;
    protected OrphanBlockStore orphanBlockStore;
    protected TransactionHistoryStore txHistoryStore;

    protected SnapshotStore snapshotStore;
    protected Blockchain blockchain;
    protected NetDB netDB;
    protected PeerClient client;
    protected ChannelManager channelMgr;
    protected NodeManager nodeMgr;
    protected NetDBManager netDBMgr;
    protected PeerServer p2p;
    protected XdagSync sync;
    protected XdagPow pow;
    private SyncManager syncMgr;

    protected byte[] firstAccount;
    protected Block firstBlock;
    protected WebSocketServer webSocketServer;
    protected PoolAwardManagerImpl poolAwardManager;
    protected XdagState xdagState;

    protected AtomicInteger channelsAccount = new AtomicInteger(0);

    protected TelnetServer telnetServer;

    protected RandomX randomx;

    // 记录运行状态
    protected AtomicBoolean isRunning = new AtomicBoolean(false);//启动后有更改
    // 记录启动时间片
    @Getter
    protected long startEpoch;


    // rpc
    private JsonRpcWeb3ServerHandler jsonRpcWeb3ServerHandler;
    private Web3 web3;
    private Web3HttpServer web3HttpServer;
    private JsonRpcWeb3FilterHandler jsonRpcWeb3FilterHandler;

    public Kernel(Config config, Wallet wallet) {
        this.config = config;
        this.wallet = wallet;
        this.coinbase = wallet.getDefKey();
        this.xdagState = XdagState.INIT;
        this.telnetServer = new TelnetServer(config.getAdminSpec().getTelnetIp(), config.getAdminSpec().getTelnetPort(),
                this);//注意一下此时的this，有点意思
    }

    public Kernel(Config config, KeyPair coinbase) {//感觉kernel类就像是拿着配置文件里得到的属性值去给大家伙分别分发属性值，这些属性值可能不足以完全使得各对象完全初始化，其一定要写相应方法让外界能够改变该属性
        this.config = config;
        this.coinbase = coinbase;
    }

    /**
     * Start the kernel.
     */
    public synchronized void testStart() throws Exception {
        if (isRunning.get()) {
            return;
        }
        isRunning.set(true);
        startEpoch = XdagTime.getCurrentEpoch();
        log.debug("启动Epoch: {}",startEpoch);

        // ====================================
        // start channel manager
        // ====================================
        channelMgr = new ChannelManager(this);//
        channelMgr.start();
        log.info("Channel Manager start...");
        netDBMgr = new NetDBManager(this.config);
        netDBMgr.init();
        log.info("NetDB Manager init.");

        // ====================================
        // wallet init
        // ====================================

//        if (wallet == null) {
//        wallet = new OldWallet();
//        wallet.init(this.config);

        log.info("Wallet init.");

        dbFactory = new RocksdbFactory(this.config);
        blockStore = new BlockStoreImpl(
                dbFactory.getDB(DatabaseName.INDEX),//indexSource
                dbFactory.getDB(DatabaseName.BLOCK),//timeSource
                dbFactory.getDB(DatabaseName.TIME),//这个数据库名字叫TIME
                dbFactory.getDB(DatabaseName.TXHISTORY));//txHistorySource
        log.info("Block Store init.");
        blockStore.init();//indexSource、timeSource、blockSource、txHistorySource

        addressStore = new AddressStoreImpl(dbFactory.getDB(DatabaseName.ADDRESS));//各用户余额，以及用户总数，以及所有地址余额总和
        addressStore.init();//ADDRESS_SIZE,AMOUNT_SUM
        log.info("Address Store init");

        orphanBlockStore = new OrphanBlockStoreImpl(dbFactory.getDB(DatabaseName.ORPHANIND));
        log.info("Orphan Pool init.");
        orphanBlockStore.init();

        if (config.getEnableTxHistory()) {
            long txPageSizeLimit = config.getTxPageSizeLimit();
            txHistoryStore = new TransactionHistoryStoreImpl(txPageSizeLimit);
            log.info("Transaction History Store init.");
        }

        // ====================================
        // netstatus netdb init
        // ====================================
        netDB = new NetDB();
        log.info("NetDB init");

        // ====================================
        // randomX init
        // ====================================
        randomx = new RandomX(config);//..
        randomx.init();
//        randomXUtils.randomXLoadingForkTime();
        log.info("RandomX init");

        // ====================================
        // initialize blockchain database
        // ====================================
        blockchain = new BlockchainImpl(this);//根据此时的账本状态，去干对应状态该干的事
        XdagStats xdagStats = blockchain.getXdagStats();
        // 如果是第一次启动，则新建一个创世块
        if (xdagStats.getOurLastBlockHash() == null) {
            firstAccount = Keys.toBytesAddress(wallet.getDefKey().getPublicKey());
            firstBlock = new Block(config, XdagTime.getCurrentTimestamp(), null, null, false, null, null, -1, XAmount.ZERO);
            firstBlock.signOut(wallet.getDefKey());
            xdagStats.setOurLastBlockHash(firstBlock.getHashLow().toArray());
            if (xdagStats.getGlobalMiner() == null) {
                xdagStats.setGlobalMiner(firstAccount);
            }
            blockchain.tryToConnect(firstBlock);//这里创世快得节点自己自行加载,且会在这里面执行区块保存操作，这里面会把该区块置为MAIN-CHAIN
        } else {
            firstAccount = Keys.toBytesAddress(wallet.getDefKey().getPublicKey());
        }
        log.info("Blockchain init");

        // randomX loading
        // TODO: paulochen randomx 需要恢复
        // 初次快照启动
        if (config.getSnapshotSpec().isSnapshotJ()) {
            randomx.randomXLoadingSnapshotJ();//共识算法
            blockStore.setSnapshotBoot();//I will know next time it starts that the snapshot has been started
        } else {
            if (config.getSnapshotSpec().isSnapshotEnabled() && !blockStore.isSnapshotBoot()) {
                // TODO: forkTime 怎么获得
                System.out.println("pre seed:" + Bytes.wrap(blockchain.getPreSeed()).toHexString());
                randomx.randomXLoadingSnapshot(blockchain.getPreSeed(), 0);
                // 设置为已通过快照启动
                blockStore.setSnapshotBoot();
            } else if (config.getSnapshotSpec().isSnapshotEnabled() && blockStore.isSnapshotBoot()) { // 快照加载后重启
                System.out.println("pre seed:" + Bytes.wrap(blockchain.getPreSeed()).toHexString());
                randomx.randomXLoadingForkTimeSnapshot(blockchain.getPreSeed(), 0);
            } else {
                randomx.randomXLoadingForkTime();//与算法开始使用高度有关的相关操作
            }
        }

        log.info("RandomX reload");

        // log.debug("Net Status:"+netStatus);

        // ====================================
        // set up client
        // ====================================

        p2p = new PeerServer(this);
        p2p.start();
        log.info("Node server start...");
        client = new PeerClient(this.config, this.coinbase);

//        libp2pNetwork = new Libp2pNetwork(this);
//        libp2pNetwork.start();

//        discoveryController = new DiscoveryController(this);
//        discoveryController.start();

        // ====================================
        // start node manager
        // ====================================
        nodeMgr = new NodeManager(this);
        nodeMgr.start();//完成本客户端节点和外界节点的连接
        log.info("Node manager start...");

        // ====================================
        // send request
        // ====================================
        sync = new XdagSync(this);
        sync.start();//设为Status.SYNCING，去开启同步任务
        log.info("XdagSync start...");

        // ====================================
        // sync block
        // ====================================
        syncMgr = new SyncManager(this);
        syncMgr.start();
        log.info("SyncManager start...");
        poolAwardManager = new PoolAwardManagerImpl(this);//这里初始了Command
        // ====================================
        // pow
        // ====================================
        pow = new XdagPow(this);
        getWsServer().start();//开启矿池之间的通信
        log.info("Node to pool websocket start...");
        // register pow
        blockchain.registerListener(pow);
        if (config instanceof MainnetConfig) {
            xdagState = XdagState.WAIT;
        } else if (config instanceof TestnetConfig) {
            xdagState = XdagState.WTST;
        } else if (config instanceof DevnetConfig) {
            xdagState = XdagState.WDST;
        }

        // ====================================
        // rpc start
        // ====================================
        if (config.getRPCSpec().isRPCEnabled()) {
            getWeb3HttpServer().start();
        }

        // ====================================
        // telnet server
        // ====================================
        telnetServer.start();

        Launcher.registerShutdownHook("kernel", this::testStop);
    }

    private Web3 getWeb3() {
        if (web3 == null) {
            web3 = buildWeb3();
        }

        return web3;
    }

    private Web3 buildWeb3() {
        Web3XdagModule web3XdagModule = new Web3XdagModuleImpl(
                new XdagModule((byte) 0x1, new XdagModuleWalletDisabled(),
                        new XdagModuleTransactionEnabled(this),
                        new XdagModuleChainBase(this.getBlockchain(), this)), this);
        return new Web3Impl(web3XdagModule);
    }

    private JsonRpcWeb3ServerHandler getJsonRpcWeb3ServerHandler() {
        if (jsonRpcWeb3ServerHandler == null) {
            try {
                jsonRpcWeb3ServerHandler = new JsonRpcWeb3ServerHandler(
                        getWeb3(),
                        config.getRPCSpec().getRpcModules()
                );
            } catch (Exception e) {
                log.error("catch an error {}", e.getMessage());
            }
        }

        return jsonRpcWeb3ServerHandler;
    }

    private Web3HttpServer getWeb3HttpServer() throws UnknownHostException {
        if (web3HttpServer == null) {
            web3HttpServer = new Web3HttpServer(
                    InetAddress.getByName(config.getRPCSpec().getRPCHost()),
                    config.getRPCSpec().getRPCPortByHttp(),
                    123,
                    true,
                    new CorsConfiguration("*"),
                    getJsonRpcWeb3FilterHandler(),
                    getJsonRpcWeb3ServerHandler()
            );
        }

        return web3HttpServer;
    }

    public WebSocketServer getWsServer() {
        if (webSocketServer == null) {
            webSocketServer = new WebSocketServer(this, config.getPoolWhiteIPList(),
                    config.getWebsocketServerPort());
        }
        return webSocketServer;
    }

    private JsonRpcWeb3FilterHandler getJsonRpcWeb3FilterHandler() throws UnknownHostException {
        if (jsonRpcWeb3FilterHandler == null) {
            jsonRpcWeb3FilterHandler = new JsonRpcWeb3FilterHandler(
                    "*",
                    InetAddress.getByName(config.getRPCSpec().getRPCHost()),
                    Collections.singletonList(config.getRPCSpec().getRPCHost())
            );
        }

        return jsonRpcWeb3FilterHandler;
    }

    /**
     * Stops the kernel.
     */
    public synchronized void testStop() {

        if (!isRunning.get()) {
            return;
        }

        isRunning.set(false);

        //
        if (web3HttpServer != null) {
            web3HttpServer.stop();
        }

        // 1. 工作层关闭
        // stop consensus
        sync.stop();
        log.info("XdagSync stop.");
        syncMgr.stop();
        log.info("SyncManager stop.");
        pow.stop();
        log.info("Block production stop.");

        // 2. 连接层关闭
        // stop node manager and channel manager
        channelMgr.stop();
        log.info("ChannelMgr stop.");
        nodeMgr.stop();
        log.info("Node manager stop.");

        log.info("ChannelManager stop.");

        // close timer
        MessageQueue.timer.shutdown();

        // close server
        p2p.close();
        log.info("Node server stop.");
        // close client
        client.close();
        log.info("Node client stop.");

        // 3. 数据层关闭
        // TODO 关闭checkmain线程
        blockchain.stopCheckMain();

        for (DatabaseName name : DatabaseName.values()) {
            dbFactory.getDB(name).close();
        }

        // release
        randomx.randomXPoolReleaseMem();
        log.info("Release randomx");
        webSocketServer.stop();
        log.info("WebSocket server stop.");
        poolAwardManager.stop();
        log.info("Pool award manager stop.");
    }

    public enum Status {
        STOPPED, SYNCING, BLOCK_PRODUCTION_ON, SYNCDONE
    }
}
