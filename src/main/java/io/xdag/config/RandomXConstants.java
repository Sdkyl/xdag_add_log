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

public class RandomXConstants {

    public static final long SEEDHASH_EPOCH_BLOCKS = 4096;//1000000000000
    public static final long SEEDHASH_EPOCH_LAG = 128;
    public static final long RANDOMX_FORK_HEIGHT = 1540096;//101111000000000000000（二进制）
    public static final int XDAG_RANDOMX = 2;//挖矿类型

    public static long SEEDHASH_EPOCH_TESTNET_BLOCKS = 2048;
    public static long SEEDHASH_EPOCH_TESTNET_LAG = 64;
    public static long RANDOMX_TESTNET_FORK_HEIGHT = 4096;// 196288

}
