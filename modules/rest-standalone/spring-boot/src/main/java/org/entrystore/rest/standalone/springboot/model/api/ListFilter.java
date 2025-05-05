package org.entrystore.rest.standalone.springboot.model.api;

public record ListFilter(
	String sort,
	String lang,
	String prio,
	String desc,
	String order,
	String offset,
	String limit
) {
}
