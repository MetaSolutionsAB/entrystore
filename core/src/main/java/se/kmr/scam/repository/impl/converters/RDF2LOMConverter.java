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

package se.kmr.scam.repository.impl.converters;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.ieee.ltsc.datatype.impl.EntityImpl;
import org.ieee.ltsc.datatype.impl.LangStringImpl.StringImpl;
import org.ieee.ltsc.lom.LOM;
import org.ieee.ltsc.lom.impl.LOMImpl;
import org.ieee.ltsc.lom.impl.LOMImpl.Technical.Duration;
import org.ietf.mimedir.vcard.VCard;
import org.openrdf.model.Graph;
import org.openrdf.model.Literal;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.model.vocabulary.RDFS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.Converter;

/**
 * Converts an RDF graph to LOM (supporting LRE vocabularies).
 * 
 * @author Hannes Ebner
 */
public class RDF2LOMConverter implements Converter {
	
	private static Logger log = LoggerFactory.getLogger(RDF2LOMConverter.class);
	
	private boolean lreSupport = false;
	
	public Counters statistics = new Counters();

	public RDF2LOMConverter() {
		ConverterUtil.prepareLOMProcessing();
	}
	
	public RDF2LOMConverter(boolean includeLRE) {
		this();
		this.lreSupport = includeLRE;
	}

	public Object convert(Object from, java.net.URI resourceURI, java.net.URI metadataURI) {
		if (from instanceof Graph) {
			Graph input = (Graph) from;
			ValueFactory vf = input.getValueFactory();
			return convertGraphToLom(input, vf.createURI(resourceURI.toString()), vf.createURI(metadataURI.toString()));
		} else {
			return null;
		}
	}
	
	public static URI createURI(String namespace, String uri) {
		ValueFactory vf = new GraphImpl().getValueFactory();
		if (namespace != null) {
			return vf.createURI(namespace, uri);
		}
		return vf.createURI(uri);
	}
	
	public static Date parseDateFromString(String dateString) {
		try {
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
			Date d = sdf.parse(dateString);
			return d;
		} catch (ParseException pe) {
			log.error(pe.getMessage());
		}
		return null;
	}
	
	public boolean hasLRESupport() {
		return lreSupport;
	}

	public void setLRESupport(boolean lreSupport) {
		this.lreSupport = lreSupport;
	}

	// 1-9 All Categories
	
	public void convertAll(Graph input, LOMImpl lom, URI resourceURI, URI metadataURI) {
		statistics.resourceURI = "\"" + resourceURI.stringValue() + "\"";
		convertGeneral(input, lom, resourceURI);
		convertLifeCycle(input, lom, resourceURI);
		convertMetaMetadata(input, lom, metadataURI);
		convertTechnical(input, lom, resourceURI);
		convertEducational(input, lom, resourceURI);
		convertRights(input, lom, resourceURI);
		convertRelation(input, lom, resourceURI);
		convertAnnotation(input, lom, resourceURI);
		convertClassification(input, lom, resourceURI);
	}
	
	// 1 General
	
	public void convertGeneral(Graph input, LOMImpl lom, URI resourceURI) {
		convertGeneralIdentifier(input, lom, resourceURI);
		convertGeneralTitle(input, lom, resourceURI);
		convertGeneralLanguage(input, lom, resourceURI);
		convertGeneralDescription(input, lom, resourceURI);
		convertGeneralKeyword(input, lom, resourceURI);
		convertGeneralCoverage(input, lom, resourceURI);
		convertGeneralStructure(input, lom, resourceURI);
		convertGeneralAggregationLevel(input, lom, resourceURI);
	}
	
	// 1.1 Identifier
	
	public void convertGeneralIdentifier(Graph input, LOMImpl lom, URI resourceURI) {
		lom.newGeneral().newIdentifier(0).newCatalog().setString("URI");
		lom.newGeneral().newIdentifier(0).newEntry().setString(resourceURI.stringValue());
		statistics.generalIdentifier++;
		
		Iterator<Statement> identifiers = input.match(resourceURI, createURI(NS.lom, "identifier"), null);
		int idCount = 1;
		while (identifiers.hasNext()) {
			Value object = identifiers.next().getObject();
			if (object instanceof Resource) {
				
				// 1.1.1 Catalog
				
				Iterator<Statement> catalog = input.match((Resource) object, createURI(NS.lom, "catalog"), null);
				if (catalog.hasNext()) {
					Value catalogObj = catalog.next().getObject();
					if (catalogObj instanceof Literal) {
						lom.newGeneral().newIdentifier(idCount).newCatalog().setString(catalogObj.stringValue());
					}
				}
				
				// 1.1.2 Entry
				
				Iterator<Statement> entry = input.match((Resource) object, createURI(NS.lom, "entry"), null);
				if (entry.hasNext()) {
					Value entryObj = entry.next().getObject();
					if (entryObj instanceof Literal) {
						lom.newGeneral().newIdentifier(idCount).newEntry().setString(entryObj.stringValue());
					}
				}
				
				idCount++;
				statistics.generalIdentifier++;
			}
		}
	}
	
	// 1.2 Title
	
	public void convertGeneralTitle(Graph input, LOMImpl lom, URI resourceURI) {
		Set<URI> properties = new HashSet<URI>();
		properties.add(createURI(NS.dcterms, "title"));
		properties.add(createURI(NS.dc, "title"));
		
		for (URI titleProp : properties) {
			Iterator<Statement> dctermsTitles = input.match(resourceURI, titleProp, null);
			while (dctermsTitles.hasNext()) {
				Value object = dctermsTitles.next().getObject();
				if (object instanceof Literal) {
					Literal literal = (Literal) object;
					StringImpl title = lom.newGeneral().newTitle().newString(-1);
					title.setString(literal.stringValue());
					String lang = literal.getLanguage();
					if (lang != null) {
						title.newLanguage().setValue(lang);
					}
					statistics.generalTitle++;
				}
			}	
		}
	}
	
	// 1.3 Language
	
	public void convertGeneralLanguage(Graph input, LOMImpl lom, URI resourceURI) {
		Set<URI> properties = new HashSet<URI>();
		properties.add(createURI(NS.dcterms, "language"));
		properties.add(createURI(NS.dc, "language"));
		
		// set to avoid duplicate languages from different merged md graphs
		Set<String> coveredLangs = new HashSet<String>();
		
		for (URI langProp : properties) {
			Iterator<Statement> languages = input.match(resourceURI, langProp, null);
			while (languages.hasNext()) {
				Statement langStmnt = languages.next();
				Value object = langStmnt.getObject();
				if (object instanceof Resource) {
					Iterator<Statement> lingSystems = input.match((Resource) object, RDF.VALUE, null);
					while (lingSystems.hasNext()) {
						Statement lingSysStmnt = lingSystems.next();
						Value lingSys = lingSysStmnt.getObject();
						if (lingSys instanceof Literal) {
							String litValue = lingSys.stringValue().trim().toLowerCase();
							if ("x-t-notapplicable".equals(litValue) || coveredLangs.contains(litValue)) {
								continue;
							}
							lom.newGeneral().newLanguage(-1).setString(litValue);
							coveredLangs.add(litValue);
							statistics.generalLanguage++;
						}
					}
				} else if (object instanceof Literal) {
					String litValue = object.stringValue().trim().toLowerCase();
					if (coveredLangs.contains(litValue)) {
						continue;
					}
					lom.newGeneral().newLanguage(-1).setString(litValue);
					coveredLangs.add(litValue);
					statistics.generalLanguage++;
				}
			}
		}
	}
	
	// 1.4 Description
	
	public void convertGeneralDescription(Graph input, LOMImpl lom, URI resourceURI) {
		Set<URI> properties = new HashSet<URI>();
		properties.add(createURI(NS.dcterms, "description"));
		properties.add(createURI(NS.dc, "description"));

		int descriptionCount = 0;

		for (URI descProp : properties) {	
			Iterator<Statement> descriptions = input.match(resourceURI, descProp, null);
			while (descriptions.hasNext()) {
				Value object = descriptions.next().getObject();
				if (object instanceof Literal) {
					Literal literal = (Literal) object;
					StringImpl description = lom.newGeneral().newDescription(descriptionCount).newString(-1);
					description.setString(literal.stringValue());
					String lang = literal.getLanguage();
					if (lang != null) {
						description.newLanguage().setValue(lang);
					}
					statistics.generalDescription++;
				} else if (object instanceof Resource) {
					Iterator<Statement> descriptionValueStmnts = input.match((Resource) object, RDF.VALUE, null);
					while (descriptionValueStmnts.hasNext()) {
						Statement descriptionValueStmnt = descriptionValueStmnts.next();
						Value description = descriptionValueStmnt.getObject();
						if (description instanceof Literal) {
							Literal descriptionLiteral = (Literal) description;
							StringImpl descriptionString = lom.newGeneral().newDescription(descriptionCount).newString(-1);
							descriptionString.setString(descriptionLiteral.stringValue());
							String descriptionLiteralLang = descriptionLiteral.getLanguage();
							if (descriptionLiteralLang != null) {
								descriptionString.newLanguage().setValue(descriptionLiteralLang);
							}
							statistics.generalDescription++;
						}
					}
				} else {
					descriptionCount--;
				}
				descriptionCount++;
			}
		}
	}
		
	// 1.5 Keyword
	
	public void convertGeneralKeyword(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> keywords = input.match(resourceURI, createURI(NS.lom, "keyword"), null);
		int keywordCount = 0;
		while (keywords.hasNext()) {
			Value object = keywords.next().getObject();
			if (object instanceof Resource) {
				Iterator<Statement> keywordValueStmnts = input.match((Resource) object, RDF.VALUE, null);
				while (keywordValueStmnts.hasNext()) {
					Statement keywordValueStmnt = keywordValueStmnts.next();
					Value keyword = keywordValueStmnt.getObject();
					if (keyword instanceof Literal) {
						Literal keywordLiteral = (Literal) keyword;
						StringImpl keywordString = lom.newGeneral().newKeyword(keywordCount).newString(-1);
						keywordString.setString(keywordLiteral.stringValue());
						String keywordLiteralLang = keywordLiteral.getLanguage();
						if (keywordLiteralLang != null) {
							keywordString.newLanguage().setValue(keywordLiteralLang);
						}
						statistics.generalKeyword++;
					}
				}
				keywordCount++;
			}
		}
	}
	
	// 1.6 Coverage
	
	public void convertGeneralCoverage(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> coverage = input.match(resourceURI, createURI(NS.dcterms, "coverage"), null);
		int coverageCount = 0;
		while (coverage.hasNext()) {
			Value object = coverage.next().getObject();
			if (object instanceof Resource) {
				Iterator<Statement> coverageValueStmnts = input.match((Resource) object, RDF.VALUE, null);
				while (coverageValueStmnts.hasNext()) {
					Statement coverageValueStmnt = coverageValueStmnts.next();
					Value coverageValue = coverageValueStmnt.getObject();
					if (coverageValue instanceof Literal) {
						Literal coverageLiteral = (Literal) coverageValue;
						StringImpl coverageString = lom.newGeneral().newCoverage(coverageCount).newString(-1);
						coverageString.setString(coverageLiteral.stringValue());
						String coverageLang = coverageLiteral.getLanguage();
						if (coverageLang != null) {
							coverageString.newLanguage().setValue(coverageLang);
						}
						statistics.generalCoverage++;
					}
				}
				coverageCount++;
			}
		}
	}
	
	// 1.7 Structure
	
	public void convertGeneralStructure(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> structureItems = input.match(resourceURI, createURI(NS.lom, "structure"), null);
		while (structureItems.hasNext()) {
			Value object = structureItems.next().getObject();
			if (object instanceof URI) {
				URI strucURI = (URI) object;
				String strucURIStr = strucURI.stringValue();
				String structure = null;
								
				if (strucURIStr.equals(NS.lomvoc + "Structure-atomic")) {
					structure = LOM.General.Structure.ATOMIC;	
				} else if (strucURIStr.equals(NS.lomvoc + "Structure-networked")) {
					structure = LOM.General.Structure.NETWORKED;
				} else if (strucURIStr.equals(NS.lomvoc + "Structure-hierarchical")) {
					structure = LOM.General.Structure.HIERARCHICAL;
				} else if (strucURIStr.equals(NS.lomvoc + "Structure-linear")) {
					structure = LOM.General.Structure.LINEAR;
				} else if (strucURIStr.equals(NS.lomvoc + "Structure-collection")) {
					structure = LOM.General.Structure.COLLECTION;
				}
				
				if (structure != null) {
					lom.newGeneral().newStructure().newValue().setString(structure);
					lom.newGeneral().newStructure().newSource().setString(LOM.LOM_V1P0_VOCABULARY);
					statistics.generalStructure++;
				}
			}
		}
	}
	
	// 1.8 Aggregation Level

	public void convertGeneralAggregationLevel(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> aggregationItems = input.match(resourceURI, createURI(NS.lom, "aggregationLevel"), null);
		while (aggregationItems.hasNext()) {
			Value object = aggregationItems.next().getObject();
			if (object instanceof URI) {
				URI aggregationURI = (URI) object;
				String aggregationURIStr = aggregationURI.stringValue();
				String aggregation = null;
								
				if (aggregationURIStr.equals(NS.lomvoc + "AggregationLevel-1")) {
					aggregation = LOM.General.AggregationLevel.LEVEL1;	
				} else if (aggregationURIStr.equals(NS.lomvoc + "AggregationLevel-2")) {
					aggregation = LOM.General.AggregationLevel.LEVEL2;
				} else if (aggregationURIStr.equals(NS.lomvoc + "AggregationLevel-3")) {
					aggregation = LOM.General.AggregationLevel.LEVEL3;
				} else if (aggregationURIStr.equals(NS.lomvoc + "AggregationLevel-4")) {
					aggregation = LOM.General.AggregationLevel.LEVEL4;
				}
				
				if (aggregation != null) {
					lom.newGeneral().newAggregationLevel().newValue().setString(aggregation);
					lom.newGeneral().newAggregationLevel().newSource().setString(LOM.LOM_V1P0_VOCABULARY);
					statistics.generalAggregationLevel++;
				}
			}
		}
	}
	
	// 2 Life Cycle
	
	public void convertLifeCycle(Graph input, LOMImpl lom, URI resourceURI) {
		convertLifeCycleVersion(input, lom, resourceURI);
		convertLifeCycleStatus(input, lom, resourceURI);
		convertLifeCycleContribute(input, lom, resourceURI);
	}
	
	// 2.1 Version
	
	public void convertLifeCycleVersion(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> version = input.match(resourceURI, createURI(NS.lom, "version"), null);
		if (version.hasNext()) {
			Value object = version.next().getObject();
			if (object instanceof Resource) {
				Iterator<Statement> versionValueStmnts = input.match((Resource) object, RDF.VALUE, null);
				while (versionValueStmnts.hasNext()) {
					Statement versionValueStmnt = versionValueStmnts.next();
					Value versionValue = versionValueStmnt.getObject();
					if (versionValue instanceof Literal) {
						Literal versionLiteral = (Literal) versionValue;
						StringImpl versionString = lom.newLifeCycle().newVersion().newString(-1);
						versionString.setString(versionLiteral.stringValue());
						String versionLang = versionLiteral.getLanguage();
						if (versionLang != null) {
							versionString.newLanguage().setValue(versionLang);
						}
						statistics.lifeCycleVersion++;
					}
				}
			}
		}
	}

	// 2.2 Status
	
	public void convertLifeCycleStatus(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> statusItems = input.match(resourceURI, createURI(NS.lom, "status"), null);
		while (statusItems.hasNext()) {
			Value object = statusItems.next().getObject();
			if (object instanceof URI) {
				URI statusURI = (URI) object;
				String statusURIStr = statusURI.stringValue();
				String status = null;
								
				if (statusURIStr.equals(NS.lomvoc + "Status-draft")) {
					status = LOM.LifeCycle.Status.DRAFT;
				} else if (statusURIStr.equals(NS.lomvoc + "Status-final")) {
					status = LOM.LifeCycle.Status.FINAL;
				} else if (statusURIStr.equals(NS.lomvoc + "Status-revised")) {
					status = LOM.LifeCycle.Status.REVISED;
				} else if (statusURIStr.equals(NS.lomvoc + "Status-unavailable")) {
					status = LOM.LifeCycle.Status.UNAVAILABLE;
				}
				
				if (status != null) {
					lom.newLifeCycle().newStatus().newValue().setString(status);
					lom.newLifeCycle().newStatus().newSource().setString(LOM.LOM_V1P0_VOCABULARY);
					statistics.lifeCycleStatus++;
				}
			}
		}
	}

	// 2.3 Contribute
	
	public void convertLifeCycleContribute(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> contributions = input.match(resourceURI, createURI(NS.lom, "contribution"), null);
		int contributionCount = 0;
		while (contributions.hasNext()) {
			Value object = contributions.next().getObject();
			if (object instanceof Resource) {
				
				// 2.3.1 Role
				
				Iterator<Statement> roleStmnts = input.match((Resource) object, createURI(NS.lom, "role"), null);
				if (roleStmnts.hasNext()) {
					Statement roleStmnt = roleStmnts.next();
					Value role = roleStmnt.getObject();
					if (role instanceof URI) {
						URI roleURI = (URI) role;
						String roleURIStr = roleURI.stringValue();
						String roleVoc = null;
						
						if (roleURIStr.equals(NS.lomvoc + "Role-author")) {
							roleVoc = LOM.LifeCycle.Contribute.Role.AUTHOR;
						} else if (roleURIStr.equals(NS.lomvoc + "Role-contentProvider")) {
							roleVoc = LOM.LifeCycle.Contribute.Role.CONTENT_PROVIDER;
						} else if (roleURIStr.equals(NS.lomvoc + "Role-editor")) {
							roleVoc = LOM.LifeCycle.Contribute.Role.EDITOR;
						} else if (roleURIStr.equals(NS.lomvoc + "Role-educationalValidator")) {
							roleVoc = LOM.LifeCycle.Contribute.Role.EDUCATIONAL_VALIDATOR;
						} else if (roleURIStr.equals(NS.lomvoc + "Role-graphicalDesigner")) {
							roleVoc = LOM.LifeCycle.Contribute.Role.GRAPHICAL_DESIGNER;
						} else if (roleURIStr.equals(NS.lomvoc + "Role-initiator")) {
							roleVoc = LOM.LifeCycle.Contribute.Role.INITIATOR;
						} else if (roleURIStr.equals(NS.lomvoc + "Role-instructionalDesigner")) {
							roleVoc = LOM.LifeCycle.Contribute.Role.INSTRUCTIONAL_DESIGNER;
						} else if (roleURIStr.equals(NS.lomvoc + "Role-publisher")) {
							roleVoc = LOM.LifeCycle.Contribute.Role.PUBLISHER;
						} else if (roleURIStr.equals(NS.lomvoc + "Role-scriptWriter")) {
							roleVoc = LOM.LifeCycle.Contribute.Role.SCRIPT_WRITER;
						} else if (roleURIStr.equals(NS.lomvoc + "Role-subjectMatterExpert")) {
							roleVoc = LOM.LifeCycle.Contribute.Role.SUBJECT_MATTER_EXPERT;
						} else if (roleURIStr.equals(NS.lomvoc + "Role-techicalImplementer")) {
							roleVoc = LOM.LifeCycle.Contribute.Role.TECHNICAL_IMPLEMENTER;
						} else if (roleURIStr.equals(NS.lomvoc + "Role-technicalValidator")) {
							roleVoc = LOM.LifeCycle.Contribute.Role.TECHNICAL_VALIDATOR;
						} else if (roleURIStr.equals(NS.lomvoc + "Role-terminator")) {
							roleVoc = LOM.LifeCycle.Contribute.Role.TERMINATOR;
						} else if (roleURIStr.equals(NS.lomvoc + "Role-validator")) {
							roleVoc = LOM.LifeCycle.Contribute.Role.VALIDATOR;
						} else {
							roleVoc = LOM.LifeCycle.Contribute.Role.UNKNOWN;
						}
						
						if (roleVoc != null) {
							lom.newLifeCycle().newContribute(contributionCount).newRole().newValue().setString(roleVoc);
							lom.newLifeCycle().newContribute(contributionCount).newRole().newSource().setString(LOM.LOM_V1P0_VOCABULARY);
							statistics.lifeCycleContributeRole++;
						}
					}
				}
				
				// 2.3.2 Entity

				Iterator<Statement> entityStmnts = input.match((Resource) object, createURI(NS.lom, "entity"), null);
				while (entityStmnts.hasNext()) {
					Statement entityStmnt = entityStmnts.next();
					Value entity = entityStmnt.getObject();
					if (entity instanceof Resource) {
						VCard vcard = null;
						try {
							vcard = EntityImpl.parseEntity(LOM.LifeCycle.Contribute.Entity.TYPE, convertGraph2VCardString(input, (Resource) entity), false);
						} catch (ParseException e) {
							log.warn("Unable to convert LOM 2.3.2 Entity of " + resourceURI + " to VCard: " + e.getMessage());
						}
						if (vcard != null) {
							lom.newLifeCycle().newContribute(contributionCount).newEntity(-1).setVCard(vcard);
							statistics.lifeCycleContributeEntity++;
						}
					}
				}
				
				// 2.3.3 Date
				
				Iterator<Statement> dateStmnts = input.match((Resource) object, createURI(NS.dcterms, "date"), null);
				if (dateStmnts.hasNext()) {
					Statement dateStmnt = dateStmnts.next();
					Value date = dateStmnt.getObject();
					if (date instanceof Literal) {
						Literal dateLiteral = (Literal) date;
						GregorianCalendar dateValue = null;
						try {
							dateValue = dateLiteral.calendarValue().toGregorianCalendar();
						} catch (IllegalArgumentException iae) {
							Date parsedDate = parseDateFromString(dateLiteral.stringValue());
							if (parsedDate != null) {
								dateValue = new GregorianCalendar();
								dateValue.setTime(parsedDate);
							} else {
								log.warn("Unable to parse 2.3.3 Date of " + resourceURI + ": " + iae.getMessage());
							}
						}
						if (dateValue != null) {
							lom.newLifeCycle().newContribute(contributionCount).newDate().newValue().setDateTime(dateValue);
						}
						statistics.lifeCycleContributeDate++;
					}
				}
				
				contributionCount++;
			}
		}
	}
	

	// 3 MetaMetadata
	
	public void convertMetaMetadata(Graph input, LOMImpl lom, URI metadataURI) {
		if (metadataURI == null) {
			log.warn("Metadata URI is null, unable to convert MetaMetadata properties");
			return;
		}
		
		convertMetaMetadataIdentifier(input, lom, metadataURI);
		convertMetaMetadataContribute(input, lom, metadataURI);
		convertMetaMetadataSchema(input, lom, metadataURI);
		convertMetaMetadataLanguage(input, lom, metadataURI);
	}

	// 3.1 Identifier
	
	public void convertMetaMetadataIdentifier(Graph input, LOMImpl lom, URI metadataURI) {
		lom.newMetaMetadata().newIdentifier(0).newCatalog().setString("URI");
		lom.newMetaMetadata().newIdentifier(0).newEntry().setString(metadataURI.stringValue());
		
		Iterator<Statement> identifiers = input.match(metadataURI, createURI(NS.lom, "identifier"), null);
		int idCount = 1;
		while (identifiers.hasNext()) {
			Value object = identifiers.next().getObject();
			if (object instanceof Resource) {
				
				// 3.1.1 Catalog
				
				Iterator<Statement> catalog = input.match((Resource) object, createURI(NS.lom, "catalog"), null);
				if (catalog.hasNext()) {
					Value catalogObj = catalog.next().getObject();
					if (catalogObj instanceof Literal) {
						lom.newMetaMetadata().newIdentifier(idCount).newCatalog().setString(catalogObj.stringValue());
						statistics.metaMetadataIdentifierCatalog++;
					}
				}
				
				// 3.1.2 Entry
				
				Iterator<Statement> entry = input.match((Resource) object, createURI(NS.lom, "entry"), null);
				if (entry.hasNext()) {
					Value entryObj = entry.next().getObject();
					if (entryObj instanceof Literal) {
						lom.newMetaMetadata().newIdentifier(idCount).newEntry().setString(entryObj.stringValue());
						statistics.metaMetadataIdentifierEntry++;
					}
				}
				
				idCount++;
			}
		}
	}

	// 3.2 Contribute
	
	public void convertMetaMetadataContribute(Graph input, LOMImpl lom, URI metadataURI) {
		Iterator<Statement> contributions = input.match(metadataURI, createURI(NS.lom, "contribute"), null);
		int contributionCount = 0;
		while (contributions.hasNext()) {
			Value object = contributions.next().getObject();
			if (object instanceof Resource) {
				
				// 3.2.1 Role
				
				Iterator<Statement> roleStmnts = input.match((Resource) object, createURI(NS.lom, "role"), null);
				if (roleStmnts.hasNext()) {
					Statement roleStmnt = roleStmnts.next();
					Value role = roleStmnt.getObject();
					if (role instanceof URI) {
						URI roleURI = (URI) role;
						String roleURIStr = roleURI.stringValue();
						String roleVoc = null;
						String vocSource = null;
						
						if (roleURIStr.equals(NS.lomvoc + "Role-creator")) {
							roleVoc = LOM.MetaMetadata.Contribute.Role.CREATOR;
						} else if (roleURIStr.equals(NS.lomvoc + "Role-validator")) {
							roleVoc = LOM.MetaMetadata.Contribute.Role.VALIDATOR;
						}
						
						if (roleVoc != null) {
							vocSource = LOM.LOM_V1P0_VOCABULARY;
						}
						
						if (roleVoc == null && lreSupport) {
							if (roleURIStr.equals(NS.lrevoc + "Role-enricher")) {
								roleVoc = LRE.MetaMetadata.Contribute.Role.ENRICHER;
							} else if (roleURIStr.equals(NS.lrevoc + "Role-provider")) {
								roleVoc = LRE.MetaMetadata.Contribute.Role.PROVIDER;
							}
							
							if (roleVoc != null) {
								vocSource = LRE.LRE_V3P0_VOCABULARY;
							} 
						}
						
						if (roleVoc != null) {
							lom.newMetaMetadata().newContribute(contributionCount).newRole().newValue().setString(roleVoc);
							if (vocSource != null) {
								lom.newMetaMetadata().newContribute(contributionCount).newRole().newSource().setString(vocSource);	
							}
							statistics.metaMetadataContributeRole++;
						}
					}
				}
				
				// 3.2.2 Entity

				Iterator<Statement> entityStmnts = input.match((Resource) object, createURI(NS.lom, "entity"), null);
				while (entityStmnts.hasNext()) {
					Statement entityStmnt = entityStmnts.next();
					Value entity = entityStmnt.getObject();
					if (entity instanceof Resource) {
						VCard vcard = null;
						try {
							vcard = EntityImpl.parseEntity(LOM.MetaMetadata.Contribute.Entity.TYPE, convertGraph2VCardString(input, (Resource) entity), false);
						} catch (ParseException e) {
							log.warn("Unable to convert LOM 3.2.2 Entity of " + metadataURI + " to VCard: " + e.getMessage());
						}
						if (vcard != null) {
							lom.newMetaMetadata().newContribute(contributionCount).newEntity(-1).setVCard(vcard);
							statistics.metaMetadataContributeEntity++;
						}
					}
				}
				
				// 3.2.3 Date
				
				Iterator<Statement> dateStmnts = input.match((Resource) object, createURI(NS.dcterms, "date"), null);
				if (dateStmnts.hasNext()) {
					Statement dateStmnt = dateStmnts.next();
					Value date = dateStmnt.getObject();
					if (date instanceof Literal) {
						Literal dateLiteral = (Literal) date;
						GregorianCalendar dateValue = null;
						try {
							dateValue = dateLiteral.calendarValue().toGregorianCalendar();
						} catch (IllegalArgumentException iae) {
							Date parsedDate = parseDateFromString(dateLiteral.stringValue());
							if (parsedDate != null) {
								dateValue = new GregorianCalendar();
								dateValue.setTime(parsedDate);
							} else {
								log.warn("Unable to parse 3.2.3 Date of " + metadataURI + ": " + iae.getMessage());
							}
						}
						if (dateValue != null) {
							lom.newMetaMetadata().newContribute(contributionCount).newDate().newValue().setDateTime(dateValue);
						}
						statistics.metaMetadataContributeDate++;
					}
				}
				
				contributionCount++;
			}
		}
	}

	// 3.3 Metadata Schema
	
	public void convertMetaMetadataSchema(Graph input, LOMImpl lom, URI metadataURI) {
		Iterator<Statement> schemaElements = input.match(metadataURI, createURI(NS.lom, "metadataScheme"), null);
		while (schemaElements.hasNext()) {
			Value object = schemaElements.next().getObject();
			if (object instanceof URI) {
				URI mdURI = (URI) object;
				String mdURIStr = mdURI.stringValue();
				String mdSchema = null;
								
				if (mdURIStr.equals(NS.lomvoc + "MetadataScheme-LOMv1.0")) {
					mdSchema = LOM.LOM_V1P0_VOCABULARY;
				} else if (mdURIStr.equals(NS.lrevoc + "MetadataScheme-LREv3.0")) {
					mdSchema = LRE.LRE_V3P0_VOCABULARY;
				}
				
				if (mdSchema != null) {
					lom.newMetaMetadata().newMetadataSchema(-1).setString(mdSchema);
					statistics.metaMetadataSchema++;
				}
			}
		}
	}

	// 3.4 Language
	
	public void convertMetaMetadataLanguage(Graph input, LOMImpl lom, URI metadataURI) {
		Iterator<Statement> languages = input.match(metadataURI, createURI(NS.dcterms, "language"), null);
		while (languages.hasNext()) {
			Value object = languages.next().getObject();
			if (object instanceof Resource) {
				Iterator<Statement> lingSystems = input.match((Resource) object, RDF.VALUE, null);
				while (lingSystems.hasNext()) {
					Statement lingSysStmnt = lingSystems.next();
					Value lingSys = lingSysStmnt.getObject();
					if (lingSys instanceof Literal) {
						lom.newMetaMetadata().newLanguage().setString(lingSys.stringValue());
						statistics.metaMetadataLanguage++;
					}
				}
			}
		}
	}
	
	// 4 Technical
	
	public void convertTechnical(Graph input, LOMImpl lom, URI resourceURI) {
		convertTechnicalFormat(input, lom, resourceURI);
		convertTechnicalSize(input, lom, resourceURI);
		convertTechnicalLocation(input, lom, resourceURI);
		convertTechnicalRequirement(input, lom, resourceURI);
		convertTechnicalInstallationRemarks(input, lom, resourceURI);
		convertTechnicalOtherPlatformRequirements(input, lom, resourceURI);
		convertTechnicalDuration(input, lom, resourceURI);
		convertTechnicalFacet(input, lom, resourceURI);
	}

	// 4.1 Format
	
	public void convertTechnicalFormat(Graph input, LOMImpl lom, URI resourceURI) {
		// set to avoid duplicate formats from different merged md graphs
		Set<String> coveredFormats = new HashSet<String>();
		
		Iterator<Statement> formatItems = input.match(resourceURI, createURI(NS.dcterms, "format"), null);
		while (formatItems.hasNext()) {
			Value object = formatItems.next().getObject();
			if (object instanceof Resource) {
				Iterator<Statement> formatValues = input.match((Resource) object, RDF.VALUE, null);
				while (formatValues.hasNext()) {
					Value next = formatValues.next().getObject();
					if (next instanceof Literal) {
						if (coveredFormats.contains(next.stringValue())) {
							continue;
						}
						lom.newTechnical().newFormat(-1).setString(next.stringValue());
						coveredFormats.add(next.stringValue());
						statistics.technicalFormat++;
					}
				}
			} else if (object instanceof Literal) {
				if (coveredFormats.contains(object.stringValue())) {
					continue;
				}
				lom.newTechnical().newFormat(-1).setString(object.stringValue());
				coveredFormats.add(object.stringValue());
				statistics.technicalFormat++;
			}
		}
	}

	// 4.2 Size
	
	public void convertTechnicalSize(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> sizeItems = input.match(resourceURI, createURI(NS.dcterms, "extent"), null);
		while (sizeItems.hasNext()) {
			Value object = sizeItems.next().getObject();
			if (object instanceof Resource) {
				Iterator<Statement> types = input.match((Resource) object, RDF.TYPE, createURI(NS.lom, "Size"));
				if (types.hasNext()) {
					Iterator<Statement> sizeValues = input.match((Resource) object, RDF.VALUE, null);
					if (sizeValues.hasNext()) {
						Value next = sizeValues.next().getObject();
						if (next instanceof Literal) {
							Literal locationValue = (Literal) next;
							if (createURI(NS.xsd, "positiveInteger").equals(locationValue.getDatatype())) {
								lom.newTechnical().newSize().setString(locationValue.stringValue());
								statistics.technicalSize++;
							}
						}
					}
				}
			}
		}
	}

	// 4.3 Location
	
	public void convertTechnicalLocation(Graph input, LOMImpl lom, URI resourceURI) {
		lom.newTechnical().newLocation(-1).setString(resourceURI.stringValue());
		
		Iterator<Statement> locationItems = input.match(resourceURI, createURI(NS.lom, "location"), null);
		while (locationItems.hasNext()) {
			Value object = locationItems.next().getObject();
			if (object instanceof Resource) {
				Iterator<Statement> locationValues = input.match((Resource) object, RDF.VALUE, null);
				while (locationValues.hasNext()) {
					Value next = locationValues.next().getObject();
					if (next instanceof Literal) {
						Literal locationValue = (Literal) next;
						if (createURI(NS.xsd, "anyURI").equals(locationValue.getDatatype())) {
							lom.newTechnical().newLocation(-1).setString(locationValue.stringValue());
							statistics.technicalLocation++;
						}
					}
				}
			}
		}
	}

	// 4.4 Requirement
	
	public void convertTechnicalRequirement(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> requirements = input.match(resourceURI, createURI(NS.lom, "requirement"), null);
		int requirementCount = 0;
		while (requirements.hasNext()) {
			Value requirementsObject = requirements.next().getObject();
			
			if (requirementsObject instanceof Resource) {
				
				// 4.4.1 OrComposite
				
				int orCompositeCount = 0;
				Iterator<Statement> orComposites = input.match((Resource) requirementsObject, createURI(NS.lom, "alternativeRequirement"), null);
				while (orComposites.hasNext()) {
					Value orCompositeObject = orComposites.next().getObject();
					
					if (orCompositeObject instanceof Resource) {

						// 4.4.1.1 Type

						Iterator<Statement> typeStmnts = input.match((Resource) orCompositeObject, RDF.TYPE, null);
						if (typeStmnts.hasNext()) {
							Statement typeStmnt = typeStmnts.next();
							Value type = typeStmnt.getObject();
							if (type instanceof URI) {
								URI typeURI = (URI) type;
								String typeURIStr = typeURI.stringValue();
								String typeVoc = null;

								if (typeURIStr.equals(NS.lomvoc + "RequirementType-browser")) {
									typeVoc = LOM.Technical.Requirement.OrComposite.Type.BROWSER;
								} else if (typeURIStr.equals(NS.lomvoc + "RequirementType-operatingSystem")) {
									typeVoc = LOM.Technical.Requirement.OrComposite.Type.OPERATING_SYSTEM;
								}

								if (typeVoc != null) {
									lom.newTechnical().newRequirement(requirementCount).newOrComposite(orCompositeCount).newType().newValue().setString(typeVoc);
									lom.newTechnical().newRequirement(requirementCount).newOrComposite(orCompositeCount).newType().newSource().setString(LOM.LOM_V1P0_VOCABULARY);
									statistics.technicalRequirementType++;
								}
							}
						}

						// 4.4.1.2 Name

						Iterator<Statement> nameStmnts = input.match((Resource) orCompositeObject, createURI(NS.lom, "technology"), null);
						if (nameStmnts.hasNext()) {
							Statement nameStmnt = nameStmnts.next();
							Value name = nameStmnt.getObject();
							if (name instanceof URI) {
								URI nameURI = (URI) name;
								String nameURIStr = nameURI.stringValue();
								String nameVoc = null;

								if (nameURIStr.equals(NS.lomvoc + "BrowserTechnology-amaya")) {
									nameVoc = LOM.Technical.Requirement.OrComposite.Name.BROWSER_AMAYA;
								} else if (nameURIStr.equals(NS.lomvoc + "BrowserTechnology-any")) {
									nameVoc = LOM.Technical.Requirement.OrComposite.Name.BROWSER_ANY;
								} else if (nameURIStr.equals(NS.lomvoc + "BrowserTechnology-ms-internetExplorer")) {
									nameVoc = LOM.Technical.Requirement.OrComposite.Name.BROWSER_MS_INTERNET_EXPLORER;
								} else if (nameURIStr.equals(NS.lomvoc + "BrowserTechnology-netscapeCommunicator")) {
									nameVoc = LOM.Technical.Requirement.OrComposite.Name.BROWSER_NETSCAPE_COMMUNICATOR;
								} else if (nameURIStr.equals(NS.lomvoc + "BrowserTechnology-opera")) {
									nameVoc = LOM.Technical.Requirement.OrComposite.Name.BROWSER_OPERA;
								} else if (nameURIStr.equals(NS.lomvoc + "OSTechnology-macos")) {
									nameVoc = LOM.Technical.Requirement.OrComposite.Name.OPERATING_SYSTEM_MACOS;
								} else if (nameURIStr.equals(NS.lomvoc + "OSTechnology-ms-windows")) {
									nameVoc = LOM.Technical.Requirement.OrComposite.Name.OPERATING_SYSTEM_MS_WINDOWS;
								} else if (nameURIStr.equals(NS.lomvoc + "OSTechnology-multi-os")) {
									nameVoc = LOM.Technical.Requirement.OrComposite.Name.OPERATING_SYSTEM_MULTI_OS;
								} else if (nameURIStr.equals(NS.lomvoc + "OSTechnology-none")) {
									nameVoc = LOM.Technical.Requirement.OrComposite.Name.OPERATING_SYSTEM_NONE;
								} else if (nameURIStr.equals(NS.lomvoc + "OSTechnology-pc-dos")) {
									nameVoc = LOM.Technical.Requirement.OrComposite.Name.OPERATING_SYSTEM_PC_DOS;
								} else if (nameURIStr.equals(NS.lomvoc + "OSTechnology-unix")) {
									nameVoc = LOM.Technical.Requirement.OrComposite.Name.OPERATING_SYSTEM_UNIX;
								}

								if (nameVoc != null) {
									lom.newTechnical().newRequirement(requirementCount).newOrComposite(orCompositeCount).newName().newValue().setString(nameVoc);
									lom.newTechnical().newRequirement(requirementCount).newOrComposite(orCompositeCount).newName().newSource().setString(LOM.LOM_V1P0_VOCABULARY);
									statistics.technicalRequirementName++;
								}
							}
						}

						// 4.4.1.3 Minimum version

						Iterator<Statement> minVersion = input.match((Resource) orCompositeObject, createURI(NS.lom, "minimumVersion"), null);
						while (minVersion.hasNext()) {
							Value object = minVersion.next().getObject();
							if (object instanceof Literal) {
								Literal literal = (Literal) object;
								lom.newTechnical().newRequirement(requirementCount).newOrComposite(orCompositeCount).newMinimumVersion().setString(literal.stringValue());
								statistics.technicalRequirementMinimumVersion++;
							}
						}

						// 4.4.1.4 Maximum version

						Iterator<Statement> maxVersion = input.match((Resource) orCompositeObject, createURI(NS.lom, "maximumVersion"), null);
						while (maxVersion.hasNext()) {
							Value object = maxVersion.next().getObject();
							if (object instanceof Literal) {
								Literal literal = (Literal) object;
								lom.newTechnical().newRequirement(requirementCount).newOrComposite(orCompositeCount).newMaximumVersion().setString(literal.stringValue());
								statistics.technicalRequirementMaximumVersion++;
							}
						}

						orCompositeCount++;
					}
				}
				requirementCount++;
			}
		}
	}

	// 4.5 Installation Remarks
	
	public void convertTechnicalInstallationRemarks(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> remarks = input.match(resourceURI, createURI(NS.lom, "installationRemarks"), null);
		if (remarks.hasNext()) {
			Value object = remarks.next().getObject();
			if (object instanceof Resource) {
				Iterator<Statement> remarkValueStmnts = input.match((Resource) object, RDF.VALUE, null);
				while (remarkValueStmnts.hasNext()) {
					Statement remarkValueStmnt = remarkValueStmnts.next();
					Value remark = remarkValueStmnt.getObject();
					if (remark instanceof Literal) {
						Literal remarkLiteral = (Literal) remark;
						StringImpl remarkString = lom.newTechnical().newInstallationRemarks().newString(-1);
						remarkString.setString(remarkLiteral.stringValue());
						String remarkLiteralLang = remarkLiteral.getLanguage();
						if (remarkLiteralLang != null) {
							remarkString.newLanguage().setValue(remarkLiteralLang);
						}
						statistics.technicalInstallationRemarks++;
					}
				}
			}
		}
	}

	// 4.6 Other Platform Requirements
	
	public void convertTechnicalOtherPlatformRequirements(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> requirements = input.match(resourceURI, createURI(NS.lom, "otherPlatformRequirements"), null);
		if (requirements.hasNext()) {
			Value object = requirements.next().getObject();
			if (object instanceof Resource) {
				Iterator<Statement> reqValueStmnts = input.match((Resource) object, RDF.VALUE, null);
				while (reqValueStmnts.hasNext()) {
					Statement reqValueStmnt = reqValueStmnts.next();
					Value req = reqValueStmnt.getObject();
					if (req instanceof Literal) {
						Literal reqLiteral = (Literal) req;
						StringImpl reqString = lom.newTechnical().newOtherPlatformRequirements().newString(-1);
						reqString.setString(reqLiteral.stringValue());
						String reqLiteralLang = reqLiteral.getLanguage();
						if (reqLiteralLang != null) {
							reqString.newLanguage().setValue(reqLiteralLang);
						}
						statistics.technicalOtherPlatformRequirements++;
					}
				}
			}
		}
	}

	// 4.7 Duration
	
	public void convertTechnicalDuration(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> durationItem = input.match(resourceURI, createURI(NS.dcterms, "extent"), null);
		while (durationItem.hasNext()) {
			Value object = durationItem.next().getObject();
			if (object instanceof Resource) {
				Iterator<Statement> learningTimeType = input.match((Resource) object, RDF.TYPE, null);
				if (learningTimeType.hasNext()) {
					Value next = learningTimeType.next().getObject();
					if (!createURI(NS.lom, "Duration").equals(next)) {
						continue;
					}
				}
				Iterator<Statement> durationValues = input.match((Resource) object, RDF.VALUE, null);
				while (durationValues.hasNext()) {
					Value next = durationValues.next().getObject();
					if (next instanceof Literal) {
						Literal durationValue = (Literal) next;
						if (createURI(NS.xsd, "duration").equals(durationValue.getDatatype())) {
							Long duration = null;
							try {
								duration = Duration.parseDuration(durationValue.stringValue());
							} catch (ParseException e) {
								log.warn("Unable to parse Duration value: " + e.getMessage());
								continue;
							}
							if (duration != null) {
								lom.newTechnical().newDuration().newValue().setDuration(duration);
								statistics.technicalDuration++;
							}
						} else {
							StringImpl durationDesc = lom.newTechnical().newDuration().newDescription().newString(-1);
							durationDesc.setString(durationValue.stringValue());
							durationDesc.newLanguage().setValue(durationValue.getLanguage());
							statistics.technicalDuration++;
						}
					}
				}
			}
		}
	}

	// 4.8 Facet
	
	public void convertTechnicalFacet(Graph input, LOMImpl lom, URI resourceURI) {
		if (!lreSupport) {
			return;
		}
		
		// TODO this is LRE specific
	}
	
	// 5 Educational
	
	public void convertEducational(Graph input, LOMImpl lom, URI resourceURI) {
		convertEducationalInteractivityType(input, lom, resourceURI);
		convertEducationalLearningResourceType(input, lom, resourceURI);
		convertEducationalInteractivityLevel(input, lom, resourceURI);
		convertEducationalSemanticDensity(input, lom, resourceURI);
		convertEducationalIntendedEndUserRole(input, lom, resourceURI);
		convertEducationalContext(input, lom, resourceURI);
		convertEducationalTypicalAgeRange(input, lom, resourceURI);
		convertEducationalDifficulty(input, lom, resourceURI);
		convertEducationalTypicalLearningTime(input, lom, resourceURI);
		convertEducationalDescription(input, lom, resourceURI);
		convertEducationalLanguage(input, lom, resourceURI);
	}
	
	// 5.1 Interactivity Type
	
	public void convertEducationalInteractivityType(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> interactTypeItems = input.match(resourceURI, createURI(NS.lom, "interactivityType"), null);
		while (interactTypeItems.hasNext()) {
			Value object = interactTypeItems.next().getObject();
			if (object instanceof URI) {
				URI interactURI = (URI) object;
				String interactURIStr = interactURI.stringValue();
				String interactivity = null;
								
				if (interactURIStr.equals(NS.lomvoc + "InteractivityType-active")) {
					interactivity = LOM.Educational.InteractivityType.ACTIVE;
				} else if (interactURIStr.equals(NS.lomvoc + "InteractivityType-expositive")) {
					interactivity = LOM.Educational.InteractivityType.EXPOSITIVE;
				} else if (interactURIStr.equals(NS.lomvoc + "InteractivityType-mixed")) {
					interactivity = LOM.Educational.InteractivityType.MIXED;
				}
				
				if (interactivity != null) {
					lom.newEducational(0).newInteractivityType().newValue().setString(interactivity);
					lom.newEducational(0).newInteractivityType().newSource().setString(LOM.LOM_V1P0_VOCABULARY);
					statistics.educationalInteractivityType++;
				}
			}
		}
	}

	// 5.2 Learning Resource Type
	
	public void convertEducationalLearningResourceType(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> lrtItems = input.match(resourceURI, RDF.TYPE, null);
		int lrtCount = 0;
		while (lrtItems.hasNext()) {
			Value object = lrtItems.next().getObject();
			if (object instanceof URI || object instanceof Literal) {
				//URI lrtURI = (URI) object;
				String lrtURIStr = object.stringValue();
				String lrt = null;
				String vocSource = null;
								
				if (lrtURIStr.equals(NS.lomvoc + "LearningResourceType-diagram")) {
					lrt = LOM.Educational.LearningResourceType.DIAGRAM;
				} else if (lrtURIStr.equals(NS.lomvoc + "LearningResourceType-exam")) {
					lrt = LOM.Educational.LearningResourceType.EXAM;
				} else if (lrtURIStr.equals(NS.lomvoc + "LearningResourceType-exercise")) {
					lrt = LOM.Educational.LearningResourceType.EXCERCISE;
				} else if (lrtURIStr.equals(NS.lomvoc + "LearningResourceType-experiment")) {
					lrt = LOM.Educational.LearningResourceType.EXPERIMENT;
				} else if (lrtURIStr.equals(NS.lomvoc + "LearningResourceType-figure")) {
					lrt = LOM.Educational.LearningResourceType.FIGURE;
				} else if (lrtURIStr.equals(NS.lomvoc + "LearningResourceType-graph")) {
					lrt = LOM.Educational.LearningResourceType.GRAPH;
				} else if (lrtURIStr.equals(NS.lomvoc + "LearningResourceType-index")) {
					lrt = LOM.Educational.LearningResourceType.INDEX;
				} else if (lrtURIStr.equals(NS.lomvoc + "LearningResourceType-lecture")) {
					lrt = LOM.Educational.LearningResourceType.LECTURE;
				} else if (lrtURIStr.equals(NS.lomvoc + "LearningResourceType-narrativeText")) {
					lrt = LOM.Educational.LearningResourceType.NARRATIVE_TEXT;
				} else if (lrtURIStr.equals(NS.lomvoc + "LearningResourceType-problemStatement")) {
					lrt = LOM.Educational.LearningResourceType.PROBLEM_STATEMENT;
				} else if (lrtURIStr.equals(NS.lomvoc + "LearningResourceType-questionnaire")) {
					lrt = LOM.Educational.LearningResourceType.QUESTIONNAIRE;
				} else if (lrtURIStr.equals(NS.lomvoc + "LearningResourceType-selfAssessment")) {
					lrt = LOM.Educational.LearningResourceType.SELF_ASSESSMENT;
				} else if (lrtURIStr.equals(NS.lomvoc + "LearningResourceType-simulation")) {
					lrt = LOM.Educational.LearningResourceType.SIMULATION;
				} else if (lrtURIStr.equals(NS.lomvoc + "LearningResourceType-slide")) {
					lrt = LOM.Educational.LearningResourceType.SLIDE;
				} else if (lrtURIStr.equals(NS.lomvoc + "LearningResourceType-table")) {
					lrt = LOM.Educational.LearningResourceType.TABLE;
				}
				
				if (lrt != null) {
					vocSource = LOM.LOM_V1P0_VOCABULARY;
				}
				
				if (lrt == null && lreSupport) {
					if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-application")) {
						lrt = LRE.Educational.LearningResourceType.APPLICATION;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-assessment")) {
						lrt = LRE.Educational.LearningResourceType.ASSESSMENT;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-learningAsset-audio")) {
						lrt = LRE.Educational.LearningResourceType.AUDIO;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-broadcast")) {
						lrt = LRE.Educational.LearningResourceType.BROADCAST;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-caseStudy")) {
						lrt = LRE.Educational.LearningResourceType.CASE_STUDY;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-course")) {
						lrt = LRE.Educational.LearningResourceType.COURSE;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-learningAsset-data")) {
						lrt = LRE.Educational.LearningResourceType.DATA;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-demonstration")) {
						lrt = LRE.Educational.LearningResourceType.DEMONSTRATION;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-drillAndPractice")) {
						lrt = LRE.Educational.LearningResourceType.DRILL_AND_PRACTICE;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-educationalGame")) {
						lrt = LRE.Educational.LearningResourceType.EDUCATIONAL_GAME;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-enquiryOrientedActivity")) {
						lrt = LRE.Educational.LearningResourceType.ENQUIRY_ORIENTED_ACTIVITY;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-experiment")) {
						lrt = LRE.Educational.LearningResourceType.EXPERIMENT;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-exploration")) {
						lrt = LRE.Educational.LearningResourceType.EXPLORATION;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-glossary")) {
						lrt = LRE.Educational.LearningResourceType.GLOSSARY;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-guide")) {
						lrt = LRE.Educational.LearningResourceType.GUIDE_ADVICE_SHEETS;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-learningAsset-image")) {
						lrt = LRE.Educational.LearningResourceType.IMAGE;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-lessonPlan")) {
						lrt = LRE.Educational.LearningResourceType.LESSON_PLAN;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-learningAsset-model")) {
						lrt = LRE.Educational.LearningResourceType.MODEL;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-openActivity")) {
						lrt = LRE.Educational.LearningResourceType.OPEN_ACTIVITY;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-other")) {
						lrt = LRE.Educational.LearningResourceType.OTHER;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-webResource-otherWebResource")) {
						lrt = LRE.Educational.LearningResourceType.OTHER_WEB_RESOURCE;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-presentation")) {
						lrt = LRE.Educational.LearningResourceType.PRESENTATION;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-project")) {
						lrt = LRE.Educational.LearningResourceType.PROJECT;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-reference")) {
						lrt = LRE.Educational.LearningResourceType.REFERENCE;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-rolePlay")) {
						lrt = LRE.Educational.LearningResourceType.ROLE_PLAY;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-simulation")) {
						lrt = LRE.Educational.LearningResourceType.SIMULATION;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-learningAsset-text")) {
						lrt = LRE.Educational.LearningResourceType.TEXT;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-tool")) {
						lrt = LRE.Educational.LearningResourceType.TOOL;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-learningAsset-video")) {
						lrt = LRE.Educational.LearningResourceType.VIDEO;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-webResource-webPage")) {
						lrt = LRE.Educational.LearningResourceType.WEB_PAGE;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-webResource-weblog")) {
						lrt = LRE.Educational.LearningResourceType.WEBLOG;
					} else if (lrtURIStr.equals(NS.lrevoc + "LearningResourceType-webResource-wiki")) {
						lrt = LRE.Educational.LearningResourceType.WIKI;
					}
					
					if (lrt != null) {
						vocSource = LRE.LRE_V3P0_VOCABULARY;
					}
				}
				
				if (lrt != null) {
					lom.newEducational(0).newLearningResourceType(lrtCount).newValue().setString(lrt);
					if (vocSource != null) {
						lom.newEducational(0).newLearningResourceType(lrtCount).newSource().setString(vocSource);	
					}
					statistics.educationalLearningResourceType++;
					lrtCount++;
				}
			}
		}
	}

	// 5.3 Interactivy Level
	
	public void convertEducationalInteractivityLevel(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> interactLevelItems = input.match(resourceURI, createURI(NS.lom, "interactivityLevel"), null);
		while (interactLevelItems.hasNext()) {
			Value object = interactLevelItems.next().getObject();
			if (object instanceof URI) {
				URI interactURI = (URI) object;
				String interactURIStr = interactURI.stringValue();
				String interactivity = null;
								
				if (interactURIStr.equals(NS.lomvoc + "InteractivityLevel-veryLow")) {
					interactivity = LOM.Educational.InteractivityLevel.VERY_LOW;
				} else if (interactURIStr.equals(NS.lomvoc + "InteractivityLevel-low")) {
					interactivity = LOM.Educational.InteractivityLevel.LOW;
				} else if (interactURIStr.equals(NS.lomvoc + "InteractivityLevel-medium")) {
					interactivity = LOM.Educational.InteractivityLevel.MEDIUM;
				} else if (interactURIStr.equals(NS.lomvoc + "InteractivityLevel-high")) {
					interactivity = LOM.Educational.InteractivityLevel.HIGH;
				} else if (interactURIStr.equals(NS.lomvoc + "InteractivityLevel-veryHigh")) {
					interactivity = LOM.Educational.InteractivityLevel.VERY_HIGH;
				}
				
				if (interactivity != null) {
					lom.newEducational(0).newInteractivityLevel().newValue().setString(interactivity);
					lom.newEducational(0).newInteractivityLevel().newSource().setString(LOM.LOM_V1P0_VOCABULARY);
					statistics.educationalInteractivityLevel++;
				}
			}
		}
	}

	// 5.4 Semantic Density
	
	public void convertEducationalSemanticDensity(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> densityItems = input.match(resourceURI, createURI(NS.lom, "semanticDensity"), null);
		while (densityItems.hasNext()) {
			Value object = densityItems.next().getObject();
			if (object instanceof URI) {
				URI densityURI = (URI) object;
				String densityURIStr = densityURI.stringValue();
				String density = null;
								
				if (densityURIStr.equals(NS.lomvoc + "SemanticDensity-veryLow")) {
					density = LOM.Educational.SemanticDensity.VERY_LOW;
				} else if (densityURIStr.equals(NS.lomvoc + "SemanticDensity-low")) {
					density = LOM.Educational.SemanticDensity.LOW;
				} else if (densityURIStr.equals(NS.lomvoc + "SemanticDensity-medium")) {
					density = LOM.Educational.SemanticDensity.MEDIUM;
				} else if (densityURIStr.equals(NS.lomvoc + "SemanticDensity-high")) {
					density = LOM.Educational.SemanticDensity.HIGH;
				} else if (densityURIStr.equals(NS.lomvoc + "SemanticDensity-veryHigh")) {
					density = LOM.Educational.SemanticDensity.VERY_HIGH;
				}
				
				if (density != null) {
					lom.newEducational(0).newSemanticDensity().newValue().setString(density);
					lom.newEducational(0).newSemanticDensity().newSource().setString(LOM.LOM_V1P0_VOCABULARY);
					statistics.educationalSemanticDensity++;
				}
			}
		}
	}

	// 5.5 Intended End User Role
	
	public void convertEducationalIntendedEndUserRole(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> eurItems = input.match(resourceURI, createURI(NS.dcterms, "audience"), null);
		int eurCount = 0;
		while (eurItems.hasNext()) {
			Value object = eurItems.next().getObject();
			if (object instanceof URI) {
				URI eurURI = (URI) object;
				String eurURIStr = eurURI.stringValue();
				String endUserRole = null;
				String vocSource = null;
								
				if (eurURIStr.equals(NS.lomvoc + "IntendedEndUserRole-author")) {
					endUserRole = LOM.Educational.IntendedEndUserRole.AUTHOR;
				} else if (eurURIStr.equals(NS.lomvoc + "IntendedEndUserRole-learner")) {
					endUserRole = LOM.Educational.IntendedEndUserRole.LEARNER;
				} else if (eurURIStr.equals(NS.lomvoc + "IntendedEndUserRole-manager")) {
					endUserRole = LOM.Educational.IntendedEndUserRole.MANAGER;
				} else if (eurURIStr.equals(NS.lomvoc + "IntendedEndUserRole-teacher")) {
					endUserRole = LOM.Educational.IntendedEndUserRole.TEACHER;
				}
				
				if (endUserRole != null) {
					vocSource = LOM.LOM_V1P0_VOCABULARY;
				}
				
				if (endUserRole == null && lreSupport) {
					if (eurURIStr.equals(NS.lrevoc + "IntendedEndUserRole-counsellor")) {
						endUserRole = LRE.Educational.IntendedEndUserRole.COUNSELLOR;
					} else if (eurURIStr.equals(NS.lrevoc + "IntendedEndUserRole-other")) {
						endUserRole = LRE.Educational.IntendedEndUserRole.OTHER;
					} else if (eurURIStr.equals(NS.lrevoc + "IntendedEndUserRole-parent")) {
						endUserRole = LRE.Educational.IntendedEndUserRole.PARENT;
					}
					
					if (endUserRole != null) {
						vocSource = LRE.LRE_V3P0_VOCABULARY;
					}
				}
				
				if (endUserRole != null) {
					lom.newEducational(0).newIntendedEndUserRole(eurCount).newValue().setString(endUserRole);
					if (vocSource != null) {
						lom.newEducational(0).newIntendedEndUserRole(eurCount).newSource().setString(vocSource);
					}
					statistics.educationalIntendedEndUserRole++;
					eurCount++;
				}
			}
		}
	}
	
	// 5.6 Context
	
	public void convertEducationalContext(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> contextItems = input.match(resourceURI, createURI(NS.lom, "context"), null);
		int contextCount = 0;
		while (contextItems.hasNext()) {
			Value object = contextItems.next().getObject();
			if (object instanceof URI || object instanceof Literal) {
				//URI contextURI = (URI) object;
				String contextURIStr = object.stringValue();
				String context = null;
				String vocSource = null;
								
				if (contextURIStr.equals(NS.lomvoc + "Context-higherEducation")) {
					context = LOM.Educational.Context.HIGHER_EDUCATION;
				} else if (contextURIStr.equals(NS.lomvoc + "Context-other")) {
					context = LOM.Educational.Context.OTHER;
				} else if (contextURIStr.equals(NS.lomvoc + "Context-school")) {
					context = LOM.Educational.Context.SCHOOL;
				} else if (contextURIStr.equals(NS.lomvoc + "Context-training")) {
					context = LOM.Educational.Context.TRAINING;
				}
				
				if (context != null) {
					vocSource = LOM.LOM_V1P0_VOCABULARY;
				}
				
				if (context == null && lreSupport) {
					if (contextURIStr.equals(NS.lrevoc + "Context-compulsoryEducation")) {
						context = LRE.Educational.LearningContext.COMPULSORY_EDUCATION;
					} else if (contextURIStr.equals(NS.lrevoc + "Context-continuingEducation")) {
						context = LRE.Educational.LearningContext.CONTINUING_EDUCATION;
					} else if (contextURIStr.equals(NS.lrevoc + "Context-distanceEducation")) {
						context = LRE.Educational.LearningContext.DISTANCE_EDUCATION;
					} else if (contextURIStr.equals(NS.lrevoc + "Context-educationalAdministration")) {
						context = LRE.Educational.LearningContext.EDUCATIONAL_ADMINISTRATION;
					} else if (contextURIStr.equals(NS.lrevoc + "Context-library")) {
						context = LRE.Educational.LearningContext.LIBRARY;
					} else if (contextURIStr.equals(NS.lrevoc + "Context-policyMaking")) {
						context = LRE.Educational.LearningContext.POLICY_MAKING;
					} else if (contextURIStr.equals(NS.lrevoc + "Context-preSchool")) {
						context = LRE.Educational.LearningContext.PRE_SCHOOL;
					} else if (contextURIStr.equals(NS.lrevoc + "Context-professionalDevelopment")) {
						context = LRE.Educational.LearningContext.PROFESSIONAL_DEVELOPMENT;
					} else if (contextURIStr.equals(NS.lrevoc + "Context-specialEducation")) {
						context = LRE.Educational.LearningContext.SPECIAL_EDUCATION;
					} else if (contextURIStr.equals(NS.lrevoc + "Context-vocationalEducation")) {
						context = LRE.Educational.LearningContext.VOCATIONAL_EDUCATION;
					}
					
					if (context != null) {
						vocSource = LRE.LRE_V3P0_VOCABULARY;
					}
				}
				
				if (context != null) {
					lom.newEducational(0).newContext(contextCount).newValue().setString(context);
					if (vocSource != null) {
						lom.newEducational(0).newContext(contextCount).newSource().setString(vocSource);
					}
					statistics.educationalContext++;
					contextCount++;
				}
			}
		}
	}

	// 5.7 Typical Age Range
	
	public void convertEducationalTypicalAgeRange(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> ageRanges = input.match(resourceURI, createURI(NS.lom, "typicalAgeRange"), null);
		while (ageRanges.hasNext()) {
			Value object = ageRanges.next().getObject();
			if (object instanceof Resource) {
				Iterator<Statement> ageRangeValueStmnts = input.match((Resource) object, RDF.VALUE, null);
				while (ageRangeValueStmnts.hasNext()) {
					Statement ageRangeValueStmnt = ageRangeValueStmnts.next();
					Value ageRange = ageRangeValueStmnt.getObject();
					if (ageRange instanceof Literal) {
						Literal ageRangeLiteral = (Literal) ageRange;
						StringImpl ageRangeString = lom.newEducational(0).newTypicalAgeRange(-1).newString(-1);
						ageRangeString.setString(ageRangeLiteral.stringValue());
						String ageRangeLiteralLang = ageRangeLiteral.getLanguage();
						if (ageRangeLiteralLang != null) {
							ageRangeString.newLanguage().setValue(ageRangeLiteralLang);
						}
						statistics.educationalTypicalAgeRange++;
					}
				}
			}
		}
	}

	// 5.8 Difficulty
	
	public void convertEducationalDifficulty(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> difficultyItems = input.match(resourceURI, createURI(NS.lom, "difficulty"), null);
		while (difficultyItems.hasNext()) {
			Value object = difficultyItems.next().getObject();
			if (object instanceof URI) {
				URI difficultyURI = (URI) object;
				String difficultyURIStr = difficultyURI.stringValue();
				String difficulty = null;
								
				if (difficultyURIStr.equals(NS.lomvoc + "Difficulty-veryEasy")) {
					difficulty = LOM.Educational.Difficulty.VERY_EASY;
				} else if (difficultyURIStr.equals(NS.lomvoc + "Difficulty-easy")) {
					difficulty = LOM.Educational.Difficulty.EASY;
				} else if (difficultyURIStr.equals(NS.lomvoc + "Difficulty-medium")) {
					difficulty = LOM.Educational.Difficulty.MEDIUM;
				} else if (difficultyURIStr.equals(NS.lomvoc + "Difficulty-difficult")) {
					difficulty = LOM.Educational.Difficulty.DIFFICULT;
				} else if (difficultyURIStr.equals(NS.lomvoc + "Difficulty-veryDifficult")) {
					difficulty = LOM.Educational.Difficulty.VERY_DIFFICULT;
				}
				
				if (difficulty != null) {
					lom.newEducational(0).newDifficulty().newValue().setString(difficulty);
					lom.newEducational(0).newDifficulty().newSource().setString(LOM.LOM_V1P0_VOCABULARY);
					statistics.educationalDifficulty++;
				}
			}
		}
	}
	
	// 5.9 Typical Learning Time
	
	public void convertEducationalTypicalLearningTime(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> learningTimeItem = input.match(resourceURI, createURI(NS.lom, "typicalLearningTime"), null);
		while (learningTimeItem.hasNext()) {
			Value object = learningTimeItem.next().getObject();
			if (object instanceof Resource) {
				Iterator<Statement> learningTimeType = input.match((Resource) object, RDF.TYPE, null);
				if (learningTimeType.hasNext()) {
					Value next = learningTimeType.next().getObject();
					if (!createURI(NS.lom, "Duration").equals(next)) {
						continue;
					}
				}
				Iterator<Statement> learningTimeValues = input.match((Resource) object, RDF.VALUE, null);
				while (learningTimeValues.hasNext()) {
					Value next = learningTimeValues.next().getObject();
					if (next instanceof Literal) {
						Literal learningTimeValue = (Literal) next;
						if (createURI(NS.xsd, "duration").equals(learningTimeValue.getDatatype())) {
							Long learningTime = null;
							try {
								learningTime = Duration.parseDuration(learningTimeValue.stringValue());
							} catch (ParseException e) {
								log.warn("Unable to parse Duration value: " + e.getMessage());
								continue;
							}
							if (learningTime != null) {
								lom.newEducational(0).newTypicalLearningTime().newValue().setDuration(learningTime);
							}
							statistics.educationalTypicalLearningTime++;
						} else {
							StringImpl durationDesc = lom.newEducational(0).newTypicalLearningTime().newDescription().newString(-1);
							durationDesc.setString(learningTimeValue.stringValue());
							durationDesc.newLanguage().setValue(learningTimeValue.getLanguage());
							statistics.educationalTypicalLearningTime++;
						}
					}
				}
			}
		}
	}

	// 5.10 Description
	
	public void convertEducationalDescription(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> descriptions = input.match(resourceURI, createURI(NS.lom, "educationalDescription"), null);
		while (descriptions.hasNext()) {
			Value object = descriptions.next().getObject();
			if (object instanceof Resource) {
				Iterator<Statement> descriptionValueStmnts = input.match((Resource) object, RDF.VALUE, null);
				while (descriptionValueStmnts.hasNext()) {
					Statement descriptionValueStmnt = descriptionValueStmnts.next();
					Value description = descriptionValueStmnt.getObject();
					if (description instanceof Literal) {
						Literal descriptionLiteral = (Literal) description;
						StringImpl descriptionString = lom.newEducational(0).newDescription(-1).newString(-1);
						descriptionString.setString(descriptionLiteral.stringValue());
						String descriptionLiteralLang = descriptionLiteral.getLanguage();
						if (descriptionLiteralLang != null) {
							descriptionString.newLanguage().setValue(descriptionLiteralLang);
						}
						statistics.educationalDescription++;
					}
				}
			}
		}
	}

	// 5.11 Language
	
	public void convertEducationalLanguage(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> languages = input.match(resourceURI, createURI(NS.lom, "educationalLanguage"), null);
		while (languages.hasNext()) {
			Value object = languages.next().getObject();
			if (object instanceof Resource) {
				Iterator<Statement> lingSystems = input.match((Resource) object, RDF.VALUE, null);
				while (lingSystems.hasNext()) {
					Statement lingSysStmnt = lingSystems.next();
					Value lingSys = lingSysStmnt.getObject();
					if (lingSys instanceof Literal) {
						lom.newEducational(0).newLanguage(-1).setString(lingSys.stringValue());
						statistics.educationalLanguage++;
					}
				}
			}
		}
	}
	
	// 6 Rights
	
	public void convertRights(Graph input, LOMImpl lom, URI resourceURI) {
		convertRightsCost(input, lom, resourceURI);
		convertRightsCopyrightAndOtherRestrictions(input, lom, resourceURI);
		convertRightsDescription(input, lom, resourceURI);
	}
	
	// 6.1 Cost
	
	public void convertRightsCost(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> costElements= input.match(resourceURI, createURI(NS.lom, "cost"), null);
		if (costElements.hasNext()) {
			Value object = costElements.next().getObject();
			if (object instanceof Literal) {
				Literal costValue = (Literal) object;
				if (createURI(NS.xsd, "boolean").equals(costValue.getDatatype())) {
					lom.newRights().newCost().newValue().setString(costValue.booleanValue() ? "yes" : "no");
					lom.newRights().newCost().newSource().setString(LOM.LOM_V1P0_VOCABULARY);
					statistics.rightsCost++;
				}
			}
		}
	}

	// 6.2 Copyright and Other Restrictions
	
	public void convertRightsCopyrightAndOtherRestrictions(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> corElements= input.match(resourceURI, createURI(NS.lom, "copyrightAndOtherRestrictions"), null);
		if (corElements.hasNext()) {
			Value object = corElements.next().getObject();
			if (object instanceof Literal) {
				Literal corValue = (Literal) object;
				if (createURI(NS.xsd, "boolean").equals(corValue.getDatatype())) {
					lom.newRights().newCopyrightAndOtherRestrictions().newValue().setString(corValue.booleanValue() ? "yes" : "no");
					lom.newRights().newCopyrightAndOtherRestrictions().newSource().setString(LOM.LOM_V1P0_VOCABULARY);
					statistics.rightsCostCopyrightAndOtherRestrictions++;
				}
			}
		}
	}

	// 6.3 Description

	public void convertRightsDescription(Graph input, LOMImpl lom, URI resourceURI) {
		Set<URI> properties = new HashSet<URI>();
		properties.add(createURI(NS.dcterms, "rights"));
		properties.add(createURI(NS.dc, "rights"));

		for (URI descProp : properties) {	
			Iterator<Statement> descriptions = input.match(resourceURI, descProp, null);
			while (descriptions.hasNext()) {
				Value object = descriptions.next().getObject();
				if (object instanceof Literal) {
					Literal descriptionLiteral = (Literal) object;
					StringImpl descriptionString = lom.newRights().newDescription().newString(-1);
					descriptionString.setString(descriptionLiteral.stringValue());
					String descriptionLiteralLang = descriptionLiteral.getLanguage();
					if (descriptionLiteralLang != null) {
						descriptionString.newLanguage().setValue(descriptionLiteralLang);
					}
					statistics.rightsDescription++;
				} else if (object instanceof Resource) {
					Iterator<Statement> descriptionValueStmnts = input.match((Resource) object, RDF.VALUE, null);
					while (descriptionValueStmnts.hasNext()) {
						Statement descriptionValueStmnt = descriptionValueStmnts.next();
						Value description = descriptionValueStmnt.getObject();
						if (description instanceof Literal) {
							Literal descriptionLiteral = (Literal) description;
							StringImpl descriptionString = lom.newRights().newDescription().newString(-1);
							descriptionString.setString(descriptionLiteral.stringValue());
							String descriptionLiteralLang = descriptionLiteral.getLanguage();
							if (descriptionLiteralLang != null) {
								descriptionString.newLanguage().setValue(descriptionLiteralLang);
							}
							statistics.rightsDescription++;
						} else if (description instanceof URI) {
							lom.newRights().newDescription().newString(-1).setString(description.stringValue());
							statistics.rightsDescription++;
						}
					}
				}
			}
		}
	}
	
	// 7 Relation
	
	public void convertRelation(Graph input, LOMImpl lom, URI resourceURI) {
		
		// 7.1 Kind
		
		Iterator<Statement> allStmnts = input.match(resourceURI, null, null);
		int relationCount = 0;
		while (allStmnts.hasNext()) {
			Statement stmnt = allStmnts.next();
			URI pred = stmnt.getPredicate();
			Value obj = stmnt.getObject();
			if (obj instanceof Resource) {
				String voc = null;
				if (pred.equals(createURI(NS.dcterms, "hasFormat"))) {
					voc = LOM.Relation.Kind.HAS_FORMAT;
				} else if (pred.equals(createURI(NS.dcterms, "hasPart"))) {
					voc = LOM.Relation.Kind.HAS_PART;
				} else if (pred.equals(createURI(NS.dcterms, "hasVersion"))) {
					voc = LOM.Relation.Kind.HAS_VERSION;
				} else if (pred.equals(createURI(NS.dcterms, "source"))) {
					voc = LOM.Relation.Kind.IS_BASED_ON;
				} else if (pred.equals(createURI(NS.lomterms, "isBasisFor"))) {
					voc = LOM.Relation.Kind.IS_BASIS_FOR;
				} else if (pred.equals(createURI(NS.dcterms, "isFormatOf"))) {
					voc = LOM.Relation.Kind.IS_FORMAT_OF;
				} else if (pred.equals(createURI(NS.dcterms, "isPartOf"))) {
					voc = LOM.Relation.Kind.IS_PART_OF;
				} else if (pred.equals(createURI(NS.dcterms, "isReferencedBy"))) {
					voc = LOM.Relation.Kind.IS_REFERENCED_BY;
				} else if (pred.equals(createURI(NS.dcterms, "isRequiredBy"))) {
					voc = LOM.Relation.Kind.IS_REQUIRED_BY;
				} else if (pred.equals(createURI(NS.dcterms, "isVersionOf"))) {
					voc = LOM.Relation.Kind.IS_VERSION_OF;
				} else if (pred.equals(createURI(NS.dcterms, "references"))) {
					voc = LOM.Relation.Kind.REFERENCES;
				} else if (pred.equals(createURI(NS.dcterms, "requires"))) {
					voc = LOM.Relation.Kind.REQUIRES;
				}
				
				if (lreSupport) {
					if (pred.equals(createURI(NS.lomterms, "hasPreview"))) {
						voc = LRE.Relation.Kind.HAS_PREVIEW;
					} else if (pred.equals(createURI(NS.lomterms, "isPreviewOf"))) {
						voc = LRE.Relation.Kind.IS_PREVIEW_OF;
					}
				}

				// 7.2 Resource

				if (voc != null) {
					
					// FIXME why is voc not used here? TODO don't forget to include vocSource
					
					// FIXME include counter for 7.1

					// 7.2.1 Identifier

					Iterator<Statement> identifiers = input.match((Resource) obj, createURI(NS.lom, "identifier"), null);
					int idCount = 1;
					while (identifiers.hasNext()) {
						Value idObject = identifiers.next().getObject();
						if (idObject instanceof Resource) {

							// 7.2.1.1 Catalog

							Iterator<Statement> catalog = input.match((Resource) idObject, createURI(NS.lom, "catalog"), null);
							if (catalog.hasNext()) {
								Value catalogObj = catalog.next().getObject();
								if (catalogObj instanceof Literal) {
									lom.newRelation(relationCount).newResource().newIdentifier(idCount).newCatalog().setString(catalogObj.stringValue());
									statistics.relationResourceIdentifierCatalog++;
								}
							}

							// 7.2.1.2 Entry

							Iterator<Statement> entry = input.match((Resource) idObject, createURI(NS.lom, "entry"), null);
							if (entry.hasNext()) {
								Value entryObj = entry.next().getObject();
								if (entryObj instanceof Literal) {
									lom.newRelation(relationCount).newResource().newIdentifier(idCount).newEntry().setString(entryObj.stringValue());
									statistics.relationResourceIdentifierEntry++;
								}
							}

							idCount++;
						}
					}

					// 7.2.2 Description

					Iterator<Statement> descriptions = input.match((Resource) obj, createURI(NS.dcterms, "description"), null);
					while (descriptions.hasNext()) {
						Value object = descriptions.next().getObject();
						if (object instanceof Resource) {
							Iterator<Statement> descriptionValueStmnts = input.match((Resource) object, RDF.VALUE, null);
							while (descriptionValueStmnts.hasNext()) {
								Statement descriptionValueStmnt = descriptionValueStmnts.next();
								Value description = descriptionValueStmnt.getObject();
								if (description instanceof Literal) {
									Literal descriptionLiteral = (Literal) description;
									StringImpl descriptionString = lom.newRelation(relationCount).newResource().newDescription(-1).newString(-1);
									descriptionString.setString(descriptionLiteral.stringValue());
									if (descriptionLiteral.getLanguage() != null) {
										descriptionString.newLanguage().setValue(descriptionLiteral.getLanguage());
									}
									statistics.relationResourceDescription++;
								}
							}
						}
					}

					relationCount++;
				}
			}
		}
	}
	
	// 8 Annotation
	
	public void convertAnnotation(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> annotations = input.match(resourceURI, createURI(NS.lom, "annotation"), null);
		int annotationCount = 0;
		while (annotations.hasNext()) {
			Value resource = annotations.next().getObject();
			if (resource instanceof Resource) {
				
				// 8.1 Entity
				
				Iterator<Statement> entityStmnts = input.match((Resource) resource, createURI(NS.lom, "entity"), null);
				if (entityStmnts.hasNext()) {
					Statement entityStmnt = entityStmnts.next();
					Value entity = entityStmnt.getObject();
					if (entity instanceof Resource) {
						VCard vcard = null;
						try {
							vcard = EntityImpl.parseEntity(LOM.Annotation.Entity.TYPE, convertGraph2VCardString(input, (Resource) entity), false);
						} catch (ParseException e) {
							log.warn("Unable to convert LOM 8.1 Entity of " + resourceURI + " to VCard: " + e.getMessage());
						}
						if (vcard != null) {
							lom.newAnnotation(annotationCount).newEntity().setVCard(vcard);
							statistics.annotationEntity++;
						}
					}
				}
				
				// 8.2 Date
				
				Iterator<Statement> dateStmnts = input.match((Resource) resource, createURI(NS.dcterms, "date"), null);
				if (dateStmnts.hasNext()) {
					Statement dateStmnt = dateStmnts.next();
					Value date = dateStmnt.getObject();
					if (date instanceof Literal) {
						Literal dateLiteral = (Literal) date;						
						if (createURI(NS.dcterms, "W3CDTF").equals(dateLiteral.getDatatype())) {
							GregorianCalendar dateValue = null;
							try {
								dateValue = dateLiteral.calendarValue().toGregorianCalendar();
							} catch (IllegalArgumentException iae) {
								Date parsedDate = parseDateFromString(dateLiteral.stringValue());
								if (parsedDate != null) {
									dateValue = new GregorianCalendar();
									dateValue.setTime(parsedDate);
								} else {
									log.warn("Unable to parse 8.2 Date of " + resourceURI + ": " + iae.getMessage());
								}
							}
							if (dateValue != null) {
								lom.newAnnotation(annotationCount).newDate().newValue().setDateTime(dateValue);
							}
							statistics.annotationDate++;
						} else {
							StringImpl dateDescription = lom.newAnnotation(annotationCount).newDate().newDescription().newString(-1);
							dateDescription.setString(dateLiteral.stringValue());
							if (dateDescription.getLangString() != null) {
								dateDescription.newLanguage().setValue(dateLiteral.getLanguage());
							}
						}
					}
				}
				
				// 8.3 Description
				
				Iterator<Statement> descriptions = input.match((Resource) resource, createURI(NS.dcterms, "description"), null);
				while (descriptions.hasNext()) {
					Value object = descriptions.next().getObject();
					if (object instanceof Resource) {
						Iterator<Statement> descriptionValueStmnts = input.match((Resource) object, RDF.VALUE, null);
						while (descriptionValueStmnts.hasNext()) {
							Statement descriptionValueStmnt = descriptionValueStmnts.next();
							Value description = descriptionValueStmnt.getObject();
							if (description instanceof Literal) {
								Literal descriptionLiteral = (Literal) description;
								StringImpl descriptionString = lom.newAnnotation(annotationCount).newDescription().newString(-1);
								descriptionString.setString(descriptionLiteral.stringValue());
								if (descriptionLiteral.getLanguage() != null) {
									descriptionString.newLanguage().setValue(descriptionLiteral.getLanguage());
								}
								statistics.annotationDescription++;
							}
						}
					}
				}
				
				// Validation
				
				Iterator<Statement> validationStmnts = input.match((Resource) resource, createURI(NS.oe, "validationStatus"), null);
				if (validationStmnts.hasNext()) {
					Value validationValue = validationStmnts.next().getObject();
					if (validationValue instanceof org.openrdf.model.URI) {
						org.openrdf.model.URI acceptedURI = createURI(NS.lrevoc, "Accepted");
						org.openrdf.model.URI rejectedURI = createURI(NS.lrevoc, "Rejected");
						String validationDescription = null;
						if (validationValue.equals(acceptedURI)) {
							validationDescription = "Quality certified";
						} else if (validationValue.equals(rejectedURI)) {
							validationDescription = "Validation status: Rejected";
						}
						StringImpl descriptionString = lom.newAnnotation(annotationCount).newDescription().newString(-1);
						descriptionString.setString(validationDescription);
						descriptionString.newLanguage().setValue("en");
					}
				}
				
				annotationCount++;
			}
		}	
	}
	
	// 9 Classification
	
	public void convertClassification(Graph input, LOMImpl lom, URI resourceURI) {
		Iterator<Statement> classifications = input.match(resourceURI, createURI(NS.lom, "annotation"), null);
		int classificationCount = 0;
		while (classifications.hasNext()) {
			Value resource = classifications.next().getObject();
			if (resource instanceof Resource) {
				
				// 9.1 Purpose
				
				Iterator<Statement> purposeItems = input.match((Resource) resource, createURI(NS.lom, "purpose"), null);
				while (purposeItems.hasNext()) {
					Value purposeValue = purposeItems.next().getObject();
					if (purposeValue instanceof URI) {
						URI difficultyURI = (URI) purposeValue;
						String difficultyURIStr = difficultyURI.stringValue();
						String difficulty = null;
										
						if (difficultyURIStr.equals(NS.lomvoc + "Difficulty-veryEasy")) {
							difficulty = LOM.Educational.Difficulty.VERY_EASY;
						} else if (difficultyURIStr.equals(NS.lomvoc + "Difficulty-easy")) {
							difficulty = LOM.Educational.Difficulty.EASY;
						} else if (difficultyURIStr.equals(NS.lomvoc + "Difficulty-medium")) {
							difficulty = LOM.Educational.Difficulty.MEDIUM;
						} else if (difficultyURIStr.equals(NS.lomvoc + "Difficulty-difficult")) {
							difficulty = LOM.Educational.Difficulty.DIFFICULT;
						} else if (difficultyURIStr.equals(NS.lomvoc + "Difficulty-veryDifficult")) {
							difficulty = LOM.Educational.Difficulty.VERY_DIFFICULT;
						}
						
						if (difficulty != null) {
							lom.newClassification(classificationCount).newPurpose().newValue().setString(difficulty);
							lom.newClassification(classificationCount).newPurpose().newSource().setString(LOM.LOM_V1P0_VOCABULARY);
							statistics.classificationPurpose++;
						}
					}
				}
				
				// 9.2 Taxon Path
				
				Iterator<Statement> taxonPathElements = input.match((Resource) resource, createURI(NS.lom, "taxonPath"), null);
				int taxonPathCount = 0;
				while (taxonPathElements.hasNext()) {
					Value taxonValue = classifications.next().getObject();
					if (taxonValue instanceof Resource) {

						// 9.2.1 Source
						
						Iterator<Statement> taxonSources = input.match((Resource) taxonValue, createURI(NS.lom, "taxonSource"), null);
						while (taxonSources.hasNext()) {
							Value taxonObject = taxonSources.next().getObject();
							if (taxonObject instanceof Resource) {
								Iterator<Statement> taxonSourceValueStmnts = input.match((Resource) taxonObject, RDF.VALUE, null);
								while (taxonSourceValueStmnts.hasNext()) {
									Statement taxonSourceValueStmnt = taxonSourceValueStmnts.next();
									Value taxonSource = taxonSourceValueStmnt.getObject();
									if (taxonSource instanceof Literal) {
										Literal taxonSourceLiteral = (Literal) taxonSource;
										StringImpl taxonSourceString = lom.newClassification(classificationCount).newTaxonPath(taxonPathCount).newTaxon(-1).newEntry().newString(-1);
										taxonSourceString.setString(taxonSourceLiteral.stringValue());
										if (taxonSourceLiteral.getLanguage() != null) {
											taxonSourceString.newLanguage().setValue(taxonSourceLiteral.getLanguage());
										}
										statistics.classificationTaxonPathSource++;
									}
								}
							}
						}

						// 9.2.2 Taxon
						
						Iterator<Statement> taxons = input.match((Resource) taxonValue, null, null);
						int taxonCount = 0;
						while (taxons.hasNext()) {
							Statement taxonStmnt = taxons.next();
							URI taxonPred = taxonStmnt.getPredicate();
							
							if (!taxonPred.stringValue().startsWith("rdf:_")) {
								// we continue if the predicate is not derived from RDFS.MEMBER
								// this should be done through infering, but this seems to be the easiest solution
								continue;
							}
							
							Value taxonObject = taxonStmnt.getObject();
							if (taxonObject instanceof Resource) {
								Iterator<Statement> taxonValueStmnts = input.match((Resource) taxonObject, RDF.VALUE, null);
								while (taxonValueStmnts.hasNext()) {
									Value taxonResource = taxonValueStmnts.next().getObject();
									if (taxonResource instanceof Resource) {
										
										// 9.2.2.1 Id
										
										Iterator<Statement> taxonIDs = input.match((Resource) taxonResource, createURI(NS.dcterms, "identifier"), null);
										if (taxonIDs.hasNext()) {
											Value taxonID = taxonIDs.next().getObject();
											if (taxonID instanceof Literal) {
												lom.newClassification(classificationCount).newTaxonPath(taxonPathCount).newTaxon(taxonCount).newId().setString(taxonID.stringValue());
												statistics.classificationTaxonPathTaxonId++;
											}
										}

										// 9.2.2.2 Entry
										
										Iterator<Statement> taxonEntries = input.match((Resource) taxonResource, RDFS.LABEL, null);
										if (taxonEntries.hasNext()) {
											Value taxonEntry = taxonEntries.next().getObject();
											if (taxonEntry instanceof Literal) {
												lom.newClassification(classificationCount).newTaxonPath(taxonPathCount).newTaxon(taxonCount).newEntry().newString(-1).setString(taxonEntry.stringValue());
												statistics.classificationTaxonPathTaxonEntry++;
											}
										}
										
										taxonCount++;
									}
								}
							}
						}
						
						taxonPathCount++;
					}
				}
				
				// 9.3 Description
				
				Iterator<Statement> descriptions = input.match((Resource) resource, createURI(NS.dcterms, "description"), null);
				while (descriptions.hasNext()) {
					Value object = descriptions.next().getObject();
					if (object instanceof Resource) {
						Iterator<Statement> descriptionValueStmnts = input.match((Resource) object, RDF.VALUE, null);
						while (descriptionValueStmnts.hasNext()) {
							Statement descriptionValueStmnt = descriptionValueStmnts.next();
							Value description = descriptionValueStmnt.getObject();
							if (description instanceof Literal) {
								Literal descriptionLiteral = (Literal) description;
								StringImpl descriptionString = lom.newClassification(classificationCount).newDescription().newString(-1);
								descriptionString.setString(descriptionLiteral.stringValue());
								if (descriptionLiteral.getLanguage() != null) {
									descriptionString.newLanguage().setValue(descriptionLiteral.getLanguage());
								}
								statistics.classificationDescription++;
							}
						}
					}
				}
				
				// 9.4 Keyword
				
				Iterator<Statement> keywords = input.match((Resource) resource, createURI(NS.lom, "keyword"), null);
				int keywordCount = 0;
				while (keywords.hasNext()) {
					Value object = keywords.next().getObject();
					if (object instanceof Resource) {
						Iterator<Statement> keywordValueStmnts = input.match((Resource) object, RDF.VALUE, null);
						while (keywordValueStmnts.hasNext()) {
							Statement keywordValueStmnt = keywordValueStmnts.next();
							Value keyword = keywordValueStmnt.getObject();
							if (keyword instanceof Literal) {
								Literal keywordLiteral = (Literal) keyword;
								StringImpl keywordString = lom.newClassification(-1).newKeyword(keywordCount).newString(-1);
								keywordString.setString(keywordLiteral.stringValue());
								String keywordLiteralLang = keywordLiteral.getLanguage();
								if (keywordLiteralLang != null) {
									keywordString.newLanguage().setValue(keywordLiteralLang);
								}
								statistics.classificationKeyword++;
							}
						}
						keywordCount++;
					}
				}
				
				classificationCount++;
			}
		}
	}

	public LOM convertGraphToLom(Graph input, URI resourceURI, URI metadataURI) {
		LOMImpl lom = new LOMImpl();
		convertAll(input, lom, resourceURI, metadataURI);
		return lom;
	}
	
	public static String createVCardString(String formattedName, String email, String organization, String structuredName) {
		StringBuffer sb = new StringBuffer();
		sb.append("BEGIN:VCARD\n");
		if (formattedName != null) {
			sb.append("FN:");
			//sb.append(MimeDirUtil.escapeTextValue(removeLinebreaks(formattedName.trim()), true));
			sb.append(removeLinebreaks(formattedName.trim()));
			sb.append("\n");
		}
		if (email != null) {
			sb.append("EMAIL;TYPE=INTERNET:");
			sb.append(removeLinebreaks(email.trim()));
			sb.append("\n");
		}
		if (organization != null) {
			sb.append("ORG:");
			sb.append(removeLinebreaks(organization.trim()));
			sb.append("\n");
		}
		if (structuredName != null) {
			sb.append("N:");
			sb.append(removeLinebreaks(structuredName.trim()));
			sb.append("\n");
		} else {
			if (formattedName != null) {
				// convert FN to N
				String fName = removeLinebreaks(formattedName.trim());
				String[] tokens = fName.split(" ");
				if (tokens.length > 1) {
					fName = tokens[tokens.length - 1] + ";";
					for (int i = 0; i < tokens.length - 1; i++) {
						fName += tokens[i] + " ";
					}
				}
				sb.append("N:");
				sb.append(fName.trim());
				sb.append("\n");
			}
		}
		sb.append("VERSION:3.0\n");
		sb.append("END:VCARD");

		return sb.toString();
	}
	
	public static String removeLinebreaks(String input) {
		return input.replace("\n", "").replace("\r", "");
	}
	
	public static String convertGraph2VCardString(Graph graph, Resource rootNode) {
		if (graph == null || rootNode == null) {
			return null;
		}
		
		String fnStr = null;
		String nStr = null;
		String emailStr = null;
		String orgStr = null;
		
		Iterator<Statement> fnStmnts = graph.match(rootNode, createURI(NS.vcard, "FN"), null);
		if (fnStmnts.hasNext()) {
			Value fnValue = fnStmnts.next().getObject();
			if (fnValue instanceof Literal) {
				fnStr = fnValue.stringValue();
			}
		}
		
		Iterator<Statement> nStmnts = graph.match(rootNode, createURI(NS.vcard, "N"), null);
		if (nStmnts.hasNext()) {
			Value nValue = nStmnts.next().getObject();
			if (nValue instanceof Literal) {
				nStr = nValue.stringValue();
			}
		}
		
		Iterator<Statement> emailStmnts = graph.match(rootNode, createURI(NS.vcard, "EMAIL"), null);
		if (emailStmnts.hasNext()) {
			Value emailValue = emailStmnts.next().getObject();
			if (emailValue instanceof Literal) {
				emailStr = emailValue.stringValue();
			}
		}
		
		Iterator<Statement> orgStmnts = graph.match(rootNode, createURI(NS.vcard, "ORG"), null);
		if (orgStmnts.hasNext()) {
			Value orgValue = orgStmnts.next().getObject();
			if (orgValue instanceof Resource) {
				Iterator<Statement> orgNameStmnts = graph.match((Resource) orgValue, createURI(NS.vcard, "Orgname"), null);
				if (orgNameStmnts.hasNext()) {
					Value orgNameValue = orgNameStmnts.next().getObject();
					if (orgNameValue instanceof Literal) {
						orgStr = orgNameValue.stringValue();
					} else if (orgNameValue instanceof URI) {
						URI orgURI = (URI) orgNameValue;
						if (OERDF2LOMConverter.ORGANIZATIONS.containsKey(orgURI)) {
							orgStr = OERDF2LOMConverter.ORGANIZATIONS.get(orgURI);
						} else {
							orgStr = orgURI.stringValue();
						}
					}
				}
			}
		}
		
		return createVCardString(fnStr, emailStr, orgStr, nStr);
	}
	
	public class Counters {
		
		private String SEP = ",";
		
		/**
		 * @return Returns a comma-separated list of metadata field counters.
		 */
		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			
			sb.append(resourceURI);
			sb.append(SEP);
			sb.append(generalIdentifier);
			sb.append(SEP);
			sb.append(generalTitle);
			sb.append(SEP);
			sb.append(generalLanguage);
			sb.append(SEP);
			sb.append(generalDescription);
			sb.append(SEP);
			sb.append(generalKeyword);
			sb.append(SEP);
			sb.append(generalCoverage);
			sb.append(SEP);
			sb.append(generalStructure);
			sb.append(SEP);
			sb.append(generalAggregationLevel);
			sb.append(SEP);
			sb.append(lifeCycleVersion);
			sb.append(SEP);
			sb.append(lifeCycleStatus);
			sb.append(SEP);
			sb.append(lifeCycleContributeRole);
			sb.append(SEP);
			sb.append(lifeCycleContributeEntity);
			sb.append(SEP);
			sb.append(lifeCycleContributeDate);
			sb.append(SEP);
			sb.append(metaMetadataIdentifierCatalog);
			sb.append(SEP);
			sb.append(metaMetadataIdentifierEntry);
			sb.append(SEP);
			sb.append(metaMetadataContributeRole);
			sb.append(SEP);
			sb.append(metaMetadataContributeEntity);
			sb.append(SEP);
			sb.append(metaMetadataContributeDate);
			sb.append(SEP);
			sb.append(metaMetadataSchema);
			sb.append(SEP);
			sb.append(metaMetadataLanguage);
			sb.append(SEP);
			sb.append(technicalFormat);
			sb.append(SEP);
			sb.append(technicalSize);
			sb.append(SEP);
			sb.append(technicalLocation);
			sb.append(SEP);
			sb.append(technicalRequirementType);
			sb.append(SEP);
			sb.append(technicalRequirementName);
			sb.append(SEP);
			sb.append(technicalRequirementMinimumVersion);
			sb.append(SEP);
			sb.append(technicalRequirementMaximumVersion);
			sb.append(SEP);
			sb.append(technicalInstallationRemarks);
			sb.append(SEP);
			sb.append(technicalOtherPlatformRequirements);
			sb.append(SEP);
			sb.append(technicalDuration);
			sb.append(SEP);
			sb.append(technicalFacetName);
			sb.append(SEP);
			sb.append(technicalFacetValue);
			sb.append(SEP);
			sb.append(technicalFacetDescription);
			sb.append(SEP);
			sb.append(educationalInteractivityType);
			sb.append(SEP);
			sb.append(educationalLearningResourceType);
			sb.append(SEP);
			sb.append(educationalInteractivityLevel);
			sb.append(SEP);
			sb.append(educationalSemanticDensity);
			sb.append(SEP);
			sb.append(educationalIntendedEndUserRole);
			sb.append(SEP);
			sb.append(educationalContext);
			sb.append(SEP);
			sb.append(educationalTypicalAgeRange);
			sb.append(SEP);
			sb.append(educationalDifficulty);
			sb.append(SEP);
			sb.append(educationalTypicalLearningTime);
			sb.append(SEP);
			sb.append(educationalDescription);
			sb.append(SEP);
			sb.append(educationalLanguage);
			sb.append(SEP);
			sb.append(rightsCost);
			sb.append(SEP);
			sb.append(rightsCostCopyrightAndOtherRestrictions);
			sb.append(SEP);
			sb.append(rightsDescription);
			sb.append(SEP);
			sb.append(relationKind);
			sb.append(SEP);
			sb.append(relationResourceIdentifierCatalog);
			sb.append(SEP);
			sb.append(relationResourceIdentifierEntry);
			sb.append(SEP);
			sb.append(relationResourceDescription);
			sb.append(SEP);
			sb.append(annotationEntity);
			sb.append(SEP);
			sb.append(annotationDate);
			sb.append(SEP);
			sb.append(annotationDescription);
			sb.append(SEP);
			sb.append(classificationPurpose);
			sb.append(SEP);
			sb.append(classificationTaxonPathSource);
			sb.append(SEP);
			sb.append(classificationTaxonPathTaxonId);
			sb.append(SEP);
			sb.append(classificationTaxonPathTaxonEntry);
			sb.append(SEP);
			sb.append(classificationDescription);
			sb.append(SEP);
			sb.append(classificationKeyword);
			
			return sb.toString();
		}
		
		/**
		 * @return Returns a comma-separated list of the name of the counters
		 *         which can be obtained with toString().
		 */
		public String getHeaders() {
			StringBuilder sb = new StringBuilder();
			
			sb.append("Resource URI");
			sb.append(SEP);
			sb.append("1.1 General Identifier");
			sb.append(SEP);
			sb.append("1.2 General Title");
			sb.append(SEP);
			sb.append("1.3 General Language");
			sb.append(SEP);
			sb.append("1.4 General Description");
			sb.append(SEP);
			sb.append("1.5 General Keyword");
			sb.append(SEP);
			sb.append("1.6 General Coverage");
			sb.append(SEP);
			sb.append("1.7 General Structure");
			sb.append(SEP);
			sb.append("1.8 General Aggregation Level");
			sb.append(SEP);
			sb.append("2.1 LifeCycle Version");
			sb.append(SEP);
			sb.append("2.2 LifeCycle Status");
			sb.append(SEP);
			sb.append("2.3.1 LifeCycle  Contribute Role");
			sb.append(SEP);
			sb.append("2.3.2 LifeCycle Contribute Entity");
			sb.append(SEP);
			sb.append("2.3.3 LifeCycle Contribute Date");
			sb.append(SEP);
			sb.append("3.1.1 MetaMetadata Identifier Catalog");
			sb.append(SEP);
			sb.append("3.1.2 MetaMetadata Identifier Entry");
			sb.append(SEP);
			sb.append("3.2.1 MetaMetadata Contribute Role");
			sb.append(SEP);
			sb.append("3.2.2 MetaMetadata Contribute Entity");
			sb.append(SEP);
			sb.append("3.2.3 MetaMetadata Contribute Date");
			sb.append(SEP);
			sb.append("3.3 MetaMetadata Schema");
			sb.append(SEP);
			sb.append("3.4 MetaMetadata Language");
			sb.append(SEP);
			sb.append("4.1 Technical Format");
			sb.append(SEP);
			sb.append("4.2 Technical Size");
			sb.append(SEP);
			sb.append("4.3 Technical Location");
			sb.append(SEP);
			sb.append("4.4.1.1 Technical Requirement Type");
			sb.append(SEP);
			sb.append("4.4.1.2 Technical Requirement Name");
			sb.append(SEP);
			sb.append("4.4.1.3 Technical Requirement Minimum Version");
			sb.append(SEP);
			sb.append("4.4.1.4 Technical Requirement Maximum Version");
			sb.append(SEP);
			sb.append("4.5 Technical Installation Remarks");
			sb.append(SEP);
			sb.append("4.6 Technical Other Platform Requirements");
			sb.append(SEP);
			sb.append("4.7 Technical Duration");
			sb.append(SEP);
			sb.append("4.8.1 Technical Facet Name");
			sb.append(SEP);
			sb.append("4.8.2 Technical Facet Value");
			sb.append(SEP);
			sb.append("4.8.3 Technical Facet Description");
			sb.append(SEP);
			sb.append("5.1 Educational Interactivity Type");
			sb.append(SEP);
			sb.append("5.2 Educational Learning Resource Type");
			sb.append(SEP);
			sb.append("5.3 Educational Interactivity Level");
			sb.append(SEP);
			sb.append("5.4 Educational Semantic Density");
			sb.append(SEP);
			sb.append("5.5 Educational Intended End User Role");
			sb.append(SEP);
			sb.append("5.6 Educational Context");
			sb.append(SEP);
			sb.append("5.7 Educational Typical Age Range");
			sb.append(SEP);
			sb.append("5.8 Educational Difficulty");
			sb.append(SEP);
			sb.append("5.9 Educational Typical Learning Time");
			sb.append(SEP);
			sb.append("5.10 Educational Description");
			sb.append(SEP);
			sb.append("5.11 Educational Language");
			sb.append(SEP);
			sb.append("6.1 Rights Cost");
			sb.append(SEP);
			sb.append("6.2 Rights Cost Copyright And Other Restrictions");
			sb.append(SEP);
			sb.append("6.3 Rights Description");
			sb.append(SEP);
			sb.append("7.1 Relation Kind");
			sb.append(SEP);
			sb.append("7.2.1.1 Relation Resource Identifier Catalog");
			sb.append(SEP);
			sb.append("7.2.1.2 Relation Resource Identifier Entry");
			sb.append(SEP);
			sb.append("7.2.2 Relation Resource Description");
			sb.append(SEP);
			sb.append("8.1 Annotation Entity");
			sb.append(SEP);
			sb.append("8.2 Annotation Date");
			sb.append(SEP);
			sb.append("8.3 Annotation Description");
			sb.append(SEP);
			sb.append("9.1 Classification Purpose");
			sb.append(SEP);
			sb.append("9.2.1 Classification TaxonPathSource");
			sb.append(SEP);
			sb.append("9.2.2.1 Classification TaxonPathTaxonId");
			sb.append(SEP);
			sb.append("9.2.2.2 Classification TaxonPathTaxonEntry");
			sb.append(SEP);
			sb.append("9.3 Classification Description");
			sb.append(SEP);
			sb.append("9.4 Classification Keyword");
			
			return sb.toString();
		}
		
		String resourceURI;
		
		// 1.1
		int generalIdentifier = 0;

		// 1.2
		int generalTitle = 0;

		// 1.3
		int generalLanguage = 0;

		// 1.4
		int generalDescription = 0;

		// 1.5
		int generalKeyword = 0;

		// 1.6
		int generalCoverage = 0;

		// 1.7
		int generalStructure = 0;

		// 1.8
		int generalAggregationLevel = 0;

		// 2.1
		int lifeCycleVersion = 0;

		// 2.2
		int lifeCycleStatus = 0;

		// 2.3.1
		int lifeCycleContributeRole = 0;
		
		// 2.3.2
		int lifeCycleContributeEntity = 0;
		
		// 2.3.3
		int lifeCycleContributeDate = 0;

		// 3.1.1
		int metaMetadataIdentifierCatalog = 0;

		// 3.1.2
		int metaMetadataIdentifierEntry = 0;

		// 3.2.1
		int metaMetadataContributeRole = 0;

		// 3.2.2
		int metaMetadataContributeEntity = 0;

		// 3.2.3
		int metaMetadataContributeDate = 0;

		// 3.3
		int metaMetadataSchema = 0;

		// 3.4
		int metaMetadataLanguage = 0;

		// 4.1
		int technicalFormat = 0;

		// 4.2
		int technicalSize = 0;

		// 4.3
		int technicalLocation = 0;

		// 4.4.1.1
		int technicalRequirementType = 0;

		// 4.4.1.2
		int technicalRequirementName = 0;

		// 4.4.1.3
		int technicalRequirementMinimumVersion = 0;

		// 4.4.1.4
		int technicalRequirementMaximumVersion = 0;

		// 4.5
		int technicalInstallationRemarks = 0;

		// 4.6
		int technicalOtherPlatformRequirements = 0;

		// 4.7
		int technicalDuration = 0;

		// 4.8.1
		int technicalFacetName = 0;

		// 4.8.2
		int technicalFacetValue = 0;

		// 4.8.3
		int technicalFacetDescription = 0;

		// 5.1
		int educationalInteractivityType = 0;

		// 5.2
		int educationalLearningResourceType = 0;

		// 5.3
		int educationalInteractivityLevel = 0;

		// 5.4
		int educationalSemanticDensity = 0;

		// 5.5
		int educationalIntendedEndUserRole = 0;

		// 5.6
		int educationalContext = 0;

		// 5.7
		int educationalTypicalAgeRange = 0;

		// 5.8
		int educationalDifficulty = 0;

		// 5.9
		int educationalTypicalLearningTime = 0;

		// 5.10
		int educationalDescription = 0;

		// 5.11
		int educationalLanguage = 0;

		// 6.1
		int rightsCost = 0;

		// 6.2
		int rightsCostCopyrightAndOtherRestrictions = 0;

		// 6.3
		int rightsDescription = 0;

		// 7.1
		int relationKind = 0;

		// 7.2.1.1
		int relationResourceIdentifierCatalog = 0;

		// 7.2.1.2
		int relationResourceIdentifierEntry = 0;

		// 7.2.2
		int relationResourceDescription = 0;

		// 8.1
		int annotationEntity = 0;

		// 8.2
		int annotationDate = 0;

		// 8.3
		int annotationDescription = 0;

		// 9.1
		int classificationPurpose = 0;

		// 9.2.1
		int classificationTaxonPathSource = 0;

		// 9.2.2.1
		int classificationTaxonPathTaxonId = 0;

		// 9.2.2.2
		int classificationTaxonPathTaxonEntry = 0;

		// 9.3
		int classificationDescription = 0;

		// 9.4
		int classificationKeyword = 0;
	}

}