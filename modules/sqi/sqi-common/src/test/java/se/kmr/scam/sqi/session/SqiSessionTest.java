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

package se.kmr.scam.sqi.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test; 


/**
 * A class to test the SqiSession
 *
 *
 * @author Mikael Karlsson (mikael.karlsson@educ.umu.se) 
 * @version $Id$
 */               
public class SqiSessionTest  {
	
    @Test
    public void createAndDestroySession()  {
    	int nrSessions = SqiSession.getNrofSessions();
    	
    	SqiSession sqiSession = SqiSession.newSession();
    	String sessionId = sqiSession.toString();
    	assertNotNull(sessionId);
    	assertEquals(1 + nrSessions, SqiSession.getNrofSessions());
    	
    	SqiSession sqiSession2 = SqiSession.newSession();
    	assertEquals(2 + nrSessions, SqiSession.getNrofSessions());
    	    	
    	assertTrue(SqiSession.sessionExist(sessionId));
    	SqiSession.destroy(sqiSession);
    	assertEquals(1 + nrSessions, SqiSession.getNrofSessions());
    	assertFalse(SqiSession.sessionExist(sessionId));
    	
    	SqiSession.destroy(sqiSession2);
    	assertEquals(0 + nrSessions, SqiSession.getNrofSessions());
    	
    }
  
}