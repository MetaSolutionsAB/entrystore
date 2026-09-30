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

package org.entrystore.rest.springboot.configuration;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@link EnvironmentPostProcessor}s that META-INF/spring.factories registers, instantiated the way Boot does.
 * {@code EnvironmentPostProcessorsFactory} is no longer public API in Boot 4, so this drives Boot's own SPI loader
 * directly: finding a processor here proves the registration AND that Boot can construct it from a
 * {@link DeferredLogFactory} — not merely that the file contains the expected text.
 */
final class RegisteredEnvironmentPostProcessors {

	private RegisteredEnvironmentPostProcessors() {
	}

	static List<EnvironmentPostProcessor> load() {
		DeferredLogFactory logFactory = _ -> new RecordingLog(new ArrayList<>());
		return SpringFactoriesLoader.forDefaultResourceLocation(RegisteredEnvironmentPostProcessors.class.getClassLoader())
				.load(EnvironmentPostProcessor.class,
						SpringFactoriesLoader.ArgumentResolver.of(DeferredLogFactory.class, logFactory),
						// Boot's own EnvironmentPostProcessors need constructor args not supplied here; skip them.
						(factoryType, factoryImplementationName, failure) -> { });
	}
}
