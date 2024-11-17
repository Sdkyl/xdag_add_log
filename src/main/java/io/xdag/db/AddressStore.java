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

import org.apache.tuweni.units.bigints.UInt64;

import io.xdag.core.XAmount;

public interface AddressStore {

    byte ADDRESS_SIZE = (byte) 0x10;//ADDRESS_SIZE -> 总地址数
    byte AMOUNT_SUM = (byte) 0x20;//AMOUNT_SUM -> 总金额
    byte ADDRESS = (byte) 0x30;//ADDRESS(一个字节)+hashLow ->8个字节(金额)

    void init();

    void reset();

    XAmount getBalanceByAddress(byte[] Address);//此处的输入是地址的双hash(长度20)，还未经过base58编码

    boolean addressIsExist(byte[] Address);

    void addAddress(byte[] Address);

    XAmount getAllBalance();

    void saveAddressSize(byte[] addressSize);

    void savaAmountSum(XAmount balanceSum);

    void updateAllBalance(XAmount balance);

    UInt64 getAddressSize();

    void updateBalance(byte[] address, XAmount balance);

    void snapshotAddress(byte[] address, XAmount balance);

}
