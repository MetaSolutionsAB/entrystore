package org.entrystore.rest.standalone.springboot.model.api;

import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

@Getter
public enum StatusExtendedIncludeEnum {
	COUNT_STATS,
	RELATION_STATS,
	RELATION_VERBOSE_STATS;

	public static StatusExtendedIncludeEnum fromString(String input) {
		var capitalInput = input.toUpperCase();
		for (var enVal : values()) {
			if (enVal.name().equals(capitalInput) ||
				StringUtils.remove(enVal.name(), '_').equals(capitalInput)) {
				return enVal;
			}
		}
		throw new IllegalArgumentException(
			"No enum constant of StatusExtendedIncludeEnum." + input);
	}
}
