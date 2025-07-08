package org.entrystore.rest.standalone.springboot.model.api;

import com.fasterxml.jackson.annotation.JsonSetter;

public record PwResetRequestBody(
	String email,
	String password,
	@JsonSetter("urlsuccess") String urlSuccess,
	@JsonSetter("urlfailure") String urlFailure,
	@JsonSetter("grecaptcharesponse") String rcResponseV2) {
}
