/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

package org.entrystore.repository.util;

import com.google.common.collect.Multimap;
import lombok.Getter;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.entrystore.AuthorizationException;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.Resource;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.repository.RepositoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Helper methods to make Entry handling easier, mostly sorting methods.
 *
 * @author Hannes Ebner
 */
public class EntryUtil {

	static Logger log = LoggerFactory.getLogger(EntryUtil.class);

	static ValueFactory valueFactory = SimpleValueFactory.getInstance();

	/**
	 * Sorts a list of entries after the modification date.
	 *
	 * @param entries
	 *            The list of entries to sort.
	 * @param ascending
	 *            True if the entry with the earliest date should come first.
	 * @param prioritizedResourceType
	 *            Determines which ResourceType should always have higher priority
	 *            than entries with a different one.
	 */
	public static void sortAfterModificationDate(List<Entry> entries, final boolean ascending, final GraphType prioritizedResourceType) {
		entries.sort((e1, e2) -> {
			int result = 0;
			if (e1 != null && e2 != null) {
				result = e1.getModifiedDate().compareTo(e2.getModifiedDate());
				if (!ascending) {
					result *= -1;
				}
			}
			return result;
		});
		prioritizeBuiltinType(entries, prioritizedResourceType, true);
	}

	/**
	 * Sorts a list of entries after the creation date.
	 *
	 * @param entries
	 *            The list of entries to sort.
	 * @param ascending
	 *            True if the entry with the earliest date should come first.
	 * @param prioritizedResourceType
	 *            Determines which ResourceType should always have higher priority
	 *            than entries with a different one.
	 */
	public static void sortAfterCreationDate(List<Entry> entries, final boolean ascending, final GraphType prioritizedResourceType) {
		entries.sort((e1, e2) -> {
			int result = 0;
			if (e1 != null && e2 != null) {
				result = e1.getCreationDate().compareTo(e2.getCreationDate());
				if (!ascending) {
					result *= -1;
				}
			}
			return result;
		});
		prioritizeBuiltinType(entries, prioritizedResourceType, true);
	}

	/**
	 * Sorts a list of entries after the file size. Folders are listed before
	 * entries with ResourceType.None and they are sorted among themselves after
	 * the amount of children they contain.
	 *
	 * @param entries
	 *            The list of entries to sort.
	 * @param ascending
	 *            True if the entry with the smallest size should come first.
	 * @param prioritizedResourceType
	 *            Determines which ResourceType should always have higher priority
	 *            than entries with a different one.
	 */
	public static void sortAfterFileSize(List<Entry> entries, final boolean ascending, final GraphType prioritizedResourceType) {
		entries.sort((e1, e2) -> {
			int result = 0;
			if (e1 != null && e2 != null) {
				GraphType e1BT = e1.getGraphType();
				GraphType e2BT = e2.getGraphType();
				if (GraphType.None.equals(e1BT) && GraphType.None.equals(e2BT)) {
					long size1 = e1.getFileSize();
					long size2 = e2.getFileSize();
					if (size1 < size2) {
						result = -1;
					} else if (size1 > size2) {
						result = 1;
					}
				} else if (GraphType.List.equals(e1BT) && GraphType.List.equals(e2BT)) {
					Resource e1Res = e1.getResource();
					Resource e2Res = e2.getResource();
					if (e1Res == null) {
						log.warn("No resource found for list: {}", e1.getEntryURI());
						return 0;
					}
					if (e2Res == null) {
						log.warn("No resource found for list: {}", e2.getEntryURI());
						return 0;
					}
					org.entrystore.List e1List = (org.entrystore.List) e1Res;
					org.entrystore.List e2List = (org.entrystore.List) e2Res;
					int size1 = e1List.getChildren().size();
					int size2 = e2List.getChildren().size();
					if (size1 < size2) {
						result = -1;
					} else if (size1 > size2) {
						result = 1;
					}
				}
				if (!ascending) {
					result *= -1;
				}
			}
			return result;
		});
		prioritizeBuiltinType(entries, prioritizedResourceType, true);
	}

	/**
	 * Sorts a list of entries after its titles.
	 *
	 * @param entries
	 *            The list of entries to sort.
	 * @param language
	 *            The language of the title to prioritize. May be null if any
	 *            title string should be taken. If no titles match the desired
	 *            language, any title is used as fallback for sorting.
	 * @param ascending
	 *            True if the entries should be sorted A-Z. Does not take the
	 *            locale into consideration.
	 * @param prioritizedResourceType
	 *            Determines which ResourceType should always have higher priority
	 *            than entries with a different one.
	 */
	public static void sortAfterTitle(List<Entry> entries, final String language, final boolean ascending, final GraphType prioritizedResourceType) {
		entries.sort((e1, e2) -> {
			int result = 0;
			if (e1 != null && e2 != null) {
				String title1 = getTitle(e1, language);
				String title2 = getTitle(e2, language);
				if (title1 != null && title2 != null) {
					result = title1.compareToIgnoreCase(title2);
				} else if (title1 == null && title2 != null) {
					result = 1;
				} else if (title1 != null) {
					result = -1;
				}
				if (!ascending) {
					result *= -1;
				}
			}
			return result;
		});
		prioritizeBuiltinType(entries, prioritizedResourceType, true);
	}

	/**
	 * Reorders the list of entries with the given ResourceType first or last,
	 * depending on the boolean parameter.
	 *
	 * @param entries
	 *            The list of entries to reorder.
	 * @param resourceType
	 *            The ResourceType to be prioritized.
	 * @param top
	 *            Determines whether the entries with the prioritized
	 *            ResourceType should come first or last in the list.
	 */
	public static void prioritizeBuiltinType(List<Entry> entries, final GraphType resourceType, final boolean top) {
		if (entries == null || resourceType == null) {
			return;
		}

		entries.sort((e1, e2) -> {
			int result = 0;
			if (e1 != null && e2 != null) {
				GraphType e1BT = e1.getGraphType();
				GraphType e2BT = e2.getGraphType();
				if (resourceType.equals(e1BT) && !resourceType.equals(e2BT)) {
					result = -1;
				} else if (!resourceType.equals(e1BT) && resourceType.equals(e2BT)) {
					result = 1;
				}
				if (!top) {
					result *= -1;
				}
			}
			return result;
		});
	}

	/**
	 * Requests a literal value from a metadata graph.
	 *
	 * @param graph
	 *            The graph to be used to search for the title.
	 * @param resourceURI
	 *            The root resource to be used for matching.
	 * @param language
	 *            The language to prioritize. May be null if any title string
	 *            should be taken. If no titles match the desired language, any
	 *            title is used as fallback.
	 * @return Returns a literal value (whichever matches first)
	 *         from a specific graph with the given resource URI as root node.
	 *         If no titles exist null is returned.
	 */
	public static String getLabel(Model graph, URI resourceURI, Set<IRI> predicates, String language) {
		String fallback = null;
		if (graph != null && resourceURI != null) {
			IRI resURI = valueFactory.createIRI(resourceURI.toString());
			for (IRI titlePred : predicates) {
				for (Statement statement : graph.filter(resURI, titlePred, null)) {
					Value value = statement.getObject();
					Literal lit = null;
					if (value instanceof Literal) {
						lit = (Literal) value;
					} else if (value instanceof org.eclipse.rdf4j.model.Resource) {
						Iterator<Statement> indirectLables = graph.filter((org.eclipse.rdf4j.model.Resource) value, RDF.VALUE, null).iterator();
						if (indirectLables.hasNext()) {
							Value indirectValue = indirectLables.next().getObject();
							if (indirectValue instanceof Literal) {
								lit = (Literal) indirectValue;
							}
						}
					}
					if (lit != null) {
						if (language != null) {
							if (lit.getLanguage().isPresent() && lit.getLanguage().get().equalsIgnoreCase(language)) {
								return lit.stringValue();
							} else {
								fallback = lit.stringValue();
							}
						} else {
							return lit.stringValue();
						}
					}
				}
			}
		}
		return fallback;
	}

	public static String getLabel(Model graph, URI resourceURI, IRI predicate, String language) {
		Set<IRI> predicates = new HashSet<>();
		predicates.add(predicate);
		return getLabel(graph, resourceURI, predicates, language);
	}

	public static IRI getResourceAsURI(Model graph, URI resourceURI, IRI predicate) {
		if (graph != null && resourceURI != null) {
			IRI resURI = valueFactory.createIRI(resourceURI.toString());
			for (Statement statement : graph.filter(resURI, predicate, null)) {
				Value value = statement.getObject();
				if (value instanceof IRI) {
					return (IRI) value;
				}
			}
		}
		return null;
	}

	public static String getResource(Model graph, URI resourceURI, IRI predicate) {
		IRI result = getResourceAsURI(graph, resourceURI, predicate);
		if (result != null) {
			return result.stringValue();
		}
		return null;
	}

	/**
	 * Requests the title of an Entry.
	 *
	 * @param graph
	 *            The graph to be used to search for the title.
	 * @param resourceURI
	 *            The root resource to be used for matching.
	 * @param language
	 *            The language to prioritize. May be null if any title string
	 *            should be taken. If no titles match the desired language, any
	 *            title is used as fallback.
	 * @return Returns the dcterms:title or dc:title (whichever matches first)
	 *         from a specific graph with the given resource URI as root node.
	 *         If no titles exist null is returned.
	 */
	public static String getTitle(Model graph, URI resourceURI, String language) {
		if (graph != null && resourceURI != null) {
			Set<IRI> titlePredicates = new HashSet<>();
			titlePredicates.add(valueFactory.createIRI(NS.dcterms + "title"));
			titlePredicates.add(valueFactory.createIRI(NS.dc + "title"));
			return getLabel(graph, resourceURI, titlePredicates, language);
		}
		return null;
	}

	public static String getTitle(Entry entry, String language) {
		if (entry != null) {
			try {
				return getTitle(entry.getMetadataGraph(), entry.getResourceURI(), language);
			} catch (AuthorizationException ae) {
				log.debug("AuthorizationException: " + ae.getMessage());
			}
		}
		return null;
	}

	public static String getName(Entry entry) {
		String result = null;
		if (entry != null) {
			String name = getLabel(entry.getMetadataGraph(), entry.getResourceURI(), valueFactory.createIRI(NS.foaf + "name"), null);
			if (name != null) {
				return name;
			}
			String givenName = getLabel(entry.getMetadataGraph(), entry.getResourceURI(), valueFactory.createIRI(NS.foaf + "givenName"), null);
			String familyName = getLabel(entry.getMetadataGraph(), entry.getResourceURI(), valueFactory.createIRI(NS.foaf + "familyName"), null);
			if (givenName != null) {
				result = givenName;
			}
			if (familyName != null) {
				if (result != null) {
					result += " " + familyName;
				} else {
					result = familyName;
				}
			}
		}
		return result;
	}

	public static String getStructuredName(Entry entry) {
		String result = null;
		if (entry != null) {
			Set<IRI> foafFirstName = new HashSet<>();
			Set<IRI> foafSurname = new HashSet<>();
			foafFirstName.add(valueFactory.createIRI(NS.foaf + "firstName"));
			foafFirstName.add(valueFactory.createIRI(NS.foaf + "givenName"));
			foafSurname.add(valueFactory.createIRI(NS.foaf + "surname"));
			foafSurname.add(valueFactory.createIRI(NS.foaf + "lastName"));
			foafSurname.add(valueFactory.createIRI(NS.foaf + "familyName"));
			String firstName = getLabel(entry.getMetadataGraph(), entry.getResourceURI(), foafFirstName, null);
			String surname = getLabel(entry.getMetadataGraph(), entry.getResourceURI(), foafSurname, null);
			if (surname != null) {
				result = surname;
			}
			if (firstName != null) {
				if (result != null) {
					result += ";" + firstName;
				} else {
					result = firstName;
				}
			}
		}
		return result;
	}

	public static String getFirstName(Entry entry) {
		if (entry != null) {
			Set<IRI> foafFN = new HashSet<>();
			foafFN.add(valueFactory.createIRI(NS.foaf + "givenName"));
			foafFN.add(valueFactory.createIRI(NS.foaf + "firstName"));
			return getLabel(entry.getMetadataGraph(), entry.getResourceURI(), foafFN, null);
		}
		return null;
	}

	public static String getLastName(Entry entry) {
		if (entry != null) {
			Set<IRI> foafLN = new HashSet<>();
			foafLN.add(valueFactory.createIRI(NS.foaf + "surname"));
			foafLN.add(valueFactory.createIRI(NS.foaf + "lastName"));
			foafLN.add(valueFactory.createIRI(NS.foaf + "familyName"));
			return getLabel(entry.getMetadataGraph(), entry.getResourceURI(), foafLN, null);
		}
		return null;
	}

	public static String getEmail(Entry entry) {
		if (entry != null) {
			return getLabel(entry.getMetadataGraph(), entry.getResourceURI(), valueFactory.createIRI(NS.foaf + "mbox"), null);
		}
		return null;
	}

	public static String getMemberOf(Entry entry) {
		if (entry != null) {
			return getResource(entry.getMetadataGraph(), entry.getResourceURI(), valueFactory.createIRI("http://open.vocab.org/terms/isMemberOf"));
		}
		return null;
	}

	public static String getFOAFTitle(Entry entry) {
		if (entry != null) {
			return getLabel(entry.getMetadataGraph(), entry.getResourceURI(), valueFactory.createIRI(NS.foaf + "title"), null);
		}
		return null;
	}

	/**
	 * Retrieves all titles from the metadata of an Entry. Includes cached
	 * external metadata if it exists.
	 *
	 * @param entry
	 *            Entry of which the titles should be returned.
	 * @return Returns all key/value (title/language) pairs for dcterms:title
	 *         and dc:title
	 */
	public static Map<String, Set<String>> getTitles(Entry entry) {
		List<IRI> titlePredicates = new ArrayList<>();
		titlePredicates.add(valueFactory.createIRI(NS.foaf, "name"));
		titlePredicates.add(valueFactory.createIRI(NS.vcard, "fn"));
		titlePredicates.add(valueFactory.createIRI(NS.dcterms, "title"));
		titlePredicates.add(valueFactory.createIRI(NS.dc, "title"));
		titlePredicates.add(valueFactory.createIRI(NS.skos, "prefLabel"));
		titlePredicates.add(valueFactory.createIRI(NS.skos, "altLabel"));
		titlePredicates.add(valueFactory.createIRI(NS.skos, "hiddenLabel"));
		titlePredicates.add(valueFactory.createIRI(NS.rdfs, "label"));
		titlePredicates.add(valueFactory.createIRI(NS.schema, "name"));

		return getLiteralValues(entry, titlePredicates);
	}

	/**
	 * Retrieves all descriptions from the metadata of an Entry. Includes cached
	 * external metadata if it exists.
	 *
	 * @param entry
	 *            Entry from where the descriptions should be returned.
	 * @return Returns all key/value (description/language) pairs for dcterms:description
	 *         and dc:description
	 */
	public static Map<String, Set<String>> getDescriptions(Entry entry) {
		List<IRI> descPreds = new ArrayList<>();
		descPreds.add(valueFactory.createIRI(NS.dcterms, "description"));
		descPreds.add(valueFactory.createIRI(NS.dc, "description"));
		return getLiteralValues(entry, descPreds);
	}

	public static String getDescription(Entry entry, String language) {
		Map<String, Set<String>> descriptions = getDescriptions(entry);

		if (descriptions.isEmpty()) {
			return null;
		}

		String description = null;
		for (String key : descriptions.keySet()) {
			if (language != null) {
				for (String lang : descriptions.get(key)) {
					if (language.equals(lang)) {
						description = key;
						break;
					}
				}
			} else {
				description = key;
			}

			if (description != null) {
				break;
			}
		}

		return description;
	}

	/**
	 * Retrieves all literals of subjects, tags and keywords from the metadata of an Entry.
	 * Includes cached external metadata if it exists.
	 *
	 * @param entry Entry from where the keywords should be returned.
	 * @return Returns all matching literal-language pairs.
	 */
	public static Map<String, Set<String>> getTagLiterals(Entry entry) {
		List<IRI> preds = new ArrayList<>();
		preds.add(valueFactory.createIRI(NS.dcterms, "subject"));
		preds.add(valueFactory.createIRI(NS.dc, "subject"));
		preds.add(valueFactory.createIRI(NS.dcat, "keyword"));
		return getLiteralValues(entry, preds);
	}

	/**
	 * Retrieves all resource values of subjects, tags and keywords from the metadata of an Entry.
	 * Includes cached external metadata if it exists.
	 *
	 * @param entry Entry from where the keywords should be returned.
	 * @return Returns all matching literal-language pairs.
	 */
	public static List<String> getTagResources(Entry entry) {
		Set<IRI> preds = new HashSet<>();
		preds.add(valueFactory.createIRI(NS.dc, "subject"));
		preds.add(valueFactory.createIRI(NS.dcterms, "subject"));
		return getResourceValues(entry, preds);
	}

	/**
	 * Retrieves literals from statements that match a set of predicates.
	 *
	 * @param entry Entry from where the metadata graph should be used for matching.
	 * @param predicates A list of predicates to use for statement matching.
	 * @return Returns all matching literal-language pairs.
	 */
	public static Map<String, Set<String>> getLiteralValues(Entry entry, List<IRI> predicates) {
		if (entry == null || predicates == null) {
			throw new IllegalArgumentException("Parameters must not be null");
		}

		Model graph = null;
		try {
			graph = entry.getMetadataGraph();
		} catch (AuthorizationException ae) {
			log.debug("AuthorizationException: " + ae.getMessage());
		}

		if (graph != null) {
			IRI resourceURI = valueFactory.createIRI(entry.getResourceURI().toString());
			Map<String, Set<String>> result = new LinkedHashMap<>();
			for (IRI pred : predicates) {
				for (Statement statement : graph.filter(resourceURI, pred, null)) {
					Value value = statement.getObject();
					Set<String> valuesSet;

					if (value instanceof Literal lit) {

						valuesSet = result.get(lit.stringValue()) == null ? new HashSet<>() : result.get(lit.stringValue());

						valuesSet.add(lit.getLanguage().orElse(null));
						result.put(lit.stringValue(), valuesSet);
					} else if (value instanceof org.eclipse.rdf4j.model.Resource) {
						Iterator<Statement> stmnts2 = graph.filter((org.eclipse.rdf4j.model.Resource) value, RDF.VALUE, null).iterator();
						if (stmnts2.hasNext()) {
							Value value2 = stmnts2.next().getObject();
							if (value2 instanceof Literal lit2) {
								valuesSet = result.get(lit2.stringValue()) == null ? new HashSet<>() : result.get(lit2.stringValue());
								valuesSet.add(lit2.getLanguage().orElse(null));
								result.put(lit2.stringValue(), valuesSet);
							}
						}
					}
				}
			}
			return result;
		}
		return null;
	}

	/**
	 * Retrieves resource values from statements that match a set of predicates.
	 *
	 * @param entry	Entry from where the metadata graph should be used for matching.
	 * @param predicates A list of predicates to use for statement matching.
	 * @return Returns a list of URIs.
	 */
    public static List<String> getResourceValues(Entry entry, Set<IRI> predicates) {
		if (entry == null || predicates == null) {
			throw new IllegalArgumentException("Parameters must not be null");
		}

		Model graph = null;
        try {
            graph = entry.getMetadataGraph();
        } catch (AuthorizationException ae) {
            log.debug("AuthorizationException: " + ae.getMessage());
        }

        if (graph != null) {
            return getResourceValues(graph, entry.getResourceURI(), predicates);
        }

        return new ArrayList<>();
    }

	public static List<String> getResourceValues(Model graph, URI resourceURI, Set<IRI> predicates) {
		if (graph == null || predicates == null) {
			throw new IllegalArgumentException("Parameters must not be null");
		}

		List<String> result = new ArrayList<>();
		for (IRI pred : predicates) {
			for (Statement statement : graph.filter(valueFactory.createIRI(resourceURI.toString()), pred, null)) {
				Value value = statement.getObject();
				if (value instanceof IRI res) {
					result.add(res.stringValue());
				}
			}
		}

		return result;
	}

	/**
	 * FIXME this does not take entries in deleted folders into consideration
	 */
	public static boolean isDeleted(Entry entry) {
		String repoURL = entry.getRepositoryManager().getRepositoryURL().toString();
		String contextID = entry.getContext().getEntry().getId();
		URI trashURI = URISplit.createURI(repoURL, contextID, RepositoryProperties.LIST_PATH, "_trash");
		Set<URI> referredBy = entry.getReferringListsInSameContext();
        return (referredBy.size() == 1) && (referredBy.contains(trashURI));
    }

	/**
	 * Fetches entries and recursively traverses the graph by following a provided set
	 * of predicates.
	 *
	 * @param entries A set of entries to start from.
	 * @param propertiesToFollow A set of predicate URIs that point to objects (entries)
	 *                           that are to be fetched during traversal.
	 * @param blacklist A map containing key/value pairs of predicate/object combinations that,
	 *                     if contained in the graph of the currently processed entry,
	 *                     trigger a stop of the traversal excluding the matching entry.
	 * @param level Current traversal level. Used for recursion, should be 0 if called manually.
	 * @param depth Maximum traversal depth of the graph.
	 * @param visited Contains URIs that have been visited during traversal.
	 *                Should be an empty map when called manually.
	 * @param context The context in which the entries reside.
	 * @param rm A RepositoryManager instance.
	 * @return Returns the merged metadata graphs of all matching entries.
	 */
	public static TraversalResult traverseAndLoadEntryMetadata(Set<IRI> entries, Set<URI> propertiesToFollow, Map<String, String> blacklist, int level, int depth, int limit, Multimap<IRI, Integer> visited, Context context, RepositoryManager rm) {
		Model resultGraph = new LinkedHashModel();
		Set<IRI> accessDenied = new HashSet<>();
		Date latestModified = null;
		for (IRI r : entries) {
	/*		if (!r.toString().startsWith(rm.getRepositoryURL().toString())) {
				log.debug("URI has external prefix, skipping: " + r);
				continue;
			}*/
			if (visited.containsEntry(r, level)) {
				log.debug("Skipping <{}>, entry already fetched and traversed on level {}", r, level);
				continue;
			}

			if (limit > 0 && visited.size() >= limit) {
				log.info("Stopping traversal because limit of {} entries has been reached", limit);
				break;
			}

			Model graph = null;
			try {
				URI uri = URI.create(r.toString());
				Entry fetchedEntry = null;
				ContextManager cm = rm.getContextManager();
				if (context != null) {
					//By Resource URI, may be a non-repository URI.
					Set<Entry> resEntries = context.getByResourceURI(uri);
					if (resEntries != null && !resEntries.isEmpty()) {
						fetchedEntry = (Entry) resEntries.toArray()[0];
						if (resEntries.size() > 1) {
							log.warn("Resource URI {} is used by {} entries in context {}; only using first matching entry for traversal result", uri, resEntries.size(), context.getURI());
						}
					}
					//Or by entry URI
					if (fetchedEntry == null) {
						// fallback in case the URI is an entry URI
						fetchedEntry = context.getByEntryURI(uri);
					}
				} else {
					//Check first via repository URIs (includes both resource URI and entry URI)
					fetchedEntry = cm.getEntry(uri);
					if (fetchedEntry == null) {
						// fallback in case we are not referring to repository URIs.
						Set<Entry> resEntries = cm.getLinks(uri);
						if (resEntries != null && !resEntries.isEmpty()) {
							fetchedEntry = (Entry) resEntries.toArray()[0];
						}
					}
				}

				if (fetchedEntry != null) {
					graph = new LinkedHashModel(fetchedEntry.getMetadataGraph());

					// we want to get the date of the latest modification of any of the entries in the traversal process
					Date entryDateTmp = fetchedEntry.getModifiedDate();
					if (entryDateTmp == null) {
						entryDateTmp = fetchedEntry.getCreationDate();
					}
					if (entryDateTmp != null) {
						if (latestModified == null || latestModified.before(entryDateTmp)) {
							latestModified = entryDateTmp;
						}
					} else {
						log.warn("Entry does neither have a creation nor a modification date: " + fetchedEntry.getEntryURI());
					}
				}
			} catch (AuthorizationException ae) {
				// if the starting point for traversal is not accessible we abort
				// if other entries further down the traversal are inaccessible
				// we continue without fetching them
				if (level == 0) {
					throw ae;
				} else {
					accessDenied.add(r);
					log.info("Unable to load entry due to ACL restrictions: {}", r);
					continue;
				}
			}

			if (graph != null) {
				visited.put(r, level);
				if (graphContainsPredicateObjectTuple(graph, blacklist)) {
					log.debug("Found blacklisted predicate/object tuple in graph, excluding {}", r);
				} else {
					resultGraph.addAll(graph);
					if (propertiesToFollow != null && level < depth) {
						Set<IRI> objects = new HashSet<>();
						for (URI prop : propertiesToFollow) {
							objects.addAll(valueToURI(graph.filter(null, valueFactory.createIRI(prop.toString()), null).objects()));
						}
						objects.remove(r);
						if (!objects.isEmpty()) {
							log.debug("Fetching " + objects.size() + " entr" + (objects.size() == 1 ? "y" : "ies") + " linked from <" + r + ">: " + objects);
							TraversalResult nextLevel = traverseAndLoadEntryMetadata(
									objects,
									propertiesToFollow,
									blacklist,
									level + 1,
									depth,
									limit,
									visited,
									context,
									rm);
							resultGraph.addAll(nextLevel.getGraph());
							accessDenied.addAll(nextLevel.getAccessDenied());
							if (nextLevel.getLatestModified() != null) {
								if (latestModified == null || latestModified.before(nextLevel.getLatestModified())) {
									latestModified = nextLevel.getLatestModified();
								}
							} else {
								log.warn("No latest modification date on traversal level " + (level + 1));
							}
						}
					}
				}
			}
		}

		for (IRI objectToRemove : accessDenied) {
			resultGraph.removeIf(s -> objectToRemove.equals(s.getObject()));
		}

		return new TraversalResult(resultGraph, latestModified, accessDenied);
	}

	/**
	 * Checks whether a graph contains a predicate/object tuple. A simple String
	 * comparison is performed, therefore it does not matter of which type
	 * (Literal, Resource) the object is.
	 */
	private static boolean graphContainsPredicateObjectTuple(Model graph, Map<String, String> tuples) {
		if (tuples != null && !tuples.isEmpty()) {
			for (Statement s : graph) {
				String predicate = s.getPredicate().stringValue();
				String object = s.getObject().stringValue();
				for (String key : tuples.keySet()) {
					if (predicate.equals(key)) {
						if (object.equals(tuples.get(key))) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	/**
	 * Converts a set of Resources to a set of URIs. Removes non-URIs, i.e., BNodes, Literals, etc.
	 *
	 * @param values A set of Values.
	 * @return A filtered and converted set of URIs.
	 */
	public static Set<IRI> valueToURI(Set<Value> values) {
		Set<IRI> result = new HashSet<>();
		for (Value v : values) {
			if (v instanceof IRI) {
				result.add((IRI) v);
			}
		}
		return result;
	}

	@Getter
	public static class TraversalResult {

		private final Model graph;

		private final Date latestModified;

		private final Set<IRI> accessDenied;

		private TraversalResult(Model graph, Date latestModified, Set<IRI> accessDenied) {
			this.graph = graph;
			this.latestModified = latestModified;
			this.accessDenied = accessDenied;
		}

	}

}
