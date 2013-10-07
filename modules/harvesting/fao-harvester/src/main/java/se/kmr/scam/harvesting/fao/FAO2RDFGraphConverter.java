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

package se.kmr.scam.harvesting.fao;

import java.util.List;

import org.entrystore.repository.Converter;
import org.openrdf.model.Graph;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.GraphImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.harvesting.fao.FAOSubject.Scheme;

/**
 * Maps FAO metadata to Dublin Core.
 * 
 * AGROVOC Schema information from
 * http://www.schemaweb.info/schema/SchemaInfo.aspx?id=86
 * 
 * @author Hannes Ebner
 */
public class FAO2RDFGraphConverter implements Converter {
	
	Logger log = LoggerFactory.getLogger(FAO2RDFGraphConverter.class);
	
	static String nsDC = "http://purl.org/dc/elements/1.1/";
	static String nsDCTERMS = "http://purl.org/dc/terms/";
	static String nsAGMES = "http://purl.org/agmes/1.1/";
	static String nsAGROVOC = "http://www.fao.org/aims/aos/agrovoc/";

	public Object convert(Object from, java.net.URI resourceURI, java.net.URI metadataURI) {
		FAOMetadata metadata = null; 

		if (from instanceof FAOMetadata) {
			metadata = (FAOMetadata) from;
		} else {
			return null;
		}

		Graph graph = new GraphImpl();
		ValueFactory vf = graph.getValueFactory(); 
		org.openrdf.model.URI root = vf.createURI(resourceURI.toString());

		if (metadata.getCreator() != null) {
			graph.add(root, vf.createURI(nsDC, "creator"), vf.createLiteral(metadata.getCreator()));
		}
		
		if (metadata.getTitle() != null) {
			graph.add(root, vf.createURI(nsDC, "title"), vf.createLiteral(metadata.getTitle()));
		}
		
		if (metadata.getDescription() != null) {
			graph.add(root, vf.createURI(nsDC, "description"), vf.createLiteral(metadata.getDescription()));
		}
		
		if (metadata.getSource() != null) {
			graph.add(root, vf.createURI(nsDC, "source"), vf.createLiteral(metadata.getSource()));
		}
		
		if (metadata.getLanguage() != null) {
			graph.add(root, vf.createURI(nsDC, "language"), vf.createLiteral(metadata.getLanguage()));
		}
		
		if (metadata.getYear() != null) {
			graph.add(root, vf.createURI(nsDC, "date"), vf.createLiteral(metadata.getYear()));
		}
		
		if (metadata.getPages() > -1) {
			graph.add(root, vf.createURI(nsDCTERMS, "extent"), vf.createLiteral(metadata.getPages()));
		}
		
		if (metadata.getPdfURI() != null) {
			graph.add(root, vf.createURI(nsDC, "source"), vf.createLiteral(metadata.getPdfURI()));
		}
		
		List<FAOSubject> subjects = metadata.getSubjects();
		if ((subjects != null) && !subjects.isEmpty()) {
			for (FAOSubject subject : subjects) {
				if (subject.getScheme().equals(Scheme.AGROVOC)) {
					//graph.add(root, vf.createURI(nsAGMES, "subjectClassification"), vf.createLiteral(Integer.toString(subject.getId()), vf.createURI(nsAGMES, "AGROVOC")));
					//graph.add(root, vf.createURI(nsAGMES, "AGROVOC"), vf.createLiteral(Integer.toString(subject.getId())));
					graph.add(root, vf.createURI(nsDCTERMS, "subject"), vf.createURI(nsAGROVOC, Integer.toString(subject.getId())));
				}
			}
		}

		return graph; 
	}

}