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

import se.kmr.scam.sqi.result.ResultFormatType;
import se.kmr.scam.sqi.translate.TranslationException;

/**
*
* 
* @author Mikael Karlsson (mikael.karlsson@educ.umu.se) 
*
*/
abstract public class QueryManagerImpl {
	 private QueryLanguageType language;
	 public abstract void initialize();
	 public abstract String query(String query, int start, int resultsSetSize, int maxResults, ResultFormatType resultFormatType) throws TranslationException; 
	 public abstract int count(String query) throws TranslationException;


	 public QueryLanguageType getLanguage() {
		 return language;
	 }

	 public void setLanguage(QueryLanguageType language) {
		 this.language = language;
	 }

	
}
