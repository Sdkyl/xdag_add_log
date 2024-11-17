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

package io.xdag.cli;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import io.xdag.Kernel;
import io.xdag.core.*;
import io.xdag.net.Channel;
import io.xdag.net.websocket.ChannelSupervise;
import io.xdag.utils.BasicUtils;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.XdagTime;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.FastDateFormat;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes32;
import org.bouncycastle.util.encoders.Hex;
import org.hyperledger.besu.crypto.KeyPair;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.xdag.config.Constants.*;
import static io.xdag.core.BlockState.MAIN;
import static io.xdag.core.XdagField.FieldType.*;
import static io.xdag.crypto.Keys.toBytesAddress;
import static io.xdag.utils.BasicUtils.*;
import static io.xdag.utils.WalletUtils.*;

@Getter
@Slf4j
public class Commands {

    private final Kernel kernel;

    public Commands(Kernel kernel) {
        this.kernel = kernel;
    }

    public static String printHeaderBlockList() {
        return """
                ---------------------------------------------------------------------------------------------------------
                height        address                            time                      state     mined by           \s
                ---------------------------------------------------------------------------------------------------------
                """;
    }

    public static String printBlock(Block block) {
        return printBlock(block, false);
    }

    public static String printBlock(Block block, boolean print_only_addresses) {
        StringBuilder sbd = new StringBuilder();
        long time = XdagTime.xdagTimestampToMs(block.getTimestamp());
        if (print_only_addresses) {
            sbd.append(String.format("%s   %08d",
                    hash2Address(block.getHash()),//data双sha256后，反转然后截取前24个字节，然后进行base64编码，即区块地址为对hash进行base64编码
                    block.getInfo().getHeight()));
        } else {
            byte[] remark = block.getInfo().getRemark();
            sbd.append(String.format("%08d   %s   %s   %-8s  %-32s",
                    block.getInfo().getHeight(),
                    hash2Address(block.getHash()),
                    FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS").format(time),
                    getStateByFlags(block.getInfo().getFlags()),//区块状态由分析flag得到
                    new String(remark == null ? "".getBytes(StandardCharsets.UTF_8) : remark, StandardCharsets.UTF_8)));
        }
        return sbd.toString();
    }

    public static String getStateByFlags(int flags) {
        int flag = flags & ~(BI_OURS | BI_REMARK);//去除flags中的这两个标志位
        // 1F
        if (flag == (BI_REF | BI_MAIN_REF | BI_APPLIED | BI_MAIN | BI_MAIN_CHAIN)) {
            return MAIN.getDesc();
        }
        // 1C
        if (flag == (BI_REF | BI_MAIN_REF | BI_APPLIED)) {
            return BlockState.ACCEPTED.getDesc();
        }
        // 18
        if (flag == (BI_REF | BI_MAIN_REF)) {
            return BlockState.REJECTED.getDesc();
        }
        return BlockState.PENDING.getDesc();//仅剩BI_REF
    }

    /**
     * list address + balance
     *
     * @param num Number of prints，要输出的该钱包地址下的多少个账户数目
     * @return address + balance
     */
    public String account(int num) {
        // account in memory, do not store in rocksdb, do not show in terminal
        StringBuilder str = new StringBuilder();
        List<KeyPair> list = kernel.getWallet().getAccounts();

        // 按balance降序排序，按key index降序排序
        list.sort((o1, o2) -> {
            int compareResult = compareAmountTo(kernel.getAddressStore().getBalanceByAddress(toBytesAddress(o2)),
                    kernel.getAddressStore().getBalanceByAddress(toBytesAddress(o1)));
            if (compareResult >= 0) {
                return 1;
            } else {
                return -1;
            }

        });

        for (KeyPair keyPair : list) {
            if (num == 0) {
                break;
            }
            str.append(toBase58(toBytesAddress(keyPair)))//账户地址：公钥双hash后(sha256再hash160)，再进行base58编码
                    .append(" ")
                    .append(kernel.getAddressStore().getBalanceByAddress(toBytesAddress(keyPair)).toDecimal(9, XUnit.XDAG).toPlainString())
                    .append(" XDAG")//AddressSource,该数据源里面存储了余额，其key为ADDRESS(0x30)+公钥双hash
                    .append("\n");
            num--;
        }

        return str.toString();
    }

    /**
     * Search Balance by Address
     *
     * @param address for search balance
     * @return balance of give address
     */
    public String balance(String address) {
        if (StringUtils.isEmpty(address)) {
            XAmount ourBalance = XAmount.ZERO;
            List<KeyPair> list = kernel.getWallet().getAccounts();
            for (KeyPair k : list) {
                ourBalance = ourBalance.add(kernel.getAddressStore().getBalanceByAddress(toBytesAddress(k)));
            }
            return String.format("Balance: %s XDAG", ourBalance.toDecimal(9, XUnit.XDAG).toPlainString());
        } else {
            Bytes32 hash;
            MutableBytes32 key = MutableBytes32.create();
            if (checkAddress(address)) {//检查是不是账户地址
                hash = pubAddress2Hash(address);//由账户地址计算hash时，其结果是一个32字节的数组，只有20个字节有值，从第九个字节开始(脚标为8)。
                key.set(8, Objects.requireNonNull(hash).slice(8, 20));//key总长度为32，只有20个字节有值，从第九个字节开始(和上面的hash一样)。
                XAmount balance = kernel.getAddressStore().getBalanceByAddress(fromBase58(address));
                return String.format("Account balance: %s XDAG", balance.toDecimal(9, XUnit.XDAG).toPlainString());
            } else {
                if (StringUtils.length(address) == 32) {//检查是不是区块地址
                    hash = address2Hash(address);//总长度为32，前8个字节为空，后二十四个字节有值
                } else {
                    hash = getHash(address);//这里的address为16进制字符组成的字符串，这里的操作是将这16进制组成的字符串转换成32字节长的字节数组，我的推测是Address里的addresshash
                }
                key.set(8, Objects.requireNonNull(hash).slice(8, 24));//非账户key，前八字节为空，后面24字节数为区块hashlow
                Block block = kernel.getBlockStore().getBlockInfoByHash(Bytes32.wrap(key));//从数据库里查
                return String.format("Block balance: %s XDAG", block.getInfo().getAmount().toDecimal(9, XUnit.XDAG).toPlainString());
            }

        }
    }


    /**
     * Real make a transaction for given amount and address
     *
     * @param sendAmount amount
     * @param address    receiver address
     * @return Transaction hash
     */
    public String xfer(double sendAmount, Bytes32 address, String remark) {
        StringBuilder str = new StringBuilder();
        str.append("Transaction :{ ").append("\n");

        XAmount amount = XAmount.of(BigDecimal.valueOf(sendAmount), XUnit.XDAG);
        MutableBytes32 to = MutableBytes32.create();
        to.set(8, address.slice(8, 20));//中间20个字节有值

        // 待转账余额
        AtomicReference<XAmount> remain = new AtomicReference<>(amount);

        // 转账输入
        Map<Address, KeyPair> ourAccounts = Maps.newHashMap();//记录此次转账用到了哪些个账户，账户是输入还是输出，以及涉及的金额是多少
        List<KeyPair> accounts = kernel.getWallet().getAccounts();
        for (KeyPair account : accounts) {
            byte[] addr = toBytesAddress(account);//20
            XAmount addrBalance = kernel.getAddressStore().getBalanceByAddress(addr);


            if (compareAmountTo(remain.get(), addrBalance) <= 0) {
                ourAccounts.put(new Address(keyPair2Hash(account), XDAG_FIELD_INPUT, remain.get(), true), account);//[keyPair2Hash(account)]->(20个字节有值),input,amount,isaddress
                remain.set(XAmount.ZERO);
                break;
            } else {
                if (compareAmountTo(addrBalance, XAmount.ZERO) > 0) {
                    remain.set(remain.get().subtract(addrBalance));
                    ourAccounts.put(new Address(keyPair2Hash(account), XDAG_FIELD_INPUT, addrBalance, true), account);
                }
            }
        }

        // 余额不足，也就是账号刷遍了，还不够，那就只能返回余额不足了
        if (compareAmountTo(remain.get(), XAmount.ZERO) > 0) {
            return "Balance not enough.";
        }

        // 生成多个交易块
        List<BlockWrapper> txs = createTransactionBlock(ourAccounts, to, remark);
        for (BlockWrapper blockWrapper : txs) {
            ImportResult result = kernel.getSyncMgr().validateAndAddNewBlock(blockWrapper);
            if (result == ImportResult.IMPORTED_BEST || result == ImportResult.IMPORTED_NOT_BEST) {
                kernel.getChannelMgr().sendNewBlock(blockWrapper);//验证通过，广播区块
                str.append(hash2Address(blockWrapper.getBlock().getHashLow())).append("\n");
            }
        }

        return str.append("}, it will take several minutes to complete the transaction.").toString();

    }


    private List<BlockWrapper> createTransactionBlock(Map<Address, KeyPair> ourKeys, Bytes32 to, String remark) {
        // 判断是否有remark
        int hasRemark = remark == null ? 0 : 1;

        List<BlockWrapper> res = Lists.newArrayList();

        // 遍历ourKeys 计算每个区块最多能放多少个
        // int res = 1 + pairs.size() + to.size() + 3*keys.size() + (defKeyIndex == -1 ? 2 : 0);

        LinkedList<Map.Entry<Address, KeyPair>> stack = Lists.newLinkedList(ourKeys.entrySet());

        // 每次创建区块用到的keys
        Map<Address, KeyPair> keys = Maps.newHashMap();
        // 保证key的唯一性
        Set<KeyPair> keysPerBlock = Sets.newHashSet();
        // 放入defkey
        keysPerBlock.add(kernel.getWallet().getDefKey());

        // base count a block <header + send address + defKey signature>,生成此交易块时对该块的签名。
        int base = 1 + 1 + 2 + hasRemark;
        XAmount amount = XAmount.ZERO;

        while (!stack.isEmpty()) {
            Map.Entry<Address, KeyPair> key = stack.peek();
            base += 1;//input多了一个
            int originSize = keysPerBlock.size();
            keysPerBlock.add(key.getValue());
            // 说明新增加的key没有重复
            if (keysPerBlock.size() > originSize) {
                // 一个字段公钥加两个字段签名
                base += 3;
            }
            // 可以将该输入 放进一个区块
            if (base < 16) {
                amount = amount.add(key.getKey().getAmount());//确保区块力能放入这个账户地址的输入，然后才把金额记录下来
                keys.put(key.getKey(), key.getValue());//记录用到的账户
                stack.poll();//移除该账户，因为上一句记录了
            } else {
                res.add(createTransaction(to, amount, keys, remark));
                // 清空keys，准备下一个
                keys = new HashMap<>();
                keysPerBlock = new HashSet<>();
                keysPerBlock.add(kernel.getWallet().getDefKey());
                base = 1 + 1 + 2 + hasRemark;
                amount = XAmount.ZERO;
            }
        }
        if (!keys.isEmpty()) {
            res.add(createTransaction(to, amount, keys, remark));
        }
        return res;
    }
    //创建一个交易块
    private BlockWrapper createTransaction(Bytes32 to, XAmount amount, Map<Address, KeyPair> keys, String remark) {
        List<Address> tos = Lists.newArrayList(new Address(to, XDAG_FIELD_OUTPUT, amount, true));//这里的to对应着addresshash
        Block block = kernel.getBlockchain().createNewBlock(new HashMap<>(keys), tos, false, remark, XAmount.of(100, XUnit.MILLI_XDAG));
        //创建交易块时，这里的block只注入了info里的type，remark以及coinbase,inputs，outputs和输入账户的公钥

        if (block == null) {
            return null;
        }

        KeyPair defaultKey = kernel.getWallet().getDefKey();

        boolean isDefaultKey = false;
        // signature
        for (KeyPair ecKey : Set.copyOf(new HashMap<>(keys).values())) {
            if (ecKey.equals(defaultKey)) {
                isDefaultKey = true;
            } else {
                block.signIn(ecKey);//此处注入了block的Map<SECPSignature, Integer> insigs属性，注入了交易块的输入签名
            }
        }
        // signOut. If the default key is changed, the output signature needs to be re-signed.
        if (isDefaultKey) {
            block.signOut(defaultKey);//此处注入了outsig属性，输出签名
        } else {
            block.signOut(kernel.getWallet().getDefKey());
        }

        return new BlockWrapper(block, kernel.getConfig().getNodeSpec().getTTL());
    }

    /**
     * Current Blockchain Status
     */
    public String stats() {
        XdagStats xdagStats = kernel.getBlockchain().getXdagStats();
        XdagTopStatus xdagTopStatus = kernel.getBlockchain().getXdagTopStatus();

        // diff
        BigInteger currentDiff = xdagTopStatus.getTopDiff() != null ? xdagTopStatus.getTopDiff() : BigInteger.ZERO;
        BigInteger netDiff = xdagStats.getMaxdifficulty() != null ? xdagStats.getMaxdifficulty() : BigInteger.ZERO;
        BigInteger maxDiff = netDiff.max(currentDiff);

        return String.format("""
                        Statistics for ours and maximum known parameters:
                                    hosts: %d of %d
                                   blocks: %d of %d
                              main blocks: %d of %d
                             extra blocks: %d
                            orphan blocks: %d
                         wait sync blocks: %d
                         chain difficulty: %s of %s
                              XDAG supply: %s of %s
                          XDAG in address: %s
                        4 hr hashrate KHs: %.9f of %.9f
                        Number of Address: %d""",
                //属性中的N，n为节点node的意思
                kernel.getNetDB().getSize(), kernel.getNetDBMgr().getWhiteDB().getSize(),
                xdagStats.getNblocks(), Math.max(xdagStats.getTotalnblocks(), xdagStats.getNblocks()),
                xdagStats.getNmain(), Math.max(xdagStats.getTotalnmain(), xdagStats.getNmain()),
                xdagStats.nextra,
                xdagStats.nnoref,
                xdagStats.nwaitsync,
//                xdagTopStatus.getTopDiff()!=null?xdagTopStatus.getTopDiff().toString(16):"",
//                xdagStats.getMaxdifficulty()!=null?xdagStats.getMaxdifficulty().toString(16):"",
                currentDiff.toString(16),
                maxDiff.toString(16),
                kernel.getBlockchain().getSupply(xdagStats.nmain).toDecimal(9, XUnit.XDAG).toPlainString(),
                kernel.getBlockchain().getSupply(Math.max(xdagStats.nmain, xdagStats.totalnmain)).toDecimal(9, XUnit.XDAG).toPlainString(),
                kernel.getAddressStore().getAllBalance().toDecimal(9, XUnit.XDAG).toPlainString(),
                xdagHashRate(kernel.getBlockchain().getXdagExtStats().getHashRateOurs()),
                xdagHashRate(kernel.getBlockchain().getXdagExtStats().getHashRateTotal()),
                kernel.getAddressStore().getAddressSize().toLong()
        );
    }

    /**
     * Connect to Node
     */
    public void connect(String server, int port) {
        kernel.getNodeMgr().doConnect(server, port);
    }

    /**
     * Query block by hash
     *
     * @param blockhash blockhash
     * @return block info
     */
    public String block(Bytes32 blockhash) {//base64地址解码后的24有效字节hash
        try {
            MutableBytes32 hashLow = MutableBytes32.create();
            hashLow.set(8, blockhash.slice(8, 24));
            Block block = kernel.getBlockStore().getRawBlockByHash(hashLow);
            if (block == null) {
                block = kernel.getBlockStore().getBlockInfoByHash(hashLow);//用blockinfo初始化一个block，默认已经设置已解析，即parsed=true，isSaved = true
                return printBlockInfo(block, false);
            } else {
                return printBlockInfo(block, true);//raw=true
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return "error, please check log";
    }

    public String block(String address) {
        Bytes32 hashlow = address2Hash(address);
        return block(hashlow);
    }

    public String printBlockInfo(Block block, boolean raw) {
        block.parse();
        long time = XdagTime.xdagTimestampToMs(block.getTimestamp());
        String heightFormat = ((block.getInfo().getFlags() & BI_MAIN) == 0 ? "" : "    height: %08d\n");
        String otherFormat = """
                      time: %s
                 timestamp: %s
                     flags: %s
                     state: %s
                      hash: %s
                    remark: %s
                difficulty: %s
                   balance: %s  %s
                -----------------------------------------------------------------------------------------------------------------------------
                                               block as transaction: details
                 direction  address                                    amount
                       fee: %s           %s""";
        StringBuilder inputs = null;
        StringBuilder outputs = null;
        if (raw) {
            if (!block.getInputs().isEmpty()) {//仅交易块有
                inputs = new StringBuilder();
                for (Address input : block.getInputs()) {
                    inputs.append(String.format("     input: %s           %s%n",
                            input.getIsAddress() ? toBase58(hash2byte(input.getAddress())) : hash2Address(input.getAddress()),
                            input.getAmount().toDecimal(9, XUnit.XDAG).toPlainString()
                    ));
                }
            }
            if (!block.getOutputs().isEmpty()) {
                outputs = new StringBuilder();
                for (Address output : block.getOutputs()) {
                    if (output.getType().equals(XDAG_FIELD_COINBASE)) continue;
                    outputs.append(String.format("    output: %s           %s%n",
                            output.getIsAddress() ? toBase58(hash2byte(output.getAddress())) : hash2Address(output.getAddress()),
                            getStateByFlags(block.getInfo().getFlags()).equals(MAIN.getDesc()) ? output.getAmount().toDecimal(9, XUnit.XDAG).toPlainString() :
                                    block.getInputs().isEmpty() ? XAmount.ZERO.toDecimal(9, XUnit.XDAG).toPlainString() :
                                            output.getAmount().subtract(MIN_GAS).toDecimal(9, XUnit.XDAG).toPlainString()//居然手续费是收款人出，上面说明链接块输出金额全是0
                    ));
                    // three type of block, 1、main block :getStateByFlags(block.getInfo().getFlags()).equals(MAIN.getDesc())
                    // 2、link block:block.getInputs().isEmpty()     3、else transaction block
                }
            }
        }

        String txHisFormat = """
                -----------------------------------------------------------------------------------------------------------------------------
                                               block as address: details
                 direction  address                                    amount                 time
                """;
        StringBuilder tx = new StringBuilder();
        if (getStateByFlags(block.getInfo().getFlags()).equals(MAIN.getDesc()) && block.getInfo().getHeight() > kernel.getConfig().getSnapshotSpec().getSnapshotHeight()) {
            tx.append(String.format("    earn: %s           %s   %s%n", hash2Address(block.getHashLow()),
                            kernel.getBlockchain().getReward(block.getInfo().getHeight()).toDecimal(9, XUnit.XDAG).toPlainString(),
                            FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS")
                                    .format(XdagTime.xdagTimestampToMs(block.getTimestamp()))))
                    .append(String.format("fee earn: %s           %s   %s%n", hash2Address(block.getHashLow()),
                            kernel.getBlockStore().getBlockInfoByHash(block.getHashLow()).getFee().toDecimal(9, XUnit.XDAG).toPlainString(),
                            FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS")
                                    .format(XdagTime.xdagTimestampToMs(block.getTimestamp()))));
        }
        for (TxHistory txHistory : kernel.getBlockchain().getBlockTxHistoryByAddress(block.getHashLow(), 1)) {
            Address address = txHistory.getAddress();
            BlockInfo blockInfo = kernel.getBlockchain().getBlockByHash(address.getAddress(), false).getInfo();
            if ((blockInfo.flags & BI_APPLIED) == 0) {
                continue;
            }
            if (address.getType().equals(XDAG_FIELD_IN)) {
                tx.append(String.format("    input: %s           %s  %s%n", hash2Address(address.getAddress()),
                        address.getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
                        FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS")
                                .format(txHistory.getTimestamp())));
            } else if (address.getType().equals(XDAG_FIELD_OUT)) {
                tx.append(String.format("   output: %s           %s   %s%n", hash2Address(address.getAddress()),
                        address.getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
                        FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS")
                                .format(txHistory.getTimestamp())));
            } else {
                tx.append(String.format(" snapshot: %s           %s   %s%n",
                        hash2Address(address.getAddress()),
                        address.getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
                        FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS")
                                .format(txHistory.getTimestamp())));
            }
        }

        // TODO need add block as transaction
        // three type of block, main block :getStateByFlags(block.getInfo().getFlags()).equals(MAIN.getDesc())
        // link block:block.getInputs().isEmpty()     else transaction block
        return String.format(heightFormat, block.getInfo().getHeight()) + String.format(otherFormat,
                FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS").format(time),
                Long.toHexString(block.getTimestamp()),
                Integer.toHexString(block.getInfo().getFlags()),
                getStateByFlags(block.getInfo().getFlags()),
                Hex.toHexString(block.getInfo().getHash()),
                block.getInfo().getRemark() == null ? StringUtils.EMPTY : new String(block.getInfo().getRemark(), StandardCharsets.UTF_8),
                block.getInfo().getDifficulty().toString(16),
                hash2Address(block.getHash()), block.getInfo().getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
                block.getInfo().getRef() == null ? "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" : hash2Address(Bytes32.wrap(block.getInfo().getRef())),
                block.getInfo().getRef() == null ? XAmount.ZERO.toDecimal(9, XUnit.XDAG).toPlainString() :
                        (getStateByFlags(block.getInfo().getFlags()).equals(MAIN.getDesc()) ? kernel.getBlockStore().getBlockInfoByHash(block.getHashLow()).getFee().toDecimal(9, XUnit.XDAG).toPlainString() :
                                (block.getInputs().isEmpty() ? XAmount.ZERO.toDecimal(9, XUnit.XDAG).toPlainString() :
                                        MIN_GAS.multiply(block.getOutputs().size()).toDecimal(9, XUnit.XDAG).toPlainString()))
        )
                + "\n"
                + (inputs == null ? "" : inputs.toString()) + (outputs == null ? "" : outputs.toString())

                + "\n"
                + txHisFormat
                + "\n"
                + tx
                ;
    }

    /**
     * Print Main blocks by given number
     *
     * @param n Number of prints
     * @return Mainblock info
     */
    public String mainblocks(int n) {
        List<Block> blocks = kernel.getBlockchain().listMainBlocks(n);
        if (CollectionUtils.isEmpty(blocks)) {
            return "empty";
        }
        return printHeaderBlockList() +
                blocks.stream().map(Commands::printBlock).collect(Collectors.joining("\n"));
    }

    /**
     * Print Mined Block by given number
     *
     * @param n Number of prints
     * @return minedblock info
     */
    public String minedBlocks(int n) {
        List<Block> blocks = kernel.getBlockchain().listMinedBlocks(n);
        if (CollectionUtils.isEmpty(blocks)) {
            return "empty";
        }
        return printHeaderBlockList() +
                blocks.stream().map(Commands::printBlock).collect(Collectors.joining("\n"));
    }

    public void run() {
        try {
            kernel.testStart();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public void stop() {
        kernel.testStop();
    }

    public String listConnect() {
        List<Channel> channelList = kernel.getChannelMgr().getActiveChannels();
        StringBuilder stringBuilder = new StringBuilder();
        for (Channel channel : channelList) {
            stringBuilder.append(channel).append(" ")
                    .append(System.lineSeparator());//System.lineSeparator(),当前系统换行符
        }

        return stringBuilder.toString();
    }

    public String pool() {
        return ChannelSupervise.showChannel();
    }

    public String keygen()
            throws InvalidAlgorithmParameterException, NoSuchAlgorithmException, NoSuchProviderException {
        kernel.getXdagState().tempSet(XdagState.KEYS);
        kernel.getWallet().addAccountRandom();

        kernel.getWallet().flush();
        int size = kernel.getWallet().getAccounts().size();
        kernel.getXdagState().rollback();
        return "Key " + (size - 1) + " generated and set as default,now key size is:" + size;
    }

    public String state() {
        return kernel.getXdagState().toString();
    }

    public String balanceMaxXfer() {
        return getBalanceMaxXfer(kernel);
    }//搜索所有我们的区块里的钱

    public static String getBalanceMaxXfer(Kernel kernel) {
        final XAmount[] balance = {XAmount.ZERO};

        kernel.getBlockStore().fetchOurBlocks(pair -> {
            Block block = pair.getValue();
            if (XdagTime.getCurrentEpoch() < XdagTime.getEpoch(block.getTimestamp()) + 2 * CONFIRMATIONS_COUNT) {
                return false;
            }
            if (compareAmountTo(block.getInfo().getAmount(), XAmount.ZERO) > 0) {
                balance[0] = balance[0].add(block.getInfo().getAmount());
            }
            return false;
        });
        return String.format("%s", balance[0].toDecimal(9, XUnit.XDAG).toPlainString());
    }

    public String address(Bytes32 wrap, int page) {
        String ov = " OverView" + "\n"
                + String.format(" address: %s", toBase58(hash2byte(wrap.mutableCopy()))) + "\n"
                + String.format(" balance: %s", kernel.getAddressStore().getBalanceByAddress(hash2byte(wrap.mutableCopy())).toDecimal(9, XUnit.XDAG).toPlainString()) + "\n";

        String txHisFormat = """
                -----------------------------------------------------------------------------------------------------------------------------
                                               histories of address: details
                 direction  address                                    amount                 time
                """;
        StringBuilder tx = new StringBuilder();

        for (TxHistory txHistory : kernel.getBlockchain().getBlockTxHistoryByAddress(wrap, page)) {
            Address address = txHistory.getAddress();
            Block block = kernel.getBlockchain().getBlockByHash(address.getAddress(), false);
            if (block != null) {
                BlockInfo blockInfo = block.getInfo();
                if ((blockInfo.flags & BI_APPLIED) == 0) {
                    continue;
                }
                if (address.getType().equals(XDAG_FIELD_INPUT)) {
                    tx.append(String.format("    input: %s           %s   %s%n", hash2Address(address.getAddress()),
                            address.getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
                            FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS")
                                    .format(txHistory.getTimestamp())));
                } else if (address.getType().equals(XDAG_FIELD_OUTPUT)) {
                    tx.append(String.format("   output: %s           %s   %s%n", hash2Address(address.getAddress()),
                            address.getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
                            FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS")
                                    .format(txHistory.getTimestamp())));
                } else if (address.getType().equals(XDAG_FIELD_COINBASE) && (blockInfo.flags & BI_MAIN) != 0) {
                    tx.append(String.format(" coinbase: %s           %s   %s%n", hash2Address(address.getAddress()),
                            address.getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
                            FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS")
                                    .format(txHistory.getTimestamp())));
                } else {
                    tx.append(String.format(" snapshot: %s           %s  %s%n", hash2Address(address.getAddress()),
                            address.getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
                            FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS")
                                    .format(txHistory.getTimestamp())));
                }
            } else {
                tx.append(String.format(" snapshot: %s           %s   %s%n", (toBase58(BytesUtils.byte32ToArray(address.getAddress()))),
                        address.getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
                        FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS")
                                .format(txHistory.getTimestamp())));
            }
        }

        return ov + "\n" + txHisFormat + "\n" + tx;
    }

    public String xferToNew() {
        StringBuilder str = new StringBuilder();
        str.append("Transaction :{ ").append("\n");

        MutableBytes32 to = MutableBytes32.create();
        Bytes32 accountHash = keyPair2Hash(kernel.getWallet().getDefKey());
        to.set(8, accountHash.slice(8, 20));

        String remark = "block balance to new address";

        // 转账输入
        Map<Address, KeyPair> ourBlocks = Maps.newHashMap();

        // our block select
        kernel.getBlockStore().fetchOurBlocks(pair -> {
            int index = pair.getKey();
            Block block = pair.getValue();
            if (XdagTime.getCurrentEpoch() < XdagTime.getEpoch(block.getTimestamp()) + 2 * CONFIRMATIONS_COUNT) {
                return false;
            }

            if (compareAmountTo(XAmount.ZERO, block.getInfo().getAmount()) < 0) {
                ourBlocks.put(new Address(block.getHashLow(), XDAG_FIELD_IN, block.getInfo().getAmount(), false),
                        kernel.getWallet().getAccounts().get(index));
                return false;
            }
            return false;
        });

        // 生成多个交易块
        List<BlockWrapper> txs = createTransactionBlock(ourBlocks, to, remark);
        for (BlockWrapper blockWrapper : txs) {
            ImportResult result = kernel.getSyncMgr().validateAndAddNewBlock(blockWrapper);
            if (result == ImportResult.IMPORTED_BEST || result == ImportResult.IMPORTED_NOT_BEST) {
                kernel.getChannelMgr().sendNewBlock(blockWrapper);
                str.append(BasicUtils.hash2Address(blockWrapper.getBlock().getHashLow())).append("\n");
            }
        }
        return str.append("}, it will take several minutes to complete the transaction.").toString();
    }

    // Distribute block rewards to node
    public StringBuilder xferToNode(Map<Address, KeyPair> paymentsToNodesMap) {
        StringBuilder str = new StringBuilder("Tx hash paid to the node :{");
        MutableBytes32 to = MutableBytes32.create();
        Bytes32 accountHash = keyPair2Hash(kernel.getWallet().getDefKey());
        to.set(8, accountHash.slice(8, 20));
        String remark = "Pay to " + kernel.getConfig().getNodeSpec().getNodeTag();
        // Generate tx block to reward node
        List<BlockWrapper> txs = createTransactionBlock(paymentsToNodesMap, to, remark);
        for (BlockWrapper blockWrapper : txs) {
            ImportResult result = kernel.getSyncMgr().validateAndAddNewBlock(blockWrapper);
            if (result == ImportResult.IMPORTED_BEST || result == ImportResult.IMPORTED_NOT_BEST) {
                kernel.getChannelMgr().sendNewBlock(blockWrapper);
                str.append(BasicUtils.hash2Address(blockWrapper.getBlock().getHashLow()));
            } else {
                return new StringBuilder("This transaction block is invalid. Tx hash:")
                        .append(BasicUtils.hash2Address(blockWrapper.getBlock().getHashLow()));
            }
        }
        return str.append("}");
    }
}
