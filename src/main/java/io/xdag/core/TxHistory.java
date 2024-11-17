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

import lombok.Getter;
import lombok.Setter;

import static io.xdag.utils.BasicUtils.hash2Address;
import static io.xdag.utils.BasicUtils.hash2byte;
import static io.xdag.utils.WalletUtils.toBase58;

@Getter
@Setter
public class TxHistory {

    Address address;//出钱的块或者地址，以及收钱的地址
    String hash;//交易块hash
    long timestamp;
    String remark;

    public TxHistory(){}

    public TxHistory(Address address, String hash, long timestamp, String remark) {
        this.address = address;
        this.hash = hash;
        this.timestamp = timestamp;
        this.remark = remark;
    }

    @Override
    public String toString() {
        String addr = address.getIsAddress() ?
                toBase58(hash2byte(address.getAddress())) :
                hash2Address(address.getAddress());

        return String.format("[addr:%s, hash:%s, type:%s, amount:%s, remark:%s, time:%s]",
                addr,
                hash,
                address.getType().asByte(),
                address.getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
                remark,
                timestamp);
    }
}
