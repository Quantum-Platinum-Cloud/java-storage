/*
 * Copyright 2015 Google LLC
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

import static com.google.cloud.storage.SignedUrlEncodingHelper.Rfc3986UriEncode;
import static com.google.cloud.storage.testing.ApiPolicyMatcher.eqApiPolicy;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.getCurrentArguments;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.util.DateTime;
import com.google.api.core.ApiClock;
import com.google.api.gax.retrying.RetrySettings;
import com.google.api.services.storage.model.Policy.Bindings;
import com.google.api.services.storage.model.StorageObject;
import com.google.api.services.storage.model.TestIamPermissionsResponse;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.Identity;
import com.google.cloud.Policy;
import com.google.cloud.ServiceOptions;
import com.google.cloud.storage.Acl.Project;
import com.google.cloud.storage.Acl.Project.ProjectRole;
import com.google.cloud.storage.Acl.Role;
import com.google.cloud.storage.Acl.User;
import com.google.cloud.storage.Storage.BlobSourceOption;
import com.google.cloud.storage.Storage.BlobTargetOption;
import com.google.cloud.storage.Storage.BucketSourceOption;
import com.google.cloud.storage.Storage.CopyRequest;
import com.google.cloud.storage.spi.StorageRpcFactory;
import com.google.cloud.storage.spi.v1.RpcBatch;
import com.google.cloud.storage.spi.v1.StorageRpc;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;
import java.io.File;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.spec.SecretKeySpec;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class StorageImplTest {

  private static final String BUCKET_NAME1 = "b1";
  private static final String BUCKET_NAME2 = "b2";
  private static final String BUCKET_NAME3 = "b3";
  private static final String BLOB_NAME1 = "n1";
  private static final String BLOB_NAME2 = "n2";
  private static final String BLOB_NAME3 = "n3";
  private static final byte[] BLOB_CONTENT = {0xD, 0xE, 0xA, 0xD};
  private static final String BASE64_KEY = "JVzfVl8NLD9FjedFuStegjRfES5ll5zc59CIXw572OA=";
  private static final Key KEY =
      new SecretKeySpec(BaseEncoding.base64().decode(BASE64_KEY), "AES256");
  private static final String KMS_KEY_NAME =
      "projects/gcloud-devel/locations/us/keyRings/gcs_kms_key_ring_us/cryptoKeys/key";
  private static final Long RETENTION_PERIOD = 10L;
  private static final String USER_PROJECT = "test-project";
  // BucketInfo objects
  private static final BucketInfo BUCKET_INFO1 =
      BucketInfo.newBuilder(BUCKET_NAME1).setMetageneration(42L).build();
  private static final BucketInfo BUCKET_INFO2 = BucketInfo.newBuilder(BUCKET_NAME2).build();
  private static final BucketInfo BUCKET_INFO3 =
      BucketInfo.newBuilder(BUCKET_NAME3)
          .setRetentionPeriod(RETENTION_PERIOD)
          .setRetentionPolicyIsLocked(true)
          .setMetageneration(42L)
          .build();

  // BlobInfo objects
  private static final BlobInfo BLOB_INFO1 =
      BlobInfo.newBuilder(BUCKET_NAME1, BLOB_NAME1, 24L)
          .setMetageneration(42L)
          .setContentType("application/json")
          .setMd5("md5string")
          .build();
  private static final BlobInfo BLOB_INFO2 = BlobInfo.newBuilder(BUCKET_NAME1, BLOB_NAME2).build();
  private static final BlobInfo BLOB_INFO3 = BlobInfo.newBuilder(BUCKET_NAME1, BLOB_NAME3).build();

  // Empty StorageRpc options
  private static final Map<StorageRpc.Option, ?> EMPTY_RPC_OPTIONS = ImmutableMap.of();

  // Bucket target options
  private static final Storage.BucketTargetOption BUCKET_TARGET_METAGENERATION =
      Storage.BucketTargetOption.metagenerationMatch();
  private static final Storage.BucketTargetOption BUCKET_TARGET_PREDEFINED_ACL =
      Storage.BucketTargetOption.predefinedAcl(Storage.PredefinedAcl.PRIVATE);
  private static final Storage.BucketTargetOption BUCKET_TARGET_USER_PROJECT =
      Storage.BucketTargetOption.userProject(USER_PROJECT);
  private static final Map<StorageRpc.Option, ?> BUCKET_TARGET_OPTIONS =
      ImmutableMap.of(
          StorageRpc.Option.IF_METAGENERATION_MATCH, BUCKET_INFO1.getMetageneration(),
          StorageRpc.Option.PREDEFINED_ACL, Storage.PredefinedAcl.PRIVATE.getEntry());
  private static final Map<StorageRpc.Option, ?> BUCKET_TARGET_OPTIONS_LOCK_RETENTION_POLICY =
      ImmutableMap.of(
          StorageRpc.Option.IF_METAGENERATION_MATCH,
          BUCKET_INFO3.getMetageneration(),
          StorageRpc.Option.USER_PROJECT,
          USER_PROJECT);

  // Blob target options (create, update, compose)
  private static final BlobTargetOption BLOB_TARGET_GENERATION = BlobTargetOption.generationMatch();
  private static final BlobTargetOption BLOB_TARGET_METAGENERATION =
      BlobTargetOption.metagenerationMatch();
  private static final BlobTargetOption BLOB_TARGET_PREDEFINED_ACL =
      BlobTargetOption.predefinedAcl(Storage.PredefinedAcl.PRIVATE);
  private static final Map<StorageRpc.Option, ?> BLOB_TARGET_OPTIONS_UPDATE =
      ImmutableMap.of(
          StorageRpc.Option.IF_METAGENERATION_MATCH, BLOB_INFO1.getMetageneration(),
          StorageRpc.Option.PREDEFINED_ACL, Storage.PredefinedAcl.PRIVATE.getEntry());
  private static final Map<StorageRpc.Option, ?> BLOB_TARGET_OPTIONS_COMPOSE =
      ImmutableMap.of(
          StorageRpc.Option.IF_GENERATION_MATCH, BLOB_INFO1.getGeneration(),
          StorageRpc.Option.IF_METAGENERATION_MATCH, BLOB_INFO1.getMetageneration());

  // Bucket get/source options
  private static final BucketSourceOption BUCKET_SOURCE_METAGENERATION =
      BucketSourceOption.metagenerationMatch(BUCKET_INFO1.getMetageneration());
  private static final Map<StorageRpc.Option, ?> BUCKET_SOURCE_OPTIONS =
      ImmutableMap.of(StorageRpc.Option.IF_METAGENERATION_MATCH, BUCKET_INFO1.getMetageneration());

  private static final BlobSourceOption BLOB_SOURCE_METAGENERATION =
      BlobSourceOption.metagenerationMatch(BLOB_INFO1.getMetageneration());
  private static final BlobSourceOption BLOB_SOURCE_GENERATION =
      BlobSourceOption.generationMatch(BLOB_INFO1.getGeneration());
  private static final BlobSourceOption BLOB_SOURCE_GENERATION_FROM_BLOB_ID =
      BlobSourceOption.generationMatch();
  private static final Map<StorageRpc.Option, ?> BLOB_SOURCE_OPTIONS =
      ImmutableMap.of(
          StorageRpc.Option.IF_METAGENERATION_MATCH, BLOB_INFO1.getMetageneration(),
          StorageRpc.Option.IF_GENERATION_MATCH, BLOB_INFO1.getGeneration());
  private static final Map<StorageRpc.Option, ?> BLOB_SOURCE_OPTIONS_COPY =
      ImmutableMap.of(
          StorageRpc.Option.IF_SOURCE_METAGENERATION_MATCH, BUCKET_INFO1.getMetageneration(),
          StorageRpc.Option.IF_SOURCE_GENERATION_MATCH, BLOB_INFO1.getGeneration());

  // ACLs
  private static final Acl ACL = Acl.of(User.ofAllAuthenticatedUsers(), Role.OWNER);
  private static final Acl OTHER_ACL = Acl.of(new Project(ProjectRole.OWNERS, "p"), Role.READER);

  // Customer supplied encryption key options
  private static final Map<StorageRpc.Option, ?> ENCRYPTION_KEY_OPTIONS =
      ImmutableMap.of(StorageRpc.Option.CUSTOMER_SUPPLIED_KEY, BASE64_KEY);

  // Customer managed encryption key options
  private static final Map<StorageRpc.Option, ?> KMS_KEY_NAME_OPTIONS =
      ImmutableMap.of(StorageRpc.Option.KMS_KEY_NAME, KMS_KEY_NAME);
  // IAM policies
  private static final String POLICY_ETAG1 = "CAE=";
  private static final String POLICY_ETAG2 = "CAI=";
  private static final Policy LIB_POLICY1 =
      Policy.newBuilder()
          .addIdentity(StorageRoles.objectViewer(), Identity.allUsers())
          .addIdentity(
              StorageRoles.objectAdmin(),
              Identity.user("test1@gmail.com"),
              Identity.user("test2@gmail.com"))
          .setEtag(POLICY_ETAG1)
          .setVersion(1)
          .build();

  private static final ServiceAccount SERVICE_ACCOUNT = ServiceAccount.of("test@google.com");

  private static final com.google.api.services.storage.model.Policy API_POLICY1 =
      new com.google.api.services.storage.model.Policy()
          .setBindings(
              ImmutableList.of(
                  new Bindings()
                      .setMembers(ImmutableList.of("allUsers"))
                      .setRole("roles/storage.objectViewer"),
                  new Bindings()
                      .setMembers(ImmutableList.of("user:test1@gmail.com", "user:test2@gmail.com"))
                      .setRole("roles/storage.objectAdmin")))
          .setEtag(POLICY_ETAG1)
          .setVersion(1);

  private static final String PRIVATE_KEY_STRING =
      "MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoG"
          + "BAL2xolH1zrISQ8+GzOV29BNjjzq4/HIP8Psd1+cZb81vDklSF+95wB250MSE0BDc81pvIMwj5OmIfLg1NY6uB"
          + "1xavOPpVdx1z664AGc/BEJ1zInXGXaQ6s+SxGenVq40Yws57gikQGMZjttpf1Qbz4DjkxsbRoeaRHn06n9pH1e"
          + "jAgMBAAECgYEAkWcm0AJF5LMhbWKbjkxm/LG06UNApkHX6vTOOOODkonM/qDBnhvKCj8Tan+PaU2j7679Cd19q"
          + "xCm4SBQJET7eBhqLD9L2j9y0h2YUQnLbISaqUS1/EXcr2C1Lf9VCEn1y/GYuDYqs85rGoQ4ZYfM9ClROSq86fH"
          + "+cbIIssqJqukCQQD18LjfJz/ichFeli5/l1jaFid2XoCH3T6TVuuysszVx68fh60gSIxEF/0X2xB+wuPxTP4IQ"
          + "+t8tD/ktd232oWXAkEAxXPych2QBHePk9/lek4tOkKBgfnDzex7S/pI0G1vpB3VmzBbCsokn9lpOv7JV8071GD"
          + "lW/7R6jlLfpQy3hN31QJAE10osSk99m5Uv8XDU3hvHnywDrnSFOBulNs7I47AYfSe7TSZhPkxUgsxejddTR27J"
          + "LyTI8N1PxRSE4feNSOXcQJAMMKJRJT4U6IS2rmXubREhvaVdLtxFxEnAYQ1JwNfZm/XqBMw6GEy2iaeTetNXVl"
          + "ZRQEIoscyn1y2v/No/F5iYQJBAKBOGASoQcBjGTOg/H/SfcE8QVNsKEpthRrs6CkpT80aZ/AV+ksfoIf2zw2M3"
          + "mAHfrO+TBLdz4sicuFQvlN9SEc=";

  private static final String PUBLIC_KEY_STRING =
      "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC9saJR9c6y"
          + "EkPPhszldvQTY486uPxyD/D7HdfnGW/Nbw5JUhfvecAdudDEhNAQ3PNabyDMI+TpiHy4NTWOrgdcWrzj6VXcdc"
          + "+uuABnPwRCdcyJ1xl2kOrPksRnp1auNGMLOe4IpEBjGY7baX9UG8+A45MbG0aHmkR59Op/aR9XowIDAQAB";

  private static final ApiClock TIME_SOURCE =
      new ApiClock() {
        @Override
        public long nanoTime() {
          return 42_000_000_000L;
        }

        @Override
        public long millisTime() {
          return 42_000L;
        }
      };

  // List of chars under test were taken from
  // https://en.wikipedia.org/wiki/Percent-encoding#Percent-encoding_reserved_characters
  private static final Map<Character, String> RFC3986_URI_ENCODING_MAP =
      ImmutableMap.<Character, String>builder()
          .put('!', "%21")
          .put('#', "%23")
          .put('$', "%24")
          .put('&', "%26")
          .put('\'', "%27")
          .put('(', "%28")
          .put(')', "%29")
          .put('*', "%2A")
          .put('+', "%2B")
          .put(',', "%2C")
          // NOTE: Whether the forward slash character should be encoded depends on the URI segment
          // being encoded. The path segment should not encode forward slashes, but others (e.g.
          // query parameter keys and values) should encode them. Tests verifying encoding behavior
          // in path segments should make a copy of this map and replace the mapping for '/' to "/".
          .put('/', "%2F")
          .put(':', "%3A")
          .put(';', "%3B")
          .put('=', "%3D")
          .put('?', "%3F")
          .put('@', "%40")
          .put('[', "%5B")
          .put(']', "%5D")
          // In addition to [a-zA-Z0-9], these chars should not be URI-encoded:
          .put('-', "-")
          .put('_', "_")
          .put('.', ".")
          .put('~', "~")
          .build();

  private static final String ACCOUNT = "account";
  private static PrivateKey privateKey;
  private static PublicKey publicKey;

  private StorageOptions options;
  private StorageRpcFactory rpcFactoryMock;
  private StorageRpc storageRpcMock;
  private Storage storage;

  private Blob expectedBlob1, expectedBlob2, expectedBlob3;
  private Bucket expectedBucket1, expectedBucket2, expectedBucket3;

  @BeforeClass
  public static void beforeClass() throws NoSuchAlgorithmException, InvalidKeySpecException {
    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
    EncodedKeySpec privateKeySpec =
        new PKCS8EncodedKeySpec(BaseEncoding.base64().decode(PRIVATE_KEY_STRING));
    privateKey = keyFactory.generatePrivate(privateKeySpec);
    EncodedKeySpec publicKeySpec =
        new X509EncodedKeySpec(BaseEncoding.base64().decode(PUBLIC_KEY_STRING));
    publicKey = keyFactory.generatePublic(publicKeySpec);
  }

  @Before
  public void setUp() {
    rpcFactoryMock = EasyMock.createMock(StorageRpcFactory.class);
    storageRpcMock = EasyMock.createMock(StorageRpc.class);
    EasyMock.expect(rpcFactoryMock.create(EasyMock.anyObject(StorageOptions.class)))
        .andReturn(storageRpcMock);
    EasyMock.replay(rpcFactoryMock);
    options =
        StorageOptions.http()
            .setProjectId("projectId")
            .setClock(TIME_SOURCE)
            .setServiceRpcFactory(rpcFactoryMock)
            .setRetrySettings(ServiceOptions.getNoRetrySettings())
            .build();
  }

  @After
  public void tearDown() throws Exception {
    EasyMock.verify(rpcFactoryMock, storageRpcMock);
  }

  private void initializeService() {
    storage = options.getService();
    initializeServiceDependentObjects();
  }

  private void initializeServiceDependentObjects() {
    expectedBlob1 = new Blob(storage, new BlobInfo.BuilderImpl(BLOB_INFO1));
    expectedBlob2 = new Blob(storage, new BlobInfo.BuilderImpl(BLOB_INFO2));
    expectedBlob3 = new Blob(storage, new BlobInfo.BuilderImpl(BLOB_INFO3));
    expectedBucket1 = new Bucket(storage, new BucketInfo.BuilderImpl(BUCKET_INFO1));
    expectedBucket2 = new Bucket(storage, new BucketInfo.BuilderImpl(BUCKET_INFO2));
    expectedBucket3 = new Bucket(storage, new BucketInfo.BuilderImpl(BUCKET_INFO3));
  }

  @Test
  public void testUpdateBucket() {
    BucketInfo updatedBucketInfo = BUCKET_INFO1.toBuilder().setIndexPage("some-page").build();
    EasyMock.expect(
            storageRpcMock.patch(
                Conversions.apiary().bucketInfo().encode(updatedBucketInfo), EMPTY_RPC_OPTIONS))
        .andReturn(Conversions.apiary().bucketInfo().encode(updatedBucketInfo));
    EasyMock.replay(storageRpcMock);
    initializeService();
    Bucket bucket = storage.update(updatedBucketInfo);
    assertEquals(new Bucket(storage, new BucketInfo.BuilderImpl(updatedBucketInfo)), bucket);
  }

  @Test
  public void testUpdateBucketWithOptions() {
    BucketInfo updatedBucketInfo = BUCKET_INFO1.toBuilder().setIndexPage("some-page").build();
    EasyMock.expect(
            storageRpcMock.patch(
                Conversions.apiary().bucketInfo().encode(updatedBucketInfo), BUCKET_TARGET_OPTIONS))
        .andReturn(Conversions.apiary().bucketInfo().encode(updatedBucketInfo));
    EasyMock.replay(storageRpcMock);
    initializeService();
    Bucket bucket =
        storage.update(
            updatedBucketInfo, BUCKET_TARGET_METAGENERATION, BUCKET_TARGET_PREDEFINED_ACL);
    assertEquals(new Bucket(storage, new BucketInfo.BuilderImpl(updatedBucketInfo)), bucket);
  }

  @Test
  public void testUpdateBlob() {
    BlobInfo updatedBlobInfo = BLOB_INFO1.toBuilder().setContentType("some-content-type").build();
    EasyMock.expect(
            storageRpcMock.patch(
                Conversions.apiary().blobInfo().encode(updatedBlobInfo), EMPTY_RPC_OPTIONS))
        .andReturn(Conversions.apiary().blobInfo().encode(updatedBlobInfo));
    EasyMock.replay(storageRpcMock);
    initializeService();
    Blob blob = storage.update(updatedBlobInfo);
    assertEquals(new Blob(storage, new BlobInfo.BuilderImpl(updatedBlobInfo)), blob);
  }

  @Test
  public void testUpdateBlobWithOptions() {
    BlobInfo updatedBlobInfo = BLOB_INFO1.toBuilder().setContentType("some-content-type").build();
    EasyMock.expect(
            storageRpcMock.patch(
                Conversions.apiary().blobInfo().encode(updatedBlobInfo),
                BLOB_TARGET_OPTIONS_UPDATE))
        .andReturn(Conversions.apiary().blobInfo().encode(updatedBlobInfo));
    EasyMock.replay(storageRpcMock);
    initializeService();
    Blob blob =
        storage.update(updatedBlobInfo, BLOB_TARGET_METAGENERATION, BLOB_TARGET_PREDEFINED_ACL);
    assertEquals(new Blob(storage, new BlobInfo.BuilderImpl(updatedBlobInfo)), blob);
  }

  @Test
  public void testDeleteBucket() {
    EasyMock.expect(
            storageRpcMock.delete(
                Conversions.apiary().bucketInfo().encode(BucketInfo.of(BUCKET_NAME1)),
                EMPTY_RPC_OPTIONS))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(storage.delete(BUCKET_NAME1));
  }

  @Test
  public void testDeleteBucketWithOptions() {
    EasyMock.expect(
            storageRpcMock.delete(
                Conversions.apiary().bucketInfo().encode(BucketInfo.of(BUCKET_NAME1)),
                BUCKET_SOURCE_OPTIONS))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(storage.delete(BUCKET_NAME1, BUCKET_SOURCE_METAGENERATION));
  }

  @Test
  public void testDeleteBlob() {
    EasyMock.expect(
            storageRpcMock.delete(
                Conversions.apiary().blobId().encode(BlobId.of(BUCKET_NAME1, BLOB_NAME1)),
                EMPTY_RPC_OPTIONS))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(storage.delete(BUCKET_NAME1, BLOB_NAME1));
  }

  @Test
  public void testDeleteBlobWithOptions() {
    EasyMock.expect(
            storageRpcMock.delete(
                Conversions.apiary().blobId().encode(BlobId.of(BUCKET_NAME1, BLOB_NAME1)),
                BLOB_SOURCE_OPTIONS))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(
        storage.delete(
            BUCKET_NAME1, BLOB_NAME1, BLOB_SOURCE_GENERATION, BLOB_SOURCE_METAGENERATION));
  }

  @Test
  public void testDeleteBlobWithOptionsFromBlobId() {
    EasyMock.expect(
            storageRpcMock.delete(
                Conversions.apiary().blobId().encode(BLOB_INFO1.getBlobId()), BLOB_SOURCE_OPTIONS))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(
        storage.delete(
            BLOB_INFO1.getBlobId(),
            BLOB_SOURCE_GENERATION_FROM_BLOB_ID,
            BLOB_SOURCE_METAGENERATION));
  }

  @Test
  public void testCompose() {
    Storage.ComposeRequest req =
        Storage.ComposeRequest.newBuilder()
            .addSource(BLOB_NAME2, BLOB_NAME3)
            .setTarget(BLOB_INFO1)
            .build();
    EasyMock.expect(
            storageRpcMock.compose(
                ImmutableList.of(
                    Conversions.apiary().blobInfo().encode(BLOB_INFO2),
                    Conversions.apiary().blobInfo().encode(BLOB_INFO3)),
                Conversions.apiary().blobInfo().encode(BLOB_INFO1),
                EMPTY_RPC_OPTIONS))
        .andReturn(Conversions.apiary().blobInfo().encode(BLOB_INFO1));
    EasyMock.replay(storageRpcMock);
    initializeService();
    Blob blob = storage.compose(req);
    assertEquals(expectedBlob1, blob);
  }

  @Test
  public void testComposeWithOptions() {
    Storage.ComposeRequest req =
        Storage.ComposeRequest.newBuilder()
            .addSource(BLOB_NAME2, BLOB_NAME3)
            .setTarget(BLOB_INFO1)
            .setTargetOptions(BLOB_TARGET_GENERATION, BLOB_TARGET_METAGENERATION)
            .build();
    EasyMock.expect(
            storageRpcMock.compose(
                ImmutableList.of(
                    Conversions.apiary().blobInfo().encode(BLOB_INFO2),
                    Conversions.apiary().blobInfo().encode(BLOB_INFO3)),
                Conversions.apiary().blobInfo().encode(BLOB_INFO1),
                BLOB_TARGET_OPTIONS_COMPOSE))
        .andReturn(Conversions.apiary().blobInfo().encode(BLOB_INFO1));
    EasyMock.replay(storageRpcMock);
    initializeService();
    Blob blob = storage.compose(req);
    assertEquals(expectedBlob1, blob);
  }

  @Test
  public void testCopy() {
    CopyRequest request = Storage.CopyRequest.of(BLOB_INFO1.getBlobId(), BLOB_INFO2.getBlobId());
    StorageRpc.RewriteRequest rpcRequest =
        new StorageRpc.RewriteRequest(
            Conversions.apiary().blobId().encode(request.getSource()),
            EMPTY_RPC_OPTIONS,
            false,
            Conversions.apiary().blobInfo().encode(BLOB_INFO2),
            EMPTY_RPC_OPTIONS,
            null);
    StorageRpc.RewriteResponse rpcResponse =
        new StorageRpc.RewriteResponse(rpcRequest, null, 42L, false, "token", 21L);
    EasyMock.expect(storageRpcMock.openRewrite(rpcRequest)).andReturn(rpcResponse);
    EasyMock.replay(storageRpcMock);
    initializeService();
    CopyWriter writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
  }

  @Test
  public void testCopyWithOptions() {
    CopyRequest request =
        Storage.CopyRequest.newBuilder()
            .setSource(BLOB_INFO2.getBlobId())
            .setSourceOptions(BLOB_SOURCE_GENERATION, BLOB_SOURCE_METAGENERATION)
            .setTarget(BLOB_INFO1, BLOB_TARGET_GENERATION, BLOB_TARGET_METAGENERATION)
            .build();
    StorageRpc.RewriteRequest rpcRequest =
        new StorageRpc.RewriteRequest(
            Conversions.apiary().blobId().encode(request.getSource()),
            BLOB_SOURCE_OPTIONS_COPY,
            true,
            Conversions.apiary().blobInfo().encode(request.getTarget()),
            BLOB_TARGET_OPTIONS_COMPOSE,
            null);
    StorageRpc.RewriteResponse rpcResponse =
        new StorageRpc.RewriteResponse(rpcRequest, null, 42L, false, "token", 21L);
    EasyMock.expect(storageRpcMock.openRewrite(rpcRequest)).andReturn(rpcResponse);
    EasyMock.replay(storageRpcMock);
    initializeService();
    CopyWriter writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
  }

  @Test
  public void testCopyWithEncryptionKey() {
    CopyRequest request =
        Storage.CopyRequest.newBuilder()
            .setSource(BLOB_INFO2.getBlobId())
            .setSourceOptions(BlobSourceOption.decryptionKey(KEY))
            .setTarget(BLOB_INFO1, BlobTargetOption.encryptionKey(BASE64_KEY))
            .build();
    StorageRpc.RewriteRequest rpcRequest =
        new StorageRpc.RewriteRequest(
            Conversions.apiary().blobId().encode(request.getSource()),
            ENCRYPTION_KEY_OPTIONS,
            true,
            Conversions.apiary().blobInfo().encode(request.getTarget()),
            ENCRYPTION_KEY_OPTIONS,
            null);
    StorageRpc.RewriteResponse rpcResponse =
        new StorageRpc.RewriteResponse(rpcRequest, null, 42L, false, "token", 21L);
    EasyMock.expect(storageRpcMock.openRewrite(rpcRequest)).andReturn(rpcResponse).times(2);
    EasyMock.replay(storageRpcMock);
    initializeService();
    CopyWriter writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
    request =
        Storage.CopyRequest.newBuilder()
            .setSource(BLOB_INFO2.getBlobId())
            .setSourceOptions(BlobSourceOption.decryptionKey(BASE64_KEY))
            .setTarget(BLOB_INFO1, BlobTargetOption.encryptionKey(KEY))
            .build();
    writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
  }

  @Test
  public void testCopyFromEncryptionKeyToKmsKeyName() {
    CopyRequest request =
        Storage.CopyRequest.newBuilder()
            .setSource(BLOB_INFO2.getBlobId())
            .setSourceOptions(BlobSourceOption.decryptionKey(KEY))
            .setTarget(BLOB_INFO1, BlobTargetOption.kmsKeyName(KMS_KEY_NAME))
            .build();
    StorageRpc.RewriteRequest rpcRequest =
        new StorageRpc.RewriteRequest(
            Conversions.apiary().blobId().encode(request.getSource()),
            ENCRYPTION_KEY_OPTIONS,
            true,
            Conversions.apiary().blobInfo().encode(request.getTarget()),
            KMS_KEY_NAME_OPTIONS,
            null);
    StorageRpc.RewriteResponse rpcResponse =
        new StorageRpc.RewriteResponse(rpcRequest, null, 42L, false, "token", 21L);
    EasyMock.expect(storageRpcMock.openRewrite(rpcRequest)).andReturn(rpcResponse).times(2);
    EasyMock.replay(storageRpcMock);
    initializeService();
    CopyWriter writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
    request =
        Storage.CopyRequest.newBuilder()
            .setSource(BLOB_INFO2.getBlobId())
            .setSourceOptions(BlobSourceOption.decryptionKey(BASE64_KEY))
            .setTarget(BLOB_INFO1, BlobTargetOption.kmsKeyName(KMS_KEY_NAME))
            .build();
    writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
  }

  @Test
  public void testCopyWithOptionsFromBlobId() {
    CopyRequest request =
        Storage.CopyRequest.newBuilder()
            .setSource(BLOB_INFO1.getBlobId())
            .setSourceOptions(BLOB_SOURCE_GENERATION_FROM_BLOB_ID, BLOB_SOURCE_METAGENERATION)
            .setTarget(BLOB_INFO1, BLOB_TARGET_GENERATION, BLOB_TARGET_METAGENERATION)
            .build();
    StorageRpc.RewriteRequest rpcRequest =
        new StorageRpc.RewriteRequest(
            Conversions.apiary().blobId().encode(request.getSource()),
            BLOB_SOURCE_OPTIONS_COPY,
            true,
            Conversions.apiary().blobInfo().encode(request.getTarget()),
            BLOB_TARGET_OPTIONS_COMPOSE,
            null);
    StorageRpc.RewriteResponse rpcResponse =
        new StorageRpc.RewriteResponse(rpcRequest, null, 42L, false, "token", 21L);
    EasyMock.expect(storageRpcMock.openRewrite(rpcRequest)).andReturn(rpcResponse);
    EasyMock.replay(storageRpcMock);
    initializeService();
    CopyWriter writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
  }

  @Test
  public void testCopyMultipleRequests() {
    CopyRequest request = Storage.CopyRequest.of(BLOB_INFO1.getBlobId(), BLOB_INFO2.getBlobId());
    StorageRpc.RewriteRequest rpcRequest =
        new StorageRpc.RewriteRequest(
            Conversions.apiary().blobId().encode(request.getSource()),
            EMPTY_RPC_OPTIONS,
            false,
            Conversions.apiary().blobInfo().encode(BLOB_INFO2),
            EMPTY_RPC_OPTIONS,
            null);
    StorageRpc.RewriteResponse rpcResponse1 =
        new StorageRpc.RewriteResponse(rpcRequest, null, 42L, false, "token", 21L);
    StorageRpc.RewriteResponse rpcResponse2 =
        new StorageRpc.RewriteResponse(
            rpcRequest,
            Conversions.apiary().blobInfo().encode(BLOB_INFO1),
            42L,
            true,
            "token",
            42L);
    EasyMock.expect(storageRpcMock.openRewrite(rpcRequest)).andReturn(rpcResponse1);
    EasyMock.expect(storageRpcMock.continueRewrite(rpcResponse1)).andReturn(rpcResponse2);
    EasyMock.replay(storageRpcMock);
    initializeService();
    CopyWriter writer = storage.copy(request);
    assertEquals(42L, writer.getBlobSize());
    assertEquals(21L, writer.getTotalBytesCopied());
    assertTrue(!writer.isDone());
    assertEquals(expectedBlob1, writer.getResult());
    assertTrue(writer.isDone());
    assertEquals(42L, writer.getTotalBytesCopied());
    assertEquals(42L, writer.getBlobSize());
  }

  @Test
  public void testReadAllBytes() {
    EasyMock.expect(
            storageRpcMock.load(
                Conversions.apiary().blobId().encode(BlobId.of(BUCKET_NAME1, BLOB_NAME1)),
                EMPTY_RPC_OPTIONS))
        .andReturn(BLOB_CONTENT);
    EasyMock.replay(storageRpcMock);
    initializeService();
    byte[] readBytes = storage.readAllBytes(BUCKET_NAME1, BLOB_NAME1);
    assertArrayEquals(BLOB_CONTENT, readBytes);
  }

  @Test
  public void testReadAllBytesWithOptions() {
    EasyMock.expect(
            storageRpcMock.load(
                Conversions.apiary().blobId().encode(BlobId.of(BUCKET_NAME1, BLOB_NAME1)),
                BLOB_SOURCE_OPTIONS))
        .andReturn(BLOB_CONTENT);
    EasyMock.replay(storageRpcMock);
    initializeService();
    byte[] readBytes =
        storage.readAllBytes(
            BUCKET_NAME1, BLOB_NAME1, BLOB_SOURCE_GENERATION, BLOB_SOURCE_METAGENERATION);
    assertArrayEquals(BLOB_CONTENT, readBytes);
  }

  @Test
  public void testReadAllBytesWithDecriptionKey() {
    EasyMock.expect(
            storageRpcMock.load(
                Conversions.apiary().blobId().encode(BlobId.of(BUCKET_NAME1, BLOB_NAME1)),
                ENCRYPTION_KEY_OPTIONS))
        .andReturn(BLOB_CONTENT)
        .times(2);
    EasyMock.replay(storageRpcMock);
    initializeService();
    byte[] readBytes =
        storage.readAllBytes(BUCKET_NAME1, BLOB_NAME1, BlobSourceOption.decryptionKey(KEY));
    assertArrayEquals(BLOB_CONTENT, readBytes);
    readBytes =
        storage.readAllBytes(BUCKET_NAME1, BLOB_NAME1, BlobSourceOption.decryptionKey(BASE64_KEY));
    assertArrayEquals(BLOB_CONTENT, readBytes);
  }

  @Test
  public void testReadAllBytesFromBlobIdWithOptions() {
    EasyMock.expect(
            storageRpcMock.load(
                Conversions.apiary().blobId().encode(BLOB_INFO1.getBlobId()), BLOB_SOURCE_OPTIONS))
        .andReturn(BLOB_CONTENT);
    EasyMock.replay(storageRpcMock);
    initializeService();
    byte[] readBytes =
        storage.readAllBytes(
            BLOB_INFO1.getBlobId(),
            BLOB_SOURCE_GENERATION_FROM_BLOB_ID,
            BLOB_SOURCE_METAGENERATION);
    assertArrayEquals(BLOB_CONTENT, readBytes);
  }

  @Test
  public void testReadAllBytesFromBlobIdWithDecriptionKey() {
    EasyMock.expect(
            storageRpcMock.load(
                Conversions.apiary().blobId().encode(BLOB_INFO1.getBlobId()),
                ENCRYPTION_KEY_OPTIONS))
        .andReturn(BLOB_CONTENT)
        .times(2);
    EasyMock.replay(storageRpcMock);
    initializeService();
    byte[] readBytes =
        storage.readAllBytes(BLOB_INFO1.getBlobId(), BlobSourceOption.decryptionKey(KEY));
    assertArrayEquals(BLOB_CONTENT, readBytes);
    readBytes =
        storage.readAllBytes(BLOB_INFO1.getBlobId(), BlobSourceOption.decryptionKey(BASE64_KEY));
    assertArrayEquals(BLOB_CONTENT, readBytes);
  }

  @Test
  public void testBatch() {
    RpcBatch batchMock = EasyMock.mock(RpcBatch.class);
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    EasyMock.replay(batchMock, storageRpcMock);
    initializeService();
    StorageBatch batch = storage.batch();
    assertSame(options, batch.getOptions());
    assertSame(storageRpcMock, batch.getStorageRpc());
    assertSame(batchMock, batch.getBatch());
    EasyMock.verify(batchMock);
  }

  @Test
  public void testSignUrl()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();
    URL url = storage.signUrl(BLOB_INFO1, 14, TimeUnit.DAYS);
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://storage.googleapis.com/")
            .append(BUCKET_NAME1)
            .append('/')
            .append(BLOB_NAME1)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpMethod.GET)
        .append("\n\n\n")
        .append(42L + 1209600)
        .append("\n/")
        .append(BUCKET_NAME1)
        .append('/')
        .append(BLOB_NAME1);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testSignUrlWithHostName()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();
    URL url =
        storage.signUrl(
            BLOB_INFO1,
            14,
            TimeUnit.DAYS,
            Storage.SignUrlOption.withHostName("https://example.com"));
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://example.com/")
            .append(BUCKET_NAME1)
            .append('/')
            .append(BLOB_NAME1)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpMethod.GET)
        .append("\n\n\n")
        .append(42L + 1209600)
        .append("\n/")
        .append(BUCKET_NAME1)
        .append('/')
        .append(BLOB_NAME1);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testSignUrlLeadingSlash()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    String blobName = "/b1";
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();
    URL url =
        storage.signUrl(BlobInfo.newBuilder(BUCKET_NAME1, blobName).build(), 14, TimeUnit.DAYS);
    String expectedResourcePath = "/b1";
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://storage.googleapis.com/")
            .append(BUCKET_NAME1)
            .append("/")
            .append(expectedResourcePath)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpMethod.GET)
        .append("\n\n\n")
        .append(42L + 1209600)
        .append("\n/")
        .append(BUCKET_NAME1)
        .append("/")
        .append(expectedResourcePath);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testSignUrlLeadingSlashWithHostName()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    String blobName = "/b1";
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();
    URL url =
        storage.signUrl(
            BlobInfo.newBuilder(BUCKET_NAME1, blobName).build(),
            14,
            TimeUnit.DAYS,
            Storage.SignUrlOption.withHostName("https://example.com"));
    String escapedBlobName = Rfc3986UriEncode(blobName, false);
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://example.com/")
            .append(BUCKET_NAME1)
            .append("/")
            .append(escapedBlobName)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpMethod.GET)
        .append("\n\n\n")
        .append(42L + 1209600)
        .append("\n/")
        .append(BUCKET_NAME1)
        .append("/")
        .append(escapedBlobName);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testSignUrlWithOptions()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();
    URL url =
        storage.signUrl(
            BLOB_INFO1,
            14,
            TimeUnit.DAYS,
            Storage.SignUrlOption.httpMethod(HttpMethod.POST),
            Storage.SignUrlOption.withContentType(),
            Storage.SignUrlOption.withMd5());
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://storage.googleapis.com/")
            .append(BUCKET_NAME1)
            .append('/')
            .append(BLOB_NAME1)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpMethod.POST)
        .append('\n')
        .append(BLOB_INFO1.getMd5())
        .append('\n')
        .append(BLOB_INFO1.getContentType())
        .append('\n')
        .append(42L + 1209600)
        .append("\n/")
        .append(BUCKET_NAME1)
        .append('/')
        .append(BLOB_NAME1);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testSignUrlWithOptionsAndHostName()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();
    URL url =
        storage.signUrl(
            BLOB_INFO1,
            14,
            TimeUnit.DAYS,
            Storage.SignUrlOption.httpMethod(HttpMethod.POST),
            Storage.SignUrlOption.withContentType(),
            Storage.SignUrlOption.withMd5(),
            Storage.SignUrlOption.withHostName("https://example.com"));
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://example.com/")
            .append(BUCKET_NAME1)
            .append('/')
            .append(BLOB_NAME1)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpMethod.POST)
        .append('\n')
        .append(BLOB_INFO1.getMd5())
        .append('\n')
        .append(BLOB_INFO1.getContentType())
        .append('\n')
        .append(42L + 1209600)
        .append("\n/")
        .append(BUCKET_NAME1)
        .append('/')
        .append(BLOB_NAME1);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testSignUrlForBlobWithSpecialChars()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();

    Map<Character, String> encodingCharsToTest =
        new HashMap<Character, String>(RFC3986_URI_ENCODING_MAP);
    // Signed URL specs say that '/' is not encoded in the resource name (path segment of the URI).
    encodingCharsToTest.put('/', "/");
    for (Map.Entry<Character, String> entry : encodingCharsToTest.entrySet()) {
      String blobName = "/a" + entry.getKey() + "b";
      URL url =
          storage.signUrl(BlobInfo.newBuilder(BUCKET_NAME1, blobName).build(), 14, TimeUnit.DAYS);
      String expectedBlobName = "/a" + entry.getValue() + "b";
      String stringUrl = url.toString();
      String expectedUrl =
          new StringBuilder("https://storage.googleapis.com/")
              .append(BUCKET_NAME1)
              .append("/")
              .append(expectedBlobName)
              .append("?GoogleAccessId=")
              .append(ACCOUNT)
              .append("&Expires=")
              .append(42L + 1209600)
              .append("&Signature=")
              .toString();
      assertTrue(stringUrl.startsWith(expectedUrl));
      String signature = stringUrl.substring(expectedUrl.length());

      StringBuilder signedMessageBuilder = new StringBuilder();
      signedMessageBuilder
          .append(HttpMethod.GET)
          .append("\n\n\n")
          .append(42L + 1209600)
          .append("\n/")
          .append(BUCKET_NAME1)
          .append("/")
          .append(expectedBlobName);

      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initVerify(publicKey);
      signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
      assertTrue(
          signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
    }
  }

  @Test
  public void testSignUrlForBlobWithSpecialCharsAndHostName()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();

    Map<Character, String> encodingCharsToTest =
        new HashMap<Character, String>(RFC3986_URI_ENCODING_MAP);
    // Signed URL specs say that '/' is not encoded in the resource name (path segment of the URI).
    encodingCharsToTest.put('/', "/");
    for (Map.Entry<Character, String> entry : encodingCharsToTest.entrySet()) {
      String blobName = "/a" + entry.getKey() + "b";
      URL url =
          storage.signUrl(
              BlobInfo.newBuilder(BUCKET_NAME1, blobName).build(),
              14,
              TimeUnit.DAYS,
              Storage.SignUrlOption.withHostName("https://example.com"));
      String expectedBlobName = "/a" + entry.getValue() + "b";
      String stringUrl = url.toString();
      String expectedUrl =
          new StringBuilder("https://example.com/")
              .append(BUCKET_NAME1)
              .append("/")
              .append(expectedBlobName)
              .append("?GoogleAccessId=")
              .append(ACCOUNT)
              .append("&Expires=")
              .append(42L + 1209600)
              .append("&Signature=")
              .toString();
      assertTrue(stringUrl.startsWith(expectedUrl));
      String signature = stringUrl.substring(expectedUrl.length());

      StringBuilder signedMessageBuilder = new StringBuilder();
      signedMessageBuilder
          .append(HttpMethod.GET)
          .append("\n\n\n")
          .append(42L + 1209600)
          .append("\n/")
          .append(BUCKET_NAME1)
          .append("/")
          .append(expectedBlobName);

      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initVerify(publicKey);
      signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
      assertTrue(
          signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
    }
  }

  @Test
  public void testSignUrlWithExtHeaders()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();
    Map<String, String> extHeaders = new HashMap<String, String>();
    extHeaders.put("x-goog-acl", "public-read");
    extHeaders.put("x-goog-meta-owner", "myself");
    URL url =
        storage.signUrl(
            BLOB_INFO1,
            14,
            TimeUnit.DAYS,
            Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
            Storage.SignUrlOption.withContentType(),
            Storage.SignUrlOption.withExtHeaders(extHeaders));
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://storage.googleapis.com/")
            .append(BUCKET_NAME1)
            .append('/')
            .append(BLOB_NAME1)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpMethod.PUT)
        .append('\n')
        .append('\n')
        .append(BLOB_INFO1.getContentType())
        .append('\n')
        .append(42L + 1209600)
        .append('\n')
        .append("x-goog-acl:public-read\n")
        .append("x-goog-meta-owner:myself\n")
        .append('/')
        .append(BUCKET_NAME1)
        .append('/')
        .append(BLOB_NAME1);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testSignUrlWithExtHeadersAndHostName()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();
    Map<String, String> extHeaders = new HashMap<String, String>();
    extHeaders.put("x-goog-acl", "public-read");
    extHeaders.put("x-goog-meta-owner", "myself");
    URL url =
        storage.signUrl(
            BLOB_INFO1,
            14,
            TimeUnit.DAYS,
            Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
            Storage.SignUrlOption.withContentType(),
            Storage.SignUrlOption.withExtHeaders(extHeaders),
            Storage.SignUrlOption.withHostName("https://example.com"));
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://example.com/")
            .append(BUCKET_NAME1)
            .append('/')
            .append(BLOB_NAME1)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpMethod.PUT)
        .append('\n')
        .append('\n')
        .append(BLOB_INFO1.getContentType())
        .append('\n')
        .append(42L + 1209600)
        .append('\n')
        .append("x-goog-acl:public-read\n")
        .append("x-goog-meta-owner:myself\n")
        .append('/')
        .append(BUCKET_NAME1)
        .append('/')
        .append(BLOB_NAME1);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testSignUrlForBlobWithSlashes()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();

    String blobName = "/foo/bar/baz #%20other cool stuff.txt";
    URL url =
        storage.signUrl(BlobInfo.newBuilder(BUCKET_NAME1, blobName).build(), 14, TimeUnit.DAYS);
    String escapedBlobName = Rfc3986UriEncode(blobName, false);
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://storage.googleapis.com/")
            .append(BUCKET_NAME1)
            .append("/")
            .append(escapedBlobName)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpMethod.GET)
        .append("\n\n\n")
        .append(42L + 1209600)
        .append("\n/")
        .append(BUCKET_NAME1)
        .append("/")
        .append(escapedBlobName);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testSignUrlForBlobWithSlashesAndHostName()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();

    String blobName = "/foo/bar/baz #%20other cool stuff.txt";
    URL url =
        storage.signUrl(
            BlobInfo.newBuilder(BUCKET_NAME1, blobName).build(),
            14,
            TimeUnit.DAYS,
            Storage.SignUrlOption.withHostName("https://example.com"));
    String escapedBlobName = Rfc3986UriEncode(blobName, false);
    String stringUrl = url.toString();
    String expectedUrl =
        new StringBuilder("https://example.com/")
            .append(BUCKET_NAME1)
            .append("/")
            .append(escapedBlobName)
            .append("?GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedUrl));
    String signature = stringUrl.substring(expectedUrl.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpMethod.GET)
        .append("\n\n\n")
        .append(42L + 1209600)
        .append("\n/")
        .append(BUCKET_NAME1)
        .append("/")
        .append(escapedBlobName);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  @Test
  public void testV2SignUrlWithQueryParams()
      throws NoSuchAlgorithmException, InvalidKeyException, SignatureException,
          UnsupportedEncodingException {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();

    String dispositionNotEncoded = "attachment; filename=\"" + BLOB_NAME1 + "\"";
    String dispositionEncoded = "attachment%3B%20filename%3D%22" + BLOB_NAME1 + "%22";
    URL url =
        storage.signUrl(
            BLOB_INFO1,
            14,
            TimeUnit.DAYS,
            Storage.SignUrlOption.withPathStyle(),
            Storage.SignUrlOption.withV2Signature(),
            Storage.SignUrlOption.withQueryParams(
                ImmutableMap.<String, String>of(
                    "response-content-disposition", dispositionNotEncoded)));

    String stringUrl = url.toString();

    String expectedPrefix =
        new StringBuilder("https://storage.googleapis.com/")
            .append(BUCKET_NAME1)
            .append('/')
            .append(BLOB_NAME1)
            // Query params aren't sorted for V2 signatures; user-supplied params are inserted at
            // the start of the query string, before the required auth params.
            .append("?response-content-disposition=")
            .append(dispositionEncoded)
            .append("&GoogleAccessId=")
            .append(ACCOUNT)
            .append("&Expires=")
            .append(42L + 1209600)
            .append("&Signature=")
            .toString();
    assertTrue(stringUrl.startsWith(expectedPrefix));
    String signature = stringUrl.substring(expectedPrefix.length());

    StringBuilder signedMessageBuilder = new StringBuilder();
    signedMessageBuilder
        .append(HttpMethod.GET)
        .append('\n')
        // No value for Content-MD5, blank
        .append('\n')
        // No value for Content-Type, blank
        .append('\n')
        // Expiration line:
        .append(42L + 1209600)
        .append('\n')
        // Resource line:
        .append('/')
        .append(BUCKET_NAME1)
        .append('/')
        .append(BLOB_NAME1);

    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initVerify(publicKey);
    signer.update(signedMessageBuilder.toString().getBytes(UTF_8));
    assertTrue(
        signer.verify(BaseEncoding.base64().decode(URLDecoder.decode(signature, UTF_8.name()))));
  }

  // TODO(b/144304815): Remove this test once all conformance tests contain query param test cases.
  @Test
  public void testV4SignUrlWithQueryParams() {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();

    String dispositionNotEncoded = "attachment; filename=\"" + BLOB_NAME1 + "\"";
    String dispositionEncoded = "attachment%3B%20filename%3D%22" + BLOB_NAME1 + "%22";
    URL url =
        storage.signUrl(
            BLOB_INFO1,
            6,
            TimeUnit.DAYS,
            Storage.SignUrlOption.withPathStyle(),
            Storage.SignUrlOption.withV4Signature(),
            Storage.SignUrlOption.withQueryParams(
                ImmutableMap.<String, String>of(
                    "response-content-disposition", dispositionNotEncoded)));
    String stringUrl = url.toString();
    String expectedPrefix =
        new StringBuilder("https://storage.googleapis.com/")
            .append(BUCKET_NAME1)
            .append('/')
            .append(BLOB_NAME1)
            .append('?')
            .toString();
    assertTrue(stringUrl.startsWith(expectedPrefix));
    String restOfUrl = stringUrl.substring(expectedPrefix.length());

    Pattern pattern =
        Pattern.compile(
            // We use the same code to construct the canonical request query string as we do to
            // construct the query string used in the final URL, so this query string should also be
            // sorted correctly, except for the trailing x-goog-signature param.
            new StringBuilder("X-Goog-Algorithm=GOOG4-RSA-SHA256")
                .append("&X-Goog-Credential=[^&]+")
                .append("&X-Goog-Date=[^&]+")
                .append("&X-Goog-Expires=[^&]+")
                .append("&X-Goog-SignedHeaders=[^&]+")
                .append("&response-content-disposition=[^&]+")
                // Signature is always tacked onto the end of the final URL; it's not sorted w/ the
                // other params above, since the signature is not known when you're constructing the
                // query string line of the canonical request string.
                .append("&X-Goog-Signature=.*")
                .toString());
    Matcher matcher = pattern.matcher(restOfUrl);
    assertTrue(restOfUrl, matcher.matches());

    // Make sure query param was encoded properly.
    assertNotEquals(-1, restOfUrl.indexOf("&response-content-disposition=" + dispositionEncoded));
  }

  @Test
  public void testGetAllArray() {
    BlobId blobId1 = BlobId.of(BUCKET_NAME1, BLOB_NAME1);
    BlobId blobId2 = BlobId.of(BUCKET_NAME1, BLOB_NAME2);
    RpcBatch batchMock = EasyMock.createMock(RpcBatch.class);
    Capture<RpcBatch.Callback<StorageObject>> callback1 = Capture.newInstance();
    Capture<RpcBatch.Callback<StorageObject>> callback2 = Capture.newInstance();
    batchMock.addGet(
        EasyMock.eq(Conversions.apiary().blobId().encode(blobId1)),
        EasyMock.capture(callback1),
        EasyMock.eq(ImmutableMap.<StorageRpc.Option, Object>of()));
    batchMock.addGet(
        EasyMock.eq(Conversions.apiary().blobId().encode(blobId2)),
        EasyMock.capture(callback2),
        EasyMock.eq(ImmutableMap.<StorageRpc.Option, Object>of()));
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    batchMock.submit();
    EasyMock.replay(storageRpcMock, batchMock);
    initializeService();
    List<Blob> resultBlobs = storage.get(blobId1, blobId2);
    callback1.getValue().onSuccess(Conversions.apiary().blobInfo().encode(BLOB_INFO1));
    callback2.getValue().onFailure(new GoogleJsonError());
    assertEquals(2, resultBlobs.size());
    assertEquals(new Blob(storage, new BlobInfo.BuilderImpl(BLOB_INFO1)), resultBlobs.get(0));
    assertNull(resultBlobs.get(1));
    EasyMock.verify(batchMock);
  }

  @Test
  public void testGetAllArrayIterable() {
    BlobId blobId1 = BlobId.of(BUCKET_NAME1, BLOB_NAME1);
    BlobId blobId2 = BlobId.of(BUCKET_NAME1, BLOB_NAME2);
    RpcBatch batchMock = EasyMock.createMock(RpcBatch.class);
    Capture<RpcBatch.Callback<StorageObject>> callback1 = Capture.newInstance();
    Capture<RpcBatch.Callback<StorageObject>> callback2 = Capture.newInstance();
    batchMock.addGet(
        EasyMock.eq(Conversions.apiary().blobId().encode(blobId1)),
        EasyMock.capture(callback1),
        EasyMock.eq(ImmutableMap.<StorageRpc.Option, Object>of()));
    batchMock.addGet(
        EasyMock.eq(Conversions.apiary().blobId().encode(blobId2)),
        EasyMock.capture(callback2),
        EasyMock.eq(ImmutableMap.<StorageRpc.Option, Object>of()));
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    batchMock.submit();
    EasyMock.replay(storageRpcMock, batchMock);
    initializeService();
    List<Blob> resultBlobs = storage.get(ImmutableList.of(blobId1, blobId2));
    callback1.getValue().onSuccess(Conversions.apiary().blobInfo().encode(BLOB_INFO1));
    callback2.getValue().onFailure(new GoogleJsonError());
    assertEquals(2, resultBlobs.size());
    assertEquals(new Blob(storage, new BlobInfo.BuilderImpl(BLOB_INFO1)), resultBlobs.get(0));
    assertNull(resultBlobs.get(1));
    EasyMock.verify(batchMock);
  }

  @Test
  public void testDeleteAllArray() {
    BlobId blobId1 = BlobId.of(BUCKET_NAME1, BLOB_NAME1);
    BlobId blobId2 = BlobId.of(BUCKET_NAME1, BLOB_NAME2);
    RpcBatch batchMock = EasyMock.createMock(RpcBatch.class);
    Capture<RpcBatch.Callback<Void>> callback1 = Capture.newInstance();
    Capture<RpcBatch.Callback<Void>> callback2 = Capture.newInstance();
    batchMock.addDelete(
        EasyMock.eq(Conversions.apiary().blobId().encode(blobId1)),
        EasyMock.capture(callback1),
        EasyMock.eq(ImmutableMap.<StorageRpc.Option, Object>of()));
    batchMock.addDelete(
        EasyMock.eq(Conversions.apiary().blobId().encode(blobId2)),
        EasyMock.capture(callback2),
        EasyMock.eq(ImmutableMap.<StorageRpc.Option, Object>of()));
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    batchMock.submit();
    EasyMock.replay(storageRpcMock, batchMock);
    initializeService();
    List<Boolean> result = storage.delete(blobId1, blobId2);
    callback1.getValue().onSuccess(null);
    callback2.getValue().onFailure(new GoogleJsonError());
    assertEquals(2, result.size());
    assertTrue(result.get(0));
    assertFalse(result.get(1));
    EasyMock.verify(batchMock);
  }

  @Test
  public void testDeleteAllIterable() {
    BlobId blobId1 = BlobId.of(BUCKET_NAME1, BLOB_NAME1);
    BlobId blobId2 = BlobId.of(BUCKET_NAME1, BLOB_NAME2);
    RpcBatch batchMock = EasyMock.createMock(RpcBatch.class);
    Capture<RpcBatch.Callback<Void>> callback1 = Capture.newInstance();
    Capture<RpcBatch.Callback<Void>> callback2 = Capture.newInstance();
    batchMock.addDelete(
        EasyMock.eq(Conversions.apiary().blobId().encode(blobId1)),
        EasyMock.capture(callback1),
        EasyMock.eq(ImmutableMap.<StorageRpc.Option, Object>of()));
    batchMock.addDelete(
        EasyMock.eq(Conversions.apiary().blobId().encode(blobId2)),
        EasyMock.capture(callback2),
        EasyMock.eq(ImmutableMap.<StorageRpc.Option, Object>of()));
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    batchMock.submit();
    EasyMock.replay(storageRpcMock, batchMock);
    initializeService();
    List<Boolean> result = storage.delete(blobId1, blobId2);
    callback1.getValue().onSuccess(null);
    callback2.getValue().onFailure(new GoogleJsonError());
    assertEquals(2, result.size());
    assertTrue(result.get(0));
    assertFalse(result.get(1));
    EasyMock.verify(batchMock);
  }

  @Test
  public void testUpdateAllArray() {
    RpcBatch batchMock = EasyMock.createMock(RpcBatch.class);
    Capture<RpcBatch.Callback<StorageObject>> callback1 = Capture.newInstance();
    Capture<RpcBatch.Callback<StorageObject>> callback2 = Capture.newInstance();
    batchMock.addPatch(
        EasyMock.eq(Conversions.apiary().blobInfo().encode(BLOB_INFO1)),
        EasyMock.capture(callback1),
        EasyMock.eq(ImmutableMap.<StorageRpc.Option, Object>of()));
    batchMock.addPatch(
        EasyMock.eq(Conversions.apiary().blobInfo().encode(BLOB_INFO2)),
        EasyMock.capture(callback2),
        EasyMock.eq(ImmutableMap.<StorageRpc.Option, Object>of()));
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    batchMock.submit();
    EasyMock.replay(storageRpcMock, batchMock);
    initializeService();
    List<Blob> resultBlobs = storage.update(BLOB_INFO1, BLOB_INFO2);
    callback1.getValue().onSuccess(Conversions.apiary().blobInfo().encode(BLOB_INFO1));
    callback2.getValue().onFailure(new GoogleJsonError());
    assertEquals(2, resultBlobs.size());
    assertEquals(new Blob(storage, new BlobInfo.BuilderImpl(BLOB_INFO1)), resultBlobs.get(0));
    assertNull(resultBlobs.get(1));
    EasyMock.verify(batchMock);
  }

  @Test
  public void testUpdateAllIterable() {
    RpcBatch batchMock = EasyMock.createMock(RpcBatch.class);
    Capture<RpcBatch.Callback<StorageObject>> callback1 = Capture.newInstance();
    Capture<RpcBatch.Callback<StorageObject>> callback2 = Capture.newInstance();
    batchMock.addPatch(
        EasyMock.eq(Conversions.apiary().blobInfo().encode(BLOB_INFO1)),
        EasyMock.capture(callback1),
        EasyMock.eq(ImmutableMap.<StorageRpc.Option, Object>of()));
    batchMock.addPatch(
        EasyMock.eq(Conversions.apiary().blobInfo().encode(BLOB_INFO2)),
        EasyMock.capture(callback2),
        EasyMock.eq(ImmutableMap.<StorageRpc.Option, Object>of()));
    EasyMock.expect(storageRpcMock.createBatch()).andReturn(batchMock);
    batchMock.submit();
    EasyMock.replay(storageRpcMock, batchMock);
    initializeService();
    List<Blob> resultBlobs = storage.update(ImmutableList.of(BLOB_INFO1, BLOB_INFO2));
    callback1.getValue().onSuccess(Conversions.apiary().blobInfo().encode(BLOB_INFO1));
    callback2.getValue().onFailure(new GoogleJsonError());
    assertEquals(2, resultBlobs.size());
    assertEquals(new Blob(storage, new BlobInfo.BuilderImpl(BLOB_INFO1)), resultBlobs.get(0));
    assertNull(resultBlobs.get(1));
    EasyMock.verify(batchMock);
  }

  @Test
  public void testGetBucketAcl() {
    EasyMock.expect(
            storageRpcMock.getAcl(
                BUCKET_NAME1, "allAuthenticatedUsers", new HashMap<StorageRpc.Option, Object>()))
        .andReturn(Conversions.apiary().bucketAcl().encode(ACL));
    EasyMock.replay(storageRpcMock);
    initializeService();
    Acl acl = storage.getAcl(BUCKET_NAME1, User.ofAllAuthenticatedUsers());
    assertEquals(ACL, acl);
  }

  @Test
  public void testGetBucketAclNull() {
    EasyMock.expect(
            storageRpcMock.getAcl(
                BUCKET_NAME1, "allAuthenticatedUsers", new HashMap<StorageRpc.Option, Object>()))
        .andReturn(null);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertNull(storage.getAcl(BUCKET_NAME1, User.ofAllAuthenticatedUsers()));
  }

  @Test
  public void testDeleteBucketAcl() {
    EasyMock.expect(
            storageRpcMock.deleteAcl(
                BUCKET_NAME1, "allAuthenticatedUsers", new HashMap<StorageRpc.Option, Object>()))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(storage.deleteAcl(BUCKET_NAME1, User.ofAllAuthenticatedUsers()));
  }

  @Test
  public void testCreateBucketAcl() {
    Acl returnedAcl = ACL.toBuilder().setEtag("ETAG").setId("ID").build();
    EasyMock.expect(
            storageRpcMock.createAcl(
                Conversions.apiary().bucketAcl().encode(ACL).setBucket(BUCKET_NAME1),
                new HashMap<StorageRpc.Option, Object>()))
        .andReturn(Conversions.apiary().bucketAcl().encode(returnedAcl));
    EasyMock.replay(storageRpcMock);
    initializeService();
    Acl acl = storage.createAcl(BUCKET_NAME1, ACL);
    assertEquals(returnedAcl, acl);
  }

  @Test
  public void testUpdateBucketAcl() {
    Acl returnedAcl = ACL.toBuilder().setEtag("ETAG").setId("ID").build();
    EasyMock.expect(
            storageRpcMock.patchAcl(
                Conversions.apiary().bucketAcl().encode(ACL).setBucket(BUCKET_NAME1),
                new HashMap<StorageRpc.Option, Object>()))
        .andReturn(Conversions.apiary().bucketAcl().encode(returnedAcl));
    EasyMock.replay(storageRpcMock);
    initializeService();
    Acl acl = storage.updateAcl(BUCKET_NAME1, ACL);
    assertEquals(returnedAcl, acl);
  }

  @Test
  public void testListBucketAcl() {
    EasyMock.expect(storageRpcMock.listAcls(BUCKET_NAME1, new HashMap<StorageRpc.Option, Object>()))
        .andReturn(
            ImmutableList.of(
                Conversions.apiary().bucketAcl().encode(ACL),
                Conversions.apiary().bucketAcl().encode(OTHER_ACL)));
    EasyMock.replay(storageRpcMock);
    initializeService();
    List<Acl> acls = storage.listAcls(BUCKET_NAME1);
    assertEquals(ImmutableList.of(ACL, OTHER_ACL), acls);
  }

  @Test
  public void testGetDefaultBucketAcl() {
    EasyMock.expect(storageRpcMock.getDefaultAcl(BUCKET_NAME1, "allAuthenticatedUsers"))
        .andReturn(Conversions.apiary().objectAcl().encode(ACL));
    EasyMock.replay(storageRpcMock);
    initializeService();
    Acl acl = storage.getDefaultAcl(BUCKET_NAME1, User.ofAllAuthenticatedUsers());
    assertEquals(ACL, acl);
  }

  @Test
  public void testGetDefaultBucketAclNull() {
    EasyMock.expect(storageRpcMock.getDefaultAcl(BUCKET_NAME1, "allAuthenticatedUsers"))
        .andReturn(null);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertNull(storage.getDefaultAcl(BUCKET_NAME1, User.ofAllAuthenticatedUsers()));
  }

  @Test
  public void testDeleteDefaultBucketAcl() {
    EasyMock.expect(storageRpcMock.deleteDefaultAcl(BUCKET_NAME1, "allAuthenticatedUsers"))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(storage.deleteDefaultAcl(BUCKET_NAME1, User.ofAllAuthenticatedUsers()));
  }

  @Test
  public void testCreateDefaultBucketAcl() {
    Acl returnedAcl = ACL.toBuilder().setEtag("ETAG").setId("ID").build();
    EasyMock.expect(
            storageRpcMock.createDefaultAcl(
                Conversions.apiary().objectAcl().encode(ACL).setBucket(BUCKET_NAME1)))
        .andReturn(Conversions.apiary().objectAcl().encode(returnedAcl));
    EasyMock.replay(storageRpcMock);
    initializeService();
    Acl acl = storage.createDefaultAcl(BUCKET_NAME1, ACL);
    assertEquals(returnedAcl, acl);
  }

  @Test
  public void testUpdateDefaultBucketAcl() {
    Acl returnedAcl = ACL.toBuilder().setEtag("ETAG").setId("ID").build();
    EasyMock.expect(
            storageRpcMock.patchDefaultAcl(
                Conversions.apiary().objectAcl().encode(ACL).setBucket(BUCKET_NAME1)))
        .andReturn(Conversions.apiary().objectAcl().encode(returnedAcl));
    EasyMock.replay(storageRpcMock);
    initializeService();
    Acl acl = storage.updateDefaultAcl(BUCKET_NAME1, ACL);
    assertEquals(returnedAcl, acl);
  }

  @Test
  public void testListDefaultBucketAcl() {
    EasyMock.expect(storageRpcMock.listDefaultAcls(BUCKET_NAME1))
        .andReturn(
            ImmutableList.of(
                Conversions.apiary().objectAcl().encode(ACL),
                Conversions.apiary().objectAcl().encode(OTHER_ACL)));
    EasyMock.replay(storageRpcMock);
    initializeService();
    List<Acl> acls = storage.listDefaultAcls(BUCKET_NAME1);
    assertEquals(ImmutableList.of(ACL, OTHER_ACL), acls);
  }

  @Test
  public void testGetBlobAcl() {
    BlobId blobId = BlobId.of(BUCKET_NAME1, BLOB_NAME1, 42L);
    EasyMock.expect(storageRpcMock.getAcl(BUCKET_NAME1, BLOB_NAME1, 42L, "allAuthenticatedUsers"))
        .andReturn(Conversions.apiary().objectAcl().encode(ACL));
    EasyMock.replay(storageRpcMock);
    initializeService();
    Acl acl = storage.getAcl(blobId, User.ofAllAuthenticatedUsers());
    assertEquals(ACL, acl);
  }

  @Test
  public void testGetBlobAclNull() {
    BlobId blobId = BlobId.of(BUCKET_NAME1, BLOB_NAME1, 42L);
    EasyMock.expect(storageRpcMock.getAcl(BUCKET_NAME1, BLOB_NAME1, 42L, "allAuthenticatedUsers"))
        .andReturn(null);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertNull(storage.getAcl(blobId, User.ofAllAuthenticatedUsers()));
  }

  @Test
  public void testDeleteBlobAcl() {
    BlobId blobId = BlobId.of(BUCKET_NAME1, BLOB_NAME1, 42L);
    EasyMock.expect(
            storageRpcMock.deleteAcl(BUCKET_NAME1, BLOB_NAME1, 42L, "allAuthenticatedUsers"))
        .andReturn(true);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertTrue(storage.deleteAcl(blobId, User.ofAllAuthenticatedUsers()));
  }

  @Test
  public void testCreateBlobAcl() {
    BlobId blobId = BlobId.of(BUCKET_NAME1, BLOB_NAME1, 42L);
    Acl returnedAcl = ACL.toBuilder().setEtag("ETAG").setId("ID").build();
    EasyMock.expect(
            storageRpcMock.createAcl(
                Conversions.apiary()
                    .objectAcl()
                    .encode(ACL)
                    .setBucket(BUCKET_NAME1)
                    .setObject(BLOB_NAME1)
                    .setGeneration(42L)))
        .andReturn(Conversions.apiary().objectAcl().encode(returnedAcl));
    EasyMock.replay(storageRpcMock);
    initializeService();
    Acl acl = storage.createAcl(blobId, ACL);
    assertEquals(returnedAcl, acl);
  }

  @Test
  public void testUpdateBlobAcl() {
    BlobId blobId = BlobId.of(BUCKET_NAME1, BLOB_NAME1, 42L);
    Acl returnedAcl = ACL.toBuilder().setEtag("ETAG").setId("ID").build();
    EasyMock.expect(
            storageRpcMock.patchAcl(
                Conversions.apiary()
                    .objectAcl()
                    .encode(ACL)
                    .setBucket(BUCKET_NAME1)
                    .setObject(BLOB_NAME1)
                    .setGeneration(42L)))
        .andReturn(Conversions.apiary().objectAcl().encode(returnedAcl));
    EasyMock.replay(storageRpcMock);
    initializeService();
    Acl acl = storage.updateAcl(blobId, ACL);
    assertEquals(returnedAcl, acl);
  }

  @Test
  public void testListBlobAcl() {
    BlobId blobId = BlobId.of(BUCKET_NAME1, BLOB_NAME1, 42L);
    EasyMock.expect(storageRpcMock.listAcls(BUCKET_NAME1, BLOB_NAME1, 42L))
        .andReturn(
            ImmutableList.of(
                Conversions.apiary().objectAcl().encode(ACL),
                Conversions.apiary().objectAcl().encode(OTHER_ACL)));
    EasyMock.replay(storageRpcMock);
    initializeService();
    List<Acl> acls = storage.listAcls(blobId);
    assertEquals(ImmutableList.of(ACL, OTHER_ACL), acls);
  }

  @Test
  public void testGetIamPolicy() {
    EasyMock.expect(storageRpcMock.getIamPolicy(BUCKET_NAME1, EMPTY_RPC_OPTIONS))
        .andReturn(API_POLICY1);
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertEquals(LIB_POLICY1, storage.getIamPolicy(BUCKET_NAME1));
  }

  @Test
  public void testSetIamPolicy() {
    com.google.api.services.storage.model.Policy preCommitApiPolicy =
        new com.google.api.services.storage.model.Policy()
            .setBindings(
                ImmutableList.of(
                    new Bindings()
                        .setMembers(ImmutableList.of("allUsers"))
                        .setRole("roles/storage.objectViewer"),
                    new Bindings()
                        .setMembers(
                            ImmutableList.of("user:test1@gmail.com", "user:test2@gmail.com"))
                        .setRole("roles/storage.objectAdmin"),
                    new Bindings()
                        .setMembers(ImmutableList.of("group:test-group@gmail.com"))
                        .setRole("roles/storage.admin")))
            .setEtag(POLICY_ETAG1)
            .setVersion(1);
    // postCommitApiPolicy is identical but for the etag, which has been updated.
    com.google.api.services.storage.model.Policy postCommitApiPolicy =
        new com.google.api.services.storage.model.Policy()
            .setBindings(
                ImmutableList.of(
                    new Bindings()
                        .setMembers(ImmutableList.of("allUsers"))
                        .setRole("roles/storage.objectViewer"),
                    new Bindings()
                        .setMembers(
                            ImmutableList.of("user:test1@gmail.com", "user:test2@gmail.com"))
                        .setRole("roles/storage.objectAdmin"),
                    new Bindings()
                        .setMembers(ImmutableList.of("group:test-group@gmail.com"))
                        .setRole("roles/storage.admin")))
            .setEtag(POLICY_ETAG2)
            .setVersion(1);
    Policy postCommitLibPolicy =
        Policy.newBuilder()
            .addIdentity(StorageRoles.objectViewer(), Identity.allUsers())
            .addIdentity(
                StorageRoles.objectAdmin(),
                Identity.user("test1@gmail.com"),
                Identity.user("test2@gmail.com"))
            .addIdentity(StorageRoles.admin(), Identity.group("test-group@gmail.com"))
            .setEtag(POLICY_ETAG2)
            .setVersion(1)
            .build();

    EasyMock.expect(storageRpcMock.getIamPolicy(BUCKET_NAME1, EMPTY_RPC_OPTIONS))
        .andReturn(API_POLICY1);
    EasyMock.expect(
            storageRpcMock.setIamPolicy(
                EasyMock.eq(BUCKET_NAME1),
                eqApiPolicy(preCommitApiPolicy),
                EasyMock.eq(EMPTY_RPC_OPTIONS)))
        .andReturn(postCommitApiPolicy);
    EasyMock.replay(storageRpcMock);
    initializeService();

    Policy currentPolicy = storage.getIamPolicy(BUCKET_NAME1);
    Policy updatedPolicy =
        storage.setIamPolicy(
            BUCKET_NAME1,
            currentPolicy
                .toBuilder()
                .addIdentity(StorageRoles.admin(), Identity.group("test-group@gmail.com"))
                .build());
    assertEquals(updatedPolicy, postCommitLibPolicy);
  }

  @Test
  public void testTestIamPermissionsNull() {
    ImmutableList<Boolean> expectedPermissions = ImmutableList.of(false, false, false);
    ImmutableList<String> checkedPermissions =
        ImmutableList.of(
            "storage.buckets.get", "storage.buckets.getIamPolicy", "storage.objects.list");

    EasyMock.expect(
            storageRpcMock.testIamPermissions(BUCKET_NAME1, checkedPermissions, EMPTY_RPC_OPTIONS))
        .andReturn(new TestIamPermissionsResponse());
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertEquals(expectedPermissions, storage.testIamPermissions(BUCKET_NAME1, checkedPermissions));
  }

  @Test
  public void testTestIamPermissionsNonNull() {
    ImmutableList<Boolean> expectedPermissions = ImmutableList.of(true, false, true);
    ImmutableList<String> checkedPermissions =
        ImmutableList.of(
            "storage.buckets.get", "storage.buckets.getIamPolicy", "storage.objects.list");

    EasyMock.expect(
            storageRpcMock.testIamPermissions(BUCKET_NAME1, checkedPermissions, EMPTY_RPC_OPTIONS))
        .andReturn(
            new TestIamPermissionsResponse()
                .setPermissions(ImmutableList.of("storage.objects.list", "storage.buckets.get")));
    EasyMock.replay(storageRpcMock);
    initializeService();
    assertEquals(expectedPermissions, storage.testIamPermissions(BUCKET_NAME1, checkedPermissions));
  }

  @Test
  public void testLockRetentionPolicy() {
    EasyMock.expect(
            storageRpcMock.lockRetentionPolicy(
                Conversions.apiary().bucketInfo().encode(BUCKET_INFO3),
                BUCKET_TARGET_OPTIONS_LOCK_RETENTION_POLICY))
        .andReturn(Conversions.apiary().bucketInfo().encode(BUCKET_INFO3));
    EasyMock.replay(storageRpcMock);
    initializeService();
    Bucket bucket =
        storage.lockRetentionPolicy(
            BUCKET_INFO3, BUCKET_TARGET_METAGENERATION, BUCKET_TARGET_USER_PROJECT);
    assertEquals(expectedBucket3, bucket);
  }

  @Test
  public void testGetServiceAccount() {
    EasyMock.expect(storageRpcMock.getServiceAccount("projectId"))
        .andReturn(Conversions.apiary().serviceAccount().encode(SERVICE_ACCOUNT));
    EasyMock.replay(storageRpcMock);
    initializeService();
    ServiceAccount serviceAccount = storage.getServiceAccount("projectId");
    assertEquals(SERVICE_ACCOUNT, serviceAccount);
  }

  @Test
  public void testRetryableException() {
    BlobId blob = BlobId.of(BUCKET_NAME1, BLOB_NAME1);
    EasyMock.expect(
            storageRpcMock.get(Conversions.apiary().blobId().encode(blob), EMPTY_RPC_OPTIONS))
        .andThrow(new StorageException(500, "internalError"))
        .andReturn(Conversions.apiary().blobInfo().encode(BLOB_INFO1));
    EasyMock.replay(storageRpcMock);
    storage =
        options
            .toBuilder()
            .setRetrySettings(ServiceOptions.getDefaultRetrySettings())
            .build()
            .getService();
    initializeServiceDependentObjects();
    Blob readBlob = storage.get(blob);
    assertEquals(expectedBlob1, readBlob);
  }

  @Test
  public void testNonRetryableException() {
    BlobId blob = BlobId.of(BUCKET_NAME1, BLOB_NAME1);
    String exceptionMessage = "Not Implemented";
    EasyMock.expect(
            storageRpcMock.get(Conversions.apiary().blobId().encode(blob), EMPTY_RPC_OPTIONS))
        .andThrow(new StorageException(501, exceptionMessage));
    EasyMock.replay(storageRpcMock);
    storage =
        options
            .toBuilder()
            .setRetrySettings(ServiceOptions.getDefaultRetrySettings())
            .build()
            .getService();
    initializeServiceDependentObjects();
    try {
      storage.get(blob);
      Assert.fail();
    } catch (StorageException ex) {
      Assert.assertNotNull(ex.getMessage());
    }
  }

  @Test
  public void testRuntimeException() {
    BlobId blob = BlobId.of(BUCKET_NAME1, BLOB_NAME1);
    String exceptionMessage = "Artificial runtime exception";
    EasyMock.expect(
            storageRpcMock.get(Conversions.apiary().blobId().encode(blob), EMPTY_RPC_OPTIONS))
        .andThrow(new RuntimeException(exceptionMessage));
    EasyMock.replay(storageRpcMock);
    storage =
        options
            .toBuilder()
            .setRetrySettings(ServiceOptions.getDefaultRetrySettings())
            .build()
            .getService();
    try {
      storage.get(blob);
      Assert.fail();
    } catch (StorageException ex) {
      Assert.assertNotNull(ex.getMessage());
    }
  }

  @Test
  public void testV4PostPolicy() {
    EasyMock.replay(storageRpcMock);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail(ACCOUNT)
            .setPrivateKey(privateKey)
            .build();
    storage = options.toBuilder().setCredentials(credentials).build().getService();

    PostPolicyV4.PostFieldsV4 fields =
        PostPolicyV4.PostFieldsV4.newBuilder().setAcl("public-read").build();
    PostPolicyV4.PostConditionsV4 conditions =
        PostPolicyV4.PostConditionsV4.newBuilder()
            .addContentTypeCondition(PostPolicyV4.ConditionV4Type.MATCHES, "image/jpeg")
            .build();

    // test fields and conditions
    PostPolicyV4 policy =
        storage.generateSignedPostPolicyV4(
            BlobInfo.newBuilder("my-bucket", "my-object").build(),
            7,
            TimeUnit.DAYS,
            fields,
            conditions);

    Map<String, String> outputFields = policy.getFields();

    assertTrue(outputFields.containsKey("x-goog-date"));
    assertTrue(outputFields.containsKey("x-goog-credential"));
    assertTrue(outputFields.containsKey("x-goog-signature"));
    assertEquals(outputFields.get("x-goog-algorithm"), "GOOG4-RSA-SHA256");
    assertEquals(outputFields.get("content-type"), "image/jpeg");
    assertEquals(outputFields.get("acl"), "public-read");
    assertEquals(outputFields.get("key"), "my-object");
    assertEquals("https://storage.googleapis.com/my-bucket/", policy.getUrl());

    // test fields, no conditions
    policy =
        storage.generateSignedPostPolicyV4(
            BlobInfo.newBuilder("my-bucket", "my-object").build(), 7, TimeUnit.DAYS, conditions);
    outputFields = policy.getFields();

    assertTrue(outputFields.containsKey("x-goog-date"));
    assertTrue(outputFields.containsKey("x-goog-credential"));
    assertTrue(outputFields.containsKey("x-goog-signature"));
    assertEquals(outputFields.get("x-goog-algorithm"), "GOOG4-RSA-SHA256");
    assertEquals(outputFields.get("content-type"), "image/jpeg");
    assertEquals(outputFields.get("key"), "my-object");
    assertEquals("https://storage.googleapis.com/my-bucket/", policy.getUrl());

    // test conditions, no fields
    policy =
        storage.generateSignedPostPolicyV4(
            BlobInfo.newBuilder("my-bucket", "my-object").build(), 7, TimeUnit.DAYS, fields);
    outputFields = policy.getFields();
    assertTrue(outputFields.containsKey("x-goog-date"));
    assertTrue(outputFields.containsKey("x-goog-credential"));
    assertTrue(outputFields.containsKey("x-goog-signature"));
    assertEquals(outputFields.get("x-goog-algorithm"), "GOOG4-RSA-SHA256");
    assertEquals(outputFields.get("acl"), "public-read");
    assertEquals(outputFields.get("key"), "my-object");

    // test no conditions no fields
    policy =
        storage.generateSignedPostPolicyV4(
            BlobInfo.newBuilder("my-bucket", "my-object").build(), 7, TimeUnit.DAYS);
    outputFields = policy.getFields();
    assertTrue(outputFields.containsKey("x-goog-date"));
    assertTrue(outputFields.containsKey("x-goog-credential"));
    assertTrue(outputFields.containsKey("x-goog-signature"));
    assertEquals(outputFields.get("x-goog-algorithm"), "GOOG4-RSA-SHA256");
    assertEquals(outputFields.get("key"), "my-object");
    assertEquals("https://storage.googleapis.com/my-bucket/", policy.getUrl());
  }

  @Test
  public void testBucketLifecycleRules() {
    BucketInfo bucketInfo =
        BucketInfo.newBuilder("b")
            .setLocation("us")
            .setLifecycleRules(
                ImmutableList.of(
                    new BucketInfo.LifecycleRule(
                        BucketInfo.LifecycleRule.LifecycleAction.newSetStorageClassAction(
                            StorageClass.COLDLINE),
                        BucketInfo.LifecycleRule.LifecycleCondition.newBuilder()
                            .setAge(1)
                            .setNumberOfNewerVersions(3)
                            .setIsLive(false)
                            .setCreatedBefore(new DateTime(System.currentTimeMillis()))
                            .setMatchesStorageClass(ImmutableList.of(StorageClass.COLDLINE))
                            .setDaysSinceNoncurrentTime(30)
                            .setNoncurrentTimeBefore(new DateTime(System.currentTimeMillis()))
                            .setCustomTimeBefore(new DateTime(System.currentTimeMillis()))
                            .setDaysSinceCustomTime(30)
                            .build())))
            .build();
    EasyMock.expect(
            storageRpcMock.create(
                Conversions.apiary().bucketInfo().encode(bucketInfo),
                new HashMap<StorageRpc.Option, Object>()))
        .andReturn(Conversions.apiary().bucketInfo().encode(bucketInfo));
    EasyMock.replay(storageRpcMock);
    initializeService();
    Bucket bucket = storage.create(bucketInfo);
    BucketInfo.LifecycleRule lifecycleRule = bucket.getLifecycleRules().get(0);
    assertEquals(3, lifecycleRule.getCondition().getNumberOfNewerVersions().intValue());
    assertNotNull(lifecycleRule.getCondition().getCreatedBefore());
    assertFalse(lifecycleRule.getCondition().getIsLive());
    assertEquals(1, lifecycleRule.getCondition().getAge().intValue());
    assertEquals(1, lifecycleRule.getCondition().getMatchesStorageClass().size());
    assertEquals(30, lifecycleRule.getCondition().getDaysSinceNoncurrentTime().intValue());
    assertNotNull(lifecycleRule.getCondition().getNoncurrentTimeBefore());
    assertEquals(30, lifecycleRule.getCondition().getDaysSinceCustomTime().intValue());
    assertNotNull(lifecycleRule.getCondition().getCustomTimeBefore());
  }

  @Test
  public void testDownloadTo() throws Exception {
    BlobId blob = BlobId.of(BUCKET_NAME1, BLOB_NAME1);
    storage = options.toBuilder().build().getService();
    final byte[] expected = {1, 2};
    EasyMock.expect(
            storageRpcMock.read(
                anyObject(StorageObject.class),
                anyObject(Map.class),
                eq(0l),
                anyObject(OutputStream.class)))
        .andAnswer(
            new IAnswer<Long>() {
              @Override
              public Long answer() throws Throwable {
                ((OutputStream) getCurrentArguments()[3]).write(expected);
                return 2l;
              }
            });
    EasyMock.replay(storageRpcMock);
    File file = File.createTempFile("blob", ".tmp");
    storage.downloadTo(blob, file.toPath());
    byte actual[] = Files.readAllBytes(file.toPath());
    assertArrayEquals(expected, actual);
  }

  @Test
  public void testDownloadToWithRetries() throws Exception {
    BlobId blob = BlobId.of(BUCKET_NAME1, BLOB_NAME1);
    storage =
        options
            .toBuilder()
            .setRetrySettings(RetrySettings.newBuilder().setMaxAttempts(2).build())
            .build()
            .getService();
    final byte[] expected = {1, 2};
    expect(
            storageRpcMock.read(
                anyObject(StorageObject.class),
                anyObject(Map.class),
                eq(0l),
                anyObject(OutputStream.class)))
        .andAnswer(
            new IAnswer<Long>() {
              @Override
              public Long answer() throws Throwable {
                ((OutputStream) getCurrentArguments()[3]).write(expected[0]);
                throw new StorageException(504, "error");
              }
            });
    expect(
            storageRpcMock.read(
                anyObject(StorageObject.class),
                anyObject(Map.class),
                eq(1l),
                anyObject(OutputStream.class)))
        .andAnswer(
            new IAnswer<Long>() {
              @Override
              public Long answer() throws Throwable {
                ((OutputStream) getCurrentArguments()[3]).write(expected[1]);
                return 1l;
              }
            });
    replay(storageRpcMock);
    File file = File.createTempFile("blob", ".tmp");
    storage.downloadTo(blob, file.toPath());
    byte actual[] = Files.readAllBytes(file.toPath());
    assertArrayEquals(expected, actual);
  }

  @Test
  public void testDownloadToWithException() throws Exception {
    BlobId blob = BlobId.of(BUCKET_NAME1, BLOB_NAME1);
    storage = options.toBuilder().build().getService();
    Exception exception = new IllegalStateException("test");
    expect(
            storageRpcMock.read(
                anyObject(StorageObject.class),
                anyObject(Map.class),
                eq(0l),
                anyObject(OutputStream.class)))
        .andThrow(exception);
    replay(storageRpcMock);
    File file = File.createTempFile("blob", ".tmp");
    try {
      storage.downloadTo(blob, file.toPath());
      fail();
    } catch (StorageException e) {
      assertSame(exception, e.getCause());
    }
  }
}
