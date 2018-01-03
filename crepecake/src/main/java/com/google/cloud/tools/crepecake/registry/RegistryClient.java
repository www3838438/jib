/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.crepecake.registry;

import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpStatusCodes;
import com.google.cloud.tools.crepecake.blob.Blob;
import com.google.cloud.tools.crepecake.http.Authorization;
import com.google.cloud.tools.crepecake.http.Connection;
import com.google.cloud.tools.crepecake.http.Request;
import com.google.cloud.tools.crepecake.http.Response;
import com.google.cloud.tools.crepecake.image.DescriptorDigest;
import com.google.cloud.tools.crepecake.image.json.ManifestTemplate;
import com.google.cloud.tools.crepecake.image.json.V22ManifestTemplate;
import com.google.cloud.tools.crepecake.json.JsonTemplateMapper;
import com.google.cloud.tools.crepecake.registry.json.ErrorEntryTemplate;
import com.google.cloud.tools.crepecake.registry.json.ErrorResponseTemplate;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import javax.annotation.Nullable;

/** Interfaces with a registry. */
public class RegistryClient {

  // TODO: This should be configurable.
  private static final String PROTOCOL = "http";

  @Nullable private final Authorization authorization;
  private final String serverUrl;
  private final String imageName;

  public RegistryClient(@Nullable Authorization authorization, String serverUrl, String imageName) {
    this.authorization = authorization;
    this.serverUrl = serverUrl;
    this.imageName = imageName;
  }

  /** Pulls the image manifest for a specific tag. */
  public ManifestTemplate pullManifest(String imageTag) throws IOException, RegistryException {
    ManifestPuller manifestPuller = new ManifestPuller(imageTag);
    return callRegistryEndpoint(null, manifestPuller);
  }

  /** Pushes the image manifest for a specific tag. */
  public void pushManifest(V22ManifestTemplate manifestTemplate, String imageTag)
      throws IOException, RegistryException {
    ManifestPusher manifestPusher = new ManifestPusher(manifestTemplate, imageTag);
    callRegistryEndpoint(null, manifestPusher);
  }

  /**
   * Downloads the BLOB to a file.
   *
   * @param blobDigest the digest of the BLOB to download
   * @param destPath the path of the file to write to
   * @return a {@link Blob} backed by the file at {@code destPath}. The file at {@code destPath}
   *     must exist for {@link Blob} to be valid.
   */
  public Blob pullBlob(DescriptorDigest blobDigest, Path destPath)
      throws RegistryException, IOException {
    BlobPuller blobPuller = new BlobPuller(blobDigest, destPath);
    return callRegistryEndpoint(null, blobPuller);
  }

  // TODO: Add mount with 'from' parameter
  /**
   * Pushes the BLOB, or skips if the BLOB already exists on the registry.
   *
   * @param blobDigest the digest of the BLOB, used for existence-check
   * @param blob the BLOB to push
   */
  public String pushBlob(DescriptorDigest blobDigest, Blob blob) throws IOException, RegistryException {
    BlobPusher blobPusher = new BlobPusher(blobDigest, blob);

    // POST /v2/<name>/blobs/uploads/?mount={blob.digest}
    return (String) callRegistryEndpoint(null, blobPusher.initializer());

    // PATCH <Location> with BLOB

    // PUT <Location>?digest={blob.digest}
  }

  private URL getApiRoute(String routeSuffix) throws MalformedURLException {
    String apiBase = "/v2/";
    return new URL(PROTOCOL + "://" + serverUrl + apiBase + imageName + routeSuffix);
  }

  /**
   * Calls the registry endpoint.
   *
   * @param url the endpoint URL to call, or {@code null} to use default from {@code
   *     registryEndpointProvider}
   * @param registryEndpointProvider the {@link RegistryEndpointProvider} to the endpoint
   */
  private <T> T callRegistryEndpoint(
      @Nullable URL url, RegistryEndpointProvider<T> registryEndpointProvider)
      throws IOException, RegistryException {
    if (url == null) {
      url = getApiRoute(registryEndpointProvider.getApiRouteSuffix());
    }

    try (Connection connection = new Connection(url)) {
      Request.Builder builder = Request.builder();
      if (authorization != null) {
        builder.setAuthorization(authorization);
      }
      registryEndpointProvider.buildRequest(builder);
      Response response =
          connection.send(registryEndpointProvider.getHttpMethod(), builder.build());

      return registryEndpointProvider.handleResponse(response);

    } catch (HttpResponseException ex) {
      switch (ex.getStatusCode()) {
        case HttpStatusCodes.STATUS_CODE_BAD_REQUEST:
        case HttpStatusCodes.STATUS_CODE_NOT_FOUND:
        case HttpStatusCodes.STATUS_CODE_METHOD_NOT_ALLOWED:
          // The name or reference was invalid.
          ErrorResponseTemplate errorResponse =
              JsonTemplateMapper.readJson(ex.getContent(), ErrorResponseTemplate.class);
          String method = registryEndpointProvider.getActionDescription(serverUrl, imageName);
          RegistryErrorExceptionBuilder registryErrorExceptionBuilder =
              new RegistryErrorExceptionBuilder(method, ex);
          for (ErrorEntryTemplate errorEntry : errorResponse.getErrors()) {
            registryErrorExceptionBuilder.addReason(errorEntry);
          }

          throw registryErrorExceptionBuilder.build();

        case HttpStatusCodes.STATUS_CODE_UNAUTHORIZED:
        case HttpStatusCodes.STATUS_CODE_FORBIDDEN:
          throw new RegistryUnauthorizedException(ex);

        case HttpStatusCodes.STATUS_CODE_TEMPORARY_REDIRECT: // Temporary Redirect
          return callRegistryEndpoint(
              new URL(ex.getHeaders().getLocation()), registryEndpointProvider);

        default: // Unknown
          throw ex;
      }
    }
  }
}
