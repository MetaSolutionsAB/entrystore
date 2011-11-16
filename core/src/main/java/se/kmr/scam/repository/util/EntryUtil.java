/**
 * Copyright (c) 2007-2010
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

package se.kmr.scam.repository.util;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openrdf.model.Graph;
import org.openrdf.model.Literal;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.model.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.AuthorizationException;
import se.kmr.scam.repository.BuiltinType;
import se.kmr.scam.repository.Entry;
import se.kmr.scam.repository.RepositoryProperties;
import se.kmr.scam.repository.Resource;
import se.kmr.scam.repository.impl.converters.NS;

/**
 * Helper methods to make Entry handling easier, mostly sorting methods.
 * 
 * @author Hannes Ebner
 */
public class EntryUtil {
	
	static Logger log = LoggerFactory.getLogger(EntryUtil.class);

	/**
	 * Sorts a list of entries after the modification date.
	 * 
	 * @param entries
	 *            The list of entries to sort.
	 * @param ascending
	 *            True if the entry with the earliest date should come first.
	 * @param prio
	 *            Determines which BuiltinType should always have higher priority
	 *            than entries with a different one.
	 */
	public static void sortAfterModificationDate(List<Entry> entries, final boolean ascending, final BuiltinType prioritizedBuiltinType) {
		Collections.sort(entries, new Comparator<Entry>() {

			public int compare(Entry e1, Entry e2) {
				int result = 0;
				if (e1 != null && e2 != null) {
					result = e1.getModifiedDate().compareTo(e2.getModifiedDate());
					if (!ascending) {
						result *= -1;
					}
				}
				return result;
			}
			
		});
		
		prioritizeBuiltinType(entries, prioritizedBuiltinType, true);
	}

	/**
	 * Sorts a list of entries after the creation date.
	 * 
	 * @param entries
	 *            The list of entries to sort.
	 * @param ascending
	 *            True if the entry with the earliest date should come first.
	 * @param prio
	 *            Determines which BuiltinType should always have higher priority
	 *            than entries with a different one.
	 */
	public static void sortAfterCreationDate(List<Entry> entries, final boolean ascending, final BuiltinType prioritizedBuiltinType) {
		Collections.sort(entries, new Comparator<Entry>() {

			public int compare(Entry e1, Entry e2) {
				int result = 0;
				if (e1 != null && e2 != null) {
					result = e1.getCreationDate().compareTo(e2.getCreationDate());
					if (!ascending) {
						result *= -1;
					}
				}
				return result;
			}
			
		});
		
		prioritizeBuiltinType(entries, prioritizedBuiltinType, true);
	}

	/**
	 * Sorts a list of entries after the file size. Folders are listed before
	 * entries with BuiltinType.None and they are sorted among themselves after
	 * the amount of children they contain.
	 * 
	 * @param entries
	 *            The list of entries to sort.
	 * @param ascending
	 *            True if the entry with the smallest size should come first.
	 * @param prio
	 *            Determines which BuiltinType should always have higher priority
	 *            than entries with a different one.
	 */
	public static void sortAfterFileSize(List<Entry> entries, final boolean ascending, final BuiltinType prioritizedBuiltinType) {
		Collections.sort(entries, new Comparator<Entry>() {

			public int compare(Entry e1, Entry e2) {
				int result = 0;
				if (e1 != null && e2 != null) {
					BuiltinType e1BT = e1.getBuiltinType();
					BuiltinType e2BT = e2.getBuiltinType();
					if (BuiltinType.None.equals(e1BT) && BuiltinType.None.equals(e2BT)) { 
						long size1 = e1.getFileSize();
						long size2 = e2.getFileSize();
						if (size1 < size2) {
							result = -1;
						} else if (size1 == size2) {
							result = 0;
						} else if (size1 > size2) {
							result = 1;
						}
					} else if (BuiltinType.List.equals(e1BT) && BuiltinType.List.equals(e2BT)) {
						Resource e1Res = e1.getResource();
						Resource e2Res = e2.getResource();
						if (e1Res == null) {
							log.warn("No resource found for List: " + e1.getEntryURI());
							return 0;
						}
						if (e2Res == null) {
							log.warn("No resource found for List: " + e2.getEntryURI());
							return 0;
						}
						se.kmr.scam.repository.List e1List = (se.kmr.scam.repository.List) e1Res;
						se.kmr.scam.repository.List e2List = (se.kmr.scam.repository.List) e2Res;
						int size1 = e1List.getChildren().size();
						int size2 = e2List.getChildren().size();
						if (size1 < size2) {
							result = -1;
						} else if (size1 == size2) {
							result = 0;
						} else if (size1 > size2) {
							result = 1;
						}
					} else {
						result = 0;
					}
					if (!ascending) {
						result *= -1;
					}
				}
				return result;
			}
			
		});
		
		prioritizeBuiltinType(entries, prioritizedBuiltinType, true);
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
	 * @param prio
	 *            Determines which BuiltinType should always have higher priority
	 *            than entries with a different one.
	 */
	public static void sortAfterTitle(List<Entry> entries, final String language, final boolean ascending, final BuiltinType prioritizedBuiltinType) {
		Collections.sort(entries, new Comparator<Entry>() {

			public int compare(Entry e1, Entry e2) {
				int result = 0;
				if (e1 != null && e2 != null) {
					String title1 = getTitle(e1, language);
					String title2 = getTitle(e2, language);
					if (title1 != null && title2 != null) {
						result = title1.compareToIgnoreCase(title2);
					} else if (title1 == null && title2 != null) {
						result = 1;
					} else if (title1 != null && title2 == null) {
						result = -1;
					}
					if (!ascending) {
						result *= -1;
					}
				}
				return result;
			}
			
		});
		
		prioritizeBuiltinType(entries, prioritizedBuiltinType, true);
	}

	/**
	 * Reorders the list of entries with the given BuiltinType first or last,
	 * depending on the boolean parameter.
	 * 
	 * @param entries
	 *            The list of entries to reorder.
	 * @param builtinType
	 *            The BuiltinType to be prioritized.
	 * @param top
	 *            Determines whether the entries with the prioritized
	 *            BuiltinType should come first or last in the list.
	 */
	public static void prioritizeBuiltinType(List<Entry> entries, final BuiltinType builtinType, final boolean top) {
		if (entries == null || builtinType == null) {
			return;
		}
		
		Collections.sort(entries, new Comparator<Entry>() {

			public int compare(Entry e1, Entry e2) {
				int result = 0;
				if (e1 != null && e2 != null) {
					BuiltinType e1BT = e1.getBuiltinType();
					BuiltinType e2BT = e2.getBuiltinType();
					if (builtinType.equals(e1BT) && !builtinType.equals(e2BT)) {
						result = -1;
					} else if (!builtinType.equals(e1BT) && builtinType.equals(e2BT)) {
						result = 1;
					} else {
						result = 0;
					}
					if (!top) {
						result *= -1;
					}
				}
				return result;
			}
			
		});
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
	public static String getLabel(Graph graph, java.net.URI resourceURI, Set<URI> predicates, String language) {
		String fallback = null;
		if (graph != null && resourceURI != null) {
			URI resURI = new URIImpl(resourceURI.toString());
			for (URI titlePred : predicates) {
				Iterator<Statement> lables = graph.match(resURI, titlePred, null);
				while (lables.hasNext()) {
					Value value = lables.next().getObject();
					Literal lit = null;
					if (value instanceof Literal) {
						lit = (Literal) value;
					} else if (value instanceof org.openrdf.model.Resource) {
						Iterator<Statement> indirectLables = graph.match((org.openrdf.model.Resource) value, RDF.VALUE, null);
						Value indirectValue = indirectLables.next().getObject();
						if (indirectValue instanceof Literal) {
							lit = (Literal) indirectValue;
						}
					}
					if (lit != null) {
						if (language != null) {
							String litLang = lit.getLanguage();
							if (litLang != null && litLang.equalsIgnoreCase(language)) {
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
	
	public static String getLabel(Graph graph, java.net.URI resourceURI, URI predicate, String language) {
		Set<URI> predicates = new HashSet<URI>();
		predicates.add(predicate);
		return getLabel(graph, resourceURI, predicates, language);
	}
	
	public static String getResource(Graph graph, java.net.URI resourceURI, URI predicate) {
		if (graph != null && resourceURI != null) {
			URI resURI = new URIImpl(resourceURI.toString());
			Iterator<Statement> stmnts = graph.match(resURI, predicate, null);
			while (stmnts.hasNext()) {
				Value value = stmnts.next().getObject();
				if (value instanceof org.openrdf.model.Resource) {
					return value.stringValue();
				}
			}
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
	public static String getTitle(Graph graph, java.net.URI resourceURI, String language) {
		if (graph != null && resourceURI != null) {
			Set<URI> titlePredicates = new HashSet<URI>();
			titlePredicates.add(new URIImpl(NS.dcterms + "title"));
			titlePredicates.add(new URIImpl(NS.dc + "title"));
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
			String name = getLabel(entry.getMetadataGraph(), entry.getResourceURI(), new URIImpl(NS.foaf + "name"), null);
			if (name != null) {
				return name;
			}
			String firstName = getLabel(entry.getMetadataGraph(), entry.getResourceURI(), new URIImpl(NS.foaf + "firstName"), null);
			String surname = getLabel(entry.getMetadataGraph(), entry.getResourceURI(), new URIImpl(NS.foaf + "surname"), null);
			if (firstName != null) {
				result = firstName;
			}
			if (surname != null) {
				if (result != null) {
					result += " " + surname;
				} else {
					result = surname;
				}
			}
		}
		return result;
	}
	
	public static String getStructuredName(Entry entry) {
		String result = null;
		if (entry != null) {
			Set<URI> foafFirstName = new HashSet<URI>();
			Set<URI> foafSurname = new HashSet<URI>();
			foafFirstName.add(new URIImpl(NS.foaf + "firstName"));
			foafSurname.add(new URIImpl(NS.foaf + "surname"));
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
			return getLabel(entry.getMetadataGraph(), entry.getResourceURI(), new URIImpl(NS.foaf + "firstName"), null);
		}
		return null;
	}
	
	public static String getSurename(Entry entry) {
		if (entry != null) {
			return getLabel(entry.getMetadataGraph(), entry.getResourceURI(), new URIImpl(NS.foaf + "surname"), null);
		}
		return null;
	}
	
	public static String getEmail(Entry entry) {
		if (entry != null) {
			return getLabel(entry.getMetadataGraph(), entry.getResourceURI(), new URIImpl(NS.foaf + "mbox"), null);
		}
		return null;
	}
	
	public static String getMemberOf(Entry entry) {
		if (entry != null) {
			return getResource(entry.getMetadataGraph(), entry.getResourceURI(), new URIImpl("http://open.vocab.org/terms/isMemberOf"));
		}
		return null;
	}
	
	public static String getFOAFTitle(Entry entry) {
		if (entry != null) {
			return getLabel(entry.getMetadataGraph(), entry.getResourceURI(), new URIImpl(NS.foaf + "title"), null);
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
	public static Map<String, String> getTitles(Entry entry) {
		Graph graph = null;
		try {
			graph = entry.getMetadataGraph();
		} catch (AuthorizationException ae) {
			log.debug("AuthorizationException: " + ae.getMessage());
		}
		
		if (graph != null) {
			URI resourceURI = new URIImpl(entry.getResourceURI().toString());
			Set<URI> titlePredicates = new HashSet<URI>();
			titlePredicates.add(new URIImpl(NS.dcterms + "title"));
			titlePredicates.add(new URIImpl(NS.dc + "title"));
			Map<String, String> result = new HashMap<String, String>();
			for (URI titlePred : titlePredicates) {
				Iterator<Statement> dctermsTitles = graph.match(resourceURI, titlePred, null);
				while (dctermsTitles.hasNext()) {
					Value value = dctermsTitles.next().getObject();
					if (value instanceof Literal) {
						Literal lit = (Literal) value;
						result.put(lit.stringValue(), lit.getLanguage());
					}
				}
			}
			return result;
		}
		return null;
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
	public static Map<String, String> getDescriptions(Entry entry) {
		Graph graph = null;
		try {
			graph = entry.getMetadataGraph();
		} catch (AuthorizationException ae) {
			log.debug("AuthorizationException: " + ae.getMessage());
		}
		
		if (graph != null) {
			URI resourceURI = new URIImpl(entry.getResourceURI().toString());
			Set<URI> descPreds = new HashSet<URI>();
			descPreds.add(graph.getValueFactory().createURI(NS.dcterms, "description"));
			descPreds.add(graph.getValueFactory().createURI(NS.dc, "description"));
			Map<String, String> result = new HashMap<String, String>();
			for (URI titlePred : descPreds) {
				Iterator<Statement> descriptions = graph.match(resourceURI, titlePred, null);
				while (descriptions.hasNext()) {
					Value value = descriptions.next().getObject();
					if (value instanceof Literal) {
						Literal lit = (Literal) value;
						result.put(lit.stringValue(), lit.getLanguage());
					} else if (value instanceof org.openrdf.model.Resource) {
						Iterator<Statement> descriptions2 = graph.match((org.openrdf.model.Resource) value, RDF.VALUE, null);
						Value value2 = descriptions2.next().getObject();
						if (value2 instanceof Literal) {
							Literal lit2 = (Literal) value2;
							result.put(lit2.stringValue(), lit2.getLanguage());
						}
					}
				}
			}
			return result;
		}
		return null;
	}
	
	/**
	 * Retrieves all keywords and subjects (dc and dcterms:subject, lom:keyword)
	 * from the metadata of an Entry. Includes cached external metadata if it
	 * exists.
	 * 
	 * @param entry
	 *            Entry from where the keywords should be returned.
	 * @return Returns all key/value (keyword/language) pairs for
	 *         dcterms:description and dc:description
	 */
	public static Map<String, String> getKeywords(Entry entry) {
		Graph graph = null;
		try {
			graph = entry.getMetadataGraph();
		} catch (AuthorizationException ae) {
			log.debug("AuthorizationException: " + ae.getMessage());
		}
		
		if (graph != null) {
			URI resourceURI = new URIImpl(entry.getResourceURI().toString());
			Set<URI> keywPreds = new HashSet<URI>();
			keywPreds.add(graph.getValueFactory().createURI(NS.dc, "subject"));
			keywPreds.add(graph.getValueFactory().createURI(NS.lom, "keyword"));
			Map<String, String> result = new HashMap<String, String>();
			for (URI titlePred : keywPreds) {
				Iterator<Statement> keywords = graph.match(resourceURI, titlePred, null);
				while (keywords.hasNext()) {
					Value value = keywords.next().getObject();
					if (value instanceof Literal) {
						Literal lit = (Literal) value;
						result.put(lit.stringValue(), lit.getLanguage());
					} else if (value instanceof org.openrdf.model.Resource) {
						Iterator<Statement> keywords2 = graph.match((org.openrdf.model.Resource) value, RDF.VALUE, null);
						Value value2 = keywords2.next().getObject();
						if (value2 instanceof Literal) {
							Literal lit2 = (Literal) value2;
							result.put(lit2.stringValue(), lit2.getLanguage());
						}
					}
				}
			}
			return result;
		}
		return null;
	}
	
	/**
	 * FIXME this does not take entries in deleted folders into consideration 
	 */
	public static boolean isDeleted(Entry entry) {
		String repoURL = entry.getRepositoryManager().getRepositoryURL().toString();
		String contextID = entry.getContext().getEntry().getId();
		java.net.URI trashURI = URISplit.fabricateURI(repoURL, contextID, RepositoryProperties.LIST_PATH, "_trash");
		Set<java.net.URI> referredBy = entry.getReferringListsInSameContext();
		if ((referredBy.size() == 1) && (referredBy.contains(trashURI))) {
			return true;
		}
		return false;
	}
	
}