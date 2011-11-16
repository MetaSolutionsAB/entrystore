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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.ieee.ltsc.datatype.impl.LangStringImpl.StringImpl;
import org.ieee.ltsc.lom.impl.LOMImpl;
import org.ieee.ltsc.lom.impl.LOMImpl.Classification;
import org.ieee.ltsc.lom.impl.LOMImpl.Classification.TaxonPath;
import org.ieee.ltsc.lom.impl.LOMImpl.Classification.TaxonPath.Taxon;
import org.openrdf.model.Graph;
import org.openrdf.model.Literal;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.URIImpl;

/**
 * Overrides some specific methods to reflect the implementation of LOM in
 * SHAME/Organic.Edunet.
 * 
 * @author Hannes Ebner
 */
public class OERDF2LOMConverter extends RDF2LOMConverter {
	
	public static Map<org.openrdf.model.URI, String> ORGANIZATIONS = new HashMap<org.openrdf.model.URI, String>();
	
	public OERDF2LOMConverter() {
		super(true);
	}
	
	static {
		ORGANIZATIONS.put(new URIImpl("http://www.aua.gr"), "Agricultural University of Athens (AUA)");
		ORGANIZATIONS.put(new URIImpl("http://www.intute.ac.uk"), "Intute / University of Nottingham (Intute)");
		ORGANIZATIONS.put(new URIImpl("http://www.uni-corvinus.hu"), "Budapesti Corvinus Egyetem (BCE)");
		ORGANIZATIONS.put(new URIImpl("http://www.mogert.uni-corvinus.hu"), "MOGERT");
		ORGANIZATIONS.put(new URIImpl("http://www.umb.no"), "Norwegian University of Life Sciences (UMB)");
		ORGANIZATIONS.put(new URIImpl("http://www.emu.ee"), "Estonian University of Life Sciences (EULS)");
		ORGANIZATIONS.put(new URIImpl("http://lefo.net"), "Miksike");
		ORGANIZATIONS.put(new URIImpl("http://www.lebensministerium.at"), "Lebensministerium (BMLFUW)");
		ORGANIZATIONS.put(new URIImpl("http://www.agro-bucuresti.ro"), "USAMVB-FA");
		ORGANIZATIONS.put(new URIImpl("http://www.ellinogermaniki.gr"), "Ellinogermaniki Agogi");
		ORGANIZATIONS.put(new URIImpl("http://www.uah.es"), "Universidad de Alcala");
		ORGANIZATIONS.put(new URIImpl("http://www.bmukk.gv.at"), "BMUKK");
		ORGANIZATIONS.put(new URIImpl("http://www.grnet.gr"), "Greek Research and Technology Network (GRNET)");
		ORGANIZATIONS.put(new URIImpl("http://www.kth.se"), "Royal Institute of Technology (KTH)");
		ORGANIZATIONS.put(new URIImpl("http://www.fao.org"), "Food and Agriculture Organization of the United Nations (FAO)");
	}
	
	/**
	 * Has to be run after a conversion, otherwise the statistics are in place.
	 * 
	 * @return
	 */
	public boolean hasAllMandatoryElements() {
		return statistics.generalTitle > 0 &&
		statistics.generalDescription > 0 &&
		statistics.generalLanguage > 0 &&
		statistics.rightsCostCopyrightAndOtherRestrictions > 0;
	}
	
	// 1.5 Keyword
	
	@Override
	public void convertGeneralKeyword(Graph input, LOMImpl lom, URI resourceURI) {
		super.convertGeneralKeyword(input, lom, resourceURI);
		
		Iterator<Statement> keywords = input.match(resourceURI, createURI(NS.dc, "subject"), null);
		while (keywords.hasNext()) {
			Value object = keywords.next().getObject();
			if (object instanceof Literal) {
				Literal keywordLiteral = (Literal) object;
				StringImpl keywordString = lom.newGeneral().newKeyword(-1).newString(-1);
				keywordString.setString(keywordLiteral.stringValue());
				String keywordLiteralLang = keywordLiteral.getLanguage();
				if (keywordLiteralLang != null) {
					keywordString.newLanguage().setValue(keywordLiteralLang);
				}
				statistics.generalKeyword++;
			}
		}
	}
	
	/**
	 * We only use 9.2.2.1 in Organic.Edunet, which is stored in
	 * dcterms:subject.
	 * 
	 * @see se.kmr.scam.repository.impl.converters.LOM2RDFConverter#convertClassification(org.openrdf.model.Graph,
	 *      org.ieee.ltsc.lom.impl.LOMImpl, org.openrdf.model.URI)
	 */
	@Override
	public void convertClassification(Graph input, LOMImpl lom, URI resourceURI) {
		ValueFactory vf = input.getValueFactory();
		Set<URI> predicates = new HashSet<URI>();
		
		predicates.add(vf.createURI("http://www.cc.uah.es/ie/ont/OE-Predicates#Supports"));
		predicates.add(vf.createURI("http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesAlternativeViewOn"));
		predicates.add(vf.createURI("http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesExamplesOn"));
		predicates.add(vf.createURI("http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesExamplesOf"));
		predicates.add(vf.createURI("http://www.cc.uah.es/ie/ont/OE-Predicates#Methodology"));
		predicates.add(vf.createURI("http://www.cc.uah.es/ie/ont/OE-Predicates#Summarizes"));
		predicates.add(vf.createURI("http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesDataOn"));
		predicates.add(vf.createURI("http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesBackgroundOn"));
		predicates.add(vf.createURI("http://www.cc.uah.es/ie/ont/OE-Predicates#Details"));
		predicates.add(vf.createURI("http://www.cc.uah.es/ie/ont/OE-Predicates#IsAbout"));
		predicates.add(vf.createURI("http://www.cc.uah.es/ie/ont/OE-Predicates#Explains"));
		predicates.add(vf.createURI("http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesTheoreticalInformationOn"));
		predicates.add(vf.createURI("http://www.cc.uah.es/ie/ont/OE-Predicates#ProvidesNewInformationOn"));
		predicates.add(vf.createURI("http://www.cc.uah.es/ie/ont/OE-Predicates#CommentsOn"));
		predicates.add(vf.createURI("http://www.cc.uah.es/ie/ont/OE-Predicates#Refutes"));
		
		// 9.2.2.1 Classification Taxon Id

		Map<String, String> taxonMap = new HashMap<String, String>();		
		for (URI pred : predicates) {
			Iterator<Statement> taxons = input.match((Resource) resourceURI, pred, null);
			while (taxons.hasNext()) {
				Value taxonID = taxons.next().getObject();
				if (taxonID instanceof URI) {
					String predEntry = pred.stringValue().substring(pred.stringValue().indexOf("#") + 1);
					String objEntry = taxonID.stringValue().substring(taxonID.stringValue().indexOf("#") + 1);
					String key = pred.stringValue() + " :: " + taxonID.stringValue();
					String value = predEntry + " :: " + objEntry;
					taxonMap.put(key, value);
					
					statistics.classificationTaxonPathTaxonId++;
				}
			}	
		}
		
		if (!taxonMap.isEmpty()) {
			Classification classification = lom.newClassification(0);
			classification.newPurpose().newValue().setString("discipline");
			
			Set<String> taxonKeys = taxonMap.keySet();
			for (String key : taxonKeys) {
				TaxonPath taxonPath = classification.newTaxonPath(-1);
				taxonPath.newSource().newString(0).setString("Organic.Edunet Ontology");
				taxonPath.newSource().newString(0).newLanguage().setValue("en");
				Taxon taxon = taxonPath.newTaxon(-1);
				taxon.newId().setString(key);
				taxon.newEntry().newString(0).setString(taxonMap.get(key));
			}
		}
	}

}