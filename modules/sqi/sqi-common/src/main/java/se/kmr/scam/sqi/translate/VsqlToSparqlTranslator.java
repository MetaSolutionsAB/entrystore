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

package se.kmr.scam.sqi.translate;

import java.io.IOException;
import java.io.StringReader;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.apache.xpath.XPathAPI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import se.kmr.scam.repository.impl.converters.NS;
import se.kmr.scam.sqi.query.QueryLanguageType;



/**
 * A class that translates VSQL to SPARQL
 * 
 * VSQL -- a proprietary query language that is metadata standard agnostic. This format only enables the
 * representation of a list of search terms and it requires the repository to provide a meaningful mapping to its
 * metadata. 
 *
 * @author Mikael Karlsson (mikael.karlsson@educ.umu.se)
 */
public class VsqlToSparqlTranslator extends TranslatorImpl {

	private static Logger log = LoggerFactory.getLogger(VsqlToSparqlTranslator.class);

    public VsqlToSparqlTranslator(QueryLanguageType startQueryLanguage, QueryLanguageType endQueryLanguage) {
        super(startQueryLanguage, endQueryLanguage);
        
        
    }

    
    /**
	 * Translate VSQL query
	 * Example of VSQL query:
     * <simpleQuery>
	 *	<term>learning object</term>
	 *	<term>dog</term>
	 * </simpleQuery>
	 *
	 * 
	 * @param query a VSQL query
	 * @param start start record of the results set. The index of the result set size starts with 1.
 	 * @param max maximum number of results.  0 (zero) = no limit
	 * @param resultsFormat format of the results
	 *
	 * @return A string with hits on the  with entries which was found or null.
	 * @throws Exception if something goes wrong
	 */
    public String translate(String query, int startResult, int resultsSetSize) throws TranslationException {
    	if (getStartQueryLanguage() != QueryLanguageType.VSQL && getEndQueryLanguage() != QueryLanguageType.SPARQL) {
    		throw new TranslationException("Translation from " +  getStartQueryLanguage().toString() + " to " + getEndQueryLanguage().toString() +" not supported");
    	}
		
    	return vsqlToSparql(query, startResult, resultsSetSize);
    }
    
    private String vsqlToSparql(String query, int startResult, int resultsSetSize) throws TranslationException {
        try {
            InputSource input = new InputSource(new StringReader(query));
            Node queryNode = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input).getFirstChild();
            NodeList nl = XPathAPI.selectNodeList(queryNode, "term/text()");
            if (nl.getLength() == 0) {
            	throw new TranslationException("Translation error: not valid VSQL:" + query);
            }
            
            String squery = "";
    		
    	    for (int i = 0; i < nl.getLength(); i++) {
                String term =nl.item(i).getNodeValue();
                if (i == 0)
                	squery +=  "regex(str(?y), \"" + term + "\", \"i\")"; 
                else
                	squery += " && " + "regex(str(?y), \"" + term + "\", \"i\")"; 
            }

    		
    		StringBuilder metadataQuery = new StringBuilder(
					"PREFIX rdf:<" + NS.rdf + "> \n" +
					"PREFIX dcterms:<" + NS.dcterms + "> \n" +
					"PREFIX dc:<" +  NS.dc + "> \n" +
					"PREFIX lom:<" + NS.lom + "> \n" +
					"SELECT DISTINCT ?g \n" +
					"WHERE { GRAPH ?g { \n" +
					"{ ?x dc:title ?y } UNION \n" +
					"{ ?x dc:description ?y } UNION \n" +
					"{ ?x dcterms:title ?y } UNION \n" +
					"{ ?x dcterms:description ?b. \n" +
					"  ?b rdf:value ?y } UNION \n" +
					"{ ?x lom:keyword ?b. \n" +
					"  ?b rdf:value ?y } UNION \n" +
					"{ ?x dc:subject ?y } \n" + 
					"FILTER(" + squery + ") \n" +
					" } } "	+
					"ORDER BY ASC(?g) \n");
  		
//    		if (resultsSetSize >= 1) {
//    			metadataQuery.append("LIMIT " + resultsSetSize + "\n");
//    		}
//    		 
//    	    // a valid number for startResult can range from 1 to the total number of results 
//    	    if (startResult - 1 >= 0) {
//    			metadataQuery.append("OFFSET " + (startResult - 1));
//    		}
    		
            log.debug("vsqlToSparql: Translated \"" + query + "\" to \"" + metadataQuery.toString() + "\"");

            return metadataQuery.toString();
        } catch (SAXException e) {
        	throw new TranslationException("Translation error:", e);
        } catch (IOException e) {
        	throw new TranslationException("Translation error:", e);
        } catch (ParserConfigurationException e) {
        	throw new TranslationException("Translation error:", e);
        } catch (TransformerException e) {
        	throw new TranslationException("Translation error:", e);
        }
    }
    
}