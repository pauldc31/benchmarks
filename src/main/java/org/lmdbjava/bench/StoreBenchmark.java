/*
 * Copyright 2016 The LmdbJava Project, http://lmdbjava.org/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lmdbjava.bench;

import static java.lang.Long.BYTES;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import static java.nio.ByteBuffer.allocateDirect;
import static java.nio.ByteOrder.BIG_ENDIAN;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import java.util.zip.CRC32;
import static org.lmdbjava.bench.LmdbJava.LMDBJAVA;
import static org.lmdbjava.bench.LmdbJni.LMDBJNI;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import static org.openjdk.jmh.annotations.Level.Iteration;
import org.openjdk.jmh.annotations.Measurement;
import static org.openjdk.jmh.annotations.Mode.AverageTime;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import static org.openjdk.jmh.annotations.Scope.Thread;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Thread)
@OutputTimeUnit(MILLISECONDS)
@Fork(1)
@Warmup(iterations = 2, time = 1, timeUnit = SECONDS)
@Measurement(iterations = 2, time = 1, timeUnit = SECONDS)
@BenchmarkMode(AverageTime)
public class StoreBenchmark {

  private static final CRC32 CRC = new CRC32();

  private static final Random RND = new SecureRandom();
  private static final Map<String, Constructor<? extends AbstractStore>> STORES
      = new HashMap<>();

  static {
    STORES.put(LMDBJNI, constructor(LmdbJni.class));
    STORES.put(LMDBJAVA, constructor(LmdbJava.class));
  }

  private static Constructor<? extends AbstractStore> constructor(
      final Class<? extends AbstractStore> store) {
    final Class<?>[] types = new Class<?>[]{ByteBuffer.class, ByteBuffer.class};
    try {
      return store.getDeclaredConstructor(types);
    } catch (NoSuchMethodException | SecurityException ex) {
      throw new RuntimeException(ex);
    }
  }

  @Param({"15000", "50000"})
  private long entries;

  @Param(value = {LMDBJAVA, LMDBJNI})
  private String store;

  private AbstractStore target;

  private byte[] valByteRnd;

  @Param({"512"})
  private int valBytes;

  @Param({"true", "false"})
  private boolean valRandom;

  @Benchmark
  public void quickTest(Blackhole bh) throws Exception {
    CRC.reset();
    target.startWritePhase();
    for (long i = 0; i < entries; i++) {
      target.key.clear();
      target.key.putLong(i);
      target.key.flip();
      CRC.update(target.key);
      target.key.flip();

      if (valRandom) {
        RND.nextBytes(valByteRnd);
      }
      target.val.clear();
      target.val.put(valByteRnd);
      target.val.flip();
      CRC.update(target.val);
      target.val.flip();

      target.put();
    }
    final long crcWrites = CRC.getValue();
    bh.consume(crcWrites);

    CRC.reset();
    target.startReadPhase();
    for (int i = 0; i < entries; i++) {
      target.key.clear();
      target.key.putLong(i);
      target.key.flip();

      target.val.clear();

      target.get();
      CRC.update(target.key);
      CRC.update(target.val);
    }
    final long crcReads = CRC.getValue();
    bh.consume(crcReads);
    if (crcReads != crcWrites) {
      throw new IllegalStateException();
    }

    target.CRC.reset();
    target.crc32();
    final long crcSequential = target.CRC.getValue();
    if (crcSequential != crcWrites) {
      throw new IllegalStateException();
    }
    bh.consume(crcSequential);
    target.finishCrcPhase();
  }

  @Setup(value = Iteration)
  public void setup() {
    if (!STORES.containsKey(store)) {
      throw new IllegalArgumentException("Unknown store: '" + store + "'");
    }
    this.target = create(STORES.get(store));
    this.valByteRnd = new byte[valBytes];
  }

  private AbstractStore create(Constructor<? extends AbstractStore> c) {
    final ByteBuffer key = allocateDirect(BYTES).order(BIG_ENDIAN);
    final ByteBuffer val = allocateDirect(valBytes);
    final Object[] objs = new Object[]{key, val};
    try {
      return c.newInstance(objs);
    } catch (InstantiationException | IllegalAccessException |
             IllegalArgumentException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

}
