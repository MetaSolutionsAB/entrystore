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

package se.kmr.scam.sqi.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.sqi.result.ResultFormatType;
import se.kmr.scam.sqi.translate.TranslationException;

/**
*
* 
* @author Mikael Karlsson (mikael.karlsson@educ.umu.se) 
*
*/
public class DummyQueryManagerImpl extends QueryManagerImpl {
	private static Logger log = LoggerFactory.getLogger(DummyQueryManagerImpl.class);
	
	private static final String LOM1 =
	 "<lom xmlns=\"http://ltsc.ieee.org/xsd/LOM\">" +
	    "<general>" +
           "<identifier>" +
             "<catalog>SCAM DummyQueryManagerImpl</catalog>" +
             "<entry>12345</entry>" +
           "</identifier>" +
           "<title>" +
             "<string language=\"en\">Title 1</string>" +
           "</title>" +
			  "<language>en</language>" +
			  "<description>" +
           	"<string language=\"en\">Description 1</string>" +
           "</description>" +
           "<keyword>" +
            "<string language=\"en\">key 1</string>" +
           "</keyword>" +
        "</general>" +
	 "</lom>";
	
	private static final String LOM2 =
	  "<lom xmlns=\"http://ltsc.ieee.org/xsd/LOM\">" +
	   "<general>" +
         "<identifier>" +
           "<catalog>SCAM DummyQueryManagerImpl</catalog>" +
           "<entry>56789</entry>" +
         "</identifier>" +
         "<title>" +
           "<string language=\"en\">Title 2</string>" +
         "</title>" +
		 "<language>en</language>" +
			  "<description>" +
         	"<string language=\"en\">Description 2</string>" +
         "</description>" +
         "<keyword>" +
          "<string language=\"en\">key 2</string>" +
         "</keyword>" +
      "</general>" +
	  "</lom>";
		
	private static final String EXAMPLE_STRICT_LRE = 
		"<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
		"<strictLreResults xmlns=\"http://fire.eun.org/xsd/strictLreResults-1.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://fire.eun.org/xsd/strictLreResults-1.0 http://fire.eun.org/xsd/strictLreResults-1.0.xsd\">" +
			LOM1 +	
			LOM2 + 
		"</strictLreResults>";	
	
	private static final String EXAMPLE_LOM =
		"<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + 
		"<results>" +
		  LOM1 +	
		  LOM2 + 
		"</results>";
	
	private static final String EXAMPLE_PLRF0 =
		"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
		"<Results xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
		"xsi:schemaLocation=\"" +
		"http://www.prolearn-project.org/PLRF/ http://www.cs.kuleuven.be/~stefaan/plql/plql.xsd " +
		"http://ltsc.ieee.org/xsd/LOM http://ltsc.ieee.org/xsd/lomv1.0/lom.xsd\" " +
		"xmlns=\"http://www.prolearn-project.org/PLRF/\">\n" +
		"<ResultInfo>\n" +
		"<ResultLevel>http://www.prolearn-project.org/PLRF/0</ResultLevel>\n" +
		"<QueryMethod>http://www.prolearn-project.org/PLQL/l0</QueryMethod>\n" +
		"<Cardinality>" + "2" + "</Cardinality>\n" +
		"</ResultInfo>\n" +
		"</Results>\n";
	
	public void initialize() {
		//Setup connection repository or db/index
		
	}
	
	/**
	 * Search in the repository after entries
	 * 
	 * @param query a query of format {@link #getLanguage()}.
	 * @param start start record of the results set. The index of the result set size starts with 1.
 	 * @param resultsSetSize number of results.  0 (zero) = no limit
 	 * @param maxResults maximum number of query results.  0 (zero) = no limit
	 * @param resultsFormat format of the results
	 *
	 * @return A string with hits or null.
	 * @throws Exception if something goes wrong
	 */
	public String query(String query, int start, int resultsSetSize, int maxResults, ResultFormatType resultsFormat) throws TranslationException {
		log.info("from:" + getLanguage().toString() 
				+ "\nto: " + QueryLanguageType.LUCENEQL.toString() 
				+ "\nresultsFormat: " + resultsFormat.toString() 
				+ "\nquery: " + query);

		//String dQuery = TranslationManagerImpl.translateQuery(query, getLanguage(), QueryLanguageType.LUCENEQL);
		String dQuery = null;
		return dummyQuery(dQuery, resultsFormat);
	}
	
	public int count(String query) throws TranslationException {
		return 2;
	}
	
	private String dummyQuery(String query, ResultFormatType resultsFormat) {

		String searchResult = null;

		if (resultsFormat == ResultFormatType.LOM) {
			searchResult = EXAMPLE_LOM;
		} else if (resultsFormat == ResultFormatType.STRICT_LRE) {
			searchResult = EXAMPLE_STRICT_LRE;
		} else if (resultsFormat == ResultFormatType.PLRF0) {
			searchResult = EXAMPLE_PLRF0;
		}

		return searchResult;
	}

}
