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

package org.entrystore.rest.springboot.model.dto;

import org.entrystore.rest.springboot.model.api.ListFilter;

import java.util.Optional;

/**
 * Sorting and pagination parameters for serializing a List resource's children.
 */
public record ListParams(
		String sort,
		String lang,
		String prio,
		String desc,
		boolean ascendingOrder,
		int offset,
		int limit) {

	public ListParams(ListFilter filter) {
		this(
				filter.sort(),
				filter.lang(),
				filter.prio(),
				filter.desc(),
				!"desc".equalsIgnoreCase(filter.order()),
				Integer.parseInt(Optional.ofNullable(filter.offset()).orElse("0")),
				Integer.parseInt(Optional.ofNullable(filter.limit()).orElse("0"))
		);
	}

	/** Params with offset/limit left unparsed (defaulted to 0) — for callers that ignore pagination. */
	public static ListParams withoutPagination(ListFilter filter) {
		return new ListParams(filter.sort(), filter.lang(), filter.prio(), filter.desc(),
				!"desc".equalsIgnoreCase(filter.order()), 0, 0);
	}
}
