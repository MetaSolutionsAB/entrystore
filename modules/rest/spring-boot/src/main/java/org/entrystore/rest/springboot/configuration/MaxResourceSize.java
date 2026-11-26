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

import lombok.Getter;
import org.entrystore.repository.util.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The limit {@code entrystore.data.max-resource-size} sets on a request body that is read into memory whole: the raw
 * PUT body of a non-file resource, and every body bound by a message converter for a handler without
 * {@code @MaxRequestBodySize}. Written in the units of {@code entrystore.data.max-file-size}; -1 removes the limit.
 * Any other value that is not positive, or above the longest array a body can be read into, fails startup.
 */
@Getter
@Component
public class MaxResourceSize {

	/**
	 * The longest body that can be read into an array together with the one byte past it that tells a body at the
	 * limit from one above it.
	 */
	static final long CEILING = Integer.MAX_VALUE - 9;

	/** The limit in bytes, or -1 for none. */
	private final long limit;

	public MaxResourceSize(@Value("${entrystore.data.max-resource-size:10M}") String value) {
		long size;
		try {
			size = StringUtils.convertUnitStringToByteSize(value);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("entrystore.data.max-resource-size must be a size such as 10M, got "
					+ value, e);
		}
		if (size != -1 && (size < 1 || size > CEILING)) {
			throw new IllegalArgumentException("entrystore.data.max-resource-size must be -1 or between 1 and "
					+ CEILING + " bytes, got " + value);
		}
		this.limit = size;
	}

	/** Whether a limit is set, i.e. the setting is not -1. */
	public boolean isLimited() {
		return limit != -1;
	}
}
