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

import org.apereo.cas.client.validation.Cas10TicketValidator;
import org.apereo.cas.client.validation.Cas20ServiceTicketValidator;
import org.apereo.cas.client.validation.Cas30ServiceTicketValidator;
import org.apereo.cas.client.validation.TicketValidator;
import org.entrystore.PrincipalManager;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.springboot.configuration.CasCustomConfiguration;
import org.entrystore.rest.springboot.configuration.CasVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@ExtendWith(MockitoExtension.class)
class CasConfigTest {

	@Mock
	private ESUserDetailsService userDetailsService;

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private RepositoryManagerImpl repositoryManager;

	private CasConfig configWithVersion(CasVersion version) {
		var server = new CasCustomConfiguration.Server("https://cas.example.org/cas", null);
		var casConfig = new CasCustomConfiguration(true, version, server, false, null, null);
		CasConfig config = new CasConfig(casConfig, userDetailsService, principalManager, repositoryManager);
		// @Value-injected field — set via reflection since we're not using Spring context in this unit test.
		ReflectionTestUtils.setField(config, "disableSslVerification", false);
		return config;
	}

	@Test
	void cas1VersionCreatesCas10TicketValidator() {
		TicketValidator validator = configWithVersion(CasVersion.CAS1).casTicketValidator();
		assertInstanceOf(Cas10TicketValidator.class, validator);
	}

	@Test
	void cas2VersionCreatesCas20TicketValidator() {
		TicketValidator validator = configWithVersion(CasVersion.CAS2).casTicketValidator();
		assertInstanceOf(Cas20ServiceTicketValidator.class, validator);
	}

	@Test
	void cas3VersionCreatesCas30TicketValidator() {
		TicketValidator validator = configWithVersion(CasVersion.CAS3).casTicketValidator();
		assertInstanceOf(Cas30ServiceTicketValidator.class, validator);
	}
}
