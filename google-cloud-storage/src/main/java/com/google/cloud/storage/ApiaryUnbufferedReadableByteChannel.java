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

import static com.google.cloud.storage.Utils.ifNonNull;

import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.core.SettableApiFuture;
import com.google.api.gax.retrying.ResultRetryAlgorithm;
import com.google.api.services.storage.Storage;
import com.google.api.services.storage.Storage.Objects;
import com.google.api.services.storage.Storage.Objects.Get;
import com.google.api.services.storage.model.StorageObject;
import com.google.cloud.storage.UnbufferedReadableByteChannelSession.UnbufferedReadableByteChannel;
import com.google.cloud.storage.spi.v1.StorageRpc;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.concurrent.Immutable;

class ApiaryUnbufferedReadableByteChannel implements UnbufferedReadableByteChannel {

  private final ApiaryReadRequest apiaryReadRequest;
  private final Storage storage;
  private final SettableApiFuture<StorageObject> result;
  private final HttpStorageOptions options;
  private final ResultRetryAlgorithm<?> resultRetryAlgorithm;
  private final Consumer<StorageObject> resolvedObjectCallback;

  private long position;
  private ScatteringByteChannel sbc;
  private boolean open;

  // returned X-Goog-Generation header value
  private Long xGoogGeneration;

  ApiaryUnbufferedReadableByteChannel(
      ApiaryReadRequest apiaryReadRequest,
      Storage storage,
      SettableApiFuture<StorageObject> result,
      HttpStorageOptions options,
      ResultRetryAlgorithm<?> resultRetryAlgorithm,
      Consumer<StorageObject> resolvedObjectCallback) {
    this.apiaryReadRequest = apiaryReadRequest;
    this.storage = storage;
    this.result = result;
    this.options = options;
    this.resultRetryAlgorithm = resultRetryAlgorithm;
    this.resolvedObjectCallback = resolvedObjectCallback;
    this.open = true;
    this.position =
        apiaryReadRequest.getByteRangeSpec() != null
            ? apiaryReadRequest.getByteRangeSpec().beginOffset()
            : 0;
  }

  @SuppressWarnings("UnnecessaryContinue")
  @Override
  public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
    do {
      if (sbc == null) {
        sbc = Retrying.run(options, resultRetryAlgorithm, this::open, Function.identity());
      }

      try {
        // According to the contract of Retrying#run it's possible for sbc to be null even after
        // invocation. However, the function we provide is guaranteed to return non-null or throw
        // an exception. So we suppress the warning from intellij here.
        //noinspection ConstantConditions
        long read = sbc.read(dsts, offset, length);
        if (read == -1) {
          open = false;
        } else {
          position += read;
        }
        return read;
      } catch (Exception t) {
        if (resultRetryAlgorithm.shouldRetry(t, null)) {
          // if our retry algorithm COULD allow a retry, continue the loop and allow trying to
          // open the stream again.
          sbc = null;
          continue;
        } else if (t instanceof IOException) {
          IOException ioE = (IOException) t;
          if (resultRetryAlgorithm.shouldRetry(StorageException.translate(ioE), null)) {
            sbc = null;
            continue;
          } else {
            throw ioE;
          }
        } else {
          throw new IOException(StorageException.coalesce(t));
        }
      }
    } while (true);
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public void close() throws IOException {
    open = false;
    if (sbc != null) {
      sbc.close();
    }
  }

  private ScatteringByteChannel open() {
    try {
      Boolean b =
          (Boolean) apiaryReadRequest.options.get(StorageRpc.Option.RETURN_RAW_INPUT_STREAM);
      boolean returnRawInputStream = b != null ? b : true;
      ApiaryReadRequest request = apiaryReadRequest.withNewBeginOffset(position);
      Get get = createGetRequest(request, storage.objects(), xGoogGeneration, returnRawInputStream);

      HttpResponse media = get.executeMedia();
      InputStream content = media.getContent();
      if (xGoogGeneration == null) {
        HttpHeaders responseHeaders = media.getHeaders();
        //noinspection unchecked
        List<String> xGoogGenHeader = (List<String>) responseHeaders.get("x-goog-generation");
        // TODO: wire in result metadata population
        if (xGoogGenHeader != null && !xGoogGenHeader.isEmpty()) {
          String s = xGoogGenHeader.get(0);
          Long generation = Long.valueOf(s);
          this.xGoogGeneration = generation;
          resolvedObjectCallback.accept(
              apiaryReadRequest.getObject().clone().setGeneration(generation));
        }
      }

      ReadableByteChannel rbc = Channels.newChannel(content);
      return StorageByteChannels.readable().asScatteringByteChannel(rbc);
    } catch (HttpResponseException e) {
      if (xGoogGeneration != null) {
        int statusCode = e.getStatusCode();
        if (statusCode == 404) {
          throw new StorageException(404, "Failure while trying to resume download", e);
        }
      }
      throw StorageException.translate(e);
    } catch (IOException e) {
      throw StorageException.translate(e);
    } catch (Throwable t) {
      throw StorageException.coalesce(t);
    }
  }

  @VisibleForTesting
  static Get createGetRequest(
      ApiaryReadRequest apiaryReadRequest,
      Objects objects,
      Long xGoogGeneration,
      boolean returnRawInputStream)
      throws IOException {
    StorageObject from = apiaryReadRequest.getObject();
    Map<StorageRpc.Option, ?> options = apiaryReadRequest.getOptions();
    Get get = objects.get(from.getBucket(), from.getName());
    if (from.getGeneration() != null) {
      get.setGeneration(from.getGeneration());
    } else if (xGoogGeneration != null) {
      get.setGeneration(xGoogGeneration);
    }
    ifNonNull(
        options.get(StorageRpc.Option.IF_GENERATION_MATCH),
        ApiaryUnbufferedReadableByteChannel::cast,
        get::setIfGenerationMatch);
    ifNonNull(
        options.get(StorageRpc.Option.IF_GENERATION_NOT_MATCH),
        ApiaryUnbufferedReadableByteChannel::cast,
        get::setIfGenerationNotMatch);
    ifNonNull(
        options.get(StorageRpc.Option.IF_METAGENERATION_MATCH),
        ApiaryUnbufferedReadableByteChannel::cast,
        get::setIfMetagenerationMatch);
    ifNonNull(
        options.get(StorageRpc.Option.IF_METAGENERATION_NOT_MATCH),
        ApiaryUnbufferedReadableByteChannel::cast,
        get::setIfMetagenerationNotMatch);
    ifNonNull(
        options.get(StorageRpc.Option.USER_PROJECT),
        ApiaryUnbufferedReadableByteChannel::cast,
        get::setUserProject);
    HttpHeaders headers = get.getRequestHeaders();
    ifNonNull(
        options.get(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY),
        ApiaryUnbufferedReadableByteChannel::cast,
        (String key) -> {
          BaseEncoding base64 = BaseEncoding.base64();
          HashFunction hashFunction = Hashing.sha256();
          headers.set("x-goog-encryption-algorithm", "AES256");
          headers.set("x-goog-encryption-key", key);
          headers.set(
              "x-goog-encryption-key-sha256",
              base64.encode(hashFunction.hashBytes(base64.decode(key)).asBytes()));
        });

    get.setReturnRawInputStream(returnRawInputStream);
    String range = apiaryReadRequest.getByteRangeSpec().getHttpRangeHeader();
    if (range != null) {
      get.getRequestHeaders().setRange(range);
    }
    get.getMediaHttpDownloader().setDirectDownloadEnabled(true);

    return get;
  }

  @SuppressWarnings("unchecked")
  private static <T> T cast(Object o) {
    return (T) o;
  }

  @Immutable
  static final class ApiaryReadRequest implements Serializable {
    private static final long serialVersionUID = -4059435314115374448L;
    private static final Gson gson = new Gson();
    private transient StorageObject object;
    private final Map<StorageRpc.Option, ?> options;
    private final ByteRangeSpec byteRangeSpec;

    private volatile String objectJson;

    ApiaryReadRequest(
        StorageObject object, Map<StorageRpc.Option, ?> options, ByteRangeSpec byteRangeSpec) {
      this.object = object;
      this.options = options;
      this.byteRangeSpec = byteRangeSpec;
    }

    StorageObject getObject() {
      return object;
    }

    Map<StorageRpc.Option, ?> getOptions() {
      return options;
    }

    ByteRangeSpec getByteRangeSpec() {
      return byteRangeSpec;
    }

    ApiaryReadRequest withNewBeginOffset(long beginOffset) {
      if (beginOffset > 0 && beginOffset != byteRangeSpec.beginOffset()) {
        return new ApiaryReadRequest(
            object, options, byteRangeSpec.withNewBeginOffset(beginOffset));
      } else {
        return this;
      }
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof ApiaryReadRequest)) {
        return false;
      }
      ApiaryReadRequest that = (ApiaryReadRequest) o;
      return java.util.Objects.equals(object, that.object)
          && java.util.Objects.equals(options, that.options)
          && java.util.Objects.equals(byteRangeSpec, that.byteRangeSpec);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(object, options, byteRangeSpec);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("byteRangeSpec", byteRangeSpec)
          .add("options", options)
          .add("object", getObjectJson())
          .toString();
    }

    private String getObjectJson() {
      if (objectJson == null) {
        synchronized (this) {
          if (objectJson == null) {
            objectJson = gson.toJson(object);
          }
        }
      }
      return objectJson;
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
      String ignore = getObjectJson();
      out.defaultWriteObject();
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
      in.defaultReadObject();
      JsonReader jsonReader = gson.newJsonReader(new StringReader(this.objectJson));
      this.object = gson.fromJson(jsonReader, StorageObject.class);
    }
  }
}
