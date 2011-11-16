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

import se.kmr.scam.sqi.query.QueryLanguageType;

/**
 * A class that translates VSQL to LUCENEQL
 * 
 * VSQL -- a proprietary query language that is metadata standard agnostic. This format only enables the
 * representation of a list of search terms and it requires the repository to provide a meaningful mapping to its
 * metadata. 
 * 
 * 
 * @author Mikael Karlsson (mikael.karlsson@educ.umu.se)
 */
public class VsqlToLuceneTranslator extends TranslatorImpl {

	private static Logger log = LoggerFactory.getLogger(VsqlToLuceneTranslator.class);

    public VsqlToLuceneTranslator(QueryLanguageType startQueryLanguage, QueryLanguageType endQueryLanguage) {
        super(startQueryLanguage, endQueryLanguage);
    }

    /*
     * Example of VSQL query:
     * <simpleQuery>
		<term>learning object</term>
		<term>dog</term>
	  </simpleQuery>
	 *
	 * return Lucene query:
	 * "learning object" AND "dog"
     */
    public String translate(String query, int startResult, int resultsSetSize) throws TranslationException {
    	if (getStartQueryLanguage() != QueryLanguageType.VSQL && getEndQueryLanguage() != QueryLanguageType.LUCENEQL) {
    		throw new TranslationException("Translation from " +  getStartQueryLanguage().toString() + " to " + getEndQueryLanguage().toString() +" not supported");
    	}
		
    	return vsqlToLucene(query);
    }
    
    private String vsqlToLucene(String query) throws TranslationException {
        try {
            InputSource input = new InputSource(new StringReader(query));
            Node queryNode = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input).getFirstChild();
            NodeList nl = XPathAPI.selectNodeList(queryNode, "term/text()");

            String lquery = "";

            for (int i = 0; i < nl.getLength(); i++) {
                String term =nl.item(i).getNodeValue();
                if (i == 0)
                	lquery += term ;
                else
                	lquery += " AND " + term;
            }
            log.debug("vsqlToLucene: Translated \"" + query + "\" to \"" + lquery + "\"");

            return lquery;
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