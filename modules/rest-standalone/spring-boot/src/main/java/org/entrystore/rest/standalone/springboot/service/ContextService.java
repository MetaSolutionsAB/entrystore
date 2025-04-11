package org.entrystore.rest.standalone.springboot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.rest.standalone.springboot.model.exception.BadRequestException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContextService {

	private final RepositoryManagerImpl repositoryManager;
	private final ReservedNamesService reservedNames;


	public List<String> getContextEntries(String contextId) {

		Context context = getContext(contextId);
		if (context == null) {
			throw new BadRequestException("No context with id '" + contextId + "' found");
		}

		return context.getEntries()
			.stream()
			.map(uri -> {
				Entry entry = context.getByEntryURI(uri);
				if (entry == null) {
					log.warn("No entry found for this referenced URI: {}", uri);
					return null;
				}
				return entry.getId();
			})
			.filter(Objects::nonNull)
			.toList();
	}

	public Context getContext(String contextId) {

		ContextManager cm = repositoryManager.getContextManager();

		if (cm != null && contextId != null) {
			if (reservedNames.contains(contextId.toLowerCase())) {
				log.error("Context ID is a reserved term and must not be used: \"{}\". This error is likely to be caused by an error in the REST routing.", contextId);
			} else {
				return cm.getContext(contextId);
			}
		}
		return null;
	}
}
