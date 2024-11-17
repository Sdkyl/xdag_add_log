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

import com.google.common.collect.Lists;
import io.xdag.config.Config;
import io.xdag.crypto.Hash;
import io.xdag.crypto.Sign;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.SimpleEncoder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import org.apache.tuweni.bytes.MutableBytes32;
import org.bouncycastle.math.ec.ECPoint;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECPPublicKey;
import org.hyperledger.besu.crypto.SECPSignature;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.xdag.core.XdagField.FieldType.*;

@Slf4j
@Getter
@Setter
/**
 * Block类为交易块
 */
public class Block implements Cloneable {
    //交易块创建整个过程，注入了如下属性：coinBase，info（type、remark），inputs,outputs,pubKeys,insigs,outsig

    public static final int MAX_LINKS = 15;
    /**
     * 区块是否存在于本地*
     */
    public boolean isSaved;
    private Address coinBase;
    private BlockInfo info;
    private long transportHeader;//尚不知其作用
    /**
     * 区块的links 列表 输入输出*
     */
    @Getter
    private List<Address> inputs = new CopyOnWriteArrayList<>();
    /**
     * ouput包含pretop
     */
    @Getter
    private List<Address> outputs = new CopyOnWriteArrayList<>();
    /**
     * 记录公钥 前缀+压缩公钥*
     */
    @Getter
    private List<SECPPublicKey> pubKeys = new CopyOnWriteArrayList<>();
    @Getter
    private Map<SECPSignature, Integer> insigs = new LinkedHashMap<>();//该Integer存的是签名所在字段的索引(起始是从零开始的)
    private SECPSignature outsig;
    /**
     * 主块的nonce记录矿工地址跟nonce*
     */
    @Getter
    private Bytes32 nonce;
    private XdagBlock xdagBlock;
    private boolean parsed;//解析后(为true后)，各字段就回全部被赋值
    private boolean isOurs;
    private byte[] encoded;
    private int tempLength;
    @Getter
    @Setter
    private boolean pretopCandidate;
    @Getter
    @Setter
    private BigInteger pretopCandidateDiff;


    public Block(//kernel.getConfig(), sendTime[0], all, refs, mining, keys, remark, defKeyIndex, fee
            Config config,
            long timestamp,
            List<Address> links,
            List<Address> pendings,
            boolean mining,
            List<KeyPair> keys,
            String remark,
            int defKeyIndex,
            XAmount fee) {
        parsed = true;
        info = new BlockInfo();
        this.info.setTimestamp(timestamp);
        this.info.setFee(fee);//
        int lenghth = 0;

        setType(config.getXdagFieldHeader(), lenghth++);



        if (CollectionUtils.isNotEmpty(links)) {
            for (Address link : links) {
                XdagField.FieldType type = link.getType();
                setType(type, lenghth++);
                if (type == XDAG_FIELD_OUT || type == XDAG_FIELD_OUTPUT) {
                    outputs.add(link);
                } else if(type == XDAG_FIELD_IN || type == XDAG_FIELD_INPUT){
                    inputs.add(link);
                }else if(type == XDAG_FIELD_COINBASE){
                    this.coinBase = link;
                    outputs.add(link);
                }
            }
        }

        if (CollectionUtils.isNotEmpty(pendings)) {
            for (Address pending : pendings) {
                XdagField.FieldType type = pending.getType();
                setType(type, lenghth++);
                if (type == XDAG_FIELD_OUT || type == XDAG_FIELD_OUTPUT) {
                    outputs.add(pending);
                } else if(type == XDAG_FIELD_IN || type == XDAG_FIELD_INPUT){
                    inputs.add(pending);
                }else if(type == XDAG_FIELD_COINBASE){
                    this.coinBase = pending;
                    outputs.add(pending);
                }
            }
        }
        //处理info里的tpye以及info里注入了remark
        if (StringUtils.isAsciiPrintable(remark)) {
            setType(XDAG_FIELD_REMARK, lenghth++);//header（必有）、input、output、remark（不一定有）
            byte[] data = remark.getBytes(StandardCharsets.UTF_8);//字节编码
            byte[] safeRemark = new byte[32];
            Arrays.fill(safeRemark, (byte) 0);
            System.arraycopy(data, 0, safeRemark, 0, Math.min(data.length, 32));
            this.info.setRemark(safeRemark);
        }

        if (CollectionUtils.isNotEmpty(keys)) {
            for (KeyPair key : keys) {
                byte[] keydata = Sign.publicKeyBytesFromPrivate(key.getPrivateKey().getEncodedBytes().toUnsignedBigInteger(), true); //poor performance
//                byte[] keydata = key.getCompressPubKeyBytes(); //good performance
                boolean yBit = BytesUtils.toByte(BytesUtils.subArray(keydata, 0, 1)) == 0x03;
                XdagField.FieldType type = yBit ? XDAG_FIELD_PUBLIC_KEY_1 : XDAG_FIELD_PUBLIC_KEY_0;//判断是奇数公钥还是偶数公钥
                setType(type, lenghth++);
                pubKeys.add(key.getPublicKey());
            }
            for (int i = 0; i < keys.size(); i++) {
                if (i != defKeyIndex) {
                    setType(XDAG_FIELD_SIGN_IN, lenghth++);
                    setType(XDAG_FIELD_SIGN_IN, lenghth++);
                } else {
                    setType(XDAG_FIELD_SIGN_OUT, lenghth++);
                    setType(XDAG_FIELD_SIGN_OUT, lenghth++);
                }
            }
        }

        if (defKeyIndex < 0) {
            setType(XDAG_FIELD_SIGN_OUT, lenghth++);
            setType(XDAG_FIELD_SIGN_OUT, lenghth);
        }

        if (mining) {
            setType(XDAG_FIELD_SIGN_IN, MAX_LINKS);
        }
    }

    /**
     * main block
     */
    public Block(Config config, long timestamp,
            List<Address> pendings,
            boolean mining) {
        this(config, timestamp, null, pendings, mining, null, null, -1, XAmount.ZERO);
    }

    /**
     * Read from raw block of 512 bytes
     */
    public Block(XdagBlock xdagBlock) {//同步管理器在拿到账户打包的交易后，又通过得到的交易块初始化xdagBlock，再由xdagBlock构造Block
        this.xdagBlock = xdagBlock;
        this.info = new BlockInfo();
        parse();//此时该块肯定没解析，然后注入的属性和传来的原本的属性也有一点不同，具体看该方法
    }

    public Block(BlockInfo blockInfo) {
        this.info = blockInfo;
        this.isSaved = true;
        this.parsed = true;
    }

    /**
     * 计算区块hash*
     */
    private byte[] calcHash() {
        if (xdagBlock == null) {
            xdagBlock = getXdagBlock();
        }
        return Bytes32.wrap(Hash.hashTwice(Bytes.wrap(xdagBlock.getData())).reverse()).toArray();
    }

    /**
     * 重计算 避免矿工挖矿发送share时直接更新 hash
     **/
    public Bytes32 recalcHash() {
        xdagBlock = new XdagBlock(toBytes());
        return Bytes32.wrap(Hash.hashTwice(Bytes.wrap(xdagBlock.getData())).reverse());
    }

    /**
     * 解析512字节数据*
     */
    public void parse() {//这里xdagBlock这个数据结构在这发挥了很多作用
        if (this.parsed) {
            return;
        }
        if (this.info == null) {
            this.info = new BlockInfo();
        }
        this.info.setHash(calcHash());
        Bytes32 header = Bytes32.wrap(xdagBlock.getField(0).getData());
        this.transportHeader = header.getLong(0, ByteOrder.LITTLE_ENDIAN);
        this.info.type = header.getLong(8, ByteOrder.LITTLE_ENDIAN);
        this.info.setTimestamp(header.getLong(16, ByteOrder.LITTLE_ENDIAN));
        this.info.setFee(XAmount.of(header.getLong(24, ByteOrder.LITTLE_ENDIAN), XUnit.NANO_XDAG));
        for (int i = 1; i < XdagBlock.XDAG_BLOCK_FIELDS; i++) {
            XdagField field = xdagBlock.getField(i);
            if (field == null) {
                throw new IllegalArgumentException("xdagBlock field:" + i + " is null");
            }
            switch (field.getType()) {
            case XDAG_FIELD_IN -> inputs.add(new Address(field,false));
            case XDAG_FIELD_INPUT -> inputs.add(new Address(field,true));
            case XDAG_FIELD_OUT -> outputs.add(new Address(field,false));
            case XDAG_FIELD_OUTPUT -> outputs.add(new Address(field,true));
            case XDAG_FIELD_REMARK -> this.info.setRemark(field.getData().toArray());
            case XDAG_FIELD_COINBASE -> {
                    this.coinBase = new Address(field,true);
                    outputs.add(new Address(field,true));
            }
            case XDAG_FIELD_SIGN_IN, XDAG_FIELD_SIGN_OUT -> {
                BigInteger r;
                BigInteger s;
                int j, signo_s = -1;
                XdagField ixf;
                for (j = i; j < XdagBlock.XDAG_BLOCK_FIELDS; ++j) {
                    ixf = xdagBlock.getField(j);
                    if (ixf.getType().ordinal() == XDAG_FIELD_SIGN_IN.ordinal()
                            || ixf.getType() == XDAG_FIELD_SIGN_OUT) {
                        if (j > i && signo_s < 0 && ixf.getType().ordinal() == xdagBlock.getField(i).getType()
                                .ordinal()) {
                            signo_s = j;
                            r = xdagBlock.getField(i).getData().toUnsignedBigInteger();
                            s = xdagBlock.getField(signo_s).getData().toUnsignedBigInteger();

                            // r and s are 0, the signature is illegal, or it is a pseudo block sent by the miner
                            if(r.compareTo(BigInteger.ZERO) == 0 && s.compareTo(BigInteger.ZERO) == 0){
                                r = BigInteger.ONE;
                                s = BigInteger.ONE;
                            }

                            SECPSignature tmp = SECPSignature.create(r, s, (byte) 0, Sign.CURVE.getN());
                            if (ixf.getType().ordinal() == XDAG_FIELD_SIGN_IN.ordinal()) {
                                insigs.put(tmp, i);//签名站两个field，所以这里i记录的是签名的索引，这里恢复了block的签名属性
                            } else {
                                outsig = tmp;
                            }
                        }
                    }
                }
                if (i == MAX_LINKS && field.getType().ordinal() == XDAG_FIELD_SIGN_IN.ordinal()) {//最后一个field是输入签名，则此区块的nonce被赋值
                    this.nonce = Bytes32.wrap(xdagBlock.getField(i).getData());
                }
            }
            case XDAG_FIELD_PUBLIC_KEY_0, XDAG_FIELD_PUBLIC_KEY_1 -> {
                Bytes key = xdagBlock.getField(i).getData();
                boolean yBit = (field.getType().ordinal() == XDAG_FIELD_PUBLIC_KEY_1.ordinal());
                ECPoint point = Sign.decompressKey(key.toUnsignedBigInteger(), yBit);
                // 解析成非压缩去前缀 公钥
                byte[] encodePub = point.getEncoded(false);
                SECPPublicKey publicKey = SECPPublicKey.create(
                        new BigInteger(1, Arrays.copyOfRange(encodePub, 1, encodePub.length)), Sign.CURVE_NAME);
                pubKeys.add(publicKey);
            }
            default -> {
            }
            //                    log.debug("no match xdagBlock field type:" + field.getType());
            }
        }
        this.parsed = true;
    }

    public byte[] toBytes() {
        SimpleEncoder encoder = new SimpleEncoder();//一个有512字节缓存的输出流
        encoder.write(getEncodedBody());//EncodedBody:header字节，输入输出的data（含金额，以及给谁或谁出），remark，公钥

        for (SECPSignature sig : insigs.keySet()) {
            encoder.writeSignature(BytesUtils.subArray(sig.encodedBytes().toArray(), 0, 64));
        }
        if (outsig != null) {
            encoder.writeSignature(BytesUtils.subArray(outsig.encodedBytes().toArray(), 0, 64));
        }
        int length = encoder.getWriteFieldIndex();//已用字段数
        tempLength = length;
        int res;//剩余字段数
        if (length == 16) {//交易块刚创建，第一次执行到这时，肯定没到16
            return encoder.toBytes();//将流里的内容输出成字节数组
        }
        res = 15 - length;//前15个还剩多少空地
        for (int i = 0; i < res; i++) {
            encoder.writeField(new byte[32]);//给这些空地填0
        }//若没填满15个位置，则用0填充剩余空间
        Bytes32 nonceNotNull = Objects.requireNonNullElse(nonce, Bytes32.ZERO);
        encoder.writeField(nonceNotNull.toArray());//签名内容的最后32个字节为0
        return encoder.toBytes();//确保512字节
    }

    /**
     * without signature
     */
    private byte[] getEncodedBody() {//header字节，输入输出的data（含金额，以及给谁或谁出），remark，公钥
        SimpleEncoder encoder = new SimpleEncoder();
        encoder.writeField(getEncodedHeader());


        List<Address> all = Lists.newArrayList();
        all.addAll(inputs);
        all.addAll(outputs);
        for (Address link : all) {
            encoder.writeField(link.getData().reverse().toArray());
        }
        if (info.getRemark() != null) {
            encoder.write(info.getRemark());
        }
        for (SECPPublicKey publicKey : pubKeys) {
            byte[] pubkeyBytes = publicKey.asEcPoint(Sign.CURVE).getEncoded(true);
            byte[] key = BytesUtils.subArray(pubkeyBytes, 1, 32);
            encoder.writeField(key);
        }
        encoded = encoder.toBytes();
        return encoded;
    }

    private byte[] getEncodedHeader() {
        //byte[] fee = BytesUtils.longToBytes(getFee(), true);
        byte[] fee = BytesUtils.longToBytes(Long.parseLong(getFee().toString()), true);
        byte[] time = BytesUtils.longToBytes(getTimestamp(), true);
        byte[] type = BytesUtils.longToBytes(getType(), true);
        byte[] transport = new byte[8];
        return BytesUtils.merge(transport, type, time, fee);
    }

    public XdagBlock getXdagBlock() {
        if (xdagBlock != null) {
            return xdagBlock;
        }
        xdagBlock = new XdagBlock(toBytes());//这里的toBytes()含签名，即一个512里面该有的都有了，不差家伙事了
        return xdagBlock;
    }

    public void signIn(KeyPair ecKey) {
        sign(ecKey, XDAG_FIELD_SIGN_IN);
    }

    public void signOut(KeyPair ecKey) {
        sign(ecKey, XDAG_FIELD_SIGN_OUT);
    }

    private void sign(KeyPair ecKey, XdagField.FieldType type) {
        byte[] encoded = toBytes();//header字节，输入输出的data（含金额，以及给谁或谁出），remark，公钥，不足512字节，填0
        // log.debug("sign encoded:{}", Hex.toHexString(encoded));
        byte[] pubkeyBytes = ecKey.getPublicKey().asEcPoint(Sign.CURVE).getEncoded(true);
        byte[] digest = BytesUtils.merge(encoded, pubkeyBytes);
        //log.debug("sign digest:{}", Hex.toHexString(digest));
        Bytes32 hash = Hash.hashTwice(Bytes.wrap(digest));//双sha256双hash
        //log.debug("sign hash:{}", Hex.toHexString(hash.toArray()));
        SECPSignature signature = Sign.SECP256K1.sign(hash, ecKey);
        if (type == XDAG_FIELD_SIGN_OUT) {
            outsig = signature;
        } else {
            insigs.put(signature, tempLength);//交易块的block的Map<SECPSignature, Integer> insigs里的tempLength(value)为0
        }
    }

    /**
     * 只匹配输入签名 并返回有用的key
     */
    public List<SECPPublicKey> verifiedKeys() {
        List<SECPPublicKey> keys = getPubKeys();
        List<SECPPublicKey> res = Lists.newArrayList();
        Bytes digest;
        Bytes32 hash;
        for (SECPSignature sig : this.getInsigs().keySet()) {
            digest = getSubRawData(this.getInsigs().get(sig) - 1);
            for (SECPPublicKey publicKey : keys) {
                // TODO： paulochen 是不是可以替换
                byte[] pubkeyBytes = publicKey.asEcPoint(Sign.CURVE).getEncoded(true);
                hash = Hash.hashTwice(Bytes.wrap(digest, Bytes.wrap(pubkeyBytes)));
                if (Sign.SECP256K1.verify(hash, sig, publicKey)) {
                    res.add(publicKey);
                }
            }
        }

        digest = getSubRawData(getOutsigIndex() - 2);//除输出签名外的内容
        for (SECPPublicKey publicKey : keys) {
            // TODO： paulochen 是不是可以替换
            byte[] pubkeyBytes = publicKey.asEcPoint(Sign.CURVE).getEncoded(true);
            hash = Hash.hashTwice(Bytes.wrap(digest, Bytes.wrap(pubkeyBytes)));
            if (Sign.SECP256K1.verify(hash, this.getOutsig(), publicKey)) {
                res.add(publicKey);
            }
        }
        return res;
    }

    /**
     * 取输出签名在字段的索引
     */
    public int getOutsigIndex() {
        int i = 1;
        long temp = this.info.type;
        while (i < XdagBlock.XDAG_BLOCK_FIELDS && (temp & 0xf) != 5) {
            temp = temp >> 4;
            i++;
        }
        return i;
    }

    public Bytes32 getHash() {
        if (this.info.getHash() == null) {
            this.info.setHash(calcHash());//用的sha256,即32字节
        }
        return Bytes32.wrap(this.info.getHash());
    }

    public MutableBytes32 getHashLow() {
        if (info.getHashlow() == null) {
            MutableBytes32 hashLow = MutableBytes32.create();
            hashLow.set(8, getHash().slice(8, 24));
            info.setHashlow(hashLow.toArray());
        }
        return MutableBytes32.wrap(info.getHashlow());
    }

    public SECPSignature getOutsig() {
        return outsig == null ? null : outsig;
    }

    @Override
    public String toString() {
        return String.format("Block info:[Hash:{%s}][Time:{%s}]", getHashLow().toHexString(),
                Long.toHexString(getTimestamp()));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Block block = (Block) o;
        return Objects.equals(getHashLow(), block.getHashLow());
    }

    @Override
    public int hashCode() {
        return Bytes.of(this.getHashLow().toArray()).hashCode();
    }

    public long getTimestamp() {
        return this.info.getTimestamp();
    }

    public long getType() {
        return this.info.type;
    }

    public XAmount getFee() {
        return this.info.getFee();
    }

    /**
     * 根据length获取前length个字段的数据 主要用于签名*
     */
    public MutableBytes getSubRawData(int length) {//除签名外的
        Bytes data = getXdagBlock().getData();
        MutableBytes res = MutableBytes.create(512);
        res.set(0, data.slice(0, (length + 1) * 32));
        for (int i = length + 1; i < 16; i++) {
            long type = data.getLong(8, ByteOrder.LITTLE_ENDIAN);
            byte typeB = (byte) (type >> (i << 2) & 0xf);
            if (XDAG_FIELD_SIGN_IN.asByte() == typeB || XDAG_FIELD_SIGN_OUT.asByte() == typeB) {
                continue;
            }
            res.set((i) * 32, data.slice((i) * 32, 32));
        }
        return res;
    }

    private void setType(XdagField.FieldType type, int n) {
        long typeByte = type.asByte();
        this.info.type |= typeByte << (n << 2);//四位四位的设置，所以需要设置16下
    }

    public List<Address> getLinks() {
        List<Address> links = Lists.newArrayList();
        links.addAll(getInputs());
        links.addAll(getOutputs());
        return links;
    }

    @Override
    public Object clone() {
        Block ano = null;
        try {
            ano = (Block) super.clone();
        } catch (CloneNotSupportedException e) {
            log.error(e.getMessage(), e);
        }
        return ano;
    }
}
