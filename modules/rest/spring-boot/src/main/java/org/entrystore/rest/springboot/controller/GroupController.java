package org.entrystore.rest.springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.rest.springboot.service.GroupService;
import org.entrystore.rest.springboot.util.HttpUtil;
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
	@PostMapping(path = "/_principals/groups")
	public ResponseEntity<Void> createGroup(
			@RequestParam(required = false) String contextId,
			@RequestParam(required = false) String name) {

		Entry newGroupEntry = groupService.createGroup(contextId, name);

		return HttpUtil.updateResponseWithModificationDateAndETag(
						ResponseEntity.created(newGroupEntry.getEntryURI()),
						newGroupEntry.getModifiedDate())
				.build();
	}
}
