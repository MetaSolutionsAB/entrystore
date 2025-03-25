package org.entrystore.rest.serializer;

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
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.impl.StringResource;
import org.entrystore.repository.util.EntryUtil;
import org.entrystore.rest.auth.UserTempLockoutCache;
import org.entrystore.rest.auth.UserTempLockoutCache.UserTemporaryLockout;
import org.entrystore.rest.util.GraphUtil;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.data.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.entrystore.EntryType.Link;
import static org.entrystore.EntryType.LinkReference;
import static org.entrystore.EntryType.Local;
import static org.entrystore.EntryType.Reference;
import static org.entrystore.GraphType.SystemContext;
import static org.entrystore.PrincipalManager.AccessProperty.Administer;
import static org.entrystore.PrincipalManager.AccessProperty.ReadMetadata;
import static org.entrystore.PrincipalManager.AccessProperty.ReadResource;
import static org.entrystore.PrincipalManager.AccessProperty.WriteMetadata;
import static org.entrystore.PrincipalManager.AccessProperty.WriteResource;

public class ResourceJsonSerializer {

	private final static Logger log = LoggerFactory.getLogger(ResourceJsonSerializer.class);
	public final static JSONObject IMMUTABLE_EMPTY_JSONOBJECT = new JSONObject(Collections.EMPTY_MAP);

	private final PrincipalManager pm;
	private final ContextManager cm;
	private final UserTempLockoutCache userTempLockoutCache;

	public ResourceJsonSerializer(PrincipalManager pm, ContextManager cm, UserTempLockoutCache userTempLockoutCache) {
		this.pm = pm;
		this.cm = cm;
		this.userTempLockoutCache = userTempLockoutCache;
	}

	public JSONObject serializeResourceGroup(Resource resource, MediaType rdfFormat) {
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

					//Relations for every user in this group.
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

				UserTemporaryLockout lockedOutUser = userTempLockoutCache.getLockedOutUser(user.getName());
				if (lockedOutUser != null) {
					resourceObj.put("disabledUntil", lockedOutUser.disableUntil());
				}

				JSONObject customProperties = new JSONObject();
				for (java.util.Map.Entry<String, String> propEntry : user.getCustomProperties().entrySet()) {
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

	public JSONObject serializeResourceList(Resource resource, ResourceJsonSerializer.ListParams params, MediaType rdfFormat) {
		JSONObject resourceObj = new JSONObject();
		if (resource instanceof org.entrystore.List list) {
			int limit = Math.min(params.limit(), 100);
			int offset = Math.max(params.offset(), 0);

			try {
				JSONArray childrenArray = new JSONArray();

				int maxPos = offset + limit;
				if (limit == 0) {
					maxPos = Integer.MAX_VALUE;
				}

				List<URI> childrenURIs = list.getChildren();
				Set<String> childrenIDs = new HashSet<>();
				List<Entry> childrenEntries = new ArrayList<>();

				for (URI uri : childrenURIs) {
					String u = uri.toString();
					String id = u.substring(u.lastIndexOf('/') + 1);
					childrenIDs.add(id);
					Entry childEntry = list.getEntry().getContext().get(id);
					if (childEntry != null) {
						childrenEntries.add(childEntry);
					} else {
						log.warn("Child resource [{}] in context [{}] does not exist, but is referenced by a list.", id, cm.getURI());
					}
				}

				if (params.sort() != null && (childrenEntries.size() < 501)) {
					Date before = new Date();
					GraphType prioritizedGraphType = null;
					if (params.prio() != null) {
						prioritizedGraphType = GraphType.valueOf(params.prio());
					}
					String sortType = params.sort();
					if ("title".equalsIgnoreCase(sortType)) {
						EntryUtil.sortAfterTitle(childrenEntries, params.lang(), params.ascendingOrder(), prioritizedGraphType);
					} else if ("modified".equalsIgnoreCase(sortType)) {
						EntryUtil.sortAfterModificationDate(childrenEntries, params.ascendingOrder(), prioritizedGraphType);
					} else if ("created".equalsIgnoreCase(sortType)) {
						EntryUtil.sortAfterCreationDate(childrenEntries, params.ascendingOrder(), prioritizedGraphType);
					} else if ("size".equalsIgnoreCase(sortType)) {
						EntryUtil.sortAfterFileSize(childrenEntries, params.ascendingOrder(), prioritizedGraphType);
					}
					long sortDuration = new Date().getTime() - before.getTime();
					log.debug("List resource sorting took " + sortDuration + " ms");
				} else if (params.sort() != null) {
					log.warn("Ignoring sort parameter for performance reasons because list has more than 500 children");
				}

				//for (int i = 0; i < childrenURIs.size(); i++) {
				for (int i = offset; i < maxPos && i < childrenEntries.size(); i++) {
					JSONObject childJSON = new JSONObject();
					Entry childEntry = childrenEntries.get(i);

					/*
					 * Children-rights
					 */
//					this.accumulateRights(childEntry, childJSON);
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

					try {
						EntryType entryType = childEntry.getEntryType();
						if (entryType == Reference || entryType == LinkReference) {
							Metadata cachedExternalMetaData = childEntry.getCachedExternalMetadata();
							if (cachedExternalMetaData != null) {
								Model cachedExternalMetaDataGraph = cachedExternalMetaData.getGraph();
								if (cachedExternalMetaDataGraph != null) {
									JSONObject childCachedExternalMetaDataJSON = GraphUtil.serializeGraphToJson(cachedExternalMetaDataGraph, rdfFormat);
									childJSON.accumulate(RepositoryProperties.EXTERNAL_MD_PATH, childCachedExternalMetaDataJSON);
								}
							}
						}

						if (entryType == Local || entryType == Link || entryType == LinkReference) {
							Metadata localMetadata = childEntry.getLocalMetadata();
							if (localMetadata != null) {
								Model localMetadataGraph = localMetadata.getGraph();
								if (localMetadataGraph != null) {
									JSONObject localMDJSON = GraphUtil.serializeGraphToJson(localMetadataGraph, rdfFormat);
									childJSON.accumulate(RepositoryProperties.MD_PATH, localMDJSON);
								}
							}
						}
					} catch (AuthorizationException e) {
						//childJSON.accumulate("noAccessToMetadata", true);
						//ODO: Replaced by using "rights" in json, do something else in this catch-clause
						// childJSON.accumulate(RepositoryProperties.MD_PATH_STUB, new JSONObject());
					}

					Model childEntryGraph = childEntry.getGraph();
					JSONObject childInfo = GraphUtil.serializeGraphToJson(childEntryGraph, rdfFormat);
					childJSON.accumulate("info", childInfo);

					Model childRelationsGraph = childEntry.getRelations();
					if (childRelationsGraph != null) {
						JSONObject childRelationObj = GraphUtil.serializeGraphToJson(childRelationsGraph, rdfFormat);
						childJSON.accumulate(RepositoryProperties.RELATION, childRelationObj);
					}

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

	public JSONObject serializeResourceGraph(Resource resource, MediaType rdfFormat) {
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

	public JSONObject serializeResourcePipeline(Resource resource, MediaType rdfFormat) {
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
			for(URI u: uris) {
				String entryId = (u.toASCIIString()).substring((u.toASCIIString()).lastIndexOf('/')+1);
				array.put(entryId);
			}
		} else {
			throw new IllegalArgumentException("Resource not instance of Context");
		}
		return array;
	}

	public JSONArray serializeResourceSystemContext(Resource resource) {
		return serializeResourceContext(resource);
	}

	public void accumulateRights(Entry entry, JSONObject jdilObj) throws JSONException {
		Set<PrincipalManager.AccessProperty> rights = pm.getRights(entry);
		if(rights.size() >0){
			for(PrincipalManager.AccessProperty ap : rights){
				if(ap == Administer)
					jdilObj.append("rights", "administer");
				else if (ap == WriteMetadata)
					jdilObj.append("rights", "writemetadata");
				else if (ap == WriteResource)
					jdilObj.append("rights","writeresource");
				else if (ap == ReadMetadata)
					jdilObj.append("rights","readmetadata");
				else if (ap == ReadResource)
					jdilObj.append("rights","readresource");
			}
		}
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

		public ListParams(Map<String, String> params) {
			this(
				params.get("sort"),
				params.get("lang"),
				params.get("prio"),
				params.get("desc"),
				!"desc".equalsIgnoreCase(params.get("order")),
				Integer.parseInt(params.getOrDefault("offset", "0")),
				Integer.parseInt(params.getOrDefault("limit", "0"))
			);
		}
	}
}
