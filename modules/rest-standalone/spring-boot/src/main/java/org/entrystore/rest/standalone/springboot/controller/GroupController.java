package org.entrystore.rest.standalone.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.rest.standalone.springboot.service.GroupService;
import org.entrystore.rest.standalone.springboot.util.HttpUtil;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class GroupController {

	private final GroupService groupService;

	@Operation(summary = "Creates a group with a linked home context. A helper resource for non-admins.")
	@PostMapping(path = "/_principals/groups", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE})
	public ResponseEntity<Void> createGroup(
		@RequestParam(required = false) String contextId,
		@RequestParam(required = false) String name) {

		Entry newGroupEntry = groupService.createGroup(contextId, name);

		return ResponseEntity
			.created(newGroupEntry.getEntryURI())
			.lastModified(newGroupEntry.getModifiedDate().getTime())
			.eTag(HttpUtil.createStrongETag(Long.toString(newGroupEntry.getModifiedDate().getTime())))
			.build();
	}
}
