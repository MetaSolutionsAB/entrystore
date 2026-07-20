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

package org.entrystore.rest.springboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = ErrorMvcAutoConfiguration.class)
@ConfigurationPropertiesScan("org.entrystore.rest.springboot.configuration")
@EnableScheduling
public class EntryStoreApplicationSpringBoot {

	static {
		// Without this, HttpURLConnection silently drops the Host header override that
		// SsrfValidator.openPinnedConnection relies on for virtual hosting (connections are pinned
		// to the resolved IP, so upstreams would receive the raw IP as Host). Must be set before
		// the HttpURLConnection class is initialized. The integration tests only mask the gap
		// because BaseSpec sets the same property in the shared test JVM.
		System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
	}

	static void main(String[] args) {
		SpringApplication.run(EntryStoreApplicationSpringBoot.class, args);
	}
}
