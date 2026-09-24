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

/**
 * One facet bucket. {@code name} is {@code null} for the {@code facet.missing} bucket. The count is Solr's, taken
 * before the per-entry authorization filtering in {@code SolrSearchIndex.sendQuery}, so a drill-down on the label
 * can return fewer hits than the count for a caller who may not read every matching entry.
 */
public record FacetValueDto(String name, long count) {
}
