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

package org.entrystore.rest.springboot.service;

import org.entrystore.Entry;
import org.entrystore.PrincipalManager;
import org.entrystore.impl.RepositoryManagerImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The class-level condition is the only gate on loading the Disney suite, whose users have passwords
 * published in {@code TestSuite}, so these cases pin that the bean is absent unless the key is enabled.
 */
@ExtendWith(MockitoExtension.class)
class TestDataLoadServiceTest {

	@Mock
	private RepositoryManagerImpl repositoryManager;

	@Mock
	private PrincipalManager principalManager;

	@Mock
	private Entry donald;

	@ParameterizedTest(name = "init-with-test-data={0} omits the loader")
	@ValueSource(strings = {"off", "false", "no", "0"})
	void disabledSettingOmitsTheLoader(String value) {
		runner().withPropertyValues("entrystore.repository.store.init-with-test-data=" + value)
				.run(context -> {
					assertNull(context.getStartupFailure());
					assertFalse(context.containsBean("testDataLoadService"));
					verifyNoInteractions(repositoryManager);
				});
	}

	@Test
	void absentSettingOmitsTheLoader() {
		runner().run(context -> {
			assertNull(context.getStartupFailure());
			assertFalse(context.containsBean("testDataLoadService"));
			verifyNoInteractions(repositoryManager);
		});
	}

	@Test
	void enabledSettingCreatesTheLoader() {
		// Donald already present, so the loader skips TestSuite and touches nothing else.
		when(repositoryManager.getPrincipalManager()).thenReturn(principalManager);
		when(principalManager.getPrincipalEntry("Donald")).thenReturn(donald);

		runner().withPropertyValues("entrystore.repository.store.init-with-test-data=on")
				.run(context -> {
					assertNull(context.getStartupFailure());
					assertTrue(context.containsBean("testDataLoadService"));
					verify(principalManager).getPrincipalEntry("Donald");
				});
	}

	private ApplicationContextRunner runner() {
		return new ApplicationContextRunner()
				.withUserConfiguration(TestDataLoadService.class)
				.withBean(RepositoryManagerImpl.class, () -> repositoryManager);
	}
}
