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

package org.entrystore.rest.springboot.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Supplies URLs for the static resources referenced by the Thymeleaf templates.
 *
 * <p>Exposed to the templates as the bean {@code webResources}, so a view can reference it
 * without every controller having to add a model attribute. That matters because
 * {@code auth.html} is also rendered from {@code AppExceptionHandler}, where
 * {@code @ModelAttribute} advice does not run.
 *
 * <p>The paths are root-relative rather than relative to the page, because the same template is
 * rendered at more than one path depth ({@code /auth/signup} and {@code /auth/signup/confirm}),
 * and rather than absolute, so the link never pins a scheme or host the page was not served on.
 * The prefix comes from {@code entrystore.baseurl.folder}: a reverse proxy may mount EntryStore
 * under a path prefix and strip it before forwarding, in which case the browser must ask for the
 * prefixed path while the application only ever sees the un-prefixed one.
 */
@Component("webResources")
public class WebResourceUrls {

	private static final String STYLESHEET = "css/entrystore.css";

	private final String stylesheetPath;

	public WebResourceUrls(@Value("${entrystore.baseurl.folder:/}") String baseUrl) {
		this.stylesheetPath = basePath(baseUrl) + STYLESHEET;
	}

	/**
	 * Extracts the path component of the configured base URL, normalised to start and end with a
	 * slash. Falls back to the root for a value that is not a parsable URI, so a misconfigured base
	 * URL costs styling rather than startup.
	 */
	static String basePath(String baseUrl) {
		String path;
		try {
			path = URI.create(baseUrl).getPath();
		} catch (IllegalArgumentException e) {
			path = null;
		}
		if (path == null || path.isBlank()) {
			return "/";
		}
		if (!path.startsWith("/")) {
			path = "/" + path;
		}
		return path.endsWith("/") ? path : path + "/";
	}

	public String getStylesheetPath() {
		return stylesheetPath;
	}
}
