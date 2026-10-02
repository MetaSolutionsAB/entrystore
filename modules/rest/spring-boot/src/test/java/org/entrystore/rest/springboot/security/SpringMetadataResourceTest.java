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

package org.entrystore.rest.springboot.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringMetadataResourceTest {

	@ParameterizedTest(name = "{0} is remote: {1}")
	@CsvSource({
			"https://idp.example.org/metadata, true",
			"HTTP://idp.example.org/metadata, true",
			"file:/etc/entrystore/idp-metadata.xml, false",
			"classpath:idp-metadata.xml, false"
	})
	void forLocation_treatsOnlyHttpUrlsAsRemote(String location, boolean remote) throws Exception {
		assertEquals(remote, SpringMetadataResource.forLocation(location).isRemote());
	}

	// OpenSAML's resolver refuses a resource that does not exist in its constructor, which would fail startup.
	@Test
	void remoteResource_existsEvenWhenTheIdpIsUnreachable() throws Exception {
		var resource = SpringMetadataResource.forLocation("http://127.0.0.1:" + closedPort() + "/metadata");

		assertTrue(resource.exists());
	}

	@Test
	@Timeout(15)
	void remoteResource_readTimesOut_whenTheIdpStalls() throws Exception {
		// Accepts the connection and never answers. Without a read timeout the fetch would hold the resolver's
		// lock, which logins wait on, indefinitely; @Timeout turns that regression into a failure, not a hang.
		var release = new CountDownLatch(1);
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/metadata", exchange -> {
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		server.start();
		try {
			var resource = SpringMetadataResource.remote(
					URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/metadata"),
					Duration.ofSeconds(5), Duration.ofSeconds(1));

			assertThrows(SocketTimeoutException.class, () -> resource.getInputStream().close());
		} finally {
			release.countDown();
			server.stop(0);
		}
	}

	private static int closedPort() throws IOException {
		try (var socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}
