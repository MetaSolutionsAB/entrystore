package org.entrystore.rest.standalone.springboot.model.api;

import lombok.Builder;

import java.util.Map;

@Builder
public record StatusExtendedResponse(
	String version,
	String repositoryStatus,
	String baseURI,
	String rowstoreURL,
	String startupTime,
	String repositoryType,
	String repositoryIndices,
	String quotaDefault,
	long echoMaxEntitySize,
	boolean provenance,
	boolean repositoryCache,
	boolean oaiHarvesterMultiThreaded,
	boolean quota,
	boolean oaiHarvester,
	Map<String, Object> jvm,
	Map<String, Object> backup,
	Map<String, Object> cors,
	Map<String, Object> auth,
	Map<String, Object> solr
) {

	public static StatusExtendedResponseBuilder fromStatusResponse(StatusResponse statusResponse) {
		return StatusExtendedResponse.builder()
			.version(statusResponse.version())
			.repositoryStatus(statusResponse.repositoryStatus());
	}
}
