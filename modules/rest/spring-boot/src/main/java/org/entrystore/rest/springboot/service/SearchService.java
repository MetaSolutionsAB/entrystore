package org.entrystore.rest.springboot.service;

import com.rometools.rome.feed.synd.SyndFeed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.common.SolrException;
import org.eclipse.rdf4j.model.Model;
import org.entrystore.AuthorizationException;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.Metadata;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.Resource;
import org.entrystore.User;
import org.entrystore.config.Config;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.repository.util.QueryResult;
import org.entrystore.repository.util.SolrSearchIndex;
import org.entrystore.rest.springboot.model.dto.QueryResultsDto;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.model.exception.CustomResponseException;
import org.entrystore.rest.springboot.model.exception.InternalServerErrorException;
import org.entrystore.rest.springboot.util.GraphUtil;
import org.entrystore.rest.springboot.util.Syndication;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.regex.Pattern;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

	private final RepositoryManagerImpl repositoryManager;
	private final PrincipalManager principalManager;
	private final Config esConfig;


	/**
	 * Valid SPARQL predicate: a full IRI ({@code <http://...>}), a prefixed name ({@code dc:title}),
	 * or the keyword {@code a} (shorthand for rdf:type).
	 */
	private static final Pattern VALID_SPARQL_PREDICATE = Pattern.compile(
			"^(<[^<>\"\\s{}|^`\\\\]+>|[a-zA-Z][\\w.-]*:[a-zA-Z_][\\w.-]*[\\w]|a)$"
	);

	public List<Entry> findEntriesSparql(String queryValue) {

		if (queryValue == null || !VALID_SPARQL_PREDICATE.matcher(queryValue).matches()) {
			log.info("Rejected invalid SPARQL predicate input: '{}'", queryValue);
			throw new BadRequestException("Invalid SPARQL predicate. Expected a full IRI (<http://...>) or prefixed name (prefix:name).");
		}

		try {
			String query = "PREFIX dc:<http://purl.org/dc/terms/> " +
					"SELECT ?x " +
					"WHERE { " +
					"  ?x " + queryValue + " ?y }";
			return repositoryManager.getContextManager().search(query, null, null);

		} catch (AuthorizationException e) {
			throw e;
		} catch (Exception e) {
			throw new BadRequestException("Exception processing SPARQL query", e);
		}
	}

	public QueryResultsDto findEntriesSolr(
			String queryValue,
			String sorting,
			int offset,
			int limit,
			List<String> filterQueries,
			SolrSearchIndex.FacetSettings facetSettings) {

		try {

			List<Entry> entries;
			long results;
			List<FacetField> responseFacetFields;

			if (repositoryManager.getIndex() == null) {
				throw new CustomResponseException("Solr search is deactivated", HttpStatus.SERVICE_UNAVAILABLE);
			}

			SolrQuery q = new SolrQuery(queryValue);
			q.setStart(offset);
			q.setRows(limit);

			if (sorting != null) {
				for (String string : sorting.split(",")) {
					String[] fieldAndOrder = string.split(" ");
					if (fieldAndOrder.length == 2) {
						String field = fieldAndOrder[0];
						if (field.startsWith("title.")) {
							field = field.replace("title.", "title_sort.");
						}
						SolrQuery.ORDER order = SolrQuery.ORDER.asc;
						try {
							order = SolrQuery.ORDER.valueOf(fieldAndOrder[1].toLowerCase());
						} catch (IllegalArgumentException iae) {
							log.warn("Unable to parse sorting value, using ascending by default");
						}
						q.addSort(field, order);
					}
				}
			} else {
				q.addSort("score", SolrQuery.ORDER.desc);
				q.addSort("modified", SolrQuery.ORDER.desc);
			}

			if (facetSettings.fields != null) {
				q.setFacet(true);
				q.setFacetMinCount(facetSettings.minCount);
				q.setFacetLimit(facetSettings.limit);
				q.setFacetMissing(facetSettings.missing);
				if (facetSettings.matches != null) {
					q.setParam("facet.matches", facetSettings.matches);
				}
				for (String ff : facetSettings.fields.split(",")) {
					q.addFacetField(ff.replace("metadata.predicate.literal.", "metadata.predicate.literal_s."));
				}
			}

			for (String fq : filterQueries) {
				q.addFilterQuery(fq);
			}

			try {
				QueryResult qResult = ((SolrSearchIndex) repositoryManager.getIndex()).sendQuery(q);
				entries = new LinkedList<>(qResult.getEntries());
				results = qResult.getHits();
				responseFacetFields = qResult.getFacetFields();
			} catch (SolrException se) {
				log.warn("SolrException: {}", se.getMessage());
				throw new BadRequestException("Search failed due to wrong parameters");
			}
			return new QueryResultsDto(entries, results, responseFacetFields);
		} catch (JSONException e) {
			throw new InternalServerErrorException("Error during Solr search", e);
		}

	}

	public String generateSyndication(List<Entry> entries, String feedType, String language, int limit,
									  String urlTemplate, String feedTitle) {

		SyndFeed feed = Syndication.createFeedFromEntries(repositoryManager.getPrincipalManager(), esConfig, entries,
				language, limit, urlTemplate);
		feed.setTitle(Syndication.sanitizeFeedTitle(feedTitle));
		feed.setLink(buildRequestUri());
		feed.setFeedType(feedType);

		try {
			return Syndication.convertSyndFeedToXml(feed);
		} catch (IllegalArgumentException e) {
			throw new BadRequestException("Invalid syndication feed type: '" + feedType + "'");
		}
	}

	public String generateJson(int offset, int limit, QueryResultsDto queryResults, String rdfFormat) {
		Instant startTime = Instant.now();
		JSONArray children = new JSONArray();
		if (queryResults.entries() != null) {
			for (Entry e : queryResults.entries()) {
				if (e != null) {
					JSONObject childJSON = new JSONObject();
					childJSON.put("entryId", e.getId());
					childJSON.put("contextId", e.getContext().getEntry().getId());
					GraphType btChild = e.getGraphType();
					EntryType locChild = e.getEntryType();
					if (btChild == GraphType.Context || btChild == GraphType.SystemContext) {
						childJSON.put("alias", repositoryManager.getContextManager().getName(e.getResourceURI()));
					} else if (btChild == GraphType.User && locChild == EntryType.Local) {
						User u = (User) e.getResource();
						childJSON.put("name", u.getName());
						try {
							if (u.isDisabled()) {
								childJSON.put("disabled", true);
							}
						} catch (AuthorizationException ae) {
							log.debug("Not allowed to read disabled status of " + e.getEntryURI());
						}
					} else if (btChild == GraphType.Group && locChild == EntryType.Local) {
						Resource groupResource = e.getResource();
						if (groupResource != null) {
							childJSON.put("name", ((Group) groupResource).getName());
						}
					}
					Set<AccessProperty> rights = principalManager.getRights(e);
					for (AccessProperty ap : rights) {
						if (ap == AccessProperty.Administer) {
							childJSON.append("rights", "administer");
						} else if (ap == AccessProperty.WriteMetadata) {
							childJSON.append("rights", "writemetadata");
						} else if (ap == AccessProperty.WriteResource) {
							childJSON.append("rights", "writeresource");
						} else if (ap == AccessProperty.ReadMetadata) {
							childJSON.append("rights", "readmetadata");
						} else if (ap == AccessProperty.ReadResource) {
							childJSON.append("rights", "readresource");
						}
					}

					try {
						EntryType ltC = e.getEntryType();
						if (EntryType.Reference.equals(ltC) || EntryType.LinkReference.equals(ltC)) {
							// get the external metadata
							Metadata cachedExternalMD = e.getCachedExternalMetadata();
							if (cachedExternalMD != null) {
								Model cachedExternalMDGraph = cachedExternalMD.getGraph();
								if (cachedExternalMDGraph != null) {
									JSONObject childCachedExternalMDJSON = GraphUtil.serializeGraphToJson(cachedExternalMDGraph, rdfFormat);
									childJSON.accumulate(RepositoryProperties.EXTERNAL_MD_PATH, childCachedExternalMDJSON);
								}
							}
						}

						if (EntryType.Link.equals(ltC) || EntryType.Local.equals(ltC) || EntryType.LinkReference.equals(ltC)) {
							// get the local metadata
							Metadata localMD = e.getLocalMetadata();
							if (localMD != null) {
								Model localMDGraph = localMD.getGraph();
								if (localMDGraph != null) {
									JSONObject localMDJSON = GraphUtil.serializeGraphToJson(localMDGraph, rdfFormat);
									childJSON.accumulate(RepositoryProperties.MD_PATH, localMDJSON);
								}
							}
						}
					} catch (AuthorizationException ae) {
						childJSON.accumulate("noAccessToMetadata", true);
					}

					try {
						JSONObject childInfo = GraphUtil.serializeGraphToJson(e.getGraph(), rdfFormat);
						childJSON.accumulate("info", Objects.requireNonNullElseGet(childInfo, JSONObject::new));
					} catch (AuthorizationException ae) {
						childJSON.accumulate("noAccessToEntryInfo", true);
					}

					try {
						Model childRelationsGraph = e.getRelations();
						if (childRelationsGraph != null) {
							JSONObject childRelationObj = GraphUtil.serializeGraphToJson(childRelationsGraph, rdfFormat);
							childJSON.accumulate(RepositoryProperties.RELATION, childRelationObj);
						}
					} catch (AuthorizationException ae) {
						childJSON.accumulate("noAccessToRelations", true);
					}

					children.put(childJSON);
				}
			}
		}

		JSONObject result = new JSONObject();
		JSONObject resource = new JSONObject();
		resource.put("children", children);
		result.put("resource", resource);
		result.put("results", queryResults.resultsCount());
		result.put("limit", limit);
		result.put("offset", offset);
		result.put("facetFields", getFacetFieldsArr(queryResults));

		log.debug("Graph fetching and serialization took {} ms ", Duration.between(startTime, Instant.now()).toMillis());

		return result.toString(2);
	}

	private static @NotNull JSONArray getFacetFieldsArr(QueryResultsDto queryResults) {
		JSONArray facetFieldsArr = new JSONArray();
		for (FacetField ff : queryResults.responseFacetFields()) {
			JSONObject ffObj = new JSONObject();
			ffObj.put("name", ff.getName());
			ffObj.put("valueCount", ff.getValueCount());
			JSONArray ffValArr = new JSONArray();
			for (FacetField.Count ffVal : ff.getValues()) {
				JSONObject ffValObj = new JSONObject();
				ffValObj.put("name", ffVal.getName());
				ffValObj.put("count", ffVal.getCount());
				ffValArr.put(ffValObj);
			}
			ffObj.put("values", ffValArr);
			facetFieldsArr.put(ffObj);
		}
		return facetFieldsArr;
	}

	/**
	 * Builds the request URI using the configured base URL from {@code entrystore.baseurl.folder}.
	 * This ensures the URI always uses the canonical public base URL regardless of how the request
	 * arrived (e.g., via reverse proxy with or without X-Forwarded-* headers).
	 *
	 * @return Request URI with scheme, host, and port from the configured base URL.
	 */
	private String buildRequestUri() {
		var repositoryURL = repositoryManager.getRepositoryURL();
		if (repositoryURL == null) {
			throw new InternalServerErrorException(
					"Repository base URL is not configured; check 'entrystore.baseurl.folder'");
		}
		String baseUrl = repositoryURL.toExternalForm();
		if (baseUrl.endsWith("/")) {
			baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		}

		var attributes = RequestContextHolder.getRequestAttributes();
		if (!(attributes instanceof ServletRequestAttributes servletAttrs)) {
			throw new InternalServerErrorException(
					"Cannot build request URI: no servlet request context available");
		}
		HttpServletRequest request = servletAttrs.getRequest();
		String query = request.getQueryString();

		return baseUrl + request.getServletPath() + (query != null ? "?" + query : "");
	}
}
