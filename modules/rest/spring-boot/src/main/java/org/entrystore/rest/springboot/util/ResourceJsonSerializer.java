/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.springboot.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Model;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.Metadata;
import org.entrystore.PrincipalManager;
import org.entrystore.Resource;
import org.entrystore.User;
import org.entrystore.impl.DataImpl;
import org.entrystore.impl.RDFResource;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.impl.StringResource;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.rest.springboot.model.api.ListFilter;
import org.entrystore.rest.springboot.model.exception.BadRequestException;
import org.entrystore.rest.springboot.service.auth.LoginAttemptService;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.entrystore.EntryType.Link;
import static org.entrystore.EntryType.LinkReference;
import static org.entrystore.EntryType.Local;
import static org.entrystore.EntryType.Reference;
import static org.entrystore.GraphType.SystemContext;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceJsonSerializer {

	public final static JSONObject IMMUTABLE_EMPTY_JSONOBJECT = new JSONObject(Collections.EMPTY_MAP);

	private final PrincipalManager pm;
	private final RepositoryManagerImpl repositoryManager;
	private final LoginAttemptService loginAttemptService;

	public JSONObject serializeResourceGroup(Resource resource, String rdfFormat) {
		JSONObject resourceObj = new JSONObject();
		if (resource instanceof Group group) {
			try {
				resourceObj.put("name", group.getName());
				JSONArray userArray = new JSONArray();
				for (User u : group.members()) {
					JSONObject childJSON = new JSONObject();
					childJSON.put("entryId", u.getEntry().getId());
					childJSON.put("name", u.getName());
					try {
						if (u.isDisabled()) {
							childJSON.put("disabled", true);
						}
					} catch (AuthorizationException ae) {
						log.debug("Not allowed to read disabled status of [{}]", u.getEntry().getEntryURI());
					}

					JSONObject childInfo = GraphUtil.serializeGraphToJson(u.getEntry().getGraph(), rdfFormat);
					childJSON.accumulate("info", childInfo);

					JSONArray rights = this.serializeRights(u.getEntry());
					childJSON.put("rights", rights);
					try {
						JSONObject childMd = GraphUtil.serializeGraphToJson(u.getEntry().getLocalMetadata().getGraph(), rdfFormat);
						childJSON.accumulate(RepositoryProperties.MD_PATH, childMd);
					} catch (AuthorizationException ae) {
						//childJSON.accumulate("noAccessToMetadata", true);
						//TODO: Replaced by using "rights" in json, do something else in this catch-clause
					}

					// Relations for every user in this group.
					Model childRelationsGraph = u.getEntry().getRelations();
					if (childRelationsGraph != null) {
						JSONObject childRelationObj = GraphUtil.serializeGraphToJson(childRelationsGraph, rdfFormat);
						childJSON.accumulate(RepositoryProperties.RELATION, childRelationObj);
					}
					userArray.put(childJSON);
				}
				resourceObj.put("children", userArray);
			} catch (AuthorizationException ae) {
				//jdilObj.accumulate("noAccessToResource", true);
				//TODO: Replaced by using "rights" in json, do something else in this catch-clause
			}
		} else {
			throw new IllegalArgumentException("Resource not instance of Group");
		}
		return resourceObj;
	}

	public JSONObject serializeResourceUser(Resource resource) {
		JSONObject resourceObj = new JSONObject();
		if (resource instanceof User user) {
			try {
				resourceObj.put("name", user.getName());

				Context homeContext = user.getHomeContext();
				if (homeContext != null) {
					resourceObj.put("homecontext", homeContext.getEntry().getId());
				}

				String prefLang = user.getLanguage();
				if (prefLang != null) {
					resourceObj.put("language", prefLang);
				}

				if (user.isDisabled()) {
					resourceObj.put("disabled", true);
				}

				Instant lockedUntil = loginAttemptService.getLockedUntil(user.getName().toLowerCase());
				if (lockedUntil != null) {
					resourceObj.put("disabledUntil", lockedUntil);
				}

				JSONObject customProperties = new JSONObject();
				for (Map.Entry<String, String> propEntry : user.getCustomProperties().entrySet()) {
					customProperties.put(propEntry.getKey(), propEntry.getValue());
				}
				resourceObj.put("customProperties", customProperties);
			} catch (AuthorizationException ae) {
				//jdilObj.accumulate("noAccessToResource", true);
				// TODO: Replaced by using "rights" in json, do something else in this catch-clause
			}
		} else {
			throw new IllegalArgumentException("Resource not instance of User");
		}
		return resourceObj;
	}

	/**
	 * Loads a single list child by the id embedded in its URI, logging (like the previous inline
	 * loop) when the referenced child is missing. Returns null if the child does not exist.
	 */
	private Entry loadListChild(org.entrystore.List list, URI uri, ContextManager cm) {
		String u = uri.toString();
		String id = u.substring(u.lastIndexOf('/') + 1);
		Entry childEntry = list.getEntry().getContext().get(id);
		if (childEntry == null) {
			log.warn("Child resource [{}] in context [{}] does not exist, but is referenced by a list.", id, cm.getURI());
		}
		return childEntry;
	}

	public JSONObject serializeResourceList(Resource resource, ListParams params, String rdfFormat) {
		ContextManager cm = repositoryManager.getContextManager();

		JSONObject resourceObj = new JSONObject();
		if (resource instanceof org.entrystore.List list) {
			int limit = Math.min(params.limit(), 100);
			int offset = Math.max(params.offset(), 0);

			try {
				JSONArray childrenArray = new JSONArray();

				// long math avoids int overflow when a client supplies a very large offset
				long maxPos = limit == 0 ? Long.MAX_VALUE : (long) offset + limit;

				List<URI> childrenURIs = list.getChildren();

				// D2: the "allUnsorted" id list is derived from the URIs by substring - no entry load.
				Set<String> childrenIDs = new HashSet<>();
				for (URI uri : childrenURIs) {
					String u = uri.toString();
					childrenIDs.add(u.substring(u.lastIndexOf('/') + 1));
				}

				// Sorting needs the whole (loaded) list; otherwise we only load the requested page.
				// The <501 cap uses the total child count (the previous code capped on the loaded
				// count, which is the same when nothing is missing).
				boolean doSort = params.sort() != null && childrenURIs.size() < 501;
				List<Entry> childrenEntries = new ArrayList<>();

				if (doSort) {
					for (URI uri : childrenURIs) {
						Entry childEntry = loadListChild(list, uri, cm);
						if (childEntry != null) {
							childrenEntries.add(childEntry);
						}
					}

					sortChildrenEntries(childrenEntries, params);

					// paginate the sorted list to [offset, maxPos)
					int from = Math.min(offset, childrenEntries.size());
					int to = (int) Math.min(maxPos, childrenEntries.size());
					childrenEntries = new ArrayList<>(childrenEntries.subList(from, Math.max(from, to)));
				} else {
					if (params.sort() != null) {
						log.warn("Ignoring sort parameter for performance reasons because list has more than 500 children");
					}
					// D2: load only the requested page of non-null children, skipping the first
					// `offset` successfully-loaded children (matching the previous pagination over the
					// loaded list, but without loading the tail beyond the page).
					int skipped = 0;
					for (URI uri : childrenURIs) {
						if (limit != 0 && childrenEntries.size() >= limit) {
							break;
						}
						Entry childEntry = loadListChild(list, uri, cm);
						if (childEntry == null) {
							continue;
						}
						if (skipped < offset) {
							skipped++;
							continue;
						}
						childrenEntries.add(childEntry);
					}
				}

				for (Entry childEntry : childrenEntries) {
					JSONObject childJSON = new JSONObject();

					/*
					 * Children-rights
					 */
					JSONArray rights = this.serializeRights(childEntry);
					childJSON.put("rights", rights);

					String uri = childEntry.getEntryURI().toString();
					String entryId = uri.substring(uri.lastIndexOf('/') + 1);
					childJSON.put("entryId", entryId);
					GraphType childGraphType = childEntry.getGraphType();
					EntryType childEntryType = childEntry.getEntryType();
					if ((childGraphType == GraphType.Context || childGraphType == SystemContext) && childEntryType == Local) {
						childJSON.put("name", cm.getName(childEntry.getResourceURI()));
					} else if (childGraphType == GraphType.List) {
						Resource childResource = childEntry.getResource();
						if (childResource instanceof org.entrystore.List childList) {
							try {
								childJSON.put("size", childList.getChildren().size());
							} catch (AuthorizationException ae) {
								//TODO: Should we do something here?
							}
						} else {
							log.warn("Entry has ResourceType.List but the resource is either null or not an instance of List");
						}
					} else if (childGraphType == GraphType.User && childEntryType == Local) {
						childJSON.put("name", ((User) childEntry.getResource()).getName());
					} else if (childGraphType == GraphType.Group && childEntryType == Local) {
						childJSON.put("name", ((Group) childEntry.getResource()).getName());
					}

					appendMetadataInfoAndRelations(childEntry, childJSON, rdfFormat, false);

					childrenArray.put(childJSON);
				}

				resourceObj.put("children", childrenArray);
				resourceObj.put("size", childrenURIs.size());
				resourceObj.put("limit", limit);
				resourceObj.put("offset", offset);

				JSONArray childrenIDArray = new JSONArray();
				for (String id : childrenIDs) {
					childrenIDArray.put(id);
				}
				resourceObj.put("allUnsorted", childrenIDArray);
			} catch (AuthorizationException ae) {
				//jdilObj.accumulate("noAccessToResource", true);
				//TODO: Replaced by using "rights" in json, do something else in this catch-clause
			}
		} else {
			throw new IllegalArgumentException("Resource not instance of List");
		}
		return resourceObj;
	}

	/**
	 * Appends the cached-external-metadata, local-metadata, entry-info and relations sections of an
	 * entry to {@code childJSON}. With {@code flagNoAccess=true} (search behavior) the two metadata
	 * sections share one guard that records a {@code noAccessToMetadata} flag (an
	 * {@link AuthorizationException} on external metadata also skips local metadata), while the
	 * entry-info and relations sections each record {@code noAccessToEntryInfo} /
	 * {@code noAccessToRelations}. With {@code flagNoAccess=false} (list behavior) a metadata
	 * {@link AuthorizationException} is swallowed silently and the info/relations sections are left
	 * unguarded so the exception propagates to the caller.
	 */
	public void appendMetadataInfoAndRelations(Entry entry, JSONObject childJSON, String rdfFormat, boolean flagNoAccess) {
		try {
			EntryType entryType = entry.getEntryType();
			if (entryType == Reference || entryType == LinkReference) {
				Metadata cachedExternalMetadata = entry.getCachedExternalMetadata();
				if (cachedExternalMetadata != null) {
					Model cachedExternalMetadataGraph = cachedExternalMetadata.getGraph();
					if (cachedExternalMetadataGraph != null) {
						childJSON.accumulate(RepositoryProperties.EXTERNAL_MD_PATH,
								GraphUtil.serializeGraphToJson(cachedExternalMetadataGraph, rdfFormat));
					}
				}
			}

			if (entryType == Local || entryType == Link || entryType == LinkReference) {
				Metadata localMetadata = entry.getLocalMetadata();
				if (localMetadata != null) {
					Model localMetadataGraph = localMetadata.getGraph();
					if (localMetadataGraph != null) {
						childJSON.accumulate(RepositoryProperties.MD_PATH,
								GraphUtil.serializeGraphToJson(localMetadataGraph, rdfFormat));
					}
				}
			}
		} catch (AuthorizationException ae) {
			if (flagNoAccess) {
				childJSON.accumulate("noAccessToMetadata", true);
			}
		}

		if (flagNoAccess) {
			try {
				JSONObject info = GraphUtil.serializeGraphToJson(entry.getGraph(), rdfFormat);
				childJSON.accumulate("info", Objects.requireNonNullElseGet(info, JSONObject::new));
			} catch (AuthorizationException ae) {
				childJSON.accumulate("noAccessToEntryInfo", true);
			}

			try {
				Model relationsGraph = entry.getRelations();
				if (relationsGraph != null) {
					childJSON.accumulate(RepositoryProperties.RELATION,
							GraphUtil.serializeGraphToJson(relationsGraph, rdfFormat));
				}
			} catch (AuthorizationException ae) {
				childJSON.accumulate("noAccessToRelations", true);
			}
		} else {
			childJSON.accumulate("info", GraphUtil.serializeGraphToJson(entry.getGraph(), rdfFormat));

			Model relationsGraph = entry.getRelations();
			if (relationsGraph != null) {
				childJSON.accumulate(RepositoryProperties.RELATION,
						GraphUtil.serializeGraphToJson(relationsGraph, rdfFormat));
			}
		}
	}

	/**
	 * Sorts {@code children} in place according to the sort/lang/prio/order list parameters.
	 * Applying the &gt;500-children performance cap is the caller's decision — the two list
	 * serializers count children differently.
	 */
	public static void sortChildrenEntries(List<Entry> children, ListParams params) {
		Date before = new Date();
		GraphType prioritizedGraphType = null;
		if (params.prio() != null) {
			try {
				prioritizedGraphType = GraphType.valueOf(params.prio());
			} catch (IllegalArgumentException e) {
				throw new BadRequestException("Invalid value for parameter 'prio': " + params.prio());
			}
		}
		String sortType = params.sort();
		if ("title".equalsIgnoreCase(sortType)) {
			EntryUtil.sortAfterTitle(children, params.lang(), params.ascendingOrder(), prioritizedGraphType);
		} else if ("modified".equalsIgnoreCase(sortType)) {
			EntryUtil.sortAfterModificationDate(children, params.ascendingOrder(), prioritizedGraphType);
		} else if ("created".equalsIgnoreCase(sortType)) {
			EntryUtil.sortAfterCreationDate(children, params.ascendingOrder(), prioritizedGraphType);
		} else if ("size".equalsIgnoreCase(sortType)) {
			EntryUtil.sortAfterFileSize(children, params.ascendingOrder(), prioritizedGraphType);
		}
		long sortDuration = new Date().getTime() - before.getTime();
		log.debug("List sorting took {} ms", sortDuration);
	}

	public JSONObject serializeResourceGraph(Resource resource, String rdfFormat) {
		if (resource instanceof RDFResource rdf) {
			if (rdf.getGraph() != null) {
				return GraphUtil.serializeGraphToJson(rdf.getGraph(), rdfFormat);
			} else {
				throw new IllegalArgumentException("Graph is empty in RDFResource");
			}
		} else {
			throw new IllegalArgumentException("Resource not instance of RDFResource");
		}
	}

	public JSONObject serializeResourcePipeline(Resource resource, String rdfFormat) {
		return serializeResourceGraph(resource, rdfFormat);
	}

	public String serializeResourceString(Resource resource) {
		if (resource instanceof StringResource string) {
			return string.getString();
		} else {
			throw new IllegalArgumentException("Resource not instance of StringResource");
		}
	}

	public JSONObject serializeResourceNone(Resource resource) {
		JSONObject resourceObj = new JSONObject();
		DataImpl data = new DataImpl(resource.getEntry());
		String digest = data.readDigest();
		if (digest != null) {
			resourceObj.put("sha256", digest);
		} else {
			log.debug("Digest does not exist for [{}]", resource.getURI());
		}
		return resourceObj;
	}

	public JSONArray serializeResourceContext(Resource resource) {
		JSONArray array = new JSONArray();
		if (resource instanceof Context context) {
			Set<URI> uris = context.getEntries();
			for (URI u : uris) {
				String uriString = u.toASCIIString();
				array.put(uriString.substring(uriString.lastIndexOf('/') + 1));
			}
		} else {
			throw new IllegalArgumentException("Resource not instance of Context");
		}
		return array;
	}

	public JSONArray serializeResourceSystemContext(Resource resource) {
		return serializeResourceContext(resource);
	}

	public JSONArray serializeRights(Entry entry) throws JSONException {
		JSONArray resourceObj = new JSONArray();
		Set<PrincipalManager.AccessProperty> rights = pm.getRights(entry);
		rights.forEach(ap -> {
			switch (ap) {
				case Administer -> resourceObj.put("administer");
				case WriteMetadata -> resourceObj.put("writemetadata");
				case WriteResource -> resourceObj.put("writeresource");
				case ReadMetadata -> resourceObj.put("readmetadata");
				case ReadResource -> resourceObj.put("readresource");
			}
		});
		return resourceObj;
	}

	public record ListParams(
		String sort,
		String lang,
		String prio,
		String desc,
		boolean ascendingOrder,
		int offset,
		int limit) {

		public ListParams(ListFilter filter) {
			this(
				filter.sort(),
				filter.lang(),
				filter.prio(),
				filter.desc(),
				!"desc".equalsIgnoreCase(filter.order()),
				Integer.parseInt(Optional.ofNullable(filter.offset()).orElse("0")),
				Integer.parseInt(Optional.ofNullable(filter.limit()).orElse("0"))
			);
		}

		/** Params with offset/limit left unparsed (defaulted to 0) — for callers that ignore pagination. */
		public static ListParams withoutPagination(ListFilter filter) {
			return new ListParams(filter.sort(), filter.lang(), filter.prio(), filter.desc(),
					!"desc".equalsIgnoreCase(filter.order()), 0, 0);
		}
	}
}
