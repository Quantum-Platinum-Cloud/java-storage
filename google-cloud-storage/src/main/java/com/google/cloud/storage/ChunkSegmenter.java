/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.storage;

import com.google.cloud.storage.Crc32cValue.Crc32cLengthKnown;
import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * When processing a "chunk" of data to write to GCS, we must turn our logical chunk into N
 * segments. Each resulting segment will then become an individual message.
 */
final class ChunkSegmenter {
  private final Hasher hasher;
  private final ByteStringStrategy bss;
  private final int maxSegmentSize;
  private final int blockSize;

  ChunkSegmenter(Hasher hasher, ByteStringStrategy bss, int maxSegmentSize) {
    this(hasher, bss, maxSegmentSize, ByteSizeConstants._256KiB);
  }

  @VisibleForTesting
  ChunkSegmenter(Hasher hasher, ByteStringStrategy bss, int maxSegmentSize, int blockSize) {
    this.hasher = hasher;
    this.bss = bss;
    this.maxSegmentSize = maxSegmentSize;
    this.blockSize = blockSize;
  }

  Hasher getHasher() {
    return hasher;
  }

  /**
   * Given {@code bbs}, yield N segments, where each segment is at most {@code maxSegmentSize}
   * bytes.
   *
   * <p>An example:
   *
   * <pre>
   * Given a "chunk" consisting of two ByteBuffers, A and B, where A contains 3 MiB and B contains 6.6 MiB
   *    A: 3 MiB                       B: 6.6 MiB
   * |-----------------------------|-----------------------------------------------------------------|
   *
   * Produce segments
   *   S1: 2 MiB            S2: 2 MiB           S3: 2 MiB           S4: 2 MiB           S5: 1.6 MiB
   * |-------------------|-------------------|-------------------|-------------------|---------------|
   * </pre>
   *
   * Each segment will conditionally compute a crc32c value depending upon {@code hasher}.
   *
   * @see #segmentBuffers(ByteBuffer[], int, int)
   */
  ChunkSegment[] segmentBuffers(ByteBuffer[] bbs) {
    return segmentBuffers(bbs, 0, bbs.length);
  }

  ChunkSegment[] segmentBuffers(ByteBuffer[] bbs, int offset, int length) {
    Deque<ChunkSegment> data = new ArrayDeque<>();

    for (int i = offset; i < length; i++) {
      ByteBuffer buffer = bbs[i];
      int remaining;
      while ((remaining = buffer.remaining()) > 0) {
        // either no chunk or most recent chunk is full, start a new one
        ChunkSegment peekLast = data.peekLast();
        if (peekLast == null || peekLast.b.size() == maxSegmentSize) {
          int limit = Math.min(remaining, maxSegmentSize);
          ChunkSegment datum = newSegment(buffer, limit);
          data.addLast(datum);
        } else {
          ChunkSegment chunkSoFar = data.pollLast();
          //noinspection ConstantConditions -- covered by peekLast check above
          int limit = Math.min(remaining, maxSegmentSize - chunkSoFar.b.size());
          ChunkSegment datum = newSegment(buffer, limit);
          ChunkSegment plus = chunkSoFar.concat(datum);
          data.addLast(plus);
        }
      }
    }

    return data.toArray(new ChunkSegment[0]);
  }

  private ChunkSegment newSegment(ByteBuffer buffer, int limit) {
    final ByteBuffer slice = buffer.slice();
    slice.limit(limit);

    Crc32cLengthKnown hash = hasher.hash(slice::duplicate);
    ByteString byteString = bss.apply(slice);
    Buffers.position(buffer, buffer.position() + limit);

    return new ChunkSegment(byteString, hash);
  }

  final class ChunkSegment {
    private final ByteString b;
    @Nullable private final Crc32cLengthKnown crc32c;
    private final boolean onlyFullBlocks;

    private ChunkSegment(ByteString b, @Nullable Crc32cLengthKnown crc32c) {
      this.b = b;
      this.onlyFullBlocks = b.size() % blockSize == 0;
      this.crc32c = crc32c;
    }

    public ChunkSegment concat(ChunkSegment other) {
      Crc32cLengthKnown newCrc = null;
      if (crc32c != null && other.crc32c != null) {
        newCrc = crc32c.concat(other.crc32c);
      }
      ByteString concat = b.concat(other.b);
      return new ChunkSegment(concat, newCrc);
    }

    public ByteString getB() {
      return b;
    }

    @Nullable
    public Crc32cLengthKnown getCrc32c() {
      return crc32c;
    }

    public boolean isOnlyFullBlocks() {
      return onlyFullBlocks;
    }
  }
}
