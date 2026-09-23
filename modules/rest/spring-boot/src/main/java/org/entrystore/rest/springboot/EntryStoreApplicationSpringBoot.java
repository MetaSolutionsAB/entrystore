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

	static void main(String[] args) {
		try {
			SpringApplication.run(EntryStoreApplicationSpringBoot.class, args);
		} catch (SpringApplication.AbandonedRunException e) {
			// Thrown on purpose when a hook stops the run early, e.g. during AOT processing
			throw e;
		} catch (Throwable t) {
			// Report the failure as if it were uncaught: Spring Boot's handler for this thread skips failures that
			// SpringApplication has already logged, and the default handler prints the others, e.g. failures before
			// SpringApplication could report anything. Then exit explicitly, because non-daemon threads started
			// before the failure (e.g. HTTP client executors) would otherwise keep the JVM running, and a container
			// would neither serve requests nor be restarted.
			Thread current = Thread.currentThread();
			current.getUncaughtExceptionHandler().uncaughtException(current, t);
			System.exit(1);
		}
	}
}
