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

package se.kmr.scam.rest.integrationtest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test; 
//import com.meterware.httpunit.*;

import java.io.IOException;
import java.net.MalformedURLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import se.kmr.scam.rest.ScamApplication;

/**
 * A class to test that integrationtest works with build environment
 *
 * Todo: remove this class before first release
 *
 * @author mlkn
 * @version $Id$
 */
public class SimpleIntegrationTest {
	@Test
	public void testSomething() {
//		Logger log = LoggerFactory.getLogger(SimpleIntegrationTest.class);
//		String baseUrl= System.getProperty("url");
//		log.info(baseUrl);  
//        
//		try {
//			//http://www.javaworld.com/javaworld/jw-04-2004/jw-0419-httpunit.html
//			WebConversation wc = new WebConversation();
//			WebRequest request = new GetMethodWebRequest(baseUrl);
//			WebResponse response = wc.getResponse(request);
//			assertEquals("OK", response.getResponseMessage());
//			assertEquals("You requested SCAM v4.0 REST-layer. There is no resource on this URI", response.getText());
//		} catch (MalformedURLException mue) {
//			System.out.println(mue);
//			assertTrue(false);
//		} catch (SAXException se) {
//			System.out.println(se);
//			assertTrue(false);
//		} catch (IOException ioe) {
//			System.out.println(ioe);
//			assertTrue(false);
//		}
		
	}
}