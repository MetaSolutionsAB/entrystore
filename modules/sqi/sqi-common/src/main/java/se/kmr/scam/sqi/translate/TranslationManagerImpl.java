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

import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.sqi.query.QueryLanguageType;

/**
 * 
 * 
 * @author Mikael Karlsson (mikael.karlsson@educ.umu.se)
 * 
 */
public class TranslationManagerImpl {
	private static Logger log = LoggerFactory.getLogger(TranslationManagerImpl.class);

	public static HashMap<HashMapKey, TranslatorImpl> translators = new HashMap<HashMapKey, TranslatorImpl>();

	 /**
	 * Translate query. This method support adding startResult and resultsSetSize to the generated query
	 * 
	 * @param query a query
	 * @param startQueryLanguage
	 * @param endQueryLanguage
	 * @param startResult start record of the results set. The index of the result set size starts with 1.
 	 * @param resultsSetSize number of results.  0 (zero) = no limit
	 *
	 * @return translated query
	 * @throws Exception if something goes wrong
	 */
	public static String translateQuery(String query, QueryLanguageType startQueryLanguage,	QueryLanguageType endQueryLanguage, int startResult, int resultsSetSize) throws TranslationException {
		return getTranslator(startQueryLanguage, endQueryLanguage).translate(query, startResult, resultsSetSize);
	}
	
	/**
	 * Translate query
	 * 
	 * @param query a query
	 * @param startQueryLanguage
	 * @param endQueryLanguage
	 *
	 * @return translated query
	 * @throws Exception if something goes wrong
	 */
	public static String translateQuery(String query, QueryLanguageType startQueryLanguage,	QueryLanguageType endQueryLanguage) throws TranslationException {
		return translateQuery(query, startQueryLanguage, endQueryLanguage, 1, 0);
	}
	private static TranslatorImpl getTranslator(QueryLanguageType startQueryLanguage, QueryLanguageType endQueryLanguage) throws TranslationException {
		if (translators.get(new HashMapKey(startQueryLanguage, endQueryLanguage)) == null) {
			switch (startQueryLanguage) {
			case VSQL:
				switch (endQueryLanguage) {
				case SPARQL:
					translators.put(new HashMapKey(startQueryLanguage, endQueryLanguage), 
							new VsqlToSparqlTranslator(startQueryLanguage, endQueryLanguage));
					break;
				case LUCENEQL:
					translators.put(new HashMapKey(startQueryLanguage, endQueryLanguage), 
							new VsqlToLuceneTranslator(startQueryLanguage, endQueryLanguage));
					break;
				}
				break;
			case PLQL0:
				switch (endQueryLanguage) {
				case LUCENEQL:
					// translators.put(new HashMapKey(startQueryLanguage, endQueryLanguage), 
							// new Plql0ToLuceneTranslator(startQueryLanguage, endQueryLanguage));
					break;
				}
				break;
			}
		}
		
		if (translators.get(new HashMapKey(startQueryLanguage, endQueryLanguage)) == null) {
			throw new TranslationException("Translation from "
					+ startQueryLanguage.toString() + " to "
					+ endQueryLanguage.toString() + " not supported");
		}

		return (TranslatorImpl) translators.get(new HashMapKey(startQueryLanguage, endQueryLanguage));
	}

	private static class HashMapKey {
		private QueryLanguageType startQueryLanguage;
		private QueryLanguageType endQueryLanguage;

		public HashMapKey(QueryLanguageType startQueryLanguage, QueryLanguageType endQueryLanguage) {
			this.startQueryLanguage = startQueryLanguage;
			this.endQueryLanguage = endQueryLanguage;
		}

		public boolean equals(Object object) {
			if (object instanceof HashMapKey) {
				HashMapKey hashMapKey = (HashMapKey) object;
				return (startQueryLanguage == hashMapKey.startQueryLanguage)
						&& (endQueryLanguage == hashMapKey.endQueryLanguage);
			}
			return false;
		}

		public int hashCode() {
			return startQueryLanguage.hashCode() + endQueryLanguage.hashCode();
		}
	}

}