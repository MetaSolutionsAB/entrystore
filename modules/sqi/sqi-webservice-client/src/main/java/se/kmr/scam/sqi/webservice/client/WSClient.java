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

package se.kmr.scam.sqi.webservice.client;

import javax.xml.namespace.QName;
import javax.xml.ws.Service;
import javax.xml.ws.soap.SOAPBinding;

import org.apache.cxf.interceptor.LoggingInInterceptor;
import org.apache.cxf.interceptor.LoggingOutInterceptor;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;

import be.cenorm.isss.ltws.wsdl.sqiv1p0.FaultCodeType;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SetMaxQueryResults;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SetQueryLanguage;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SetResultsFormat;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SetResultsSetSize;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiTargetPort;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiTargetService;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiSessionManagementPort;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiSessionManagementService;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SQIFault;

/**
*
* 
* @author Mikael Karlsson (mikael.karlsson@educ.umu.se) 
*
*/ 
public class WSClient {
    public static void main (String[] args) {
    	try {
    		// Connect Alternative 1 -CXF
    		SqiTargetService targetService = new SqiTargetService();
    		SqiTargetPort targetPort = targetService.getSqiTargetPort();     
    		
    		SqiSessionManagementService sessionService = new SqiSessionManagementService();
    		SqiSessionManagementPort sessionPort = sessionService.getSqiSessionManagementPort();   
    	
    		
    		// Connect Alternative 2 -CXF proxy
//    		JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();
//        	factory.getInInterceptors().add(new LoggingInInterceptor());
//        	factory.getOutInterceptors().add(new LoggingOutInterceptor());
//        	factory.setServiceClass(SqiTargetPort.class);
//        	factory.setAddress("http://localhost:8080/scam-sqi-webservice-module-4.0-SNAPSHOT/services/SqiTargetService");
//        	SqiTargetPort targetPort = (SqiTargetPort) factory.create();
//        	
//        	factory.setServiceClass(SqiSessionManagementPort.class);
//        	factory.setAddress("http://localhost:8080/scam-sqi-webservice-module-4.0-SNAPSHOT/services/SqiSessionManagementService");
//        	SqiSessionManagementPort sessionPort = (SqiSessionManagementPort) factory.create();

                	
        	// Connect Alternative 3 - javax.xml.ws
//        	Service targetService = Service.create(new QName("urn:www.cenorm.be/isss/ltws/wsdl/SQIv1p0", "SqiTargetService"));
//            String endpointAddress = "http://localhost:8080/scam-sqi-webservice-module-4.0-SNAPSHOT/services/SqiTargetService";
//            targetService.addPort(new QName("urn:www.cenorm.be/isss/ltws/wsdl/SQIv1p0", "SqiTargetPort"), SOAPBinding.SOAP11HTTP_BINDING, endpointAddress);
//            SqiTargetPort targetPort = targetService.getPort(SqiTargetPort.class);
//            
//            Service sessionService = Service.create(new QName("urn:www.cenorm.be/isss/ltws/wsdl/SQIv1p0", "SqiSessionManagementService"));
//            endpointAddress = "http://localhost:8080/scam-sqi-webservice-module-4.0-SNAPSHOT/services/SqiSessionManagementService";
//            sessionService.addPort(new QName("urn:www.cenorm.be/isss/ltws/wsdl/SQIv1p0", "SqiSessionManagementPort"), SOAPBinding.SOAP11HTTP_BINDING, endpointAddress);
//            SqiSessionManagementPort sessionPort = sessionService.getPort(SqiSessionManagementPort.class);
//        	
    		
            String sessionId = sessionPort.createAnonymousSession();
    		
    		SetQueryLanguage setQueryLanguage = new SetQueryLanguage();
    		setQueryLanguage.setQueryLanguageID("VSQL");        
    		setQueryLanguage.setTargetSessionID(sessionId);
    		targetPort.setQueryLanguage(setQueryLanguage);
    		
    		SetResultsFormat setResultsFormat = new SetResultsFormat();
    		setResultsFormat.setResultsFormat("LOM");        
    		setResultsFormat.setTargetSessionID(sessionId);
    		targetPort.setResultsFormat(setResultsFormat);
    		
    		StringBuilder sBuild = new StringBuilder();
        	sBuild.append("<simpleQuery>\n");
    		sBuild.append("<term>Mickey</term>\n");
    		sBuild.append("</simpleQuery>\n");
    		String query = sBuild.toString();
    		
    		SetResultsSetSize setResultsSetSize = new SetResultsSetSize();
    		setResultsSetSize.setResultsSetSize(2);
    		setResultsSetSize.setTargetSessionID(sessionId);
    		targetPort.setResultsSetSize(setResultsSetSize);
    		
    		SetMaxQueryResults setMaxQueryResults = new SetMaxQueryResults();
    		setMaxQueryResults.setMaxQueryResults(100);
    		setMaxQueryResults.setTargetSessionID(sessionId);
    		targetPort.setMaxQueryResults(setMaxQueryResults);
    		
    		System.out.println("QUERY:\n" + query);
    		System.out.println("TOTALCOUNT:\n" + targetPort.getTotalResultsCount(sessionId, query));
    		
    		try {
    			for (int i = 1;; i= i + setResultsSetSize.getResultsSetSize()) {
    				System.out.println("RESULT:\n" + targetPort.synchronousQuery(sessionId, query, i));
    			}
    		} catch (SQIFault sqifault) {
        		if (sqifault.getFaultInfo().getSqiFaultCode().equals(FaultCodeType.SQI_00012)) {
        			//No more results
        		} else {
        			sqifault.printStackTrace(System.out);
        		}
        	}
    		
    	
    	} catch (Exception e) {
             e.printStackTrace(System.out);
        }
    	
    } 
}
