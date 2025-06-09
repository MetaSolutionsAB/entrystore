package org.entrystore.rest.standalone.springboot.model.api;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MetadataType {
	LOCAL_METADATA("metadata"),
	CACHED_EXTERNAL_METADATA("cached-external-metadata"),
	MERGED_METADATA("merged-metadata");

	private final String key;

	public static MetadataType fromString(String input) {
		for (var enVal : values()) {
			if (enVal.getKey().equals(input)) {
				return enVal;
			}
		}
		return null;
	}
}
