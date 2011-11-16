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

import java.io.IOException;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.config.Config;
import se.kmr.scam.repository.config.ConfigurationManager;


/**
*
* 
* @author Mikael Karlsson (mikael.karlsson@educ.umu.se) 
*
*/
public class QueryManagerFactory {
	private static Logger log = LoggerFactory.getLogger(QueryManagerFactory.class);
	
	public static HashMap<QueryLanguageType, QueryManagerImpl> queryManagers = new HashMap<QueryLanguageType, QueryManagerImpl>(); 
	
	
	public static QueryManagerImpl getQueryManagerImpl(QueryLanguageType queryLanguageType) {
		if(queryManagers.containsKey(queryLanguageType) == false){
			
			ConfigurationManager confManager = null;
			try {
				confManager = new ConfigurationManager(ConfigurationManager.getConfigurationURI());
			} catch (IOException e) {
				log.error("Unable to load SCAM configuration: " + e.getMessage());
				return null;
			}
			
			Config config = confManager.getConfiguration();
			
			String queryManager = config.getString("scam.sqi.querymanager" + "." + queryLanguageType.toString().toLowerCase(), config.getString("scam.sqi.querymanager"));
			try {
                Class implClass = Class.forName(queryManager);
                QueryManagerImpl qm = (QueryManagerImpl) implClass.newInstance();
                qm.setLanguage(queryLanguageType);
                qm.initialize();
                queryManagers.put(queryLanguageType, qm);
            } catch (Exception e) {
                log.error("Error while initializing querymanager class", e);
            }
		}	
		return (QueryManagerImpl) queryManagers.get(queryLanguageType);
	}

	/**
	 * 
	 * @param queryLanguageType
	 * @return a enum if success or null otherwise.
	 */
	public static QueryLanguageType getQueryLanguageType(String queryLanguageType) {
		
		if(queryLanguageType.equals("VSQL")) {
			return QueryLanguageType.VSQL; 
		} else if(queryLanguageType.equals("PLQL0") || queryLanguageType.equals("http://www.prolearn-project.org/PLQL/l0")) {
			return QueryLanguageType.PLQL0; 
		} else if(queryLanguageType.equals("PLQL1") || queryLanguageType.equals("http://www.prolearn-project.org/PLQL/l1")) {
			return QueryLanguageType.PLQL1; 
		} else if(queryLanguageType.equals("PLQL2") || queryLanguageType.equals("http://www.prolearn-project.org/PLQL/l2")) {
			return QueryLanguageType.PLQL2; 
		} else if(queryLanguageType.equals("LREQL")) {
			return QueryLanguageType.PLQL2; 
		} else if(queryLanguageType.equals("LUCENEQL")) {
			return QueryLanguageType.LUCENEQL; 
		}
		return null;
	}
	 
}