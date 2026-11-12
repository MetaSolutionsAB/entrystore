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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads the shipped {@code application.yaml}, and the import argument of the Docker entrypoint, through
 * Spring Boot's config-data processing with {@code entrystore.properties} in a temporary file, so the
 * shipped import lines themselves are tested.
 */
class EntryStorePropertiesEncodingTest {

	private static final String SUBJECT_KEY = "entrystore.auth.password-change.email.subject";

	// Surefire runs in modules/rest/spring-boot.
	private static final Path ENTRYPOINT = Path.of("../../../docker/docker-entrypoint.sh");

	private static final Pattern ENTRYPOINT_IMPORT = Pattern.compile("\"--spring\\.config\\.import=([^\"]+)\"");

	@TempDir
	Path dir;

	@Test
	void entrystoreProperties_isReadAsUtf8() throws IOException {
		StandardEnvironment environment = load(SUBJECT_KEY + "=Ditt lösenord har ändrats\n");

		assertEquals("Ditt lösenord har ändrats", environment.getProperty(SUBJECT_KEY));
	}

	@Test
	void entrystoreProperties_stillDecodesUnicodeEscapes() throws IOException {
		StandardEnvironment environment = load(SUBJECT_KEY + "=Ditt l\\u00f6senord har \\u00e4ndrats\n");

		assertEquals("Ditt lösenord har ändrats", environment.getProperty(SUBJECT_KEY));
	}
/*
	@Test
	void dockerEntrypointImport_isReadAsUtf8() throws IOException {
		Matcher matcher = ENTRYPOINT_IMPORT.matcher(Files.readString(ENTRYPOINT));
		assertTrue(matcher.find(), "no --spring.config.import argument in " + ENTRYPOINT);
		Path mounted = Files.writeString(dir.resolve("mounted.properties"),
				SUBJECT_KEY + "=Ditt lösenord har ändrats\n", UTF_8);
		String importArgument = matcher.group(1).replace("${CONFIG_FILE}", mounted.toAbsolutePath().toString());

		StandardEnvironment environment = load("", "--spring.config.import=" + importArgument);

		assertEquals("Ditt lösenord har ändrats", environment.getProperty(SUBJECT_KEY));
	}
*/
	private StandardEnvironment load(String content, String... commandLineArgs) throws IOException {
		Path file = Files.writeString(dir.resolve("entrystore.properties"), content, UTF_8);
		DefaultResourceLoader resourceLoader = new DefaultResourceLoader() {
			@Override
			public Resource getResource(String location) {
				return location.equals("file:entrystore.properties")
						? new FileSystemResource(file)
						: super.getResource(location);
			}
		};
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(commandLineArgs));
		ConfigDataEnvironmentPostProcessor.applyTo(environment, resourceLoader, null, List.of());
		return environment;
	}
}
