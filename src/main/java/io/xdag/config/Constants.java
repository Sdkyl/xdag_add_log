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

package io.xdag.config;

import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import org.apache.tuweni.units.bigints.UInt64;

public class Constants {

    public static final long MAIN_CHAIN_PERIOD = 64 << 10;

    /**
     * setmain设置区块为主块时标志该位
     */
    public static final byte BI_MAIN = 0x01;//0000,0001
    /**
     * 跟BI_MAIN差不多 不过BI_MAIN是确定的 BI_MAIN_CHAIN是还未确定的
     */
    public static final byte BI_MAIN_CHAIN = 0x02;//0000,0010
    /**
     * 区块被应用apply后可能会标志该标识位（因为有可能区块存在问题不过还是被指向了 但是会标示为拒绝状态）
     */
    public static final byte BI_APPLIED = 0x04;//0000,0100
    /**
     * 区块应用apply过后会置该标识位
     */
    public static final byte BI_MAIN_REF = 0x08;//0000,1000
    /**
     * 从孤块链中移除 即有区块链接孤块的时候 将孤块置为BI_REF(referenced)             有区块指向或者有区块包含了(链接了)该块
     */
    public static final byte BI_REF = 0x10;//0001,0000
    /**
     * 添加区块时如果该区块的签名可以用自身的公钥解 则说明该区块是自己的区块
     */
    public static final byte BI_OURS = 0x20;//0010,0000
    /**
     * 候补主块未持久化
     */
    public static final byte BI_EXTRA = 0x40;//0100,0000
    public static final byte BI_REMARK = (byte) 0x80;//1000,0000
    public static final Long SEND_PERIOD = 10L;//.....1010

    public static final long REQUEST_BLOCKS_MAX_TIME = UInt64.valueOf(1L << 20).toLong();
    public static final long REQUEST_WAIT = 64;
    public static final long MAX_ALLOWED_EXTRA = 65536;//64*1024
    /**
     * 每一轮的确认数是16
     */
    public static final int CONFIRMATIONS_COUNT = 16;
    public static final int MAIN_BIG_PERIOD_LOG = 21;

    public static final String WALLET_FILE_NAME = "wallet.data";


    public static final String CLIENT_NAME = "xdagj";

    public static final String CLIENT_VERSION = System.getProperty("xdagj.version");

    /**
     * 同步问题 分叉高度
     */
    public static final Long SYNC_FIX_HEIGHT = 0L;

    public static final int HASH_RATE_LAST_MAX_TIME = 32;

    public enum MessageType {
        UPDATE,
        PRE_TOP,
        NEW_LINK
    }

    public static final short MAINNET_VERSION = 0;
    public static final short TESTNET_VERSION = 0;
    public static final short DEVNET_VERSION = 0;

    public static final XAmount MIN_GAS = XAmount.of(100, XUnit.MILLI_XDAG);

}
