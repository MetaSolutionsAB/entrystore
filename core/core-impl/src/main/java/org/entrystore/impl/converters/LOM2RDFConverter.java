/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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

package org.entrystore.impl.converters;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;

import org.entrystore.Converter;
import org.entrystore.repository.util.NS;
import org.ieee.ltsc.datatype.impl.EntityImpl;
import org.ieee.ltsc.lom.LOM;
import org.ieee.ltsc.lom.LOMUtil;
import org.ieee.ltsc.lom.LOM.Educational.Context;
import org.ieee.ltsc.lom.LOM.Educational.IntendedEndUserRole;
import org.ieee.ltsc.lom.LOM.Educational.LearningResourceType;
import org.ieee.ltsc.lom.LOM.Educational.TypicalAgeRange;
import org.ieee.ltsc.lom.LOM.Educational.TypicalLearningTime;
import org.ieee.ltsc.lom.LOM.General.Identifier;
import org.ieee.ltsc.lom.LOM.LifeCycle.Version;
import org.ieee.ltsc.lom.LOM.LifeCycle.Contribute.Date;
import org.ieee.ltsc.lom.LOM.LifeCycle.Contribute.Entity;
import org.ieee.ltsc.lom.LOM.Rights.CopyrightAndOtherRestrictions;
import org.ieee.ltsc.lom.LOM.Rights.Cost;
import org.ieee.ltsc.lom.LOM.Rights.Description;
import org.ieee.ltsc.lom.LOM.Technical.Duration;
import org.ieee.ltsc.lom.LOM.Technical.InstallationRemarks;
import org.ieee.ltsc.lom.LOM.Technical.Location;
import org.ieee.ltsc.lom.LOM.Technical.OtherPlatformRequirements;
import org.ieee.ltsc.lom.LOM.Technical.Requirement;
import org.ieee.ltsc.lom.LOM.Technical.Size;
import org.ieee.ltsc.lom.LOM.Technical.Requirement.OrComposite;
import org.ieee.ltsc.lom.LOM.Technical.Requirement.OrComposite.MaximumVersion;
import org.ieee.ltsc.lom.LOM.Technical.Requirement.OrComposite.MinimumVersion;
import org.ieee.ltsc.lom.LOM.Technical.Requirement.OrComposite.Name;
import org.ieee.ltsc.lom.LOM.Technical.Requirement.OrComposite.Type;
import org.ieee.ltsc.lom.impl.LOMImpl;
import org.ietf.mimedir.MimeDir.ContentLine;
import org.ietf.mimedir.util.MimeDirUtil;
import org.ietf.mimedir.vcard.VCard;
import org.openrdf.model.BNode;
import org.openrdf.model.Graph;
import org.openrdf.model.Literal;
import org.openrdf.model.Resource;
import org.openrdf.model.URI;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.model.impl.LiteralImpl;
import org.openrdf.model.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;


/**
 * Converts LOM to an RDF graph (supporting LRE vocabularies).
 * 
 * @author Hannes Ebner
 */
public class LOM2RDFConverter implements Converter {
	
	protected static Logger log = LoggerFactory.getLogger(LOM2RDFConverter.class);
	
	protected boolean lreSupport = false;

	public Counters statistics = new Counters();
	
	protected ValueFactory vf = new GraphImpl().getValueFactory();

	public LOM2RDFConverter() {
		ConverterUtil.prepareLOMProcessing();
	}

	public Object convert(Object from, java.net.URI resourceURI, java.net.URI metadataURI) {
		if (from instanceof LOMImpl) {
			return convertLomToGraph((LOMImpl) from, resourceURI, metadataURI);
		} else if (from instanceof NodeList) {
			LOMImpl lom = ConverterUtil.readLOMfromReader((Node) from);
			if (lom != null) {
				return convertLomToGraph(lom, resourceURI, metadataURI);
			}
		}
		return null;
	}
	
	public Graph convertLomToGraph(LOMImpl input, java.net.URI resourceURI, java.net.URI metadataURI) {
		Graph graph = new GraphImpl();
		ValueFactory vf = graph.getValueFactory();
		URI sesameResURI = null;
		if (resourceURI != null) {
			sesameResURI = vf.createURI(resourceURI.toString());
		}
		URI sesameMdURI = null;
		if (sesameMdURI != null) {
			sesameMdURI = vf.createURI(metadataURI.toString());
		}
		convertAll(input, graph, sesameResURI, sesameMdURI);
		return graph;
	}
	
	public static URI createURI(String namespace, String uri) {
		ValueFactory vf = new GraphImpl().getValueFactory();
		if (namespace != null) {
			return vf.createURI(namespace, uri);
		}
		return vf.createURI(uri);
	}
	
	public static String createW3CDTF(java.util.Date date) {
		SimpleDateFormat W3CDTF = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
		return W3CDTF.format(date);
	}
	
	public boolean hasLRESupport() {
		return lreSupport;
	}

	public void setLRESupport(boolean lreSupport) {
		this.lreSupport = lreSupport;
	}
	
	public Graph convertVCard2Graph(VCard vcard, Resource rootNode) {
		if (rootNode == null) {
			return null;
		}
		
		Graph vcardStmnts = new GraphImpl();
		
		ContentLine fnCL = MimeDirUtil.getContentLineByName(vcard, "FN");
		if (fnCL != null) {
			String fnValue = MimeDirUtil.unescapeTextValue(fnCL.getMDCLValue().toString(), true).trim();
			Literal lit = vf.createLiteral(fnValue.substring(fnValue.indexOf(":") + 1));
			vcardStmnts.add(vf.createStatement(rootNode, createURI(NS.vcard, "FN"), lit));
		}
		
		ContentLine emailCL = MimeDirUtil.getContentLineByName(vcard, "EMAIL");
		if (emailCL != null) {
			String emailValue = MimeDirUtil.unescapeTextValue(emailCL.getMDCLValue().toString(), true).trim();
			Literal lit = vf.createLiteral(emailValue.substring(emailValue.indexOf(":") + 1));
			vcardStmnts.add(vf.createStatement(rootNode, createURI(NS.vcard, "EMAIL"), lit));
		}
		
		ContentLine[] orgCLs = MimeDirUtil.getContentLinesByName(vcard, "ORG");
		if (orgCLs != null) {
			BNode orgBNode = vf.createBNode();
			Graph orgStmns = new GraphImpl();
			for (ContentLine orgCL : orgCLs) {
				if (orgCL != null) {
					String orgValue = MimeDirUtil.unescapeTextValue(orgCL.getMDCLValue().toString(), true).trim();
					Literal lit = vf.createLiteral(orgValue.substring(orgValue.indexOf(":") + 1));
					orgStmns.add(vf.createStatement(orgBNode, createURI(NS.vcard, "Orgname"), lit));
				}
			}

			if (!orgStmns.isEmpty()) {
				vcardStmnts.addAll(orgStmns);
				vcardStmnts.add(vf.createStatement(rootNode, createURI(NS.vcard, "ORG"), orgBNode));
			}
		}
		
		return vcardStmnts;
	}

	// 1-9 All Categories
	
	public void convertAll(LOMImpl input, Graph graph, URI resourceURI, URI metadataURI) {
		statistics.resourceURI = "\"" + resourceURI.stringValue() + "\"";
		
		convertGeneral(input, graph, resourceURI);
		convertLifeCycle(input, graph, resourceURI);
		convertMetaMetadata(input, graph, metadataURI);
		convertTechnical(input, graph, resourceURI);
		convertEducational(input, graph, resourceURI);
		convertRights(input, graph, resourceURI);
		convertRelation(input, graph, resourceURI);
		convertAnnotation(input, graph, resourceURI);
		convertClassification(input, graph, resourceURI);
	}
	
	// 1 General
	
	public void convertGeneral(LOMImpl input, Graph graph, URI resourceURI) {
		if (LOMUtil.getGeneral(input) != null) {
			convertGeneralIdentifier(input, graph, resourceURI);
			convertGeneralTitle(input, graph, resourceURI);
			convertGeneralLanguage(input, graph, resourceURI);
			convertGeneralDescription(input, graph, resourceURI);
			convertGeneralKeyword(input, graph, resourceURI);
			convertGeneralCoverage(input, graph, resourceURI);
			convertGeneralStructure(input, graph, resourceURI);
			convertGeneralAggregationLevel(input, graph, resourceURI);	
		}
	}
	
	// 1.1 Identifier
	
	public void convertGeneralIdentifier(LOMImpl input, Graph graph, URI resourceURI) {
		Identifier[] ids = LOMUtil.getGeneralIdentifiers(input);
		if (ids == null) {
			return;
		}
		for (Identifier identifier : ids) {
			BNode idBNode = vf.createBNode();
			String catalog = identifier.getCatalog();
			String catalogStr = null;
			if (catalog != null) {
				catalogStr = catalog.trim();
			} else {
				log.warn("Identifier has no catalog, skipping");
				continue;
			}
			String entry = identifier.getEntry();
			String entryStr = null;
			if (entry != null) {
				entryStr = entry.trim();
			} else {
				log.warn("Identifier has no entry, skipping");
				continue;
			}

			graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "identifier"), idBNode));
			graph.add(vf.createStatement(idBNode, createURI(NS.lom, "catalog"), vf.createLiteral(catalogStr)));
			graph.add(vf.createStatement(idBNode, createURI(NS.lom, "entry"), vf.createLiteral(entryStr)));
			
			statistics.generalIdentifier++;
		}
	}
	
	// 1.2 Title
	
	public void convertGeneralTitle(LOMImpl input, Graph graph, URI resourceURI) {
		org.ieee.ltsc.lom.LOM.General.Title title = LOMUtil.getGeneralTitle(input);
		if (title != null) {
			for (int i = 0; title.string(i) != null; i++) {
				String lomTitle = LOMUtil.getString(title, i);
				if (lomTitle != null) {
					String titleStr = lomTitle.trim();
					String titleLang = LOMUtil.getLanguage(title, i);
					Literal literal = new LiteralImpl(titleStr, titleLang);
					graph.add(vf.createStatement(resourceURI, createURI(NS.dcterms, "title"), literal));
					statistics.generalTitle++;
				}
			}
		}
	}
	
	// 1.3 Language
	
	public void convertGeneralLanguage(LOMImpl input, Graph graph, URI resourceURI) {
		BNode bnode = vf.createBNode();
		Graph langStmnts = new GraphImpl();
		for (int i = 0; ; i++) {
			org.ieee.ltsc.lom.LOM.General.Language lang = LOMUtil.getGeneralLanguage(input, i);
			if (lang == null) {
				break;
			}
		
			String langStr = LOMUtil.getString(lang);
			if (langStr != null) {
				Literal literal = vf.createLiteral(langStr.trim(), createURI(NS.dcterms, "RFC4646"));
				langStmnts.add(vf.createStatement(bnode, RDF.VALUE, literal));
				statistics.generalLanguage++;
			}
		}
		
		if (!langStmnts.isEmpty()) {
			graph.add(vf.createStatement(resourceURI, createURI(NS.dcterms, "language"), bnode));
			graph.add(vf.createStatement(bnode, RDF.TYPE, createURI(NS.dcterms, "LinguisticSystem")));
			graph.addAll(langStmnts);
		}
	}
	
	// 1.4 Description
	
	public void convertGeneralDescription(LOMImpl input, Graph graph, URI resourceURI) {
		for (int i = 0; ; i++) {
			org.ieee.ltsc.lom.LOM.General.Description desc = LOMUtil.getGeneralDescription(input, i);
			if (desc == null) {
				break;
			}
			
			BNode bnode = vf.createBNode();
			Graph descStmnts = new GraphImpl();
			
			for (int j = 0; desc.string(j) != null; j++) {
				String descStr = LOMUtil.getString(desc, j);
				String descLang = LOMUtil.getLanguage(desc, j);
				if (descStr != null) {
					Literal literal = new LiteralImpl(descStr.trim(), descLang);
					descStmnts.add(vf.createStatement(bnode, RDF.VALUE, literal));
					statistics.generalDescription++;
				}
			}
			
			if (!descStmnts.isEmpty()) {
				graph.add(vf.createStatement(resourceURI, createURI(NS.dcterms, "description"), bnode));
				graph.addAll(descStmnts);
			}
		}
	}
		
	// 1.5 Keyword
	
	public void convertGeneralKeyword(LOMImpl input, Graph graph, URI resourceURI) {
		for (int i = 0; ; i++) {
			org.ieee.ltsc.lom.LOM.General.Keyword desc = LOMUtil.getGeneralKeyword(input, i);
			if (desc == null) {
				break;
			}
			
			BNode bnode = vf.createBNode();
			Graph descStmnts = new GraphImpl();
			
			for (int j = 0; desc.string(j) != null; j++) {
				String keywordStr = LOMUtil.getString(desc, j);
				String keywordLang = LOMUtil.getLanguage(desc, j);
				if (keywordStr != null) {
					Literal literal = vf.createLiteral(keywordStr.trim(), keywordLang);
					descStmnts.add(vf.createStatement(bnode, RDF.VALUE, literal));
					statistics.generalKeyword++;
				}
			}
			
			if (!descStmnts.isEmpty()) {
				graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "keyword"), bnode));
				graph.add(vf.createStatement(bnode, RDF.TYPE, createURI(NS.lom, "LangString")));
				graph.addAll(descStmnts);
			}
		}
	}
	
	// 1.6 Coverage
	
	public void convertGeneralCoverage(LOMImpl input, Graph graph, URI resourceURI) {
		for (int i = 0; ; i++) {
			org.ieee.ltsc.lom.LOM.General.Coverage cov = LOMUtil.getGeneralCoverage(input, i);
			if (cov == null) {
				break;
			}
			
			BNode bnode = vf.createBNode();
			Graph covStmnts = new GraphImpl();
			
			for (int j = 0; cov.string(j) != null; j++) {
				String covStr = LOMUtil.getString(cov, j);
				String covLang = LOMUtil.getLanguage(cov, j);
				if (covStr != null) {
					Literal literal = new LiteralImpl(covStr.trim(), covLang);
					covStmnts.add(vf.createStatement(bnode, RDF.VALUE, literal));
					statistics.generalCoverage++;
				}
			}
			
			if (!covStmnts.isEmpty()) {
				graph.add(vf.createStatement(resourceURI, createURI(NS.dcterms, "coverage"), bnode));
				graph.add(vf.createStatement(bnode, RDF.TYPE, createURI(NS.dcterms, "LocationPeriodOrJurisdiction")));
				graph.addAll(covStmnts);
			}
		}
	}
	
	// 1.7 Structure
	
	public void convertGeneralStructure(LOMImpl input, Graph graph, URI resourceURI) {
		String structure = LOMUtil.getValue(LOMUtil.getGeneralStructure(input));
		if (structure != null) {
			structure = structure.trim();
			URI structureURI = null;
			if (structure.equals(LOM.General.Structure.ATOMIC)) {
				structureURI = createURI(NS.lomvoc, "Structure-atomic");
			} else if (structure.equals(LOM.General.Structure.NETWORKED)) {
				structureURI = createURI(NS.lomvoc, "Structure-networked");
			} else if (structure.equals(LOM.General.Structure.HIERARCHICAL)) {
				structureURI = createURI(NS.lomvoc, "Structure-hierarchical");
			} else if (structure.equals(LOM.General.Structure.LINEAR)) {
				structureURI = createURI(NS.lomvoc, "Structure-linear");
			} else if (structure.equals(LOM.General.Structure.COLLECTION)) {
				structureURI = createURI(NS.lomvoc, "Structure-collection");
			}
			
			if (structureURI != null) {
				graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "structure"), structureURI));
				graph.add(vf.createStatement(structureURI, RDF.TYPE, createURI(NS.lom, "Structure")));
				statistics.generalStructure++;
			}
		}
	}
	
	// 1.8 Aggregation Level

	public void convertGeneralAggregationLevel(LOMImpl input, Graph graph, URI resourceURI) {
		String aggregationLevel = LOMUtil.getValue(LOMUtil.getGeneralAggregationLevel(input));
		if (aggregationLevel != null) {
			aggregationLevel = aggregationLevel.trim();
			URI aggLevelURI = null;
			if (aggregationLevel.equals(LOM.General.AggregationLevel.LEVEL1)) {
				aggLevelURI = createURI(NS.lomvoc, "AggregationLevel-1");
			} else if (aggregationLevel.equals(LOM.General.AggregationLevel.LEVEL2)) {
				aggLevelURI = createURI(NS.lomvoc, "AggregationLevel-2");
			} else if (aggregationLevel.equals(LOM.General.AggregationLevel.LEVEL3)) {
				aggLevelURI = createURI(NS.lomvoc, "AggregationLevel-3");
			} else if (aggregationLevel.equals(LOM.General.AggregationLevel.LEVEL4)) {
				aggLevelURI = createURI(NS.lomvoc, "AggregationLevel-4");
			}
			
			if (aggLevelURI != null) {
				graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "aggregationLevel"), aggLevelURI));
				graph.add(vf.createStatement(aggLevelURI, RDF.TYPE, createURI(NS.lom, "AggregationLevel")));
				statistics.generalAggregationLevel++;
			}
		}
	}
	
	// 2 Life Cycle
	
	public void convertLifeCycle(LOMImpl input, Graph graph, URI resourceURI) {
		if (LOMUtil.getLifeCycle(input) != null) {
			convertLifeCycleVersion(input, graph, resourceURI);
			convertLifeCycleStatus(input, graph, resourceURI);
			convertLifeCycleContribute(input, graph, resourceURI);
		}
	}
	
	// 2.1 Version
	
	public void convertLifeCycleVersion(LOMImpl input, Graph graph, URI resourceURI) {
		Version ver = LOMUtil.getLifeCycleVersion(input);
		if (ver != null) {
			BNode bnode = vf.createBNode();
			Graph verStmnts = new GraphImpl();
			for (int i = 0; ver.string(i) != null; i++) {
				String titleStr = LOMUtil.getString(ver, i);
				String titleLang = LOMUtil.getLanguage(ver, i);
				if (titleStr != null) {
					Literal literal = new LiteralImpl(titleStr.trim(), titleLang);
					verStmnts.add(graph.getValueFactory().createStatement(bnode, RDF.VALUE, literal));
					statistics.lifeCycleVersion++;
				}
			}
			
			if (!verStmnts.isEmpty()) {
				graph.add(vf.createStatement(bnode, RDF.TYPE, createURI(NS.lom, "Version")));
				graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "version"), bnode));
				graph.addAll(verStmnts);
			}
		}
	}

	// 2.2 Status
	
	public void convertLifeCycleStatus(LOMImpl input, Graph graph, URI resourceURI) {
		String lifeCycleStatus = LOMUtil.getValue(LOMUtil.getLifeCycleStatus(input));
		if (lifeCycleStatus != null) {
			lifeCycleStatus = lifeCycleStatus.trim();
			URI lifeCyleStatusURI = null;
			if (lifeCycleStatus.equals(LOM.LifeCycle.Status.DRAFT)) {
				lifeCyleStatusURI = createURI(NS.lomvoc, "Status-draft");
			} else if (lifeCycleStatus.equals(LOM.LifeCycle.Status.FINAL)) {
				lifeCyleStatusURI = createURI(NS.lomvoc, "Status-final");
			} else if (lifeCycleStatus.equals(LOM.LifeCycle.Status.REVISED)) {
				lifeCyleStatusURI = createURI(NS.lomvoc, "Status-revised");
			} else if (lifeCycleStatus.equals(LOM.LifeCycle.Status.UNAVAILABLE)) {
				lifeCyleStatusURI = createURI(NS.lomvoc, "Status-unavailable");
			}
			
			if (lifeCyleStatusURI != null) {
				graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "status"), lifeCyleStatusURI));
				graph.add(vf.createStatement(lifeCyleStatusURI, RDF.TYPE, createURI(NS.lom, "Status")));
				statistics.generalAggregationLevel++;
			}
		}
	}

	// 2.3 Contribute
	
	public void convertLifeCycleContribute(LOMImpl input, Graph graph, URI resourceURI) {
		for (int i = 0; ; i++) {
			if (LOMUtil.getLifeCycleContribute(input, i) == null) {
				break;
			}
			
			BNode bnode = vf.createBNode();
			Graph contribStmnts = new GraphImpl();
			
			// 2.3.1 Role
			
			String role = LOMUtil.getValue(LOMUtil.getLifeCycleContributeRole(input, i));
			if (role != null) {
				role = role.trim();
				String roleVocStr = null;

				if (role.equals(LOM.LifeCycle.Contribute.Role.AUTHOR)) {
					roleVocStr = "Role-author";
				} else if (role.equals(LOM.LifeCycle.Contribute.Role.CONTENT_PROVIDER)) {
					roleVocStr = "Role-contentProvider"; 
				} else if (role.equals(LOM.LifeCycle.Contribute.Role.EDITOR)) {
					roleVocStr = "Role-editor";
				} else if (role.equals(LOM.LifeCycle.Contribute.Role.EDUCATIONAL_VALIDATOR)) {
					roleVocStr = "Role-educationalValidator";
				} else if (role.equals(LOM.LifeCycle.Contribute.Role.GRAPHICAL_DESIGNER)) {
					roleVocStr = "Role-graphicalDesigner";
				} else if (role.equals(LOM.LifeCycle.Contribute.Role.INITIATOR)) {
					roleVocStr = "Role-initiator";
				} else if (role.equals(LOM.LifeCycle.Contribute.Role.INSTRUCTIONAL_DESIGNER)) {
					roleVocStr = "Role-instructionalDesigner";
				} else if (role.equals(LOM.LifeCycle.Contribute.Role.PUBLISHER)) {
					roleVocStr = "Role-publisher";
				} else if (role.equals(LOM.LifeCycle.Contribute.Role.SCRIPT_WRITER)) {
					roleVocStr = "Role-scriptWriter";
				} else if (role.equals(LOM.LifeCycle.Contribute.Role.SUBJECT_MATTER_EXPERT)) {
					roleVocStr = "Role-subjectMatterExpert";
				} else if (role.equals(LOM.LifeCycle.Contribute.Role.TECHNICAL_IMPLEMENTER)) {
					roleVocStr = "Role-techicalImplementer";
				} else if (role.equals(LOM.LifeCycle.Contribute.Role.TECHNICAL_VALIDATOR)) {
					roleVocStr = "Role-technicalValidator";
				} else if (role.equals(LOM.LifeCycle.Contribute.Role.TERMINATOR)) {
					roleVocStr = "Role-terminator";
				} else if (role.equals(LOM.LifeCycle.Contribute.Role.VALIDATOR)) {
					roleVocStr = "Role-validator";
				}
				
				if (roleVocStr != null) {
					URI roleURI = vf.createURI(NS.lomvoc, roleVocStr);
					contribStmnts.add(vf.createStatement(bnode, createURI(NS.lom, "role"), roleURI));
					contribStmnts.add(vf.createStatement(roleURI, RDF.TYPE, createURI(NS.lom, "Role")));
					statistics.lifeCycleContributeRole++;
				}
			}
			
			// 2.3.2 Entity
			
			for (int j = 0; ; j++) {
				Entity entity = LOMUtil.getLifeCycleContributeEntity(input, i, j);
				if (entity != null && entity.string() != null) {
					org.ietf.mimedir.vcard.VCard vcard = null;
					try {
						vcard = EntityImpl.parseEntity(LOM.LifeCycle.Contribute.Entity.TYPE, entity.string(), false);
					} catch (ParseException e) {
						log.warn(e.getMessage());
					}
					if (vcard != null) {
						BNode entityBNode = vf.createBNode();
						Graph entityStmnts = convertVCard2Graph(vcard, entityBNode);

						if (entityStmnts != null && !entityStmnts.isEmpty()) {
							contribStmnts.add(vf.createStatement(bnode, createURI(NS.lom, "entity"), entityBNode));
							contribStmnts.addAll(entityStmnts);
						}
						
						statistics.lifeCycleContributeEntity++;
					}
				} else {
					break;
				}
			}
			
			// 2.3.3 Date
			
			Date contributeDate = LOMUtil.getLifeCycleContributeDate(input, i);
			if (contributeDate != null) {
				Calendar date = LOMUtil.getDateTime(contributeDate);
				if (date != null) {
					String dateStr = createW3CDTF(date.getTime());
					Literal dateLiteral = vf.createLiteral(dateStr, createURI(NS.dcterms, "W3CDTF"));
					contribStmnts.add(vf.createStatement(bnode, createURI(NS.dcterms, "date"), dateLiteral));
				}
				for (int j = 0; ; j++) {
					String dateDesc = LOMUtil.getString(contributeDate.description(), j);
					String dateDescLang = LOMUtil.getLanguage(contributeDate.description(), j);
					if (dateDesc != null) {
						Literal dateDescLiteral = vf.createLiteral(dateDesc, dateDescLang);
						contribStmnts.add(vf.createStatement(bnode, createURI(NS.dcterms, "date"), dateDescLiteral));
						statistics.lifeCycleContributeDate++;
					} else {
						break;
					}
				}
			}
			
			// add statements to graph
			
			if (!contribStmnts.isEmpty()) {
				graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "contribution"), bnode));
				graph.add(vf.createStatement(bnode, RDF.TYPE, createURI(NS.lom, "Contribution")));
				graph.addAll(contribStmnts);
			}
		}
	}
	

	// 3 MetaMetadata
	
	public void convertMetaMetadata(LOMImpl input, Graph graph, URI metadataURI) {
		if (LOMUtil.getMetaMetadata(input) != null) {
			if (metadataURI == null) {
				log.warn("Metadata URI is null, unable to convert MetaMetadata properties");
				return;
			}
			convertMetaMetadataIdentifier(input, graph, metadataURI);
			convertMetaMetadataContribute(input, graph, metadataURI);
			convertMetaMetadataSchema(input, graph, metadataURI);
			convertMetaMetadataLanguage(input, graph, metadataURI);
		}
	}

	// 3.1 Identifier
	
	public void convertMetaMetadataIdentifier(LOMImpl input, Graph graph, URI metadataURI) {
		// TODO OE
		
//		lom.newMetaMetadata().newIdentifier(0).newCatalog().setString("URI");
//		lom.newMetaMetadata().newIdentifier(0).newEntry().setString(metadataURI.stringValue());
//		
//		Iterator<Statement> identifiers = input.match(metadataURI, createURI(NS.lom, "identifier"), null);
//		int idCount = 1;
//		while (identifiers.hasNext()) {
//			Value object = identifiers.next().getObject();
//			if (object instanceof Resource) {
//				
//				// 3.1.1 Catalog
//				
//				Iterator<Statement> catalog = input.match((Resource) object, createURI(NS.lom, "catalog"), null);
//				if (catalog.hasNext()) {
//					Value catalogObj = catalog.next().getObject();
//					if (catalogObj instanceof Literal) {
//						lom.newMetaMetadata().newIdentifier(idCount).newCatalog().setString(catalogObj.stringValue());
//						statistics.metaMetadataIdentifierCatalog++;
//					}
//				}
//				
//				// 3.1.2 Entry
//				
//				Iterator<Statement> entry = input.match((Resource) object, createURI(NS.lom, "entry"), null);
//				if (entry.hasNext()) {
//					Value entryObj = entry.next().getObject();
//					if (entryObj instanceof Literal) {
//						lom.newMetaMetadata().newIdentifier(idCount).newEntry().setString(entryObj.stringValue());
//						statistics.metaMetadataIdentifierEntry++;
//					}
//				}
//				
//				idCount++;
//			}
//		}
	}

	// 3.2 Contribute
	
	public void convertMetaMetadataContribute(LOMImpl input, Graph graph, URI metadataURI) {
		// TODO OE
		
//		Iterator<Statement> contributions = input.match(metadataURI, createURI(NS.lom, "contribute"), null);
//		int contributionCount = 0;
//		while (contributions.hasNext()) {
//			Value object = contributions.next().getObject();
//			if (object instanceof Resource) {
//				
//				// 3.2.1 Role
//				
//				Iterator<Statement> roleStmnts = input.match((Resource) object, createURI(NS.lom, "role"), null);
//				if (roleStmnts.hasNext()) {
//					Statement roleStmnt = roleStmnts.next();
//					Value role = roleStmnt.getObject();
//					if (role instanceof URI) {
//						URI roleURI = (URI) role;
//						String roleURIStr = roleURI.stringValue();
//						String roleVoc = null;
//						String vocSource = null;
//						
//						if (roleURIStr.equals(NS.lomvoc + "Role-creator")) {
//							roleVoc = LOM.MetaMetadata.Contribute.Role.CREATOR;
//						} else if (roleURIStr.equals(NS.lomvoc + "Role-validator")) {
//							roleVoc = LOM.MetaMetadata.Contribute.Role.VALIDATOR;
//						}
//						
//						if (roleVoc != null) {
//							vocSource = LOM.LOM_V1P0_VOCABULARY;
//						}
//						
//						if (roleVoc == null && lreSupport) {
//							if (roleURIStr.equals(LREVOC + "Role-enricher")) {
//								roleVoc = LRE.MetaMetadata.Contribute.Role.ENRICHER;
//							} else if (roleURIStr.equals(LREVOC + "Role-provider")) {
//								roleVoc = LRE.MetaMetadata.Contribute.Role.PROVIDER;
//							}
//							
//							if (roleVoc != null) {
//								vocSource = LRE.LRE_V3P0_VOCABULARY;
//							} 
//						}
//						
//						if (roleVoc != null) {
//							lom.newMetaMetadata().newContribute(contributionCount).newRole().newValue().setString(roleVoc);
//							if (vocSource != null) {
//								lom.newMetaMetadata().newContribute(contributionCount).newRole().newSource().setString(vocSource);	
//							}
//							statistics.metaMetadataContributeRole++;
//						}
//					}
//				}
//				
//				// 3.2.2 Entity
//
//				Iterator<Statement> entityStmnts = input.match((Resource) object, createURI(NS.lom, "entity"), null);
//				int entityCount = 0;
//				while (entityStmnts.hasNext()) {
//					Statement entityStmnt = entityStmnts.next();
//					Value entity = entityStmnt.getObject();
//					if (entity instanceof Literal) {
//						String entityStr = ((Literal) entity).stringValue();
//						if (entityStr != null) {
//							VCard vcard = null;
//							try {
//								vcard = EntityImpl.parseEntity(LOM.LifeCycle.Contribute.Entity.TYPE, entityStr, false);
//							} catch (ParseException e) {
//								log.warn("Unable to parse LOM 3.2.2 Entity of " + metadataURI + " to VCard: " + e.getMessage());
//								log.info("Trying to construct VCard with String as Name instead");
//								try {
//									vcard = EntityImpl.parseEntity(LOM.MetaMetadata.Contribute.Entity.TYPE, createVCardString(entityStr, null), false);
//								} catch (ParseException pe) {
//									log.warn("VCard construction failed: " + pe.getMessage());
//								}
//							}
//							if (vcard != null) {
//								lom.newMetaMetadata().newContribute(contributionCount).newEntity(entityCount).setVCard(vcard);
//								entityCount++;
//								statistics.metaMetadataContributeEntity++;
//							}
//						}
//					}
//				}
//				
//				// 3.2.3 Date
//				
//				Iterator<Statement> dateStmnts = input.match((Resource) object, createURI(NS.dcterms, "date"), null);
//				if (dateStmnts.hasNext()) {
//					Statement dateStmnt = dateStmnts.next();
//					Value date = dateStmnt.getObject();
//					if (date instanceof Literal) {
//						Literal dateLiteral = (Literal) date;
//						XMLGregorianCalendar dateValue = null;
//						try {
//							dateValue = dateLiteral.calendarValue();
//						} catch (IllegalArgumentException iae) {
//							log.warn("Unable to parse date: " + iae.getMessage());
//						}
//						if (dateValue != null) {
//							lom.newMetaMetadata().newContribute(contributionCount).newDate().newValue().setDateTime(dateValue.toGregorianCalendar());
//						}
//						statistics.metaMetadataContributeDate++;
//					}
//				}
//				
//				contributionCount++;
//			}
//		}
	}

	// 3.3 Metadata Schema
	
	public void convertMetaMetadataSchema(LOMImpl input, Graph graph, URI metadataURI) {
		// TODO OE
		
//		Iterator<Statement> schemaElements = input.match(metadataURI, createURI(NS.lom, "metadataScheme"), null);
//		while (schemaElements.hasNext()) {
//			Value object = schemaElements.next().getObject();
//			if (object instanceof URI) {
//				URI mdURI = (URI) object;
//				String mdURIStr = mdURI.stringValue();
//				String mdSchema = null;
//								
//				if (mdURIStr.equals(NS.lomvoc + "MetadataScheme-LOMv1.0")) {
//					mdSchema = LOM.LOM_V1P0_VOCABULARY;
//				} else if (mdURIStr.equals(LREVOC + "MetadataScheme-LREv3.0")) {
//					mdSchema = LRE.LRE_V3P0_VOCABULARY;
//				}
//				
//				if (mdSchema != null) {
//					lom.newMetaMetadata().newMetadataSchema(-1).setString(mdSchema);
//					statistics.metaMetadataSchema++;
//				}
//			}
//		}
	}

	// 3.4 Language
	
	public void convertMetaMetadataLanguage(LOMImpl input, Graph graph, URI metadataURI) {
		// TODO OE
		
//		Iterator<Statement> languages = input.match(metadataURI, createURI(NS.dcterms, "language"), null);
//		while (languages.hasNext()) {
//			Value object = languages.next().getObject();
//			if (object instanceof Resource) {
//				Iterator<Statement> lingSystems = input.match((Resource) object, RDF.VALUE, null);
//				while (lingSystems.hasNext()) {
//					Statement lingSysStmnt = lingSystems.next();
//					Value lingSys = lingSysStmnt.getObject();
//					if (lingSys instanceof Literal) {
//						lom.newMetaMetadata().newLanguage().setString(lingSys.stringValue());
//						statistics.metaMetadataLanguage++;
//					}
//				}
//			}
//		}
	}
	
	// 4 Technical
	
	public void convertTechnical(LOMImpl input, Graph graph, URI resourceURI) {
		if (LOMUtil.getTechnical(input) != null) {
			convertTechnicalFormat(input, graph, resourceURI);
			convertTechnicalSize(input, graph, resourceURI);
			convertTechnicalLocation(input, graph, resourceURI);
			convertTechnicalRequirement(input, graph, resourceURI);
			convertTechnicalInstallationRemarks(input, graph, resourceURI);
			convertTechnicalOtherPlatformRequirements(input, graph, resourceURI);
			convertTechnicalDuration(input, graph, resourceURI);
			convertTechnicalFacet(input, graph, resourceURI);	
		}
	}

	// 4.1 Format
	
	public void convertTechnicalFormat(LOMImpl input, Graph graph, URI resourceURI) {
		BNode bnode = vf.createBNode();
		Graph formatStmnts = new GraphImpl();
		for (int i = 0; ; i++) {
			org.ieee.ltsc.lom.LOM.Technical.Format format = LOMUtil.getTechnicalFormat(input, i);
			if (format == null) {
				break;
			}
		
			String formatStr = LOMUtil.getString(format).trim();
			Literal literal = null;
			if ("non-digital".equalsIgnoreCase(formatStr)) {
				literal = vf.createLiteral(formatStr);
			} else {
				literal = vf.createLiteral(formatStr, createURI(NS.dcterms, "IMT"));
			}
			formatStmnts.add(vf.createStatement(bnode, RDF.VALUE, literal));
			statistics.technicalFormat++;
		}
		
		if (!formatStmnts.isEmpty()) {
			graph.add(vf.createStatement(resourceURI, createURI(NS.dcterms, "format"), bnode));
			graph.addAll(formatStmnts);
		}
	}

	// 4.2 Size
	
	public void convertTechnicalSize(LOMImpl input, Graph graph, URI resourceURI) {
		Size size = LOMUtil.getTechnicalSize(input);
		if (size != null) {
			BNode bnode = vf.createBNode();
			Literal literal = vf.createLiteral(size.string().trim(), createURI(NS.xsd, "positiveInteger"));
			graph.add(vf.createStatement(resourceURI, createURI(NS.dcterms, "extent"), bnode));
			graph.add(vf.createStatement(bnode, RDF.TYPE, createURI(NS.lom, "Size")));
			graph.add(vf.createStatement(bnode, RDF.VALUE, literal));
		}
	}

	// 4.3 Location
	
	public void convertTechnicalLocation(LOMImpl input, Graph graph, URI resourceURI) {
		Location[] locations = LOMUtil.getTechnicalLocations(input);
		if (locations != null) {
			BNode bnode = vf.createBNode();
			Graph locStmnts = new GraphImpl();
			for (Location location : locations) {
				if (location != null) {
					Literal locLiteral = vf.createLiteral(location.string().trim(), createURI(NS.xsd, "anyURI"));
					locStmnts.add(vf.createStatement(bnode, RDF.VALUE, locLiteral));
					statistics.technicalLocation++;
				}
			}

			if (!locStmnts.isEmpty()) {
				graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "location"), bnode));
				graph.addAll(locStmnts);
			}
		}
	}

	// 4.4 Requirement
	
	public void convertTechnicalRequirement(LOMImpl input, Graph graph, URI resourceURI) {
		for (int i = 0; ; i++) {
			Requirement requirement = LOMUtil.getTechnicalRequirement(input, i);
			if (requirement == null) {
				break;
			}
			BNode requirementBNode = vf.createBNode();

			// 4.4.1 OrComposite
			
			for (int j = 0; ; j++) {
				OrComposite orComposite = LOMUtil.getTechnicalRequirementOrComposite(input, i, j);
				if (orComposite == null) {
					break;
				}
				BNode orCompositeBNode = vf.createBNode();
				Graph orCompStmnts = new GraphImpl();
				
				// 4.4.1.1 Type
				
				Type type = LOMUtil.getTechnicalRequirementOrCompositeType(input, i, j);
				if (type != null) {
					String typeStr = LOMUtil.getValue(type).trim();
					URI typeURI = null;
					
					if (LOM.Technical.Requirement.OrComposite.Type.BROWSER.equals(typeStr)) {
						typeURI = createURI(NS.lomvoc, "RequirementType-browser");
					} else if (LOM.Technical.Requirement.OrComposite.Type.OPERATING_SYSTEM.equals(typeStr)) {
						typeURI = createURI(NS.lomvoc, "RequirementType-operatingSystem");
					}
					
					if (typeURI != null) {
						orCompStmnts.add(vf.createStatement(orCompositeBNode, RDF.TYPE, typeURI));
						orCompStmnts.add(vf.createStatement(typeURI, RDF.TYPE, createURI(NS.lom, "Technology")));
					}
				}
				
				// 4.4.1.2 Name
				
				Name name = LOMUtil.getTechnicalRequirementOrCompositeName(input, i, j);
				if (name != null) {
					String nameStr = LOMUtil.getValue(name).trim();
					URI nameURI = null;
					
					if (LOM.Technical.Requirement.OrComposite.Name.BROWSER_AMAYA.equals(nameStr)) {
						nameURI = createURI(NS.lomvoc, "BrowserTechnology-amaya");
					} else if (LOM.Technical.Requirement.OrComposite.Name.BROWSER_ANY.equals(nameStr)) {
						nameURI = createURI(NS.lomvoc, "BrowserTechnology-any");
					} else if (LOM.Technical.Requirement.OrComposite.Name.BROWSER_MS_INTERNET_EXPLORER.equals(nameStr)) {
						nameURI = createURI(NS.lomvoc, "BrowserTechnology-ms-internetExplorer");
					} else if (LOM.Technical.Requirement.OrComposite.Name.BROWSER_NETSCAPE_COMMUNICATOR.equals(nameStr)) {
						nameURI = createURI(NS.lomvoc, "BrowserTechnology-netscapeCommunicator");
					} else if (LOM.Technical.Requirement.OrComposite.Name.BROWSER_OPERA.equals(nameStr)) {
						nameURI = createURI(NS.lomvoc, "BrowserTechnology-opera");
					} else if (LOM.Technical.Requirement.OrComposite.Name.OPERATING_SYSTEM_MACOS.equals(nameStr)) {
						nameURI = createURI(NS.lomvoc, "OSTechnology-macos");
					} else if (LOM.Technical.Requirement.OrComposite.Name.OPERATING_SYSTEM_MS_WINDOWS.equals(nameStr)) {
						nameURI = createURI(NS.lomvoc, "OSTechnology-ms-windows");
					} else if (LOM.Technical.Requirement.OrComposite.Name.OPERATING_SYSTEM_MULTI_OS.equals(nameStr)) {
						nameURI = createURI(NS.lomvoc, "OSTechnology-multi-os");
					} else if (LOM.Technical.Requirement.OrComposite.Name.OPERATING_SYSTEM_NONE.equals(nameStr)) {
						nameURI = createURI(NS.lomvoc, "OSTechnology-none");
					} else if (LOM.Technical.Requirement.OrComposite.Name.OPERATING_SYSTEM_PC_DOS.equals(nameStr)) {
						nameURI = createURI(NS.lomvoc, "OSTechnology-pc-dos");
					} else if (LOM.Technical.Requirement.OrComposite.Name.OPERATING_SYSTEM_UNIX.equals(nameStr)) {
						nameURI = createURI(NS.lomvoc, "OSTechnology-unix");
					}
					
					if (nameURI != null) {
						orCompStmnts.add(vf.createStatement(orCompositeBNode, createURI(NS.lom, "technology"), nameURI));
						orCompStmnts.add(vf.createStatement(nameURI, RDF.TYPE, createURI(NS.lom, "Technology")));
					}
				}
				
				// 4.4.1.3 Minimum version
				
				MinimumVersion minVersion = LOMUtil.getTechnicalRequirementOrCompositeMinimumVersion(input, i, j);
				if (minVersion != null) {
					Literal lit = vf.createLiteral(minVersion.string().trim());
					orCompStmnts.add(vf.createStatement(orCompositeBNode, createURI(NS.lom, "minimumVersion"), lit));
				}
				
				// 4.4.1.4 Maximum version
				
				MaximumVersion maxVersion = LOMUtil.getTechnicalRequirementOrCompositeMaximumVersion(input, i, j);
				if (maxVersion != null) {
					Literal lit = vf.createLiteral(maxVersion.string().trim());
					orCompStmnts.add(vf.createStatement(orCompositeBNode, createURI(NS.lom, "maximumVersion"), lit));
				}
				
				if (!orCompStmnts.isEmpty()) {
					graph.add(resourceURI, createURI(NS.lom, "requirement"), requirementBNode);
					graph.add(requirementBNode, createURI(NS.lom, "alternativeRequirement"), orCompositeBNode);
					graph.addAll(orCompStmnts);
				}
			}
		}
	}

	// 4.5 Installation Remarks
	
	public void convertTechnicalInstallationRemarks(LOMImpl input, Graph graph, URI resourceURI) {
		InstallationRemarks installationRemarks = LOMUtil.getTechnicalInstallationRemarks(input);
		if (installationRemarks == null) {
			return;
		}
		
		BNode bnode = vf.createBNode();
		Graph irStmnts = new GraphImpl();
		
		for (int j = 0; installationRemarks.string(j) != null; j++) {
			String remarkStr = LOMUtil.getString(installationRemarks, j);
			String remarkLang = LOMUtil.getLanguage(installationRemarks, j);
			if (remarkStr != null) {
				Literal literal = vf.createLiteral(remarkStr.trim(), remarkLang);
				irStmnts.add(vf.createStatement(bnode, RDF.VALUE, literal));
				statistics.technicalInstallationRemarks++;
			}
		}
		
		if (!irStmnts.isEmpty()) {
			graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "installationRemarks"), bnode));
			graph.add(vf.createStatement(bnode, RDF.TYPE, createURI(NS.lom, "LangString")));
			graph.addAll(irStmnts);
		}
	}

	// 4.6 Other Platform Requirements
	
	public void convertTechnicalOtherPlatformRequirements(LOMImpl input, Graph graph, URI resourceURI) {
		OtherPlatformRequirements platformRequirements = LOMUtil.getTechnicalOtherPlatformRequirements(input);
		if (platformRequirements == null) {
			return;
		}
		
		BNode bnode = vf.createBNode();
		Graph prStmnts = new GraphImpl();
		
		for (int j = 0; platformRequirements.string(j) != null; j++) {
			String reqStr = LOMUtil.getString(platformRequirements, j);
			String reqLang = LOMUtil.getLanguage(platformRequirements, j);
			if (reqStr != null) {
				Literal literal = vf.createLiteral(reqStr.trim(), reqLang);
				prStmnts.add(vf.createStatement(bnode, RDF.VALUE, literal));
				statistics.technicalOtherPlatformRequirements++;
			}
		}
		
		if (!prStmnts.isEmpty()) {
			graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "otherPlatformRequirements"), bnode));
			graph.add(vf.createStatement(bnode, RDF.TYPE, createURI(NS.lom, "LangString")));
			graph.addAll(prStmnts);
		}
	}

	// 4.7 Duration
	
	public void convertTechnicalDuration(LOMImpl input, Graph graph, URI resourceURI) {
		Duration techDuration = LOMUtil.getTechnicalDuration(input);
		if (techDuration == null) {
			return;
		}
		
		BNode bnode = vf.createBNode();
		Graph durationStmnts = new GraphImpl();

		org.ieee.ltsc.datatype.Duration.Value duration = techDuration.value();
		if (duration != null) {
			String durationValue = duration.string().trim();
			durationStmnts.add(vf.createStatement(bnode, RDF.VALUE, vf.createLiteral(durationValue, createURI(NS.xsd, "duration"))));
			statistics.technicalDuration++;
		}

		org.ieee.ltsc.datatype.Duration.Description ltDesc = techDuration.description();
		if (ltDesc != null) {
			for (int j = 0; ltDesc.string(j) != null; j++) {
				String desc = LOMUtil.getString(ltDesc, j);
				String lang = LOMUtil.getLanguage(ltDesc, j);
				if (desc != null) {
					Literal descLiteral = vf.createLiteral(desc.trim(), lang);
					durationStmnts.add(vf.createStatement(bnode, RDF.VALUE, descLiteral));
					statistics.technicalDuration++;
				}
			}
		}

		if (!durationStmnts.isEmpty()) {
			graph.add(vf.createStatement(resourceURI, createURI(NS.dcterms, "extent"), bnode));
			graph.add(vf.createStatement(bnode, RDF.TYPE, createURI(NS.lom, "Duration")));
			graph.addAll(durationStmnts);
		}
	}

	// 4.8 Facet
	
	public void convertTechnicalFacet(LOMImpl input, Graph graph, URI resourceURI) {
		if (!lreSupport) {
			return;
		}
		
		// TODO this is LRE specific
	}
	
	// 5 Educational
	
	public void convertEducational(LOMImpl input, Graph graph, URI resourceURI) {
		if (LOMUtil.getEducational(input, 0) != null) {
			
			// TODO only checking, should be removed later
			int i = 0;
			for ( ; input.educational(i) != null; i++);
			if (i > 1) {
				log.warn("FOUND " + i + " EDUCATIONAL CATEGORIES, IGNORING ALL BUT ONE");
			}
			// <- removed later

			convertEducationalInteractivityType(input, graph, resourceURI);
			convertEducationalLearningResourceType(input, graph, resourceURI);
			convertEducationalInteractivityLevel(input, graph, resourceURI);
			convertEducationalSemanticDensity(input, graph, resourceURI);
			convertEducationalIntendedEndUserRole(input, graph, resourceURI);
			convertEducationalContext(input, graph, resourceURI);
			convertEducationalTypicalAgeRange(input, graph, resourceURI);
			convertEducationalDifficulty(input, graph, resourceURI);
			convertEducationalTypicalLearningTime(input, graph, resourceURI);
			convertEducationalDescription(input, graph, resourceURI);
			convertEducationalLanguage(input, graph, resourceURI);
		}
	}
	
	// 5.1 Interactivity Type
	
	public void convertEducationalInteractivityType(LOMImpl input, Graph graph, URI resourceURI) {
		// TODO discuss mapping with mini, should it be double indirect?
		
		for (int i = 0; input.educational(i) != null; i++) {

			
			if (i > 0) {
				break;
			}
			
			
			String interactivityType = LOMUtil.getValue(LOMUtil.getEducationalInteractivityType(input, i));
			if (interactivityType == null) {
				continue;
			}
			
			interactivityType = interactivityType.trim();
			URI interTypeURI = null;
			if (interactivityType.equals(LOM.Educational.InteractivityType.ACTIVE)) {
				interTypeURI = createURI(NS.lomvoc, "InteractivityType-active");
			} else if (interactivityType.equals(LOM.Educational.InteractivityType.EXPOSITIVE)) {
				interTypeURI = createURI(NS.lomvoc, "InteractivityType-expositive");
			} else if (interactivityType.equals(LOM.Educational.InteractivityType.MIXED)) {
				interTypeURI = createURI(NS.lomvoc, "InteractivityType-mixed");
			}

			if (interTypeURI != null) {
				graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "interactivityType"), interTypeURI));
				graph.add(vf.createStatement(interTypeURI, RDF.TYPE, createURI(NS.lom, "InteractivityType")));
				statistics.educationalInteractivityType++;
			}
		}
	}

	// 5.2 Learning Resource Type
	
	public void convertEducationalLearningResourceType(LOMImpl input, Graph graph, URI resourceURI) {
		// TODO discuss mapping with mini, should it be double indirect?
		
		for (int i = 0; input.educational(i) != null; i++) {

			
			if (i > 0) {
				break;
			}
			
			for (int j = 0; input.educational(i).learningResourceType(j) != null; j++) {
				LearningResourceType learningResourceType = LOMUtil.getEducationalLearningResourceType(input, i, j);
				String lrt = LOMUtil.getValue(learningResourceType);
				
				if (lrt == null) {
					continue;
				}
				
				lrt = lrt.trim();
				URI lrtURI = null;
				
				if (LOM.Educational.LearningResourceType.DIAGRAM.equals(lrt)) {
					lrtURI = createURI(NS.lomvoc, "LearningResourceType-diagram");
					lrt = LOM.Educational.LearningResourceType.DIAGRAM;
				} else if (LOM.Educational.LearningResourceType.EXAM.equals(lrt)) {
					lrtURI = createURI(NS.lomvoc, "LearningResourceType-exam");
				} else if (LOM.Educational.LearningResourceType.EXCERCISE.equals(lrt)) {
					lrtURI = createURI(NS.lomvoc, "LearningResourceType-exercise");
				} else if (LOM.Educational.LearningResourceType.EXPERIMENT.equals(lrt)) {
					lrtURI = createURI(NS.lomvoc, "LearningResourceType-experiment");
				} else if (LOM.Educational.LearningResourceType.FIGURE.equals(lrt)) {
					lrtURI = createURI(NS.lomvoc, "LearningResourceType-figure");
				} else if (LOM.Educational.LearningResourceType.GRAPH.equals(lrt)) {
					lrtURI = createURI(NS.lomvoc, "LearningResourceType-graph");
				} else if (LOM.Educational.LearningResourceType.INDEX.equals(lrt)) {
					lrtURI = createURI(NS.lomvoc, "LearningResourceType-index");
				} else if (LOM.Educational.LearningResourceType.LECTURE.equals(lrt)) {
					lrtURI = createURI(NS.lomvoc, "LearningResourceType-lecture");
				} else if (LOM.Educational.LearningResourceType.NARRATIVE_TEXT.equals(lrt)) {
					lrtURI = createURI(NS.lomvoc, "LearningResourceType-narrativeText");
				} else if (LOM.Educational.LearningResourceType.PROBLEM_STATEMENT.equals(lrt)) {
					lrtURI = createURI(NS.lomvoc, "LearningResourceType-problemStatement");
				} else if (LOM.Educational.LearningResourceType.QUESTIONNAIRE.equals(lrt)) {
					lrtURI = createURI(NS.lomvoc, "LearningResourceType-questionnaire");
				} else if (LOM.Educational.LearningResourceType.SELF_ASSESSMENT.equals(lrt)) {
					lrtURI = createURI(NS.lomvoc, "LearningResourceType-selfAssessment");
				} else if (LOM.Educational.LearningResourceType.SIMULATION.equals(lrt)) {
					lrtURI = createURI(NS.lomvoc, "LearningResourceType-simulation");
				} else if (LOM.Educational.LearningResourceType.SLIDE.equals(lrt)) {
					lrtURI = createURI(NS.lomvoc, "LearningResourceType-slide");
				} else if (LOM.Educational.LearningResourceType.TABLE.equals(lrt)) {
					lrtURI = createURI(NS.lomvoc, "LearningResourceType-table");
				}

				if (lrtURI == null && lreSupport) {
					if (LRE.Educational.LearningResourceType.APPLICATION.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-application");
					} else if (LRE.Educational.LearningResourceType.ASSESSMENT.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-assessment");
					} else if (LRE.Educational.LearningResourceType.AUDIO.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-learningAsset-audio");
					} else if (LRE.Educational.LearningResourceType.BROADCAST.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-broadcast");
					} else if (LRE.Educational.LearningResourceType.CASE_STUDY.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-caseStudy");
					} else if (LRE.Educational.LearningResourceType.COURSE.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-course");
					} else if (LRE.Educational.LearningResourceType.DATA.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-learningAsset-data");
					} else if (LRE.Educational.LearningResourceType.DEMONSTRATION.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-demonstration");
					} else if (LRE.Educational.LearningResourceType.DRILL_AND_PRACTICE.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-drillAndPractice");
					} else if (LRE.Educational.LearningResourceType.EDUCATIONAL_GAME.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-educationalGame");
					} else if (LRE.Educational.LearningResourceType.ENQUIRY_ORIENTED_ACTIVITY.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-enquiryOrientedActivity");
					} else if (LRE.Educational.LearningResourceType.EXPERIMENT.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-experiment");
					} else if (LRE.Educational.LearningResourceType.EXPLORATION.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-exploration");
					} else if (LRE.Educational.LearningResourceType.GLOSSARY.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-glossary");
					} else if (LRE.Educational.LearningResourceType.GUIDE_ADVICE_SHEETS.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-guide");
					} else if ( LRE.Educational.LearningResourceType.IMAGE.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-learningAsset-image");
					} else if (LRE.Educational.LearningResourceType.LESSON_PLAN.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-lessonPlan");
					} else if (LRE.Educational.LearningResourceType.MODEL.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-learningAsset-model");
					} else if (LRE.Educational.LearningResourceType.OPEN_ACTIVITY.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-openActivity");
					} else if (LRE.Educational.LearningResourceType.OTHER.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-other");
					} else if (LRE.Educational.LearningResourceType.OTHER_WEB_RESOURCE.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-webResource-otherWebResource");
					} else if (LRE.Educational.LearningResourceType.PRESENTATION.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-presentation");
					} else if (LRE.Educational.LearningResourceType.PROJECT.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-project");
					} else if (LRE.Educational.LearningResourceType.REFERENCE.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-reference");
					} else if (LRE.Educational.LearningResourceType.ROLE_PLAY.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-rolePlay");
					} else if (LRE.Educational.LearningResourceType.SIMULATION.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-simulation");
					} else if (LRE.Educational.LearningResourceType.TEXT.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-learningAsset-text");
					} else if (LRE.Educational.LearningResourceType.TOOL.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-tool");
					} else if (LRE.Educational.LearningResourceType.VIDEO.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-learningAsset-video");
					} else if (LRE.Educational.LearningResourceType.WEB_PAGE.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-webResource-webPage");
					} else if (LRE.Educational.LearningResourceType.WEBLOG.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-webResource-weblog");
					} else if (LRE.Educational.LearningResourceType.WIKI.equals(lrt)) {
						lrtURI = createURI(NS.lrevoc, "LearningResourceType-webResource-wiki");
					}
				}

				if (lrtURI != null) {
					graph.add(vf.createStatement(resourceURI, RDF.TYPE, lrtURI));
					statistics.educationalLearningResourceType++;
				}
			}
		}
	}

	// 5.3 Interactivy Level
	
	public void convertEducationalInteractivityLevel(LOMImpl input, Graph graph, URI resourceURI) {
		// TODO discuss mapping with mini, should it be double indirect?
		
		for (int i = 0; input.educational(i) != null; i++) {

			
			if (i > 0) {
				break;
			}
			
			
			String interactivityLevel = LOMUtil.getValue(LOMUtil.getEducationalInteractivityLevel(input, i));
			if (interactivityLevel == null) {
				continue;
			}
			
			interactivityLevel = interactivityLevel.trim();
			URI interLevelURI = null;
			if (interactivityLevel.equals(LOM.Educational.InteractivityLevel.VERY_LOW)) {
				interLevelURI = createURI(NS.lomvoc, "InteractivityLevel-veryLow");
			} else if (interactivityLevel.equals(LOM.Educational.InteractivityLevel.LOW)) {
				interLevelURI = createURI(NS.lomvoc, "InteractivityLevel-low");
			} else if (interactivityLevel.equals(LOM.Educational.InteractivityLevel.MEDIUM)) {
				interLevelURI = createURI(NS.lomvoc, "InteractivityLevel-medium");
			} else if (interactivityLevel.equals(LOM.Educational.InteractivityLevel.HIGH)) {
				interLevelURI = createURI(NS.lomvoc, "InteractivityLevel-high");
			} else if (interactivityLevel.equals(LOM.Educational.InteractivityLevel.VERY_HIGH)) {
				interLevelURI = createURI(NS.lomvoc, "InteractivityLevel-veryHigh");
			}

			if (interLevelURI != null) {
				graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "interactivityLevel"), interLevelURI));
				graph.add(vf.createStatement(interLevelURI, RDF.TYPE, createURI(NS.lom, "InteractivityLevel")));
				statistics.educationalInteractivityLevel++;
			}
		}
	}

	// 5.4 Semantic Density
	
	public void convertEducationalSemanticDensity(LOMImpl input, Graph graph, URI resourceURI) {
		// TODO discuss mapping with mini, should it be double indirect?
		
		for (int i = 0; input.educational(i) != null; i++) {

			
			if (i > 0) {
				break;
			}
			
			
			String semanticDensity = LOMUtil.getValue(LOMUtil.getEducationalSemanticDensity(input, i));
			if (semanticDensity == null) {
				continue;
			}
			
			semanticDensity = semanticDensity.trim();
			URI semDensityURI = null;
			if (semanticDensity.equals(LOM.Educational.SemanticDensity.VERY_LOW)) {
				semDensityURI = createURI(NS.lomvoc, "SemanticDensity-veryLow");
			} else if (semanticDensity.equals(LOM.Educational.SemanticDensity.LOW)) {
				semDensityURI = createURI(NS.lomvoc, "SemanticDensity-low");
			} else if (semanticDensity.equals(LOM.Educational.SemanticDensity.MEDIUM)) {
				semDensityURI = createURI(NS.lomvoc, "SemanticDensity-medium");
			} else if (semanticDensity.equals(LOM.Educational.SemanticDensity.HIGH)) {
				semDensityURI = createURI(NS.lomvoc, "SemanticDensity-high");
			} else if (semanticDensity.equals(LOM.Educational.SemanticDensity.VERY_HIGH)) {
				semDensityURI = createURI(NS.lomvoc, "SemanticDensity-veryHigh");
			}

			if (semDensityURI != null) {
				graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "semanticDensity"), semDensityURI));
				graph.add(vf.createStatement(semDensityURI, RDF.TYPE, createURI(NS.lom, "SemanticDensity")));
				statistics.educationalSemanticDensity++;
			}
		}
	}

	// 5.5 Intended End User Role
	
	public void convertEducationalIntendedEndUserRole(LOMImpl input, Graph graph, URI resourceURI) {
		// TODO discuss mapping with mini, should it be double indirect?
		
		for (int i = 0; input.educational(i) != null; i++) {

			
			if (i > 0) {
				break;
			}
			
			for (int j = 0; input.educational(i).intendedEndUserRole(j) != null; j++) {
				IntendedEndUserRole intendedEndUserRole = LOMUtil.getEducationalIntendedEndUserRole(input, i, j);
				String role = LOMUtil.getValue(intendedEndUserRole);
				
				if (role == null) {
					continue;
				}
				
				role = role.trim();
				URI roleURI = null;
				if (LOM.Educational.IntendedEndUserRole.AUTHOR.equals(role)) {
					roleURI = createURI(NS.lomvoc, "IntendedEndUserRole-author");
				} else if (LOM.Educational.IntendedEndUserRole.LEARNER.equals(role)) {
					roleURI = createURI(NS.lomvoc, "IntendedEndUserRole-learner");
				} else if (LOM.Educational.IntendedEndUserRole.MANAGER.equals(role)) {
					roleURI = createURI(NS.lomvoc, "IntendedEndUserRole-manager");
				} else if (LOM.Educational.IntendedEndUserRole.TEACHER.equals(role)) {
					roleURI = createURI(NS.lomvoc, "IntendedEndUserRole-teacher");
				}

				if (roleURI == null && lreSupport) {
					if (LRE.Educational.IntendedEndUserRole.COUNSELLOR.equals(role)) {
						roleURI = createURI(NS.lrevoc, "IntendedEndUserRole-counsellor");
					} else if (LRE.Educational.IntendedEndUserRole.OTHER.equals(role)) {
						roleURI = createURI(NS.lrevoc, "IntendedEndUserRole-other");
					} else if (LRE.Educational.IntendedEndUserRole.PARENT.equals(role)) {
						roleURI = createURI(NS.lrevoc, "IntendedEndUserRole-parent");
					}
				}

				if (roleURI != null) {
					graph.add(vf.createStatement(resourceURI, createURI(NS.dcterms, "audience"), roleURI));
					statistics.educationalIntendedEndUserRole++;
				}
			}
		}
	}
	
	// 5.6 Context
	
	public void convertEducationalContext(LOMImpl input, Graph graph, URI resourceURI) {
		// TODO discuss mapping with mini, should it be double indirect?
		
		for (int i = 0; input.educational(i) != null; i++) {

			
			if (i > 0) {
				break;
			}
			

			for (int j = 0; input.educational(i).context(j) != null; j++) {
				Context educationalContext = LOMUtil.getEducationalContext(input, i, j);
				String context = LOMUtil.getValue(educationalContext);
				if (context == null) {
					continue;
				}

				context = context.trim();
				URI contextURI = null;
				if (LOM.Educational.Context.HIGHER_EDUCATION.equals(context)) {
					contextURI = createURI(NS.lomvoc, "Context-higherEducation");
				} else if (LOM.Educational.Context.OTHER.equals(context)) {
					contextURI = createURI(NS.lomvoc, "Context-other");
				} else if (LOM.Educational.Context.SCHOOL.equals(context)) {
					contextURI = createURI(NS.lomvoc, "Context-school");
				} else if (LOM.Educational.Context.TRAINING.equals(context)) {
					contextURI = createURI(NS.lomvoc, "Context-training");
				}

				if (context == null && lreSupport) {
					if (LRE.Educational.LearningContext.COMPULSORY_EDUCATION.equals(context)) {
						contextURI = createURI(NS.lrevoc, "Context-compulsoryEducation");
					} else if (LRE.Educational.LearningContext.CONTINUING_EDUCATION.equals(context)) {
						contextURI = createURI(NS.lrevoc, "Context-continuingEducation");
					} else if (LRE.Educational.LearningContext.DISTANCE_EDUCATION.equals(context)) {
						contextURI = createURI(NS.lrevoc, "Context-distanceEducation");
					} else if (LRE.Educational.LearningContext.EDUCATIONAL_ADMINISTRATION.equals(context)) {
						contextURI = createURI(NS.lrevoc, "Context-educatioalAdministration");
					} else if (LRE.Educational.LearningContext.LIBRARY.equals(context)) {
						contextURI = createURI(NS.lrevoc, "Context-library");
					} else if (LRE.Educational.LearningContext.POLICY_MAKING.equals(context)) {
						contextURI = createURI(NS.lrevoc, "Context-policyMaking");
					} else if (LRE.Educational.LearningContext.PRE_SCHOOL.equals(context)) {
						contextURI = createURI(NS.lrevoc, "Context-preSchool");
					} else if (LRE.Educational.LearningContext.PROFESSIONAL_DEVELOPMENT.equals(context)) {
						contextURI = createURI(NS.lrevoc, "Context-professionalDevelopment");
					} else if (LRE.Educational.LearningContext.SPECIAL_EDUCATION.equals(context)) {
						contextURI = createURI(NS.lrevoc, "Context-specialEducation");
					} else if (LRE.Educational.LearningContext.VOCATIONAL_EDUCATION.equals(context)) {
						contextURI = createURI(NS.lrevoc, "Context-vocationalEducation");
					}
				}

				if (contextURI != null) {
					graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "context"), contextURI));
					graph.add(vf.createStatement(contextURI, RDF.TYPE, createURI(NS.lom, "Context")));
					statistics.educationalContext++;
				}
			}
		}
	}

	// 5.7 Typical Age Range
	
	public void convertEducationalTypicalAgeRange(LOMImpl input, Graph graph, URI resourceURI) {
		// TODO discuss mapping with mini, should it be double indirect?
		
		for (int i = 0; input.educational(i) != null; i++) {

			
			if (i > 0) {
				break;
			}
			
			
			for (int j = 0; input.educational(i).typicalAgeRange(j) != null; j++) {
				BNode bnode = vf.createBNode();
				Graph ageStmnts = new GraphImpl();
				TypicalAgeRange ageRange = LOMUtil.getEducationalTypicalAgeRange(input, i, j);
				for (int k = 0; ageRange.string(k) != null; k++) {	
					String ageRangeStr = LOMUtil.getString(ageRange, k);
					String ageRangeLang = LOMUtil.getLanguage(ageRange, k);
					if (ageRangeStr != null) {
						Literal literal = new LiteralImpl(ageRangeStr.trim(), ageRangeLang);
						ageStmnts.add(vf.createStatement(bnode, RDF.VALUE, literal));
						statistics.educationalTypicalAgeRange++;
					}
				}
				if (!ageStmnts.isEmpty()) {
					graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "typicalAgeRange"), bnode));
					graph.add(vf.createStatement(bnode, RDF.TYPE, createURI(NS.lom, "LangString")));
					graph.addAll(ageStmnts);
				}
			}
		}
	}

	// 5.8 Difficulty
	
	public void convertEducationalDifficulty(LOMImpl input, Graph graph, URI resourceURI) {
		// TODO discuss mapping with mini, should it be double indirect?
		
		for (int i = 0; input.educational(i) != null; i++) {

			
			if (i > 0) {
				break;
			}
			
			
			String difficulty = LOMUtil.getValue(LOMUtil.getEducationalDifficulty(input, i));
			if (difficulty == null) {
				continue;
			}
			
			difficulty = difficulty.trim();
			URI difficultyURI = null;
			if (difficulty.equals(LOM.Educational.Difficulty.VERY_EASY)) {
				difficultyURI = createURI(NS.lomvoc, "Difficulty-veryEasy");
			} else if (difficulty.equals(LOM.Educational.Difficulty.EASY)) {
				difficultyURI = createURI(NS.lomvoc, "Difficulty-easy");
			} else if (difficulty.equals(LOM.Educational.Difficulty.MEDIUM)) {
				difficultyURI = createURI(NS.lomvoc, "Difficulty-medium");
			} else if (difficulty.equals(LOM.Educational.Difficulty.DIFFICULT)) {
				difficultyURI = createURI(NS.lomvoc, "Difficulty-difficult");
			} else if (difficulty.equals(LOM.Educational.Difficulty.VERY_DIFFICULT)) {
				difficultyURI = createURI(NS.lomvoc, "Difficulty-veryDifficult");
			}

			if (difficultyURI != null) {
				graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "difficulty"), difficultyURI));
				graph.add(vf.createStatement(difficultyURI, RDF.TYPE, createURI(NS.lom, "Difficulty")));
				statistics.educationalDifficulty++;
			}
		}
	}
	
	// 5.9 Typical Learning Time
	
	public void convertEducationalTypicalLearningTime(LOMImpl input, Graph graph, URI resourceURI) {
		// TODO discuss mapping with mini, should it be double indirect?
		
		for (int i = 0; input.educational(i) != null; i++) {

			
			if (i > 0) {
				break;
			}
			
			
			TypicalLearningTime learningTime = LOMUtil.getEducationalTypicalLearningTime(input, i);
			if (learningTime == null) {
				continue;
			}
			
			BNode bnode = vf.createBNode();
			Graph ltStmnts = new GraphImpl();

			org.ieee.ltsc.datatype.Duration.Value duration = learningTime.value();
			if (duration != null) {
				String durationValue = duration.string().trim();
				ltStmnts.add(vf.createStatement(bnode, RDF.VALUE, vf.createLiteral(durationValue, createURI(NS.xsd, "duration"))));
				statistics.educationalTypicalLearningTime++;
			}

			org.ieee.ltsc.datatype.Duration.Description ltDesc = learningTime.description();
			if (ltDesc != null) {
				for (int j = 0; ltDesc.string(j) != null; j++) {
					String desc = LOMUtil.getString(ltDesc, j);
					String lang = LOMUtil.getLanguage(ltDesc, j);
					if (desc != null) {
						Literal descLiteral = vf.createLiteral(desc.trim(), lang);
						ltStmnts.add(vf.createStatement(bnode, RDF.VALUE, descLiteral));
						statistics.educationalTypicalLearningTime++;
					}
				}
			}

			if (!ltStmnts.isEmpty()) {
				graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "typicalLearningTime"), bnode));
				graph.add(vf.createStatement(bnode, RDF.TYPE, createURI(NS.lom, "Duration")));
				graph.addAll(ltStmnts);
			}
		}
	}

	// 5.10 Description
	
	public void convertEducationalDescription(LOMImpl input, Graph graph, URI resourceURI) {
		// TODO discuss mapping with mini, should it be double indirect?
		
		for (int i = 0; input.educational(i) != null; i++) {

			
			if (i > 0) {
				break;
			}
			
			
			for (int j = 0; input.educational(i).description(j) != null; j++) {
				BNode bnode = vf.createBNode();
				Graph descStmnts = new GraphImpl();
				org.ieee.ltsc.lom.LOM.Educational.Description desc = LOMUtil.getEducationalDescription(input, i, j);
				
				for (int k = 0; desc.string(k) != null; k++) {	
					String descStr = LOMUtil.getString(desc, k);
					String descLang = LOMUtil.getLanguage(desc, k);
					if (descStr != null) {
						Literal literal = new LiteralImpl(descStr.trim(), descLang);
						descStmnts.add(vf.createStatement(bnode, RDF.VALUE, literal));
						statistics.educationalDescription++;
					}
				}
				
				if (!descStmnts.isEmpty()) {
					graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "educationalDescription"), bnode));
					graph.add(vf.createStatement(bnode, RDF.TYPE, createURI(NS.lom, "LangString")));
					graph.addAll(descStmnts);
				}	
			}
		}
	}

	// 5.11 Language
	
	public void convertEducationalLanguage(LOMImpl input, Graph graph, URI resourceURI) {
		// TODO discuss mapping with mini, should it be double indirect?
		
		for (int i = 0; input.educational(i) != null; i++) {
			
			
			if (i > 0) {
				break;
			}
			
			
			BNode bnode = vf.createBNode();
			Graph langStmnts = new GraphImpl();
			for (int j = 0; input.educational(i).language(j) != null; j++) {
				org.ieee.ltsc.lom.LOM.Educational.Language lang = LOMUtil.getEducationalLanguage(input, i, j);
				if (lang != null) {
					String langStr = LOMUtil.getString(lang);
					if (langStr != null) {
						Literal literal = new LiteralImpl(langStr.trim(), createURI(NS.dcterms, "RFC4646"));
						langStmnts.add(vf.createStatement(bnode, RDF.VALUE, literal));
						statistics.educationalLanguage++;
					}
				}
			}
			
			if (!langStmnts.isEmpty()) {
				graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "educationalLanguage"), bnode));
				graph.add(vf.createStatement(bnode, RDF.TYPE, createURI(NS.lom, "LinguisticSystem")));
				graph.addAll(langStmnts);
			}
		}
	}
	
	// 6 Rights
	
	public void convertRights(LOMImpl input, Graph graph, URI resourceURI) {
		if (LOMUtil.getRights(input) != null) {
			convertRightsCost(input, graph, resourceURI);
			convertRightsCopyrightAndOtherRestrictions(input, graph, resourceURI);
			convertRightsDescription(input, graph, resourceURI);
		}
	}
	
	// 6.1 Cost
	
	public void convertRightsCost(LOMImpl input, Graph graph, URI resourceURI) {
		Cost cost = LOMUtil.getRightsCost(input);
		if (cost != null) {
			String costValue = LOMUtil.getValue(cost).trim();
			boolean costBool = false;
			if ("yes".equalsIgnoreCase(costValue) || "true".equalsIgnoreCase(costValue)) {
				costBool = true;
			}
			graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "cost"), vf.createLiteral(costBool)));
			statistics.rightsCost++;
		}
	}

	// 6.2 Copyright and Other Restrictions
	
	public void convertRightsCopyrightAndOtherRestrictions(LOMImpl input, Graph graph, URI resourceURI) {
		CopyrightAndOtherRestrictions copyright = LOMUtil.getRightsCopyrightAndOtherRestrictions(input);
		if (copyright != null) {
			String copyrightValue = LOMUtil.getValue(copyright).trim();
			boolean copyrightBool = false;
			if ("yes".equalsIgnoreCase(copyrightValue) || "true".equalsIgnoreCase(copyrightValue)) {
				copyrightBool = true;
			}
			graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "copyrightAndOtherRestrictions"), vf.createLiteral(copyrightBool)));
			statistics.rightsCostCopyrightAndOtherRestrictions++;
		}
	}

	// 6.3 Description

	public void convertRightsDescription(LOMImpl input, Graph graph, URI resourceURI) {
		Description desc = LOMUtil.getRightsDescription(input);
		if (desc != null) {
			BNode bnode = vf.createBNode();
			Graph descStmnts = new GraphImpl();
			
			for (int j = 0; desc.string(j) != null; j++) {
				String descStr = LOMUtil.getString(desc, j);
				String descLang = LOMUtil.getLanguage(desc, j);
				if (descStr != null) {
					Literal literal = new LiteralImpl(descStr.trim(), descLang);
					descStmnts.add(vf.createStatement(bnode, RDF.VALUE, literal));
					statistics.rightsDescription++;
				}
			}
			
			if (!descStmnts.isEmpty()) {
				graph.add(vf.createStatement(resourceURI, createURI(NS.dcterms, "rights"), bnode));
				graph.addAll(descStmnts);
			}
		}
	}
	
	// 7 Relation
	
	public void convertRelation(LOMImpl input, Graph graph, URI resourceURI) {
		// TODO

//		// 7.1 Kind
//		
//		Iterator<Statement> allStmnts = input.match(resourceURI, null, null);
//		int relationCount = 0;
//		while (allStmnts.hasNext()) {
//			Statement stmnt = allStmnts.next();
//			URI pred = stmnt.getPredicate();
//			Value obj = stmnt.getObject();
//			if (obj instanceof Resource) {
//				String voc = null;
//				if (pred.equals(createURI(NS.dcterms, "hasFormat"))) {
//					voc = LOM.Relation.Kind.HAS_FORMAT;
//				} else if (pred.equals(createURI(NS.dcterms, "hasPart"))) {
//					voc = LOM.Relation.Kind.HAS_PART;
//				} else if (pred.equals(createURI(NS.dcterms, "hasVersion"))) {
//					voc = LOM.Relation.Kind.HAS_VERSION;
//				} else if (pred.equals(createURI(NS.dcterms, "source"))) {
//					voc = LOM.Relation.Kind.IS_BASED_ON;
//				} else if (pred.equals(createURI(NS.lomterms, "isBasisFor"))) {
//					voc = LOM.Relation.Kind.IS_BASIS_FOR;
//				} else if (pred.equals(createURI(NS.dcterms, "isFormatOf"))) {
//					voc = LOM.Relation.Kind.IS_FORMAT_OF;
//				} else if (pred.equals(createURI(NS.dcterms, "isPartOf"))) {
//					voc = LOM.Relation.Kind.IS_PART_OF;
//				} else if (pred.equals(createURI(NS.dcterms, "isReferencedBy"))) {
//					voc = LOM.Relation.Kind.IS_REFERENCED_BY;
//				} else if (pred.equals(createURI(NS.dcterms, "isRequiredBy"))) {
//					voc = LOM.Relation.Kind.IS_REQUIRED_BY;
//				} else if (pred.equals(createURI(NS.dcterms, "isVersionOf"))) {
//					voc = LOM.Relation.Kind.IS_VERSION_OF;
//				} else if (pred.equals(createURI(NS.dcterms, "references"))) {
//					voc = LOM.Relation.Kind.REFERENCES;
//				} else if (pred.equals(createURI(NS.dcterms, "requires"))) {
//					voc = LOM.Relation.Kind.REQUIRES;
//				}
//				
//				if (lreSupport) {
//					if (pred.equals(createURI(NS.lomterms, "hasPreview"))) {
//						voc = LRE.Relation.Kind.HAS_PREVIEW;
//					} else if (pred.equals(createURI(NS.lomterms, "isPreviewOf"))) {
//						voc = LRE.Relation.Kind.IS_PREVIEW_OF;
//					}
//				}
//
//				// 7.2 Resource
//
//				if (voc != null) {
//					
//					// FIXME why is voc not used here? TODO don't forget to include vocSource
//					
//					// FIXME include counter for 7.1
//
//					// 7.2.1 Identifier
//
//					Iterator<Statement> identifiers = input.match((Resource) obj, createURI(NS.lom, "identifier"), null);
//					int idCount = 1;
//					while (identifiers.hasNext()) {
//						Value idObject = identifiers.next().getObject();
//						if (idObject instanceof Resource) {
//
//							// 7.2.1.1 Catalog
//
//							Iterator<Statement> catalog = input.match((Resource) idObject, createURI(NS.lom, "catalog"), null);
//							if (catalog.hasNext()) {
//								Value catalogObj = catalog.next().getObject();
//								if (catalogObj instanceof Literal) {
//									lom.newRelation(relationCount).newResource().newIdentifier(idCount).newCatalog().setString(catalogObj.stringValue());
//									statistics.relationResourceIdentifierCatalog++;
//								}
//							}
//
//							// 7.2.1.2 Entry
//
//							Iterator<Statement> entry = input.match((Resource) idObject, createURI(NS.lom, "entry"), null);
//							if (entry.hasNext()) {
//								Value entryObj = entry.next().getObject();
//								if (entryObj instanceof Literal) {
//									lom.newRelation(relationCount).newResource().newIdentifier(idCount).newEntry().setString(entryObj.stringValue());
//									statistics.relationResourceIdentifierEntry++;
//								}
//							}
//
//							idCount++;
//						}
//					}
//
//					// 7.2.2 Description
//
//					Iterator<Statement> descriptions = input.match((Resource) obj, createURI(NS.dcterms, "description"), null);
//					while (descriptions.hasNext()) {
//						Value object = descriptions.next().getObject();
//						if (object instanceof Resource) {
//							Iterator<Statement> descriptionValueStmnts = input.match((Resource) object, RDF.VALUE, null);
//							while (descriptionValueStmnts.hasNext()) {
//								Statement descriptionValueStmnt = descriptionValueStmnts.next();
//								Value description = descriptionValueStmnt.getObject();
//								if (description instanceof Literal) {
//									Literal descriptionLiteral = (Literal) description;
//									StringImpl descriptionString = lom.newRelation(relationCount).newResource().newDescription(-1).newString(-1);
//									descriptionString.setString(descriptionLiteral.stringValue());
//									if (descriptionLiteral.getLanguage() != null) {
//										descriptionString.newLanguage().setValue(descriptionLiteral.getLanguage());
//									}
//									statistics.relationResourceDescription++;
//								}
//							}
//						}
//					}
//
//					relationCount++;
//				}
//			}
//		}
	}
	
	// 8 Annotation
	
	public void convertAnnotation(LOMImpl input, Graph graph, URI resourceURI) {
		for (int i = 0; ; i++) {
			if (LOMUtil.getAnnotation(input, i) == null) {
				break;
			}
			
			BNode bnode = vf.createBNode();
			Graph annotationStmnts = new GraphImpl();
						
			// 8.1 Entity
			
			org.ieee.ltsc.lom.LOM.Annotation.Entity entity = LOMUtil.getAnnotationEntity(input, i);
			if (entity != null && entity.string() != null) {
				VCard vcard = null;
				try {
					vcard = EntityImpl.parseEntity(LOM.Annotation.Entity.TYPE, entity.string(), false);
				} catch (ParseException e) {
					log.warn(e.getMessage());
				}
				if (vcard != null) {
					BNode entityBNode = vf.createBNode();
					Graph entityStmnts = convertVCard2Graph(vcard, entityBNode);

					if (entityStmnts != null && !entityStmnts.isEmpty()) {
						annotationStmnts.add(vf.createStatement(bnode, createURI(NS.lom, "entity"), entityBNode));
						annotationStmnts.addAll(entityStmnts);
					}

					statistics.annotationEntity++;
				}
			}
			
			// 8.2 Date
			
			org.ieee.ltsc.lom.LOM.Annotation.Date annotationDate = LOMUtil.getAnnotationDate(input, i);
			if (annotationDate != null) {
				Calendar date = LOMUtil.getDateTime(annotationDate);
				if (date != null) {
					String dateStr = createW3CDTF(date.getTime());
					Literal dateLiteral = vf.createLiteral(dateStr, createURI(NS.dcterms, "W3CDTF"));
					annotationStmnts.add(vf.createStatement(bnode, createURI(NS.dcterms, "date"), dateLiteral));
				}
				for (int j = 0; ; j++) {
					String dateDesc = LOMUtil.getString(annotationDate.description(), j);
					String dateDescLang = LOMUtil.getLanguage(annotationDate.description(), j);
					if (dateDesc != null) {
						Literal dateDescLiteral = vf.createLiteral(dateDesc.trim(), dateDescLang);
						annotationStmnts.add(vf.createStatement(bnode, createURI(NS.dcterms, "date"), dateDescLiteral));
						statistics.annotationDate++;
					} else {
						break;
					}
				}
			}
			
			// 8.3 Description
			
			org.ieee.ltsc.lom.LOM.Annotation.Description description = LOMUtil.getAnnotationDescription(input, i);
			if (description != null) {
				BNode descBNode = vf.createBNode();
				Graph descStmnts = new GraphImpl();
				
				for (int j = 0; ; j++) {
					String desc = LOMUtil.getString(description, j);
					String descLang = LOMUtil.getLanguage(description, j);
					if (desc != null) {
						Literal descLiteral = vf.createLiteral(desc.trim(), descLang);
						descStmnts.add(vf.createStatement(descBNode, RDF.VALUE, descLiteral));
						statistics.annotationDescription++;
					} else {
						break;
					}	
				}
				
				if (!descStmnts.isEmpty()) {
					annotationStmnts.add(vf.createStatement(bnode, createURI(NS.dcterms, "description"), descBNode));
					annotationStmnts.addAll(descStmnts);
				}
			}
			
			// add statements to graph
			
			if (!annotationStmnts.isEmpty()) {
				graph.add(vf.createStatement(resourceURI, createURI(NS.lom, "annotation"), bnode));
				graph.add(vf.createStatement(bnode, RDF.TYPE, createURI(NS.lom, "Annotation")));
				graph.addAll(annotationStmnts);
			}
		}
	}
	
	// 9 Classification
	
	public void convertClassification(LOMImpl input, Graph graph, URI resourceURI) {
		// TODO

//		Iterator<Statement> classifications = input.match(resourceURI, createURI(NS.lom, "annotation"), null);
//		int classificationCount = 0;
//		while (classifications.hasNext()) {
//			Value resource = classifications.next().getObject();
//			if (resource instanceof Resource) {
//				
//				// 9.1 Purpose
//				
//				Iterator<Statement> purposeItems = input.match((Resource) resource, createURI(NS.lom, "purpose"), null);
//				while (purposeItems.hasNext()) {
//					Value purposeValue = purposeItems.next().getObject();
//					if (purposeValue instanceof URI) {
//						URI difficultyURI = (URI) purposeValue;
//						String difficultyURIStr = difficultyURI.stringValue();
//						String difficulty = null;
//										
//						if (difficultyURIStr.equals(NS.lomvoc + "Difficulty-veryEasy")) {
//							difficulty = LOM.Educational.Difficulty.VERY_EASY;
//						} else if (difficultyURIStr.equals(NS.lomvoc + "Difficulty-easy")) {
//							difficulty = LOM.Educational.Difficulty.EASY;
//						} else if (difficultyURIStr.equals(NS.lomvoc + "Difficulty-medium")) {
//							difficulty = LOM.Educational.Difficulty.MEDIUM;
//						} else if (difficultyURIStr.equals(NS.lomvoc + "Difficulty-difficult")) {
//							difficulty = LOM.Educational.Difficulty.DIFFICULT;
//						} else if (difficultyURIStr.equals(NS.lomvoc + "Difficulty-veryDifficult")) {
//							difficulty = LOM.Educational.Difficulty.VERY_DIFFICULT;
//						}
//						
//						if (difficulty != null) {
//							lom.newClassification(classificationCount).newPurpose().newValue().setString(difficulty);
//							lom.newClassification(classificationCount).newPurpose().newSource().setString(LOM.LOM_V1P0_VOCABULARY);
//							statistics.classificationPurpose++;
//						}
//					}
//				}
//				
//				// 9.2 Taxon Path
//				
//				Iterator<Statement> taxonPathElements = input.match((Resource) resource, createURI(NS.lom, "taxonPath"), null);
//				int taxonPathCount = 0;
//				while (taxonPathElements.hasNext()) {
//					Value taxonValue = classifications.next().getObject();
//					if (taxonValue instanceof Resource) {
//
//						// 9.2.1 Source
//						
//						Iterator<Statement> taxonSources = input.match((Resource) taxonValue, createURI(NS.lom, "taxonSource"), null);
//						while (taxonSources.hasNext()) {
//							Value taxonObject = taxonSources.next().getObject();
//							if (taxonObject instanceof Resource) {
//								Iterator<Statement> taxonSourceValueStmnts = input.match((Resource) taxonObject, RDF.VALUE, null);
//								while (taxonSourceValueStmnts.hasNext()) {
//									Statement taxonSourceValueStmnt = taxonSourceValueStmnts.next();
//									Value taxonSource = taxonSourceValueStmnt.getObject();
//									if (taxonSource instanceof Literal) {
//										Literal taxonSourceLiteral = (Literal) taxonSource;
//										StringImpl taxonSourceString = lom.newClassification(classificationCount).newTaxonPath(taxonPathCount).newTaxon(-1).newEntry().newString(-1);
//										taxonSourceString.setString(taxonSourceLiteral.stringValue());
//										if (taxonSourceLiteral.getLanguage() != null) {
//											taxonSourceString.newLanguage().setValue(taxonSourceLiteral.getLanguage());
//										}
//										statistics.classificationTaxonPathSource++;
//									}
//								}
//							}
//						}
//
//						// 9.2.2 Taxon
//						
//						Iterator<Statement> taxons = input.match((Resource) taxonValue, null, null);
//						int taxonCount = 0;
//						while (taxons.hasNext()) {
//							Statement taxonStmnt = taxons.next();
//							URI taxonPred = taxonStmnt.getPredicate();
//							
//							if (!taxonPred.stringValue().startsWith("rdf:_")) {
//								// we continue if the predicate is not derived from RDFS.MEMBER
//								// this should be done through infering, but this seems to be the easiest solution
//								continue;
//							}
//							
//							Value taxonObject = taxonStmnt.getObject();
//							if (taxonObject instanceof Resource) {
//								Iterator<Statement> taxonValueStmnts = input.match((Resource) taxonObject, RDF.VALUE, null);
//								while (taxonValueStmnts.hasNext()) {
//									Value taxonResource = taxonValueStmnts.next().getObject();
//									if (taxonResource instanceof Resource) {
//										
//										// 9.2.2.1 Id
//										
//										Iterator<Statement> taxonIDs = input.match((Resource) taxonResource, createURI(NS.dcterms, "identifier"), null);
//										if (taxonIDs.hasNext()) {
//											Value taxonID = taxonIDs.next().getObject();
//											if (taxonID instanceof Literal) {
//												lom.newClassification(classificationCount).newTaxonPath(taxonPathCount).newTaxon(taxonCount).newId().setString(taxonID.stringValue());
//												statistics.classificationTaxonPathTaxonId++;
//											}
//										}
//
//										// 9.2.2.2 Entry
//										
//										Iterator<Statement> taxonEntries = input.match((Resource) taxonResource, RDFS.LABEL, null);
//										if (taxonEntries.hasNext()) {
//											Value taxonEntry = taxonEntries.next().getObject();
//											if (taxonEntry instanceof Literal) {
//												lom.newClassification(classificationCount).newTaxonPath(taxonPathCount).newTaxon(taxonCount).newEntry().newString(-1).setString(taxonEntry.stringValue());
//												statistics.classificationTaxonPathTaxonEntry++;
//											}
//										}
//										
//										taxonCount++;
//									}
//								}
//							}
//						}
//						
//						taxonPathCount++;
//					}
//				}
//				
//				// 9.3 Description
//				
//				Iterator<Statement> descriptions = input.match((Resource) resource, createURI(NS.dcterms, "description"), null);
//				while (descriptions.hasNext()) {
//					Value object = descriptions.next().getObject();
//					if (object instanceof Resource) {
//						Iterator<Statement> descriptionValueStmnts = input.match((Resource) object, RDF.VALUE, null);
//						while (descriptionValueStmnts.hasNext()) {
//							Statement descriptionValueStmnt = descriptionValueStmnts.next();
//							Value description = descriptionValueStmnt.getObject();
//							if (description instanceof Literal) {
//								Literal descriptionLiteral = (Literal) description;
//								StringImpl descriptionString = lom.newClassification(classificationCount).newDescription().newString(-1);
//								descriptionString.setString(descriptionLiteral.stringValue());
//								if (descriptionLiteral.getLanguage() != null) {
//									descriptionString.newLanguage().setValue(descriptionLiteral.getLanguage());
//								}
//								statistics.classificationDescription++;
//							}
//						}
//					}
//				}
//				
//				// 9.4 Keyword
//				
//				Iterator<Statement> keywords = input.match((Resource) resource, createURI(NS.lom, "keyword"), null);
//				int keywordCount = 0;
//				while (keywords.hasNext()) {
//					Value object = keywords.next().getObject();
//					if (object instanceof Resource) {
//						Iterator<Statement> keywordValueStmnts = input.match((Resource) object, RDF.VALUE, null);
//						while (keywordValueStmnts.hasNext()) {
//							Statement keywordValueStmnt = keywordValueStmnts.next();
//							Value keyword = keywordValueStmnt.getObject();
//							if (keyword instanceof Literal) {
//								Literal keywordLiteral = (Literal) keyword;
//								StringImpl keywordString = lom.newClassification(-1).newKeyword(keywordCount).newString(-1);
//								keywordString.setString(keywordLiteral.stringValue());
//								String keywordLiteralLang = keywordLiteral.getLanguage();
//								if (keywordLiteralLang != null) {
//									keywordString.newLanguage().setValue(keywordLiteralLang);
//								}
//								statistics.classificationKeyword++;
//							}
//						}
//						keywordCount++;
//					}
//				}
//				
//				classificationCount++;
//			}
//		}
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