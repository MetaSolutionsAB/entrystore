package org.entrystore.rest.standalone.springboot.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.session.SessionInformationExpiredEvent;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;

import java.io.IOException;

public class ESExpiredSessionStrategy implements SessionInformationExpiredStrategy {

	@Override
	public void onExpiredSessionDetected(SessionInformationExpiredEvent event) throws IOException {
		HttpServletResponse response = event.getResponse();
		response.setContentType("text/html");
		response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "The session has expired.");
	}
}
