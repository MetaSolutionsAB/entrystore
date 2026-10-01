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
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.UrlResource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;

/**
 * Adapts a Spring {@link org.springframework.core.io.Resource} to the Shibboleth
 * {@link Resource} expected by OpenSAML's {@code ResourceBackedMetadataResolver}, so SAML IdP
 * metadata can be loaded uniformly from {@code classpath:}, {@code file:} and {@code https:}
 * locations through Spring's resource loading (and JDK URL connections) without a separate HTTP
 * client. {@link RefreshableRelyingPartyRegistrationRepository} needs its own adapter (Spring Security's
 * {@code SpringResource} is private) to configure the resolver's refresh interval.
 *
 * <p>An {@code http(s)} location is {@linkplain #isRemote() remote} and differs from a local one in three ways:
 * <ul>
 *   <li>connections time out ({@link #CONNECT_TIMEOUT}, {@link #READ_TIMEOUT}), so an unresponsive IdP neither
 *       stalls startup nor holds the resolver's lock, which logins wait on, indefinitely;</li>
 *   <li>{@link #exists()} is {@code true}: the resolver's constructor checks it, and an IdP that is down at
 *       startup must not fail the application — the fetch reports unreachability instead;</li>
 *   <li>{@link #lastModified()} is the current time: the resolver downloads only when it is later than the last
 *       refresh attempt, and Spring reports 0 when the IdP sends no {@code Last-Modified} header, so the metadata
 *       would never be (re)loaded after a failed first fetch, nor refreshed from such an IdP.</li>
 * </ul>
 */
class SpringMetadataResource implements Resource {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

	private final org.springframework.core.io.Resource resource;

	private SpringMetadataResource(org.springframework.core.io.Resource resource) {
		this.resource = resource;
	}

	/**
	 * The metadata at {@code location}: an {@code http(s)} URL becomes a {@linkplain #isRemote() remote} resource
	 * with the default timeouts; any other location is resolved by Spring's {@link DefaultResourceLoader}.
	 */
	static SpringMetadataResource forLocation(String location) throws MalformedURLException {
		if (location.regionMatches(true, 0, "http:", 0, 5) || location.regionMatches(true, 0, "https:", 0, 6)) {
			return remote(URI.create(location), CONNECT_TIMEOUT, READ_TIMEOUT);
		}
		return new SpringMetadataResource(new DefaultResourceLoader().getResource(location));
	}

	static SpringMetadataResource remote(URI uri, Duration connectTimeout, Duration readTimeout)
			throws MalformedURLException {
		return new SpringMetadataResource(new TimeLimitedUrlResource(uri, connectTimeout, readTimeout));
	}

	boolean isRemote() {
		return resource instanceof TimeLimitedUrlResource;
	}

	@Override
	public boolean exists() {
		return isRemote() || this.resource.exists();
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
		return isRemote() ? System.currentTimeMillis() : this.resource.lastModified();
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

	/**
	 * A {@link UrlResource} whose HTTP connections time out. Every connection it opens — the metadata fetch and
	 * the HEAD requests behind {@code exists()}, {@code contentLength()} and {@code lastModified()} — passes
	 * through {@link #customizeConnection(HttpURLConnection)}.
	 */
	private static final class TimeLimitedUrlResource extends UrlResource {

		private final int connectTimeoutMillis;
		private final int readTimeoutMillis;

		TimeLimitedUrlResource(URI uri, Duration connectTimeout, Duration readTimeout) throws MalformedURLException {
			super(uri);
			this.connectTimeoutMillis = Math.toIntExact(connectTimeout.toMillis());
			this.readTimeoutMillis = Math.toIntExact(readTimeout.toMillis());
		}

		@Override
		protected void customizeConnection(HttpURLConnection con) throws IOException {
			super.customizeConnection(con);
			con.setConnectTimeout(connectTimeoutMillis);
			con.setReadTimeout(readTimeoutMillis);
		}
	}
}
