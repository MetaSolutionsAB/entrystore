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

package org.entrystore.rest.standalone.springboot.service;

import org.entrystore.PrincipalManager;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
import org.entrystore.rest.standalone.springboot.model.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetAddress;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class ProxyServiceTest {

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private ContextService contextService;

	private ProxyService service;

	@BeforeEach
	void setUp() {
		service = new ProxyService(principalManager, repositoryManager, contextService);
		service.setWhitelistLocal(Set.of());
		service.setWhitelistAnon(Set.of());
	}

	// --- validateUrl tests ---

	@Test
	void validateUrl_httpScheme_noException() {
		assertDoesNotThrow(() -> service.validateUrl("http://example.com"));
	}

	@Test
	void validateUrl_httpsScheme_noException() {
		assertDoesNotThrow(() -> service.validateUrl("https://example.com"));
	}

	@Test
	void validateUrl_ftpScheme_throwsBadRequest() {
		assertThrows(BadRequestException.class, () -> service.validateUrl("ftp://example.com"));
	}

	@Test
	void validateUrl_fileScheme_throwsBadRequest() {
		assertThrows(BadRequestException.class, () -> service.validateUrl("file:///etc/passwd"));
	}

	@Test
	void validateUrl_noScheme_throwsBadRequest() {
		assertThrows(BadRequestException.class, () -> service.validateUrl("example.com/path"));
	}

	@Test
	void validateUrl_malformedUrl_throwsBadRequest() {
		assertThrows(BadRequestException.class, () -> service.validateUrl("://bad"));
	}

	@Test
	void validateUrl_userinfo_throwsBadRequest() {
		assertThrows(BadRequestException.class,
				() -> service.validateUrl("http://user:pass@example.com/path"));
	}

	@Test
	void validateUrl_userinfoUsernameOnly_throwsBadRequest() {
		assertThrows(BadRequestException.class,
				() -> service.validateUrl("http://user@example.com/path"));
	}

	// --- resolveAndValidate tests ---

	@Test
	void resolveAndValidate_publicHostname_returnsAddress() {
		InetAddress result = service.resolveAndValidate("example.com");
		assertNotNull(result);
	}

	@Test
	void resolveAndValidate_localhost_notWhitelisted_throwsForbidden() {
		assertThrows(ForbiddenException.class, () -> service.resolveAndValidate("localhost"));
	}

	@Test
	void resolveAndValidate_localhost_whitelisted_returnsAddress() {
		service.setWhitelistLocal(Set.of("localhost"));
		InetAddress result = service.resolveAndValidate("localhost");
		assertNotNull(result);
		assertTrue(result.isLoopbackAddress());
	}

	@Test
	void resolveAndValidate_ipv4_throwsForbidden() {
		assertThrows(ForbiddenException.class, () -> service.resolveAndValidate("192.168.1.1"));
	}

	@Test
	void resolveAndValidate_numericIpv4_throwsForbidden() {
		assertThrows(ForbiddenException.class, () -> service.resolveAndValidate("2130706433"));
	}

	@Test
	void resolveAndValidate_ipv6_throwsForbidden() {
		assertThrows(ForbiddenException.class, () -> service.resolveAndValidate("::1"));
	}

	@Test
	void resolveAndValidate_localDomain_throwsForbidden() {
		assertThrows(ForbiddenException.class, () -> service.resolveAndValidate("myhost.local"));
	}

	@Test
	void resolveAndValidate_unresolvableHost_throwsForbidden() {
		assertThrows(ForbiddenException.class,
				() -> service.resolveAndValidate("definitely-not-a-real-host-xyz123.invalid"));
	}
}
