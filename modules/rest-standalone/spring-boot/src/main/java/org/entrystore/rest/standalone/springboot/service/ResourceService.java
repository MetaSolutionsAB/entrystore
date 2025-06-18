package org.entrystore.rest.standalone.springboot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.entrystore.Entry;
import org.entrystore.impl.RepositoryManagerImpl;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceService {

	private final RepositoryManagerImpl repositoryManager;
	private final SyndicationService syndicationService;

	public String getResource(Entry entry, String format, String syndication, Integer feedSize) {

		return "";
	}

}
