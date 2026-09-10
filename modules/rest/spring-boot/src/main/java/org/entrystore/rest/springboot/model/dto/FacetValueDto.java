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

import java.util.List;

/**
 * One facet bucket. {@code name} is {@code null} for the {@code facet.missing} bucket. {@code langs} lists, in
 * natural order, the normalised language tags a literal label occurs in within the result set; it is empty for a
 * label that only occurs untagged and for every non-literal facet.
 */
public record FacetValueDto(String name, long count, List<String> langs) {
}
