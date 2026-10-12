/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.transforms.rowstore;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Arrays;

/**
 * Sends requests to the RowStore dataset API.
 *
 * <p>Transport failures and timeouts are returned as a {@link Response} with status
 * {@link Response#TRANSPORT_FAILURE} or {@link Response#TIMEOUT} instead of being thrown, so callers handle them
 * like any unsuccessful HTTP status.
 */
@Slf4j
class RowStoreClient {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	private final HttpClient httpClient;

	private final Duration requestTimeout;

	RowStoreClient() {
		this(Duration.ofSeconds(60));
	}

	/**
	 * @param requestTimeout time until the response headers must have arrived, including the upload of the body
	 */
	RowStoreClient(Duration requestTimeout) {
		// HTTP/1.1 avoids the h2c upgrade headers, which not every RowStore deployment accepts
		this.httpClient = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(CONNECT_TIMEOUT)
				.build();
		this.requestTimeout = requestTimeout;
	}

	/**
	 * @param body        the request body, or null to send none
	 * @param contentType the body's media type; ignored without a body
	 */
	Response send(String method, URI uri, BodyPublisher body, String contentType) {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(requestTimeout);
		if (body == null) {
			request.method(method, BodyPublishers.noBody());
		} else {
			request.method(method, body).header("Content-Type", contentType);
		}

		try {
			var response = httpClient.send(request.build(), BodyHandlers.ofInputStream());
			// The body is not needed; closing it right away means a stalled body cannot outlast the timeout
			response.body().close();
			URI location = response.headers().firstValue("Location").map(uri::resolve).orElse(null);
			return new Response(response.statusCode(), location);
		} catch (ConnectException | HttpConnectTimeoutException e) {
			log.warn("RowStore request {} {} could not connect: {}; {}", method, uri, withRootCause(e),
					resolvedAddresses(uri));
			return new Response(Response.TRANSPORT_FAILURE, null);
		} catch (HttpTimeoutException e) {
			log.warn("RowStore request {} {} timed out: {}", method, uri, e.toString());
			return new Response(Response.TIMEOUT, null);
		} catch (IOException | IllegalArgumentException e) {
			log.warn("RowStore request {} {} failed: {}", method, uri, withRootCause(e));
			return new Response(Response.TRANSPORT_FAILURE, null);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("RowStore request {} {} was interrupted", method, uri);
			return new Response(Response.TRANSPORT_FAILURE, null);
		}
	}

	/** HttpClient often wraps the cause without a message, e.g. a ConnectException around "Connection refused". */
	private static String withRootCause(Throwable e) {
		Throwable root = e;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		return root == e ? e.toString() : e + " caused by " + root;
	}

	/**
	 * @return the addresses the host resolves to, one of which the failed connection used: a container may resolve
	 * the public RowStore host to an address that only works on the host. Called right after the connect attempt,
	 * so the JVM's address cache normally answers without a lookup.
	 */
	private static String resolvedAddresses(URI uri) {
		String host = uri.getHost();
		try {
			return host + " resolves to " + Arrays.stream(InetAddress.getAllByName(host))
					.map(InetAddress::getHostAddress)
					.toList();
		} catch (UnknownHostException e) {
			return host + " does not resolve";
		}
	}

	/**
	 * @param location the Location header resolved against the request URI, or null if absent
	 */
	record Response(int status, URI location) {

		static final int TRANSPORT_FAILURE = -1;

		static final int TIMEOUT = -2;

		boolean isSuccess() {
			return status >= 200 && status < 300;
		}

		boolean isTimeout() {
			return status == TIMEOUT;
		}

		/** Whether RowStore answered at all, as opposed to a transport failure or timeout. */
		boolean isAnswer() {
			return status >= 0;
		}
	}
}
