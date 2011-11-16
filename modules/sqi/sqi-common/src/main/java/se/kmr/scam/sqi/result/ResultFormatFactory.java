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

package se.kmr.scam.sqi.result;

import se.kmr.scam.sqi.query.QueryLanguageType;

/**
*
* 
* @author Mikael Karlsson (mikael.karlsson@educ.umu.se) 
*
*/
public class ResultFormatFactory {
	/**
	 * 
	 * @param resultFormat
	 * @return a enum if success or null otherwise.
	 */
	public static ResultFormatType getResultFormatType(String resultFormat) {
		
		if(resultFormat.equals("PLRF0")) {
			return ResultFormatType.PLRF0; 
		} else if(resultFormat.equals("LOM") || resultFormat.equals("http://ltsc.ieee.org/xsd/LOM")) {
			return ResultFormatType.LOM; 
		} else if(resultFormat.equals("STRICT_LRE") || resultFormat.equals("http://fire.eun.org/xsd/strictLreResults-1.0")) {
			return ResultFormatType.STRICT_LRE; 
		}
		return null;
	}
}