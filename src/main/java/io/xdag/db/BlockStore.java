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
package io.xdag.db;

import io.xdag.core.*;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;

import java.util.List;
import java.util.function.Function;

public interface BlockStore {

    byte SETTING_STATS = (byte) 0x10;//indexSource  SETTING_STATS(一个字节) -> xdagStats
    byte TIME_HASH_INFO = (byte) 0x20;//timeSource  TIME_HASH_INFO(1个字节)+epoch(8个字节)  或者  TIME_HASH_INFO(1个字节)+epoch(8个字节)+hashlow(32字节)
    byte HASH_BLOCK_INFO = (byte) 0x30;//indexSource  HASH_BLOCK_INFO(一个字节)+hashLow(32字节) -> blockInfo     ,后面做快照时，该数据库会变成snapshotSource
    byte SUMS_BLOCK_INFO = (byte) 0x40;//indexSource  SUMS_BLOCK_INFO(一个字节)+四个文件夹名中的一个 -> sums
    byte OURS_BLOCK_INFO = (byte) 0x50;//indexSource  OURS_BLOCK_INFO(一个字节)+公钥位置索引(4个字节)+hashLow -> 0
    byte SETTING_TOP_STATUS = (byte) 0x60;//indexSource  SETTING_TOP_STATUS(一个字节) -> xdagTopStatus
    byte SNAPSHOT_BOOT = (byte) 0x70;//indexSource  SNAPSHOT_BOOT(一个字节) -> 1或者0
    byte BLOCK_HEIGHT = (byte) 0x80;//indexSource  BLOCK_HEIGHT(一个字节)+height(8个字节) -> blockInfo
    byte SNAPSHOT_PRESEED = (byte) 0x90;//indexSource  SNAPSHOT_PRESEED -> preseed
    byte TX_HISTORY = (byte) 0xa0;//txHistorySource  TX_HISTORY(1个字节)+引用的区块的hash(32个字节)+该引用所在区块的hash(32个字节)+该引用在区块的位置(4个字节) -> tx
    String SUM_FILE_NAME = "sums.dat";

    void init();

    void reset();

    XdagStats getXdagStatus();

    void saveXdagTopStatus(XdagTopStatus status);

    XdagTopStatus getXdagTopStatus();

    void saveBlock(Block block);

    void saveBlockInfo(BlockInfo blockInfo);

    void saveOurBlock(int index, byte[] hashlow);

    void saveTxHistoryToRocksdb(TxHistory txHistory,int id);

    List<TxHistory> getAllTxHistoryFromRocksdb();

    void deleteAllTxHistoryFromRocksdb();

    boolean hasBlock(Bytes32 hashlow);

    boolean hasBlockInfo(Bytes32 hashlow);

    List<Block> getBlocksUsedTime(long startTime, long endTime);

    List<Block> getBlocksByTime(long startTime);

    Block getBlockByHeight(long height);

    Block getBlockByHash(Bytes32 hashlow, boolean isRaw);

    Block getBlockInfoByHash(Bytes32 hashlow);

    Block getRawBlockByHash(Bytes32 hashlow);

    Bytes getOurBlock(int index);

    int getKeyIndexByHash(Bytes32 hashlow);

    void removeOurBlock(byte[] hashlow);

    void fetchOurBlocks(Function<Pair<Integer, Block>, Boolean> function);

    // Snapshot Boot
    boolean isSnapshotBoot();

    void setSnapshotBoot();

    // RandomX seed
    void savePreSeed(byte[] preseed);

    byte[] getPreSeed();

    // sums.dat and sum.dat
    void saveBlockSums(Block block);

    MutableBytes getSums(String key);

    void putSums(String key, Bytes sums);

    void updateSum(String key, long sum, long size, long index);

    int loadSum(long starttime, long endtime, MutableBytes sums);

    void saveXdagStatus(XdagStats status);

}
