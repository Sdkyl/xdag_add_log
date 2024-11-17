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

package io.xdag.utils;

import com.google.common.primitives.UnsignedLong;
import io.xdag.core.XAmount;
import io.xdag.crypto.Keys;
import io.xdag.utils.exception.XdagOverFlowException;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import org.apache.tuweni.bytes.MutableBytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.hyperledger.besu.crypto.KeyPair;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import static io.xdag.config.Constants.HASH_RATE_LAST_MAX_TIME;
import static io.xdag.utils.BytesUtils.equalBytes;
import static io.xdag.utils.BytesUtils.long2UnsignedLong;
import static io.xdag.utils.WalletUtils.toBase58;

public class BasicUtils {

    public static BigInteger getDiffByHash(Bytes32 hash) {
        MutableBytes data = MutableBytes.create(16);
        // 实现了右移32位 4个字节
        data.set(4, hash.slice(0, 12));
        BigInteger res = new BigInteger(data.toUnprefixedHexString(), 16);
        // max是2的128次方减1 这样效率高吗
        BigInteger max = new BigInteger("ffffffffffffffffffffffffffffffff", 16);
        return max.divide(res);
    }

    public static Bytes32 getHash(String address) {
        Bytes32 hash = null;
        if (address != null) {
            hash = Bytes32.fromHexString(address);//将16进制字符串转换成32字节长度的字节数组；不足补零，超了报异常
        }
        return hash;
    }

    public static double getDouble(String value) {
        double num = Double.parseDouble(value);
        BigDecimal bigDecimal = new BigDecimal(num);
        return bigDecimal.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public static String hash2Address(Bytes32 hash) {
        return hash.reverse().slice(0, 24).toBase64String();
    }
    public static String hash2PubAddress(Bytes32 hash) {
       return toBase58(hash2byte(hash.mutableCopy()));
    }
    public static MutableBytes32 hexPubAddress2Hashlow(String hexPubAddress){
        Bytes hash = Bytes.fromHexString(hexPubAddress);
        MutableBytes32 hashLow = MutableBytes32.create();
        hashLow.set(8, hash);
        return hashLow;
    }

    public static Bytes32 address2Hash(String address) {
        Bytes ret = Bytes.fromBase64String(address);
        MutableBytes32 res = MutableBytes32.create();
        res.set(8, ret.reverse().slice(0, 24));
        return res;
    }

    public static Bytes32 pubAddress2Hash(String address) {
        Bytes ret = Bytes.wrap(WalletUtils.fromBase58(address));//账户地址解码后，会得到长度为20的字节数组
        MutableBytes32 res = MutableBytes32.create();
        res.set(8, ret);
        return res;
    }

    public static Bytes32 keyPair2Hash(KeyPair keyPair) {
        Bytes ret = Bytes.wrap(Keys.toBytesAddress(keyPair));
        MutableBytes32 res = MutableBytes32.create();
        res.set(8, ret);
        return res;
    }

    public static byte[] hash2byte(MutableBytes32 hash){
        Bytes bytes = hash.slice(8,20);
        return bytes.toArray();
    }
    public static byte[] hash2byte(Bytes32 hash){
        Bytes bytes = hash.slice(8,20);
        return bytes.toArray();
    }

    public static UInt64 xdag2amount(double input) {
        if (input < 0) {
            throw new XdagOverFlowException();
        }
        long amount = (long) Math.floor(input);

        UInt64 res = UInt64.valueOf(amount).shiftLeft(32);
        input -= amount; // 小数部分
        input = input * Math.pow(2, 32);
        long tmp = (long) Math.ceil(input);
        return res.add(tmp);
    }

    /**
     * Xfer:transferred   44796588980   10.430000000 XDAG to the address 0000002f28322e9d817fd94a1357e51a. 10.43
     * Xfer:transferred   42949672960   10.000000000 XDAG to the address 0000002f28322e9d817fd94a1357e51a. 10
     * Xfer:transferred 4398046511104 1024.000000000 XDAG to the address 0000002f28322e9d817fd94a1357e51a. 1024
     */
    public static double amount2xdag(long xdag) {
        if (xdag < 0) {
            throw new XdagOverFlowException();
        }
        long first = xdag >>> 32;
        long temp = xdag - (first << 32);
        double tem = temp / Math.pow(2, 32);
        BigDecimal bigDecimal = new BigDecimal(first + tem);
        return bigDecimal.setScale(12, RoundingMode.HALF_UP).doubleValue();
    }

    public static double amount2xdag(UInt64 xdag) {
        UInt64 first = xdag.shiftRight(32);
        UInt64 temp = xdag.subtract(first.shiftLeft(32));
        double tem = 1.0*temp.toLong()/Math.pow(2, 32);
        BigDecimal bigDecimal = new BigDecimal(first.toLong() + tem);
        return bigDecimal.setScale(12, RoundingMode.HALF_UP).doubleValue();
    }

    public static boolean crc32Verify(byte[] src, int crc) {
        CRC32 crc32 = new CRC32();
        crc32.update(src, 0, 512);
        return equalBytes(
                BytesUtils.intToBytes((int) crc32.getValue(), true), BytesUtils.intToBytes(crc, true));
    }

    public static double xdag_diff2log(BigInteger diff) {
        if (diff.compareTo(BigInteger.ZERO) > 0) {
            return Math.log(diff.doubleValue());
        } else {
            return 0.0;
        }
    }


    public static double xdagHashRate(BigInteger[] diffs){
        double sum = 0;
        for (int i = 0; i < HASH_RATE_LAST_MAX_TIME; i++) {
            sum += xdag_diff2log(diffs[i]);
        }
        sum /= HASH_RATE_LAST_MAX_TIME;
        return Math.exp(sum) * Math.pow(2, -48);
    }

    public static int compareAmountTo(long amount1, long amount2) {
        return long2UnsignedLong(amount1).compareTo(long2UnsignedLong(amount2));
    }

    public static int compareAmountTo(XAmount amount1, XAmount amount2) {
        return amount1.compareTo(amount2);
    }

    public static int compareAmountTo(UInt64 amount1, UInt64 amount2) {
        return amount1.compareTo(amount2);
    }

    public static int compareAmountTo(UnsignedLong amount1, UnsignedLong amount2) {
        return amount1.compareTo(amount2);
    }

    public static BigDecimal amount2xdagNew(long xdag) {
        if(xdag < 0) throw new XdagOverFlowException();
        long first = xdag >> 32;
        long temp = xdag - (first << 32);
        double tem = temp / Math.pow(2, 32);
        return new BigDecimal(first + tem);
    }
    /**
     * @param v1 dividend
     * @param v2 divisor
     * @param scale Accurate to the number of digits after the decimal point
     * @return The result after rounding
     */
    public static double div(double v1, double v2, int scale) {
        if (scale < 0) {
            throw new IllegalArgumentException("The scale must be a positive integer or zero");
        }
        BigDecimal b1 = BigDecimal.valueOf(v1);
        BigDecimal b2 = BigDecimal.valueOf(v2);
        return b1.divide(b2, scale, RoundingMode.HALF_UP).doubleValue();
    }
    // Parse and extract IP addresses, for example /133.22.245.177:11328 -> 133.22.245.177
    public static String extractIpAddress(String ipAddressAndPort) {
        String pattern = "/(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}):\\d+";
        Pattern r = Pattern.compile(pattern);
        Matcher m = r.matcher(ipAddressAndPort);
        if (m.find()) {
            return m.group(1);
        } else {
            return null;
        }
    }
}
