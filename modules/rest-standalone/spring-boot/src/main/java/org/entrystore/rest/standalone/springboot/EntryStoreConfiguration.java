/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

package org.entrystore.rest.standalone.springboot;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.MalformedURLException;
import java.net.URL;
import org.entrystore.PrincipalManager;
import org.entrystore.SearchIndex;
import org.entrystore.config.Config;
import org.entrystore.repository.RepositoryManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Main class to start EntryStore using Spring Boot.
 *
 * @author Björn Frantzén
 */
@Configuration
public class EntryStoreConfiguration {

	@Bean
	public RepositoryManager repositoryManager() throws MalformedURLException {
		RepositoryManager rm = mock(RepositoryManager.class);
		when(rm.getConfiguration()).thenReturn(mock(Config.class));
		when(rm.getPrincipalManager()).thenReturn(mock(PrincipalManager.class));
		when(rm.getIndex()).thenReturn(mock(SearchIndex.class));
		when(rm.getRepositoryURL()).thenReturn(new URL("http://test.com"));
		return rm;
	}
}
