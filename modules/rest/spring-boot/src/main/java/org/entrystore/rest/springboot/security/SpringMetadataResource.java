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

import net.shibboleth.shared.resource.Resource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;

/**
 * Adapts a Spring {@link org.springframework.core.io.Resource} to the Shibboleth
 * {@link Resource} expected by OpenSAML's {@code ResourceBackedMetadataResolver}, so SAML IdP
 * metadata can be loaded uniformly from {@code classpath:}, {@code file:} and {@code https:}
 * locations through Spring's resource loading (and JDK URL connections) without a separate HTTP
 * client.
 *
 * <p>Spring Security ships an identical adapter as the private nested class {@code SpringResource}
 * inside {@code OpenSaml5AssertingPartyMetadataRepository.MetadataLocationRepositoryBuilder} and
 * therefore cannot be reused; {@link RefreshableRelyingPartyRegistrationRepository} needs its own
 * copy to configure the resolver's refresh interval (which the Spring builder does not expose).
 */
class SpringMetadataResource implements Resource {

	private final org.springframework.core.io.Resource resource;

	SpringMetadataResource(org.springframework.core.io.Resource resource) {
		this.resource = resource;
	}

	@Override
	public boolean exists() {
		return this.resource.exists();
	}

	@Override
	public boolean isReadable() {
		return this.resource.isReadable();
	}

	@Override
	public boolean isOpen() {
		return this.resource.isOpen();
	}

	@Override
	public URL getURL() throws IOException {
		return this.resource.getURL();
	}

	@Override
	public URI getURI() throws IOException {
		return this.resource.getURI();
	}

	@Override
	public File getFile() throws IOException {
		return this.resource.getFile();
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return this.resource.getInputStream();
	}

	@Override
	public long contentLength() throws IOException {
		return this.resource.contentLength();
	}

	@Override
	public long lastModified() throws IOException {
		return this.resource.lastModified();
	}

	@Override
	public Resource createRelativeResource(String relativePath) throws IOException {
		return new SpringMetadataResource(this.resource.createRelative(relativePath));
	}

	@Override
	public String getFilename() {
		return this.resource.getFilename();
	}

	@Override
	public String getDescription() {
		return this.resource.getDescription();
	}
}
