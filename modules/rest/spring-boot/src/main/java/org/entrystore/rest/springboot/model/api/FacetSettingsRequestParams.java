package org.entrystore.rest.springboot.model.api;

import jakarta.validation.constraints.Min;
import lombok.NoArgsConstructor;
import org.entrystore.repository.util.SolrSearchIndex;

@NoArgsConstructor
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
