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

package io.xdag.core;

import java.math.BigInteger;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
//该全节点(本地)的链状态
public class XdagStats {

    public BigInteger difficulty;
    public BigInteger maxdifficulty;
    public long nblocks;
    public long totalnblocks;
    public long nmain;//本地最新区块高度
    public long totalnmain;
    public int nhosts;
    public int totalnhosts;
    public long nwaitsync;
    public long nnoref;
    public long nextra;
    public long maintime;
    public XAmount balance = XAmount.ZERO;//区块里的钱

    private byte[] globalMiner;//该钱包的账户地址
    private byte[] ourLastBlockHash;

    public XdagStats() {
        difficulty = BigInteger.ZERO;
        maxdifficulty = BigInteger.ZERO;
    }

    /**
     * 用于记录remote node的
     */
    public XdagStats(
            BigInteger maxdifficulty,
            long totalnblocks,
            long totalnmain,
            int totalnhosts,
            long maintime) {
        this.maxdifficulty = maxdifficulty;
        this.totalnblocks = totalnblocks;
        this.totalnmain = totalnmain;
        this.totalnhosts = totalnhosts;
        this.maintime = maintime;
    }

    public XdagStats(XdagStats xdagStats) {
        this.difficulty = xdagStats.difficulty;
        this.maxdifficulty = xdagStats.maxdifficulty;
        this.nblocks = xdagStats.nblocks;
        this.totalnblocks = xdagStats.totalnblocks;
        this.nmain = xdagStats.nmain;
        this.totalnmain = xdagStats.totalnmain;
        this.nhosts = xdagStats.nhosts;
        this.totalnhosts = xdagStats.totalnhosts;
    }

    public void init(BigInteger diff, long totalnmain, long totalnblocks) {
        this.difficulty = this.maxdifficulty = diff;
        this.nblocks = this.totalnblocks = totalnblocks;
        this.nmain = this.totalnmain = totalnmain;
    }

    public void update(XdagStats remoteXdagStats) {//只更新全网的状态情况，自身的不改
        this.totalnhosts = Math.max(this.totalnhosts, remoteXdagStats.totalnhosts);
        this.totalnblocks = Math.max(this.totalnblocks, remoteXdagStats.totalnblocks);
        this.totalnmain = Math.max(this.totalnmain, remoteXdagStats.totalnmain);
        if (this.maxdifficulty != null && remoteXdagStats.maxdifficulty != null
                && remoteXdagStats.maxdifficulty.compareTo(this.maxdifficulty) > 0) {
            this.maxdifficulty = remoteXdagStats.maxdifficulty;
        }
    }

    public void updateMaxDiff(BigInteger maxdifficulty) {
        if (this.getMaxdifficulty().compareTo(maxdifficulty) < 0) {
            this.maxdifficulty = maxdifficulty;
        }
    }

    public void updateDiff(BigInteger difficulty) {
        if (this.difficulty.compareTo(difficulty) <0) {
            this.difficulty = difficulty;
        }
    }

    @Override
    public String toString() {
        return "XdagStatus[nmain:" +
                this.nmain + ",totalmain:" + this.totalnmain + ",nblocks:" + this.nblocks + ",totalblocks:"
                + this.totalnblocks
                + "]";
    }
}
