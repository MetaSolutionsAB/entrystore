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

package org.entrystore.rest.springboot.model.api;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.entrystore.repository.util.SolrSearchIndex;

@NoArgsConstructor
@Getter
@Setter
public class FacetSettingsRequestParams {

	private String facetFields;

	@Min(1)
	private Integer facetMinCount = 1;   // default = 1

	private Integer facetLimit;          // we'll enforce min and max in code

	private String facetMatches;

	private Boolean facetMissing = false;


	public SolrSearchIndex.FacetSettings toSolrFacetSettings(int maxFacetLimit, int defaultFacetLimit) {

		SolrSearchIndex.FacetSettings facetSettings = new SolrSearchIndex.FacetSettings();

		facetSettings.fields = this.facetFields;
		facetSettings.minCount = this.facetMinCount;

		int limit = this.facetLimit != null
				? Math.min(this.facetLimit, maxFacetLimit)
				: defaultFacetLimit;
		facetSettings.limit = limit < 1 ? defaultFacetLimit : limit;

		facetSettings.matches = this.facetMatches;
		facetSettings.missing = Boolean.TRUE.equals(this.facetMissing);

		return facetSettings;
	}
}
