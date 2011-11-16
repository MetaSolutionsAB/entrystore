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

package se.kmr.scam.sqi.webservice.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import org.junit.Test; 
import java.net.URL;

import javax.xml.namespace.QName;
import javax.xml.ws.Endpoint;


import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import be.cenorm.isss.ltws.wsdl.sqiv1p0.DestroySession;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.FaultCodeType;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SQIFault;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiSessionManagementPort;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiSessionManagementService;




/**
 * A class to test the SqiSessionManagement
 *
 *
 * @author Mikael Karlsson (mikael.karlsson@educ.umu.se) 
 * @version $Id$
 */               
public class SqiSessionManagementPortImplTest  {
	protected Endpoint ep;
    protected String address;
    protected URL wsdlURL;
    protected QName serviceName;
    protected QName portName;
   
    /**
     * The Java Endpoint class creates an embedded server that can be used for hosting the web service without need 
     * of an explicit servlet container. Apache CXF internally uses Jetty to implement this class
     * The main advantage of using Endpoint from a Maven/JUnit perspective is that this class works 
     * directly with the service implementation bean (SqiSessionManagementPortImpl) without need for a WAR file
     */
    @Before
    public void setUp() throws Exception {
       address = "http://localhost:9000/services/SqiSessionManagementService";
       wsdlURL = new URL(address + "?wsdl");
       serviceName = new QName("urn:www.cenorm.be/isss/ltws/wsdl/SQIv1p0", "SqiSessionManagementService");
       portName = new QName("urn:www.cenorm.be/isss/ltws/wsdl/SQIv1p0", "SqiSessionManagementPort");
       ep = Endpoint.publish(address, new SqiSessionManagementPortImpl());
    }

    @After
    public void tearDown() {
       try {
          ep.stop();
       } catch (Throwable t) {
          System.out.println("Error thrown: " + t.getMessage());
       }
    }

    
    /*
     * This test uses wsimport/wsdl2java generated artifacts, both service and
     * service endpoint interface (SEI)
     */
    @Test
    public void createAnonymousSession() throws SQIFault {
    	SqiSessionManagementService sessionService = new SqiSessionManagementService(wsdlURL, serviceName);
		SqiSessionManagementPort sessionPort = sessionService.getSqiSessionManagementPort();   
		String sessionId = sessionPort.createAnonymousSession();        
		assertNotNull(sessionId);
    }
  
    @Test
    public void destroySession() throws SQIFault {
    	SqiSessionManagementService sessionService = new SqiSessionManagementService(wsdlURL, serviceName);
		SqiSessionManagementPort sessionPort = sessionService.getSqiSessionManagementPort();
		String sessionId = sessionPort.createAnonymousSession();        
		DestroySession ds = new DestroySession();
		ds.setSessionID(sessionId);
		sessionPort.destroySession(ds);
		
		try {
			sessionPort.destroySession(ds); //this should throw SessionExpiredException 
		    fail(" didn't throw when I expected it to");
		} catch (SQIFault sqifault) {
			assertEquals(FaultCodeType.SQI_00013, sqifault.getFaultInfo().getSqiFaultCode());
		}
	}

    @Test
    public void createSession() throws SQIFault {
    	SqiSessionManagementService sessionService = new SqiSessionManagementService(wsdlURL, serviceName);
		SqiSessionManagementPort sessionPort = sessionService.getSqiSessionManagementPort();   
		String userID = "username";
		String password = "password";
		try {
			String sessionId = sessionPort.createSession(userID, password);        
		    fail( "createSession not supported yet, didn't throw when I expected it to" );
		} catch (SQIFault sqifault) {
			assertEquals(FaultCodeType.SQI_00015, sqifault.getFaultInfo().getSqiFaultCode());
		}
    }
}
