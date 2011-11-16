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
import static org.junit.Assert.fail;

import org.ieee.ltsc.lo.LO;
import org.junit.Test; 

import java.io.IOException;
import java.net.URL;

import javax.xml.namespace.QName;

import javax.xml.ws.Endpoint;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.mortbay.log.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.ContextManager;
import se.kmr.scam.repository.config.Config;
import se.kmr.scam.repository.config.ConfigurationManager;
import se.kmr.scam.repository.config.Settings;
import se.kmr.scam.repository.impl.RepositoryManagerImpl;
import se.kmr.scam.repository.test.TestSuite;
import se.kmr.scam.sqi.session.SessionExpiredException;
import se.kmr.scam.sqi.session.SqiSession;

import be.cenorm.isss.ltws.wsdl.sqiv1p0.AsynchronousQuery;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.FaultCodeType;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SQIFault;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SetMaxDuration;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SetMaxQueryResults;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SetQueryLanguage;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SetResultsFormat;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SetResultsSetSize;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SetSourceLocation;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SQIFaultType;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiSessionManagementPort;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiSessionManagementService;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiTargetPort;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiTargetService;



/**
 * A class to test the SqiTargetPortImpl
 *
 *
 * @author Mikael Karlsson (mikael.karlsson@educ.umu.se) 
 * @version $Id$
 */               
public class SqiTargetPortImplTest  {
	private static Logger log = LoggerFactory.getLogger(SqiTargetPortImplTest.class);
	
	protected Endpoint ep;
    protected String address;
    protected URL wsdlURL;
    protected QName serviceName;
    protected QName portName;
    protected SqiTargetService sqiTargetService;
    protected SqiTargetPort targetPort; 
    
	protected Endpoint sessionEp;
    protected String sessionAddress;
    protected URL sessionWsdlURL;
    protected QName sessionServiceName;
    protected QName sessionPortName;
    protected SqiSessionManagementService sessionService;
    protected SqiSessionManagementPort sessionPort;
    
    protected RepositoryManagerImpl rm;
    protected ContextManager cm;
	
    protected static final String INVALID_SESSION = "INVALID_SESSION_ID";
    protected static final String INVALID_QUERY_LANGUAGE = "INVALID_QL";
    protected static final String INVALID_RESULT_FORMAT  = "INVALID_RF";
    protected static final String INVALID_QUERY = "<invalid></invalid>";
    
    
    /**
     * The Java Endpoint class creates an embedded server that can be used for hosting the web service without need 
     * of an explicit servlet container. Apache CXF internally uses Jetty to implement this class
     * The main advantage of using Endpoint from a Maven/JUnit perspective is that this class works 
     * directly with the service implementation bean (SqiTargetPortImplImpl) without need for a WAR file
     */
    @Before
    public void setUp() throws Exception {
       // TargetService endpoints
       address = "http://localhost:9000/services/SqiTargetService";
       wsdlURL = new URL(address + "?wsdl");
       serviceName = new QName("urn:www.cenorm.be/isss/ltws/wsdl/SQIv1p0", "SqiTargetService");
       portName = new QName("urn:www.cenorm.be/isss/ltws/wsdl/SQIv1p0", "SqiTargetPort");
       ep = Endpoint.publish(address, new SqiTargetPortImpl());
       sqiTargetService = new SqiTargetService(wsdlURL, serviceName);
       targetPort = sqiTargetService.getSqiTargetPort(); 
       
       // create anonymous session
       sessionAddress = "http://localhost:9000/services/SqiSessionManagementService";
       sessionWsdlURL = new URL(sessionAddress + "?wsdl");
       sessionServiceName = new QName("urn:www.cenorm.be/isss/ltws/wsdl/SQIv1p0", "SqiSessionManagementService");
       sessionPortName = new QName("urn:www.cenorm.be/isss/ltws/wsdl/SQIv1p0", "SqiSessionManagementPort");
       sessionEp = Endpoint.publish(sessionAddress, new SqiSessionManagementPortImpl());
       sessionService = new SqiSessionManagementService(sessionWsdlURL, sessionServiceName);
	   sessionPort = sessionService.getSqiSessionManagementPort();   
	   
	   // Fill scam repository with testdata
	   ConfigurationManager confMan = null;
		try {
			confMan = new ConfigurationManager(ConfigurationManager.getConfigurationURI());
		} catch (IOException e) {
			e.printStackTrace();
		}
		Config config = confMan.getConfiguration();
		config.setProperty(Settings.SCAM_STORE_TYPE, "memory");
		
		// Check the URL in the scam.properties file 
		String scamBaseURI = config.getString(Settings.SCAM_BASE_URL, "http://scam4.org");
		
		rm = new RepositoryManagerImpl(scamBaseURI, config);
		rm.setCheckForAuthorization(false);
		cm = rm.getContextManager();

		TestSuite.initDisneySuite(rm);
		TestSuite.addEntriesInDisneySuite(rm);
	     
    }

    @After
    public void tearDown() {
       try {
          ep.stop();
          sessionEp.stop();
          
       } catch (Throwable t) {
          System.out.println("Error thrown: " + t.getMessage());
       }
    }

   
    @Test
    public void setResultsFormat() throws SQIFault {
    	SetResultsFormat setResultsFormat = new SetResultsFormat();

		String sessionId = sessionPort.createAnonymousSession();
		
    	// test setting valid ResultsFormat
		try {
			setResultsFormat.setResultsFormat("LOM");        
			setResultsFormat.setTargetSessionID(sessionId);
			targetPort.setResultsFormat(setResultsFormat);
		} catch (SQIFault sqifault) {
			sqifault.printStackTrace();
			fail("setResultsFormat");
    	}
		
    	// test no such session
    	try {
    		setResultsFormat.setResultsFormat("LOM");
    		setResultsFormat.setTargetSessionID(INVALID_SESSION);
        	targetPort.setResultsFormat(setResultsFormat);
        	fail("setResultsFormat should have thrown SQI_00013");
    	} catch (SQIFault sqifault) {
			assertEquals(FaultCodeType.SQI_00013, sqifault.getFaultInfo().getSqiFaultCode());
    	}
    	
    	// test unsupported resultsformat
    	try {
    		setResultsFormat.setResultsFormat(INVALID_QUERY_LANGUAGE);
    		setResultsFormat.setTargetSessionID(sessionId);
    		targetPort.setResultsFormat(setResultsFormat);
    		fail("setResultsFormat should have thrown SQI_00010");
    	} catch (SQIFault sqifault) {
			assertEquals(FaultCodeType.SQI_00010, sqifault.getFaultInfo().getSqiFaultCode());
    	}
    	
    	
    }
  
    @Test
    public void setMaxDuration() throws SQIFault {
    	SetMaxDuration setMaxDuration = new SetMaxDuration();
		String sessionId = sessionPort.createAnonymousSession();

		setMaxDuration.setMaxDuration(1000);        
		setMaxDuration.setTargetSessionID(sessionId);
    	
    	// Method not supported: setMaxDuration
    	try {
    		targetPort.setMaxDuration(setMaxDuration);
    		fail("setMaxDuration should have thrown SQI_00012");
    	} catch (SQIFault sqifault) {
			assertEquals(FaultCodeType.SQI_00012, sqifault.getFaultInfo().getSqiFaultCode());
    	}
    	
    }
    
    @Test
    public void setMaxQueryResults() throws SQIFault {
    	
    	SetMaxQueryResults setMaxQueryResults = new SetMaxQueryResults();
		String sessionId = sessionPort.createAnonymousSession();

    	// test setting MaxQueryResults
    	try {
    		setMaxQueryResults.setMaxQueryResults(100);
    		setMaxQueryResults.setTargetSessionID(sessionId);
    		targetPort.setMaxQueryResults(setMaxQueryResults);
    	} catch (SQIFault sqifault) {
    		sqifault.printStackTrace();
			fail("setMaxQueryResults");
    	}
    	
    	// test no such session
    	try {
    		setMaxQueryResults.setMaxQueryResults(100);
    		setMaxQueryResults.setTargetSessionID(INVALID_SESSION);
        	targetPort.setMaxQueryResults(setMaxQueryResults);   
    		fail("setQueryLanguage should have thrown SQI_00013");
    	} catch (SQIFault sqifault) {
			assertEquals(FaultCodeType.SQI_00013, sqifault.getFaultInfo().getSqiFaultCode());
    	}
    	
    	// test unsupported setMaxQueryResults
    	try {
    		setMaxQueryResults.setMaxQueryResults(-1);
    		setMaxQueryResults.setTargetSessionID(sessionId);
    		targetPort.setMaxQueryResults(setMaxQueryResults);
    		fail("setMaxQueryResults should have thrown SQI_00007");
    	} catch (SQIFault sqifault) {
			assertEquals(FaultCodeType.SQI_00007, sqifault.getFaultInfo().getSqiFaultCode());
    	}
    	
    	
    	
    }
    
    @Test
    public void setQueryLanguage() throws SQIFault {
    	SetQueryLanguage setQueryLanguage = new SetQueryLanguage();
    	String sessionId = sessionPort.createAnonymousSession();

    	// test setting VSQL language
    	try {
    		setQueryLanguage.setQueryLanguageID("VSQL");        
    		setQueryLanguage.setTargetSessionID(sessionId);
    		targetPort.setQueryLanguage(setQueryLanguage);
    	} catch (SQIFault sqifault) {
    		sqifault.printStackTrace();
			fail("setQueryLanguage VSQL");
    	}
    	
    	// test no such session
    	try {
    		setQueryLanguage.setQueryLanguageID("VSQL");
    		setQueryLanguage.setTargetSessionID(INVALID_SESSION);
        	targetPort.setQueryLanguage(setQueryLanguage);   
    		fail("setQueryLanguage should have thrown SQI_00013");
    	} catch (SQIFault sqifault) {
			assertEquals(FaultCodeType.SQI_00013, sqifault.getFaultInfo().getSqiFaultCode());
    	}
    	
    	// test unsupported queryLanguage
    	try {
    		setQueryLanguage.setQueryLanguageID(INVALID_QUERY_LANGUAGE);
    		setQueryLanguage.setTargetSessionID(sessionId);
    		targetPort.setQueryLanguage(setQueryLanguage);
    		fail("setQueryLanguage should have thrown SQI_00011");
    	} catch (SQIFault sqifault) {
			assertEquals(FaultCodeType.SQI_00011, sqifault.getFaultInfo().getSqiFaultCode());
    	}
    }
    
    @Test
    public void getTotalResultsCount() throws SQIFault {
    	StringBuilder sBuild = new StringBuilder();
    	sBuild.append("<simpleQuery>\n");
		sBuild.append("<term>Donald</term>\n");
		sBuild.append("</simpleQuery>\n");
		
		String query = sBuild.toString();

		// test ResultsCount VSQL
    	try {
    		String sessionId = sessionPort.createAnonymousSession();
    		int count = targetPort.getTotalResultsCount(sessionId, query);
    		assertEquals(5, count);
    	} catch (SQIFault sqifault) {
    		sqifault.printStackTrace();
			fail("totalResultsCount VSQL");
    	}
    	
    	// test no such session
    	try {
    		int count = targetPort.getTotalResultsCount("INVALID SESSION", query);
    		fail("invalid session, should have thrown SQI_00013");
    	} catch (SQIFault sqifault) {
			assertEquals(FaultCodeType.SQI_00013, sqifault.getFaultInfo().getSqiFaultCode());
    	}
    	
    	// test unsupported queryLanguage
    	try {
    		String sessionId = sessionPort.createAnonymousSession();
    		SqiSession sqisession = SqiSession.getSession(sessionId);
    		sqisession.setParameter("queryLanguage", INVALID_QUERY_LANGUAGE);
    		int count = targetPort.getTotalResultsCount(sessionId, query);
    		fail("queryLanguage should have thrown SQI_00011");
    	} catch (SQIFault sqifault) {
			assertEquals(FaultCodeType.SQI_00011, sqifault.getFaultInfo().getSqiFaultCode());
    	} catch (SessionExpiredException see) {
    		fail("SessionExpired");
    	}
    }
    
    @Test
    public void setResultsSetSize() throws SQIFault {
    	SetResultsSetSize setResultsSetSize = new SetResultsSetSize();
    	String sessionId = sessionPort.createAnonymousSession();

    	// test setting resultsSetSize
    	try {
    		setResultsSetSize.setResultsSetSize(25);        
    		setResultsSetSize.setTargetSessionID(sessionId);
    		targetPort.setResultsSetSize(setResultsSetSize);
    	} catch (SQIFault sqifault) {
    		sqifault.printStackTrace();
			fail("setResultsSetSize");
    	}
    	
    	// test no such session
    	try {
    		setResultsSetSize.setResultsSetSize(25);  
    		setResultsSetSize.setTargetSessionID(INVALID_SESSION);
        	targetPort.setResultsSetSize(setResultsSetSize);      
        	fail("setResultsSetSize should have thrown SQI_00013");
    	} catch (SQIFault sqifault) {
			assertEquals(FaultCodeType.SQI_00013, sqifault.getFaultInfo().getSqiFaultCode());
    	}
    	
    	// test unsupported resultsSetSize
    	try {
    		setResultsSetSize.setResultsSetSize(-1);  
    		setResultsSetSize.setTargetSessionID(sessionId);
    		targetPort.setResultsSetSize(setResultsSetSize);
    		fail("setResultsSetSize should have thrown SQI_00005");
    	} catch (SQIFault sqifault) {
			assertEquals(FaultCodeType.SQI_00005, sqifault.getFaultInfo().getSqiFaultCode());
    	}
    }
    
    @Test
    public void setSourceLocation() throws SQIFault {
    	SetSourceLocation setSourceLocation = new SetSourceLocation();
		String sessionId = sessionPort.createAnonymousSession();

		setSourceLocation.setSourceLocation("http://...");        
		setSourceLocation.setTargetSessionID(sessionId);
    	
    	// Method not supported: setSourceLocation
    	try {
    		targetPort.setSourceLocation(setSourceLocation);
    		fail("setSourceLocation should have thrown SQI_00012");
    	} catch (SQIFault sqifault) {
			assertEquals(FaultCodeType.SQI_00012, sqifault.getFaultInfo().getSqiFaultCode());
    	}
    }
    
    @Test
    public void asynchronousQuery() throws SQIFault {
    	AsynchronousQuery asynchronousQuery = new AsynchronousQuery();
		String sessionId = sessionPort.createAnonymousSession();

		asynchronousQuery.setQueryID("client_query_id");
		asynchronousQuery.setQueryStatement("query");
		asynchronousQuery.setTargetSessionID(sessionId);
    	
    	// Method not supported: asynchronousQuery
    	try {
    		targetPort.asynchronousQuery(asynchronousQuery);
    		fail("asynchronousQuery should have thrown SQI_00012");
    	} catch (SQIFault sqifault) {
			assertEquals(FaultCodeType.SQI_00012, sqifault.getFaultInfo().getSqiFaultCode());
    	}
    }
    
    @Test
    public void  synchronousQueryGeneral() throws SQIFault {
    	StringBuilder sBuild = new StringBuilder();
    	sBuild.append("<simpleQuery>\n");
		sBuild.append("<term>123456</term>\n");
		sBuild.append("<term>7891011</term>\n");
		sBuild.append("</simpleQuery>\n");
		
		String query = sBuild.toString();
		int startResult = 1;
		String sessionId = sessionPort.createAnonymousSession();
		
    	// test no such session
    	try {
        	targetPort.synchronousQuery(INVALID_SESSION, query, startResult);
        	fail("synchronousQuery should have thrown SQI_00013");
    	} catch (SQIFault sqifault) {
			assertEquals(FaultCodeType.SQI_00013, sqifault.getFaultInfo().getSqiFaultCode());
    	}
    	// test invalid number for startResult
    	try {
        	targetPort.synchronousQuery(sessionId, query, -1);
        	fail("synchronousQuery should have thrown SQI_00003");
    	} catch (SQIFault sqifault) {
    		assertEquals(FaultCodeType.SQI_00003, sqifault.getFaultInfo().getSqiFaultCode());
    	}
    	
    	// test valid VSQL query, but will return no results 
    	try {
    		addLangAndResult(sessionId, "VSQL", "LOM");
        	String result = targetPort.synchronousQuery(sessionId, query, startResult);
        	fail("synchronousQuery should have thrown SQI_00012 (No more results)");
    	} catch (SQIFault sqifault) {
    		assertEquals(FaultCodeType.SQI_00012, sqifault.getFaultInfo().getSqiFaultCode());
    	}
    	
    	// test invalid VSQL query 
    	try {
    		addLangAndResult(sessionId, "VSQL", "LOM");
    		String result = targetPort.synchronousQuery(sessionId, INVALID_QUERY, startResult);
        	fail("synchronousQuery should have thrown SQI_00004 (Invalid Query statement)");
    	} catch (SQIFault sqifault) {
    		assertEquals(FaultCodeType.SQI_00004, sqifault.getFaultInfo().getSqiFaultCode());
    	}
    }	
    
    @Test
    public void  synchronousQueryVSQLtoLOM() throws SQIFault {
    	StringBuilder sBuild = new StringBuilder();
    	sBuild.append("<simpleQuery>\n");
		sBuild.append("<term>Mickey</term>\n");
		sBuild.append("</simpleQuery>\n");
		
		String query = sBuild.toString();
		log.info("VSQL query:" + query);
		int startResult = 1;
		String sessionId = sessionPort.createAnonymousSession();
		addLangAndResult(sessionId, "VSQL", "LOM");

		// test VSQL query 
    	try {
    		String result = targetPort.synchronousQuery(sessionId, query, startResult);
        	log.info("VSQL searchresult:" +  result);
        	// TODO:  assert that we got LOM resultset back
    	} catch (SQIFault sqifault) {
    		sqifault.printStackTrace();
    		fail("synchronousQueryVSQLtoLOM");
    		
    	}
    	
    	// test max limits
    	int totalresultCount = targetPort.getTotalResultsCount(sessionId, query);
    	log.info("totalresultCount=" + totalresultCount);
    	    	
    	SetResultsSetSize setResultsSetSize = new SetResultsSetSize();
		setResultsSetSize.setResultsSetSize(2);
		setResultsSetSize.setTargetSessionID(sessionId);
		targetPort.setResultsSetSize(setResultsSetSize);
		log.info("setResultsSetSize=" + setResultsSetSize.getResultsSetSize());
		
		SetMaxQueryResults setMaxQueryResults = new SetMaxQueryResults();
		setMaxQueryResults.setMaxQueryResults(5);
		setMaxQueryResults.setTargetSessionID(sessionId);
		targetPort.setMaxQueryResults(setMaxQueryResults);
		log.info("setMaxQueryResults=" +setMaxQueryResults.getMaxQueryResults());
		try {
			for (int i = 1; ; i= i + setResultsSetSize.getResultsSetSize()) {
				log.info("get set [" + i + " - " +  ((i + setResultsSetSize.getResultsSetSize()) - 1) +"]");
				String result = targetPort.synchronousQuery(sessionId, query, i);
			} 
		} catch (SQIFault sqifault) {
			if (sqifault.getFaultInfo().getSqiFaultCode().equals(FaultCodeType.SQI_00012)) {
				// No more results
			} else {
				sqifault.printStackTrace();
				fail("synchronousQueryVSQLtoLOM");
			}
    	}
    }
   
    private void addLangAndResult(String sessionId, String queryLanguage, String resultsFormat) {
    	try {
    		SetQueryLanguage setQueryLanguage = new SetQueryLanguage();
    		setQueryLanguage.setQueryLanguageID(queryLanguage);        
    		setQueryLanguage.setTargetSessionID(sessionId);
    		targetPort.setQueryLanguage(setQueryLanguage);
    	
    		SetResultsFormat setResultsFormat = new SetResultsFormat();
    		setResultsFormat.setResultsFormat(resultsFormat);        
    		setResultsFormat.setTargetSessionID(sessionId);
    		targetPort.setResultsFormat(setResultsFormat);
    	} catch (SQIFault sqifault) {
    		sqifault.printStackTrace();
    		fail("addLangAndResult");
    	}
    }
}
