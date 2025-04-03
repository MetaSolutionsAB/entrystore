package org.entrystore.rest.standalone.springboot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.repository.util.URISplit;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.eclipse.rdf4j.model.util.Values.iri;

@Slf4j
@Service
@RequiredArgsConstructor
public class RelationService {

	private final RepositoryManagerImpl repositoryManager;

	public Map<String, Object> getRelationStats(boolean verbose) {
		Repository repository = repositoryManager.getRepository();
		long checkedRelations = 0;
		Map<String, Long> relationStats = new HashMap<>();
		List<String> relationContextsWithoutEntryContext = new ArrayList<>();
		List<String> statementsWithDanglingObject = new ArrayList<>();

		try (RepositoryConnection rc = repository.getConnection()) {
			try (RepositoryResult<Resource> rr = rc.getContextIDs()) {
				for (Resource context : rr) {
					if (context != null && context.isIRI()) {
						String contextIRIStr = context.toString();
						if (isRelationIRI(contextIRIStr)) {
							// check whether corresponding entry exists
							if (!doesEntryExist(contextIRIStr)) {
								log.warn("No corresponding entry found for relation graph <{}>", contextIRIStr);
								relationContextsWithoutEntryContext.add(contextIRIStr);
								continue;
							}

							// check whether relation target exists
							try (RepositoryConnection rc2 = repository.getConnection()) {
								try (RepositoryResult<Statement> relations = rc2.getStatements(null, null, null, false, context)) {
									for (Statement relation : relations) {
										if (!relation.getObject().isIRI()) {
											log.warn("Statement does not have a resource in object position: {}", relation);
										} else {
											try (RepositoryConnection rc3 = repository.getConnection()) {
												try (RepositoryResult<Statement> targetEntry = rc3.getStatements((Resource) relation.getObject(), null, null, false)) {
													checkedRelations++;
													if (!targetEntry.hasNext()) {
														log.warn("Relation target does not exist: <{}>, statement: {}", relation.getObject(), relation);
														statementsWithDanglingObject.add(relation.toString());
														String predicate = relation.getPredicate().toString();
														relationStats.put(predicate, relationStats.getOrDefault(predicate, 0L) + 1L);
													}
												}
											}
										}
									}
								}
							}
						}
					}
				}
			}
		} catch (RepositoryException e) {
			log.error(e.getMessage());
		}

		for (Map.Entry<String, Long> entry : relationStats.entrySet()) {
			log.info("{}: {}", entry.getKey(), entry.getValue());
		}

		log.info("Checked relations: {}", checkedRelations);
		log.info("Active relations pointing to non-existing targets: {}", statementsWithDanglingObject.size());
		log.info("Relation contexts without existing entry context: {}", relationContextsWithoutEntryContext.size());

		Map<String, Object> result = new HashMap<>();
		result.put("checkedRelationCount", checkedRelations);
		result.put("activeRelationsWithNonExistingTargetPredicateStats", relationStats);
		result.put("activeRelationsWithNonExistingTargetCount", statementsWithDanglingObject.size());
		result.put("relationContextsWithoutEntryContextCount", relationContextsWithoutEntryContext.size());

		if (verbose) {
			result.put("activeRelationsWithNonExistingTarget", statementsWithDanglingObject);
			result.put("relationContextsWithoutEntryContext", relationContextsWithoutEntryContext);
		}

		return result;
	}

	private boolean doesEntryExist(String relationIRIStr) {
		IRI entryURI = iri(new URISplit(URI.create(relationIRIStr), repositoryManager.getRepositoryURL()).getMetaMetadataURI().toString());
		try (RepositoryConnection rc = repositoryManager.getRepository().getConnection()) {
			try (RepositoryResult<Statement> entryGraph = rc.getStatements(null, null, null, false, entryURI)) {
				if (entryGraph.hasNext()) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean isRelationIRI(String iriStr) {
		return iriStr.startsWith(repositoryManager.getRepositoryURL().toString()) && iriStr.contains("/relations/");
	}
}
