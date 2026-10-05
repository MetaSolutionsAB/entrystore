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

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class RowStoreClientTest {

	private record ReceivedRequest(String method, String path, Headers headers, String body) {
	}

	private final AtomicReference<ReceivedRequest> received = new AtomicReference<>();

	private HttpServer server;

	private URI baseUri;

	private volatile int responseStatus;

	private volatile String responseLocation;

	private volatile Duration responseDelay = Duration.ZERO;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			received.set(new ReceivedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders(), body));
			try {
				Thread.sleep(responseDelay);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (responseLocation != null) {
				exchange.getResponseHeaders().add("Location", responseLocation);
			}
			exchange.sendResponseHeaders(responseStatus, -1);
			exchange.close();
		});
		server.start();
		baseUri = URI.create("http://localhost:" + server.getAddress().getPort());
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	void postSendsFileBodyWithContentTypeAndContentLength(@TempDir Path tempDir) throws IOException {
		Path csv = Files.writeString(tempDir.resolve("data.csv"), "a,b\n1,2\n");
		responseStatus = 202;
		responseLocation = "http://rowstore.example.com/datasets/abc";

		RowStoreClient.Response response = new RowStoreClient().send("POST", baseUri.resolve("/datasets"),
				BodyPublishers.ofFile(csv), "text/csv");

		ReceivedRequest request = received.get();
		assertEquals("POST", request.method());
		assertEquals("/datasets", request.path());
		assertEquals("text/csv", request.headers().getFirst("Content-Type"));
		assertEquals("8", request.headers().getFirst("Content-Length"));
		assertEquals("a,b\n1,2\n", request.body());
		assertEquals(202, response.status());
		assertEquals(URI.create("http://rowstore.example.com/datasets/abc"), response.location());
	}

	@Test
	void relativeLocationIsResolvedAgainstRequestUri() {
		responseStatus = 202;
		responseLocation = "/datasets/abc";

		RowStoreClient.Response response = new RowStoreClient().send("POST", baseUri.resolve("/rowstore/datasets"),
				BodyPublishers.ofString("a,b"), "text/csv");

		assertEquals(baseUri.resolve("/datasets/abc"), response.location());
	}

	@Test
	void missingLocationIsReturnedAsNull() {
		responseStatus = 202;

		RowStoreClient.Response response = new RowStoreClient().send("POST", baseUri.resolve("/datasets"),
				BodyPublishers.ofString("a,b"), "text/csv");

		assertNull(response.location());
	}

	@Test
	void deleteWithoutBodySendsNoContentType() {
		responseStatus = 204;

		RowStoreClient.Response response = new RowStoreClient().send("DELETE",
				baseUri.resolve("/datasets/abc/aliases"), null, null);

		ReceivedRequest request = received.get();
		assertEquals("DELETE", request.method());
		assertEquals("/datasets/abc/aliases", request.path());
		assertNull(request.headers().getFirst("Content-Type"));
		assertEquals("", request.body());
		assertEquals(204, response.status());
	}

	@Test
	void requestsUseHttp11WithoutH2cUpgrade() {
		responseStatus = 200;

		new RowStoreClient().send("PUT", baseUri.resolve("/datasets/abc"), BodyPublishers.ofString("a,b"), "text/csv");

		assertNull(received.get().headers().getFirst("Upgrade"));
	}

	@Test
	void errorStatusIsReturnedAsUnsuccessful() {
		responseStatus = 500;

		RowStoreClient.Response response = new RowStoreClient().send("PUT", baseUri.resolve("/datasets/abc"),
				BodyPublishers.ofString("a,b"), "text/csv");

		assertEquals(500, response.status());
		assertFalse(response.isSuccess());
	}

	@Test
	void unreachableServerIsReportedAsTransportFailure() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
			closedPort = socket.getLocalPort();
		}

		RowStoreClient.Response response = new RowStoreClient().send("POST",
				URI.create("http://localhost:" + closedPort + "/datasets"), BodyPublishers.ofString("a,b"), "text/csv");

		assertEquals(RowStoreClient.Response.TRANSPORT_FAILURE, response.status());
		assertFalse(response.isSuccess());
	}

	@Test
	void slowServerIsReportedAsTransportFailure() {
		responseStatus = 202;
		responseDelay = Duration.ofSeconds(2);

		RowStoreClient.Response response = new RowStoreClient(Duration.ofMillis(200)).send("POST",
				baseUri.resolve("/datasets"), BodyPublishers.ofString("a,b"), "text/csv");

		assertEquals(RowStoreClient.Response.TRANSPORT_FAILURE, response.status());
	}
}
