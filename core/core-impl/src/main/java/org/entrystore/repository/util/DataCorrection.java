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

package org.entrystore.repository.util;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.EntryType;
import org.entrystore.Metadata;
import org.entrystore.PrincipalManager;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.User;
import org.entrystore.impl.converters.ConverterUtil;
import org.openrdf.model.Graph;
import org.openrdf.model.Literal;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.model.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class modifies old statements to reflect the changes in the OE AP.
 * Probably only used once on old installations of SCAM/Confolio.
 * 
 * @author Hannes Ebner
 */
public class DataCorrection {
	
	private static Logger log = LoggerFactory.getLogger(DataCorrection.class);
	
	private PrincipalManager pm;
	
	private ContextManager cm;
	
//	private RepositoryManager rm;
	
//	private Writer writer;
	
	public DataCorrection(RepositoryManager rm) {
		this.pm = rm.getPrincipalManager();
		this.cm = rm.getContextManager();
//		this.rm = rm;
//		try {
//			writer = new PrintWriter("/tmp/fix.log");
//		} catch (IOException e) {
//			e.printStackTrace();
//		}
	}
	
	private static void log(String msg) {
		log.info(msg);
//		try {
//			writer.write(msg + "\n");
//		} catch (IOException e) {
//			e.printStackTrace();
//		}
	}
	
	public static String createW3CDTF(java.util.Date date) {
		SimpleDateFormat W3CDTF = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
		return W3CDTF.format(date);
	}
	
	public static org.openrdf.model.URI createURI(String namespace, String uri) {
		ValueFactory vf = new GraphImpl().getValueFactory();
		if (namespace != null) {
			return vf.createURI(namespace, uri);
		}
		return vf.createURI(uri);
	}
	
	private Set<URI> getContexts() {
		Set<URI> contexts = new HashSet<URI>();
		Set<String> aliases = cm.getContextAliases();
		for (String contextAlias : aliases) {
			URI contextURI = cm.getContextURI(contextAlias);
			contexts.add(contextURI);
		}
		if (contexts.contains(null)) {
			contexts.remove(null);
		}

		return contexts;
	}
	
	private List<Entry> getEntries(Set<URI> contexts) {
		List<Entry> entries = new ArrayList<Entry>();
		for (URI uri : contexts) {
			if (uri == null) {
				continue;
			}
			String contextURI = uri.toString();
			String contextId = contextURI.substring(contextURI.toString().lastIndexOf("/") + 1);
			Context context = cm.getContext(contextId);
			Set<URI> contextEntries = context.getEntries();
			for (URI entryURI : contextEntries) {
				String entryId = entryURI.toString().substring(entryURI.toString().lastIndexOf("/") + 1);
				Entry entry = context.get(entryId);
				if (entry == null) {
					log.warn("No entry found for URI: " + entryURI);
					continue;
				}
				entries.add(entry);
			}
		}
		return entries;
	}
	
	private void fixMetadataOfPrincipal(Entry entry) {
		if (entry == null) {
			return;
		}
		Graph metadata = entry.getGraph();
		if (metadata != null) {
			ValueFactory vf = new GraphImpl().getValueFactory();
			
			org.openrdf.model.URI resourceURI = vf.createURI(entry.getResourceURI().toString());
			org.openrdf.model.URI metadataURI = vf.createURI(entry.getLocalMetadata().getURI().toString());
			
			Statement resourceRights = vf.createStatement(resourceURI, createURI(NS.entrystore, "write"), resourceURI);
			Statement metadataRights = vf.createStatement(metadataURI, createURI(NS.entrystore, "write"), resourceURI);
			
			if (!metadata.match(resourceRights.getSubject(), resourceRights.getPredicate(), resourceRights.getObject()).hasNext()) {
				metadata.add(resourceRights);
				log("Added statement: " + resourceRights);
			}
			if (!metadata.match(metadataRights.getSubject(), metadataRights.getPredicate(), metadataRights.getObject()).hasNext()) {
				metadata.add(metadataRights);
				log("Added statement: " + metadataRights);
			}
			
			entry.setGraph(metadata);
		}
	}
	
	private void fixMetadataOfEntry(Entry entry) {
		Metadata localMd = entry.getLocalMetadata();
		if (localMd != null) {
			Graph metadata = entry.getLocalMetadata().getGraph();
			if (metadata != null) {
				boolean updateNecessary = false;
				ValueFactory vf = metadata.getValueFactory();
				URI rURI = entry.getResourceURI();
				if (rURI == null) {
					log.error("Resource URI is null!");
					return;
				}
				org.openrdf.model.URI resURI = vf.createURI(rURI.toString());
				
				List<Statement> toRemove = new ArrayList<Statement>();
				List<Statement> toAdd = new ArrayList<Statement>();
				
				// 1.4 Description
				
//				BNode descBNode = vf.createBNode();
//				Iterator<Statement> descriptions = metadata.match(resURI, createURI(NS.dcterms, "description"), null);
//				while (descriptions.hasNext()) {
//					Statement desc = descriptions.next();
//					if (desc.getObject() instanceof Literal) {
//						toAdd.add(vf.createStatement(resURI, createURI(NS.dcterms, "description"), descBNode));
//						toAdd.add(vf.createStatement(descBNode, RDF.VALUE, desc.getObject()));
//						toRemove.add(desc);
//					}
//				}
				
				// 2.1 Version
				
//				Iterator<Statement> version = metadata.match(resURI, createURI(NS.lom, "version"), null);
//				while (version.hasNext()) {
//					Value object = version.next().getObject();
//					if (object instanceof Resource) {
//						Iterator<Statement> versionValueStmnts = metadata.match((Resource) object, RDF.VALUE, null);
//						while (versionValueStmnts.hasNext()) {
//							Statement versionValueStmnt = versionValueStmnts.next();
//							Value versionValue = versionValueStmnt.getObject();
//							if (versionValue instanceof Resource) {
//								Iterator<Statement> vStmnts = metadata.match((Resource) versionValue, RDF.VALUE, null);
//								while (vStmnts.hasNext()) {
//									Statement vStmnt = vStmnts.next();
//									Value vValue = vStmnt.getObject();
//									if (vValue instanceof Literal) {
//										toAdd.add(vf.createStatement((Resource) object, RDF.VALUE, vValue));
//									}
//								}
//								Iterator<Statement> delStmnts = metadata.match((Resource) versionValue, null, null);
//								while (vStmnts.hasNext()) {
//									toRemove.add(delStmnts.next());
//								}
//								toRemove.add(versionValueStmnt);
//							}
//						}
//					}
//				}
				
				// 1.3 Language

//				Iterator<Statement> langStmts = metadata.match(null, createURI(NS.dcterms, "language"), null);
//				while (langStmts.hasNext()) {
//					Value sub = langStmts.next().getObject();
//					if (sub instanceof BNode) {
//						Iterator<Statement> stmnts = metadata.match((Resource) sub, RDF.VALUE, null);
//						while (stmnts.hasNext()) {
//							Statement stmnt = stmnts.next();
//							Value obj = stmnt.getObject();
//							if (obj instanceof Literal) {
//								Literal lit = (Literal) obj;
//								if (lit.getDatatype().equals(createURI(NS.dcterms, "RFC3066"))) {
//									Literal newLit = vf.createLiteral(lit.stringValue(), createURI(NS.dcterms, "RFC4646"));
//									toAdd.add(vf.createStatement(stmnt.getSubject(), stmnt.getPredicate(), newLit));
//									toRemove.add(stmnt);
//								}
//							}
//						}
//					}
//				}
				
				// 2.3 Contribution
				
//				Iterator<Statement> contPredStmts = metadata.match(null, createURI(NS.lom, "contribute"), null);
//				while (contPredStmts.hasNext()) {
//					Statement contribution = contPredStmts.next();
//					toAdd.add(vf.createStatement(contribution.getSubject(), createURI(NS.lom, "contribution"), contribution.getObject()));
//					toRemove.add(contribution);
//				}
//				Iterator<Statement> contObjStmts = metadata.match(null, null, createURI(NS.lom, "Contribute"));
//				while (contObjStmts.hasNext()) {
//					Statement contribution = contObjStmts.next();
//					toAdd.add(vf.createStatement(contribution.getSubject(), contribution.getPredicate(), createURI(NS.lom, "Contribution")));
//					toRemove.add(contribution);
//				}
				
				// 2.3.2 Contribute Entity
				
//				Iterator<Statement> entityStmnts = metadata.match(null, createURI(NS.lom, "entity"), null);
//				while (entityStmnts.hasNext()) {
//					Statement entityStmnt = entityStmnts.next();
//					Value entity = entityStmnt.getObject();
//					if (entity instanceof Literal) {
//						BNode bnode = vf.createBNode();
//						toAdd.add(vf.createStatement(entityStmnt.getSubject(), createURI(NS.lom, "entity"), bnode));
//						toAdd.add(vf.createStatement(bnode, createURI(NS.vcard, "FN"), vf.createLiteral(entity.stringValue())));
//						toRemove.add(entityStmnt);
//					}
//				}
				
				// 2.3.3 Contribute Date
				
//				Iterator<Statement> contDateStmts = metadata.match(resURI, createURI(NS.lom, "contribution"), null);
//				while (contDateStmts.hasNext()) {
//					Value contribution = contDateStmts.next().getObject();
//					if (contribution instanceof BNode) {
//						Iterator<Statement> dateStmnts = metadata.match((BNode) contribution, createURI(NS.dcterms, "date"), null);
//						while (dateStmnts.hasNext()) {
//							Statement stat = dateStmnts.next();
//							String dateString = stat.getObject().stringValue();
//							Date newDate = parseDateFromString(dateString);
//							if (newDate != null) {
//								Literal dateLiteral = vf.createLiteral(createW3CDTF(newDate), createURI(NS.dcterms, "W3CDTF"));
//								toAdd.add(vf.createStatement(stat.getSubject(), stat.getPredicate(), dateLiteral));
//								toRemove.add(stat);
//							}
//							//log("CONTRIBUTE :: dcterms:date :: " + dateStmnts.next().getObject().stringValue() + " :: " + localMd.getURI());
//						}
//					}
//				}
				
				// 5.2 Learning Resource Type
				
//				Iterator<Statement> typeStmts = metadata.match(resURI, RDF.TYPE, null);
//				while (typeStmts.hasNext()) {
//					Statement type = typeStmts.next();
//					if (type.getObject() instanceof Literal) {
//						try {
//							org.openrdf.model.URI typeURI = vf.createURI(type.getObject().stringValue());
//							toAdd.add(vf.createStatement(type.getSubject(), RDF.TYPE, typeURI));
//							toRemove.add(type);
//						} catch (IllegalArgumentException iae) {
//							log.error("rdf:type is not a URI: " + type.getObject().stringValue());
//						}
//					}
//				}
				
				// 5.6 Context
				
//				Iterator<Statement> contextStmts = metadata.match(resURI, createURI(NS.lom, "context"), null);
//				while (contextStmts.hasNext()) {
//					Statement context = contextStmts.next();
//					if (context.getObject() instanceof Literal) {
//						try {
//							org.openrdf.model.URI contextURI = vf.createURI(context.getObject().stringValue());
//							toAdd.add(vf.createStatement(context.getSubject(), createURI(NS.lom, "context"), contextURI));
//							toRemove.add(context);
//						} catch (IllegalArgumentException iae) {
//							log.error("lom:context is not a URI: " + context.getObject().stringValue());
//						}
//					}
//				}
				
				// 6.3 Description
				
//				BNode rightsDescBNode = vf.createBNode();
//				Iterator<Statement> rightsDescriptions = metadata.match(resURI, createURI(NS.dcterms, "rights"), null);
//				while (rightsDescriptions.hasNext()) {
//					Statement desc = rightsDescriptions.next();
//					if (desc.getObject() instanceof Literal) {
//						toAdd.add(vf.createStatement(resURI, createURI(NS.dcterms, "rights"), rightsDescBNode));
//						toAdd.add(vf.createStatement(rightsDescBNode, RDF.VALUE, desc.getObject()));
//						toRemove.add(desc);
//					}
//				}
				
				// 9 Classification
				
//				Iterator<Statement> ontTerms = metadata.match(resURI, createURI(NS.dcterms, "subject"), null);
//				while (ontTerms.hasNext()) {
//					Statement ontTerm = ontTerms.next();
//					if (ontTerm.getObject() instanceof Resource) {
//						toAdd.add(vf.createStatement(resURI, vf.createURI("http://organic-edunet.eu/LOM/rdf/voc#Explains"), ontTerm.getObject()));
//						toRemove.add(ontTerm);
//					}
//				}
//				
//				Iterator<Statement> isAboutPreds = metadata.match(resURI, vf.createURI("http://organic-edunet.eu/LOM/rdf/voc#IsAbout"), null);
//				while (isAboutPreds.hasNext()) {
//					Statement isAboutPred = isAboutPreds.next();
//					if (isAboutPred.getObject() instanceof Resource) {
//						toAdd.add(vf.createStatement(resURI, vf.createURI("http://organic-edunet.eu/LOM/rdf/voc#Explains"), isAboutPred.getObject()));
//						toRemove.add(isAboutPred);
//					}
//				}
//				
//				Iterator<Statement> provExmplOnPreds = metadata.match(resURI, vf.createURI("http://organic-edunet.eu/LOM/rdf/voc#ProvidesExamplesOn"), null);
//				while (provExmplOnPreds.hasNext()) {
//					Statement provExmplPred = provExmplOnPreds.next();
//					if (provExmplPred.getObject() instanceof Resource) {
//						toAdd.add(vf.createStatement(resURI, vf.createURI("http://organic-edunet.eu/LOM/rdf/voc#ProvidesExamplesOf"), provExmplPred.getObject()));
//						toRemove.add(provExmplPred);
//					}
//				}
				
				// 8 Annotation / Validation
				
//				Map<String, org.openrdf.model.URI> o2uri = new HashMap<String, org.openrdf.model.URI>();
//				org.openrdf.model.URI aua = vf.createURI("http://www.aua.gr");
//				o2uri.put("Agricultural University of Athens".toLowerCase(), aua);
//				o2uri.put("Agricultural University".toLowerCase(), aua);
//				o2uri.put("vprot@aua.gr".toLowerCase(), aua);
//				o2uri.put("agriculturral university of athens".toLowerCase(), aua);
//				o2uri.put("vassilis protonotarios".toLowerCase(), aua);
//				
//				org.openrdf.model.URI intute = vf.createURI("http://www.intute.ac.uk");
//				o2uri.put("Intute".toLowerCase(), intute);
//				o2uri.put("Intrute".toLowerCase(), intute);
//				o2uri.put("lynda.gibbins@nottingham.ac.uk".toLowerCase(), intute);
//				
//				org.openrdf.model.URI bce = vf.createURI("http://www.uni-corvinus.hu");
//				o2uri.put("BCE".toLowerCase(), bce);
//				
//				org.openrdf.model.URI mogert = vf.createURI("http://www.mogert.uni-corvinus.hu");
//				o2uri.put("MÖGÉRT".toLowerCase(), mogert);
//				o2uri.put("mogert".toLowerCase(), mogert);
//				
//				org.openrdf.model.URI umb = vf.createURI("http://www.umb.no");
//				o2uri.put("Norwegian University of Life Sciences (UMB)".toLowerCase(), umb);
//				o2uri.put("Norwegian University of Life Sciences".toLowerCase(), umb);
//				o2uri.put("norwegian unviersity of life sciences (umb)".toLowerCase(), umb);
//				
//				org.openrdf.model.URI euls = vf.createURI("http://www.emu.ee");
//				o2uri.put("Estonian University of Life Sciences".toLowerCase(), euls);
//				o2uri.put("EULS".toLowerCase(), euls);
//				
//				org.openrdf.model.URI miksike = vf.createURI("http://lefo.net");
//				o2uri.put("Miksike".toLowerCase(), miksike);
//				o2uri.put("mikske".toLowerCase(), miksike);
//				o2uri.put("miks´ike".toLowerCase(), miksike);
//				o2uri.put("keila algkool".toLowerCase(), miksike);
//				o2uri.put("krila algkool".toLowerCase(), miksike);
//				
//				org.openrdf.model.URI bmfluw = vf.createURI("http://www.lebensministerium.at");
//				o2uri.put("Lebensministerium".toLowerCase(), bmfluw);
//				o2uri.put("lebensminisiterium".toLowerCase(), bmfluw);
//				o2uri.put("lebenministerium".toLowerCase(), bmfluw);
//				o2uri.put("lebensministeriun".toLowerCase(), bmfluw);
//				o2uri.put("lebensminisiterium".toLowerCase(), bmfluw);
//				o2uri.put("lebensminisiterum".toLowerCase(), bmfluw);
//				o2uri.put("lebensministeriums".toLowerCase(), bmfluw);
//				o2uri.put("lebensministerium.at".toLowerCase(), bmfluw);
//				o2uri.put("christiane.wagner-alt@lebensministerium.at".toLowerCase(), bmfluw);
//				
//				org.openrdf.model.URI usamvb = vf.createURI("http://www.agro-bucuresti.ro");
//				o2uri.put("USAMVB-FA".toLowerCase(), usamvb);
//				o2uri.put("USAMVB_FA".toLowerCase(), usamvb);
//				o2uri.put("USAMVB".toLowerCase(), usamvb);
//				o2uri.put("usamv-fa".toLowerCase(), usamvb);
//				
//				org.openrdf.model.URI ea = vf.createURI("http://www.ellinogermaniki.gr");
//				o2uri.put("EA".toLowerCase(), ea);
//				o2uri.put("εα".toLowerCase(), ea);
//				o2uri.put("9th Primary school of Rethymnon".toLowerCase(), ea);
//				o2uri.put("science laboratory centre for primary education [slcpe] rethymno, crete".toLowerCase(), ea);
//				
//				org.openrdf.model.URI uah = vf.createURI("http://www.uah.es");
//				o2uri.put("Universidad de Alcala".toLowerCase(), uah);
//				
//				Iterator<Statement> annotations = metadata.match(resURI, createURI(NS.lom, "annotation"), null);
//				while (annotations.hasNext()) {
//					Value resource = annotations.next().getObject();
//					if (resource instanceof Resource) {
//						Iterator<Statement> entityStmnts = metadata.match((Resource) resource, createURI(NS.lom, "entity"), null);
//						while (entityStmnts.hasNext()) {
//							Statement entityStmnt = entityStmnts.next();
//							Value entity = entityStmnt.getObject();
//							if (entity instanceof Resource) {
//								Iterator<Statement> orgStmnts = metadata.match((Resource) entity, createURI(NS.vcard, "ORG"), null);
//								if (orgStmnts.hasNext()) {
//									Value orgValue = orgStmnts.next().getObject();
//									if (orgValue instanceof Resource) {
//										Iterator<Statement> orgNameStmnts = metadata.match((Resource) orgValue, createURI(NS.vcard, "Orgname"), null);
//										while (orgNameStmnts.hasNext()) {
//											Statement orgNameStmnt = orgNameStmnts.next();
//											Value orgNameValue = orgNameStmnt.getObject();
//											if (orgNameValue instanceof Literal) {
//												String orgStr = orgNameValue.stringValue().trim().toLowerCase();
//												if (o2uri.containsKey(orgStr)) {
//													toAdd.add(vf.createStatement(orgNameStmnt.getSubject(), orgNameStmnt.getPredicate(), o2uri.get(orgStr)));
//													toRemove.add(orgNameStmnt);
//												} else {
//													if (orgStr.length() == 6 && orgStr.startsWith("m") && orgStr.endsWith("rt")) {
//														toAdd.add(vf.createStatement(orgNameStmnt.getSubject(), orgNameStmnt.getPredicate(), o2uri.get("mogert")));
//														toRemove.add(orgNameStmnt);
//													}
//													log.warn("No URI found for: " + orgStr + ", Entry URI: " + entry.getEntryURI());
//												}
//											}
//										}
//									}
//								}
//							}
//						}
//					}
//				}
				
				// explains fix
				
//				Iterator<Statement> explainsPreds = metadata.match(resURI, vf.createURI("http://organic-edunet.eu/LOM/rdf/voc#explains"), null);
//				while (explainsPreds.hasNext()) {
//					Statement explainsPred = explainsPreds.next();
//					if (explainsPred.getObject() instanceof Resource) {
//						toAdd.add(vf.createStatement(resURI, vf.createURI("http://organic-edunet.eu/LOM/rdf/voc#Explains"), explainsPred.getObject()));
//						toRemove.add(explainsPred);
//					}
//				}
				
				// Ontology term replacement
				
//				String oldNS = "http://www.semanticweb.org/ontologies/2008/8/Ontology1222276003796.owl#";
//				String newNS = "http://www.cc.uah.es/ie/ont/OE-OAAE#";
//				Iterator<Statement> ontStmnts = metadata.match(resURI, null, null);
//				while (ontStmnts.hasNext()) {
//					Statement s = ontStmnts.next();
//					if (s.getObject() instanceof org.openrdf.model.URI) {
//						String p = s.getPredicate().stringValue();
//						String newP = null;
//						String o = s.getObject().stringValue();
//						String newO = null;
//						
//						if (p.equals("http://organic-edunet.eu/LOM/rdf/voc#Methodology")) {
//							newP = "http://www.cc.uah.es/ie/ont/OE-Predicates#Methodology";
//						} else if (p.equals("http://organic-edunet.eu/LOM/rdf/voc#ProvidesTheoreticalInformationOn")) {
//							newP = "http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesTheoreticalInformationOn";
//						} else if (p.equals("http://organic-edunet.eu/LOM/rdf/voc#Supports")) {
//							newP = "http://www.cc.uah.es/ie/ont/OE-Predicates#Supports";
//						} else if (p.equals("http://organic-edunet.eu/LOM/rdf/voc#ProvidesNewInformationOn")) {
//							newP = "http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesNewInformationOn";
//						} else if (p.equals("http://organic-edunet.eu/LOM/rdf/voc#Details")) {
//							newP = "http://www.cc.uah.es/ie/ont/OE-Predicates#Details";
//						} else if (p.equals("http://organic-edunet.eu/LOM/rdf/voc#ProvidesExamplesOf")) {
//							newP = "http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesExamplesOf";
//						} else if (p.equals("http://organic-edunet.eu/LOM/rdf/voc#CommentsOn")) {
//							newP = "http://www.cc.uah.es/ie/ont/OE-Predicates#CommentsOn";
//						} else if (p.equals("http://organic-edunet.eu/LOM/rdf/voc#ProvidesAlternativeViewOn")) {
//							newP = "http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesAlternativeViewOn";
//						} else if (p.equals("http://organic-edunet.eu/LOM/rdf/voc#Refutes")) {
//							newP = "http://www.cc.uah.es/ie/ont/OE-Predicates#Refutes";
//						} else if (p.equals("http://organic-edunet.eu/LOM/rdf/voc#ProvidesBackgroundOn")) {
//							newP = "http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesBackgroundOn";
//						} else if (p.equals("http://organic-edunet.eu/LOM/rdf/voc#ProvidesDataOn")) {
//							newP = "http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesDataOn";
//						} else if (p.equals("http://organic-edunet.eu/LOM/rdf/voc#Summarizes")) {
//							newP = "http://www.cc.uah.es/ie/ont/OE-Predicates#Summarizes";
//						} else if (p.equals("http://organic-edunet.eu/LOM/rdf/voc#Explains")) {
//							newP = "http://www.cc.uah.es/ie/ont/OE-Predicates#Explains";
//						}
//						
//						if (o.endsWith("#VegetableOriginProduct")) {
//							newO = "PlantOriginProduct";
//						} else if (o.endsWith("#Cheese")) {
//							newO = "DairyProduct";
//						} else if (o.endsWith("#DiseasesPrevention")) {
//							newO = "DiseasePrevention";
//						} else if (o.endsWith("#AgriculturalPractice")) {
//							newO = "AgriculturalMethod";
//						} else if (o.endsWith("#DiseasesTreatment")) {
//							newO = "DiseaseTreatment";
//						} else if (o.endsWith("#ClimateChangeMitigation")) {
//							newO = "EnvironmentIssue";
//						} else if (o.endsWith("#OrganicCertification")) {
//							newO = "Certification";
//						} else if (o.endsWith("#AgroFoodSystem")) {
//							newO = "FoodSystem";
//						} else if (o.endsWith("#WineProductionProcess")) {
//							newO = "Viticulture";
//						} else if (o.endsWith("#TraditionalFarming")) {
//							newO = "AgriculturalMethod";
//						} else if (o.endsWith("#Producer")) {
//							newO = "Farmer";
//						} else if (o.endsWith("#FoodHealth")) {
//							newO = "FoodIssue";
//						} else if (o.endsWith("#RegulatoryAgency")) {
//							newO = "CertificationAgency";
//						} else if (o.endsWith("#EUOrganicCertification")) {
//							newO = "Certification";
//						} else if (o.endsWith("#CommunitySupportedAgriculture")) {
//							newO = "AlternativeFarming";
//						} else if (o.endsWith("#FishFarming")) {
//							newO = "AquaCulture";
//						} else if (o.startsWith(oldNS)) {
//							newO = o.substring(oldNS.length());
//						}
//						
//						if (newP != null || newO != null) {
//							toRemove.add(s);
//							if (newP != null && newO != null) {
//								toAdd.add(vf.createStatement(s.getSubject(), vf.createURI(newP), vf.createURI(newNS, newO)));
//							} else if (newP == null && newO != null) {
//								toAdd.add(vf.createStatement(s.getSubject(), s.getPredicate(), vf.createURI(newNS, newO)));
//							} else if (newP != null && newO == null) {
//								toAdd.add(vf.createStatement(s.getSubject(), vf.createURI(newP), s.getObject()));
//							} else {
//								log.error("THIS SHOULDN'T HAPPEN");
//								toRemove.remove(s);
//							}
//						}
//					}
//				}
				
//				String oeNS = "http://www.cc.uah.es/ie/ont/OE-OAAE#";
//				Iterator<Statement> ontStmnts = metadata.match(resURI, null, null);
//				while (ontStmnts.hasNext()) {
//					Statement s = ontStmnts.next();
//					if (s.getObject() instanceof org.openrdf.model.URI) {
//						String o = s.getObject().stringValue();
//						String newO = null;
//
//						if (o.equals(oeNS + "SheepMilk")) {
//							newO = oeNS + "Milk";
//						} else if (o.equals(oeNS + "NaturalPerson")) {
//							newO = oeNS + "IndividualEntity";
//						} else if (o.equals(oeNS + "MethodOrTechnique")) {
//							newO = oeNS + "Method";
//						} else if (o.equals(oeNS + "Certification")) {
//							newO = oeNS + "CertificationProcess";
//						} else if (o.equals(oeNS + "FarmingMethod")) {
//							newO = oeNS + "AgriculturalMethod";
//						} else if (o.equals(oeNS + "IdeologyIssue")) {
//							newO = oeNS + "EthicalIssue";
//						} else if (o.equals(oeNS + "Legislation")) {
//							newO = oeNS + "Standard";
//						} else if (o.equals(oeNS + "FodderCrop")) {
//							newO = oeNS + "FoddCrop";
//						} else if (o.equals(oeNS + "FarmersMarket")) {
//							newO = oeNS + "LocalMarket";
//						} else if (o.equals(oeNS + "MarketTrends")) {
//							newO = oeNS + "MarketTrend";
//						} else if (o.equals(oeNS + "GMOAvoidance")) {
//							newO = oeNS + "GMO";
//						} else if (o.equals(oeNS + "ConversionProcess")) {
//							newO = oeNS + "OrganicConversion";
//						} else if (o.equals(oeNS + "AnimalTransport")) {
//							newO = oeNS + "TransportOfAnimals";
//						} else if (o.equals(oeNS + "ProductsTransport")) {
//							newO = oeNS + "TransportOfProducts";
//						} else if (o.equals(oeNS + "MeatProductionProcess")) {
//							newO = oeNS + "MeatProduction";
//						} else if (o.equals(oeNS + "MilkProductionProcess")) {
//							newO = oeNS + "MilkProduction";
//						} else if (o.equals(oeNS + "LabelingRegulation")) {
//							newO = oeNS + "Labeling";
//						} else if (o.equals(oeNS + "EULegislation")) {
//							newO = oeNS + "Legislation";
//						} else if (o.equals(oeNS + "EULegislationOnGMO")) {
//							newO = oeNS + "LegislationOnOrganicAgriculture";
//						} else if (o.equals(oeNS + "Dairy")) {
//							newO = oeNS + "DairyProduct";
//						} else if (o.equals(oeNS + "Fodder")) {
//							newO = oeNS + "FodderCereal";
//						} else if (o.equals(oeNS + "BuffaloMilk")) {
//							newO = oeNS + "Milk";
//						} else if (o.equals(oeNS + "CattleMeat")) {
//							newO = oeNS + "Meat";
//						} else if (o.equals(oeNS + "ChickenMeat")) {
//							newO = oeNS + "Meat";
//						} else if (o.equals(oeNS + "CowMilk")) {
//							newO = oeNS + "Milk";
//						} else if (o.equals(oeNS + "GoatMilk")) {
//							newO = oeNS + "Milk";
//						} else if (o.equals(oeNS + "Jelly")) {
//							newO = oeNS + "JellyFish";
//						} else if (o.equals(oeNS + "LambMeat")) {
//							newO = oeNS + "Meat";
//						} else if (o.equals(oeNS + "PigMeat")) {
//							newO = oeNS + "Meat";
//						} else if (o.equals(oeNS + "PoultryMeat")) {
//							newO = oeNS + "Meat";
//						} else if (o.equals(oeNS + "RabbitMeat")) {
//							newO = oeNS + "Meat";
//						} else if (o.equals(oeNS + "ActivityType")) {
//							newO = oeNS + "Activity";
//						} else if (o.equals(oeNS + "EducationActivity")) {
//							newO = oeNS + "EducationalActivity";
//						} else if (o.equals(oeNS + "JuridicalPerson")) {
//							newO = oeNS + "JuridicalEntity";
//						} else if (o.equals(oeNS + "ChemicallyOrganicFertilizer")) {
//							newO = "remove";
//						} else if (o.equals(oeNS + "ChemicallyInorganicFertilizer")) {
//							newO = "remove";
//						} else if (o.equals(oeNS + "UnprocessedProduct")) {
//							newO = "remove";
//						} else if (o.equals(oeNS + "ProcessedProduct")) {
//							newO = "remove";
//						} else if (o.equals(oeNS + "ProductionProcess")) {
//							newO = "remove";
//						} else if (o.equals(oeNS + "ProductIntegrity")) {
//							newO = "remove";
//						} else if (o.equals(oeNS + "Fermentation")) {
//							newO = "remove";
//						} else if (o.equals(oeNS + "Sterilization")) {
//							newO = "remove";
//						} else if (o.equals(oeNS + "Concepts")) {
//							newO = "remove";
//						} else if (o.equals(oeNS + "LowInputAgriculture")) {
//							newO = oeNS + "ExtensiveFarming";
//						} else if (o.equals(oeNS + "CerealsCultivation")) {
//							newO = oeNS + "CultivationOfCereals";
//						} else if (o.equals(oeNS + "Diet")) {
//							newO = oeNS + "AnimalDiet";
//						} else if (o.equals(oeNS + "Fertilization")) {
//							newO = oeNS + "Fertilizing";
//						} else if (o.equals(oeNS + "NaturalNutrition")) {
//							newO = oeNS + "AnimalNutrition";
//						} else if (o.equals(oeNS + "SoilTillage")) {
//							newO = oeNS + "Tillage";
//						} else if (o.equals(oeNS + "ProductsExport")) {
//							newO = oeNS + "Import-ExportIssue";
//						} else if (o.equals(oeNS + "RenewevableResources")) {
//							newO = oeNS + "RenewableResources";
//						} else if (o.equals(oeNS + "ProductsImport")) {
//							newO = oeNS + "Import-ExportIssue";
//						} else if (o.equals(oeNS + "SoilBiology")) {
//							newO = oeNS + "SoilBiologicalActivity";
//						} else if (o.equals(oeNS + "EUOrganicStandard")) {
//							newO = oeNS + "OrganicStandard";
//						} else if (o.equals(oeNS + "CarbonSequestration")) {
//							newO = oeNS + "EnvironmentalIssue";
//						} else if (o.equals(oeNS + "HACCP")) {
//							newO = oeNS + "FoodSafety";
//						} else if (o.equals(oeNS + "ShellAquaculture")) {
//							newO = oeNS + "AquaCulture";
//						} else if (o.equals(oeNS + "Corn")) {
//							newO = oeNS + "Maize";
//						} else if (o.equals(oeNS + "IFOAMPrinciples")) {
//							newO = oeNS + "OrganicPrinciple";
//						} else if (o.equals(oeNS + "NaturalBehaviour")) {
//							newO = oeNS + "AnimalBehaviour";
//						} else if (o.equals(oeNS + "CO2Miles")) {
//							newO = oeNS + "EcologicalFootprint";
//						} else if (o.equals(oeNS + "FodderPreference")) {
//							newO = oeNS + "AnimalDiet";
//						} else if (o.equals(oeNS + "AnimalProductionDerivedActivity")) {
//							newO = oeNS + "AnimalProductionActivity";
//						} else if (o.equals(oeNS + "ShelfLife")) {
//							newO = oeNS + "Market";
//						} else if (o.equals(oeNS + "BeeOrigin")) {
//							newO = oeNS + "Apiculture";
//						} else if (o.equals(oeNS + "ISO14000Program")) {
//							newO = oeNS + "FoodQuality";
//						} else if (o.equals(oeNS + "PlantProductionDerivedActivity")) {
//							newO = oeNS + "PlantProductionActivity";
//						} else if (o.equals(oeNS + "EULegislationOnOrganicFertilizers")) {
//							newO = oeNS + "Legislation";
//						} else if (o.equals(oeNS + "Sausage")) {
//							newO = oeNS + "AnimalOriginProcessedProduct";
//						} else if (o.equals(oeNS + "SoyaDrink")) {
//							newO = oeNS + "PlantOriginProcessedProduct";
//						} else if (o.equals(oeNS + "Ecoturism")) {
//							newO = oeNS + "Ecotourism";
//						} else if (o.equals(oeNS + "GeneticResistence")) {
//							newO = oeNS + "GeneticResistance";
//						} else if (o.equals(oeNS + "LeatherProduction")) {
//							newO = oeNS + "Leather";
//						} else if (o.equals(oeNS + "PlantoriginProcessedProduct")) {
//							newO = oeNS + "PlantOriginProcessedProduct";
//						} else if (o.equals(oeNS + "AnimaloriginProcessedProduct")) {
//							newO = oeNS + "AnimalOriginProcessedProduct";
//						}
//
//						if (newO != null) {
//							if (newO.equals("remove")) {
//								toRemove.add(s);
//							} else {
//								toAdd.add(vf.createStatement(s.getSubject(), s.getPredicate(), vf.createURI(newO)));
//								toRemove.add(s);
//							}
//						}
//					}
//				}

				Iterator<Statement> rdfsTypeStmnts = metadata.match(null, vf.createURI("http://www.w3.org/TR/rdf-schema/type"), null);
				while (rdfsTypeStmnts.hasNext()) {
					Statement rdfsTypeStmnt = rdfsTypeStmnts.next();
					if (rdfsTypeStmnt.getObject() instanceof Resource) {
						String objValue = rdfsTypeStmnt.getObject().stringValue();
						if (objValue.startsWith("http://purl.org/telmap/") || // fix for telmap
								objValue.equals("http://http://xmlns.com/foaf/0.1/Person")) { // fix for voa3r
							toAdd.add(vf.createStatement(rdfsTypeStmnt.getSubject(), RDF.TYPE, rdfsTypeStmnt.getObject()));
							toRemove.add(rdfsTypeStmnt);
						}
					}
				}
				
				// Logging and graph modification
				
				if (!toAdd.isEmpty()) {
					log("ADD");
					for (Statement statement : toAdd) {
						log(statement.toString());
					}
					if (!metadata.addAll(toAdd)) {
						log("STRANGE: Graph not modified");
					} else {
						updateNecessary = true;
					}
				}

				if (!toRemove.isEmpty()) {
					log("DEL");
					for (Statement statement : toRemove) {
						log(statement.toString());
					}
					if (!metadata.removeAll(toRemove)) {
						log("STRANGE: Graph not modified");
					} else {
						updateNecessary = true;
					}
				}

				if (updateNecessary) {
					// set the updated graph
					localMd.setGraph(metadata);
					log("----- Updated metadata of entry: " + entry.getEntryURI());
				}
			}
		}
	}
	
	private static Set<Date> getStrangeDates(Entry entry) {
		Set<Date> result = new HashSet<Date>();
		
		Date from = parseDateFromStringStrict("1910-01-01");
		Date until = parseDateFromStringStrict("2009-12-01");
		org.openrdf.model.URI dctermsDate = new URIImpl(NS.dcterms + "date");
		org.openrdf.model.URI w3cdtf = new URIImpl("http://purl.org/dc/terms/W3CDTF");
		
		Metadata localMd = entry.getLocalMetadata();
		if (localMd != null) {
			Graph metadata = entry.getLocalMetadata().getGraph();
			if (metadata != null) {
				Iterator<Statement> stmnts = metadata.match(null, dctermsDate, null);
				while (stmnts.hasNext()) {
					Value obj = stmnts.next().getObject();
					if (obj instanceof Literal) {
						Literal lit = (Literal) obj;
						if (!w3cdtf.equals(lit.getDatatype())) {
							log("STRANGE: incorrect datatype!");
						}
						Date date = parseDateFromStringStrict(lit.stringValue());
						if (date.before(from) || date.after(until)) {
							result.add(date);
						}
					}
				}
			}
		}
		return result;
	}
	
	private static Date parseDateFromStringStrict(String dateString) {
		try {
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");//'T'HH:mm:ssZ");
			Date d = sdf.parse(dateString);
			//log("Matched: yyyy-MM-dd'T'HH:mm:ssZ :: " + dateString);
			return d;
		} catch (ParseException pe) {
			log(pe.getMessage());
		}
		return null;
	}
	
	private static String dateToString(Date date) {
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
		return sdf.format(date);
	}
	
	private static Date parseDateFromString(String dateString) {
		try {
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
			Date d = sdf.parse(dateString);
			log("Matched: yyyy-MM-dd'T'HH:mm:ssZ :: " + dateString);
			return d;
		} catch (ParseException pe) {}
		
		Set<String> formats = new HashSet<String>();
		formats.add("dd MMMMM yyyy");
		formats.add("MMMMM yyyy");
		formats.add("dd.MM.yy");
		formats.add("dd/MM/yyyy");
		formats.add("dd-MM-yyyy");
		formats.add("dd-MMMMM-yyyy");
		formats.add("ddMMyyyy");
		formats.add("yyyy-MM-dd");
		
		SimpleDateFormat sdf = null;
		for (String format : formats) {
			sdf = new SimpleDateFormat(format, Locale.ENGLISH);
			try {
				Date d = sdf.parse(dateString);
				log("Matched: " + format + " :: " + dateString);
				return d;
			} catch (ParseException pe) {}
		}
		
		// the following patterns cannot be included in the Set above because the order matters
		try {
			sdf = new SimpleDateFormat("dd.MM.yyyy");
			Date d = sdf.parse(dateString);
			log("Matched: dd.MM.yyyy :: " + dateString);
			return d;
		} catch (ParseException pe) {}
		try {
			sdf = new SimpleDateFormat("MM/yyyy");
			Date d = sdf.parse(dateString);
			log("Matched: MM/yyyy :: " + dateString);
			return d;
		} catch (ParseException pe) {}
		try {
			sdf = new SimpleDateFormat("MM-yyyy");
			Date d = sdf.parse(dateString);
			log("Matched: MM-yyyy :: " + dateString);
			return d;
		} catch (ParseException pe) {}
		try {
			sdf = new SimpleDateFormat("yyyy-MM");
			Date d = sdf.parse(dateString);
			log("Matched: yyyy-MM :: " + dateString);
			return d;
		} catch (ParseException pe) {}
		try {
			sdf = new SimpleDateFormat("yyyy");
			Date d = sdf.parse(dateString);
			log("Matched: yyyy :: " + dateString);
			return d;
		} catch (ParseException pe) {}
		
		log("UNMATCHED: " + dateString);
		return null;
	}
	
	public void checkAllDates() {
		URI currentUser = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		Writer writer = null;
		try {
			writer = new FileWriter(new File("/home/hannes/Desktop/dates.txt"));
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			List<Entry> entries = getEntries(getContexts());
			for (Entry entry : entries) {
				if (!ConverterUtil.isValidated(entry.getMetadataGraph(), entry.getResourceURI())) {
					continue;
				}
				Set<Date> strangeDates = getStrangeDates(entry);
				if (!strangeDates.isEmpty()) {
					String datesStr = "";
					for (Date date : strangeDates) {
						datesStr += dateToString(date) + ";";
					}
					String logMsg = "URI: " + entry.getEntryURI() + ", Title: \"" + EntryUtil.getTitle(entry, "en") + "\", suspicious date(s): " + datesStr;
					log(logMsg);
					try {
						writer.write(logMsg + "\n");
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
		try {
			writer.flush();
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void fixMetadataGlobally() {
		URI currentUser = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		try {
			List<Entry> entries = getEntries(getContexts());
			for (Entry entry : entries) {
				if (!entry.getEntryType().equals(EntryType.Reference)) {// && !entry.getResourceType().equals(ResourceType.None)) {
					fixMetadataOfEntry(entry);
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}
	
	public void fixPrincipalsGlobally() {
		URI currentUser = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		try {
			List<User> users = pm.getUsers();
			for (User user : users) {
				Entry entry = user.getEntry();
				if (entry != null) {
					fixMetadataOfPrincipal(entry);
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}
	
	public void convertPasswordsToHashes() {
		URI currentUser = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		try {
			List<User> users = pm.getUsers();
			for (User user : users) {
				String secret = user.getSecret();
				if (secret != null) {
					log.warn("Replacing password with salted hashed password for user " + user.getURI());
					if (!user.setSecret(secret)) {
						log.error("Unable to reset password of user " + user.getURI());
					}
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}
	
	public void printFileNamesGlobally() {
		URI currentUser = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		try {
			List<Entry> entries = getEntries(getContexts());
			for (Entry entry : entries) {
				if (entry.getEntryType().equals(EntryType.Local)) {
					if (entry.getFilename() != null) {
						log.info(entry.getFilename());
					}
				}
			}
		} finally {
			pm.setAuthenticatedUserURI(currentUser);
		}
	}
	
}